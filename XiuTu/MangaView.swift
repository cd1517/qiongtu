import PhotosUI
import SwiftUI
import UIKit

/// 漫画人生：把生活照片画成一页漫画。风格和指令来自 LifeManga，界面和修图编辑器一致。
@MainActor
@Observable
final class MangaModel {
    struct BubbleMode: Identifiable, Hashable {
        let id: String
        let title: String
    }
    static let bubbleModes = [
        BubbleMode(id: "chinese", title: "中文台词"), BubbleMode(id: "japanese", title: "日文台词"),
        BubbleMode(id: "english", title: "英文台词"), BubbleMode(id: "empty", title: "气泡留空"),
        BubbleMode(id: "none", title: "不画气泡"),
    ]

    var photos: [UIImage] = []
    var style: MangaStyle = .shonenJump
    var isColor = false
    var bubbleMode = "chinese"
    var note = ""
    /// 续接：把上一页一起发给 AI，画风、人物和剧情接着往下画。
    var continueFromLast = false
    /// 这一页要出场的角色（来自角色库），最多 4 个。
    var cast: [MangaCharacter] = []

    var pages: [UIImage] = []
    var pageIndex = 0
    var running = false
    var started = Date()
    var aiError: String?
    var usageText: String?

    @ObservationIgnored private var task: Task<Void, Never>?

    var currentPage: UIImage? { pages.indices.contains(pageIndex) ? pages[pageIndex] : nil }

    func add(_ images: [UIImage]) {
        let room = max(0, 8 - photos.count)
        photos.append(contentsOf: images.prefix(room).map { AIOneShot.prepared($0, maxSide: 2048) })
    }

    func generate() {
        guard !running else { return }
        guard !photos.isEmpty else {
            aiError = "先在\u{201C}素材\u{201D}里添加至少一张照片。"
            return
        }
        guard AISettings.shared.endpoint() != nil else {
            aiError = "还没有设置 \(AISettings.shared.active.shortTitle) 的 API Key。"
            return
        }
        let previous = continueFromLast ? pages.last : nil
        // 指令的拼法和 LifeManga 一致：风格 → 气泡 → 续接 → 补充说明。
        var parts = [style.effectivePrompt(isColor: isColor), BubbleDirective.simpleMode(bubbleMode)]
        if previous != nil { parts.append(BubbleDirective.continuityDirective) }
        // 角色库里已经删掉的角色不再带上。
        cast = cast.filter { c in CharacterStore.shared.characters.contains { $0.id == c.id } }
        var castImages = cast.compactMap { CharacterStore.shared.image(for: $0) }
        let castReady = castImages.count == cast.count
        if castReady, let directive = CharacterPrompts.directive(for: cast) {
            parts.append(directive)
            // 有三视图的角色把三视图也带上，AI 才知道侧面和背面长什么样。接口一次最多 16 张图，超了就不带。
            let sheets = cast.compactMap { CharacterStore.shared.sheet(for: $0) }
            let used = (previous == nil ? 0 : 1) + photos.count + castImages.count
            if !sheets.isEmpty, used + sheets.count <= 16 {
                castImages += sheets
                parts.append("Some characters also have a three-view TURNAROUND SHEET attached after the character portraits (front, side and back of the same character). Use it only to keep the character's side and back views consistent; never copy the sheet layout itself into the manga page.")
            }
        }
        let extra = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !extra.isEmpty { parts.append("Additional user note: " + extra) }
        let prompt = parts.joined(separator: "\n\n")
        // 图片顺序和 LifeManga 一致：上一页 → 照片 → 角色设定图。
        let images = (previous.map { [$0] } ?? []) + photos + (castReady ? castImages : [])

        aiError = nil
        usageText = nil
        running = true
        started = Date()
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await AIOneShot.run(prompt: prompt, images: images)
                guard !Task.isCancelled else { return }
                self.pages.append(result.image)
                self.pageIndex = self.pages.count - 1
                self.usageText = result.usage?.summary
                self.running = false
            } catch is CancellationError {
                self.running = false
            } catch {
                guard !Task.isCancelled else { return }
                self.running = false
                self.aiError = error.localizedDescription
            }
        }
    }

    func cancel() {
        task?.cancel()
        running = false
    }

    func toggleCast(_ character: MangaCharacter) {
        if let index = cast.firstIndex(where: { $0.id == character.id }) {
            cast.remove(at: index)
        } else if cast.count < 4 {
            cast.append(character)
        }
    }
}

struct MangaView: View {
    var onClose: () -> Void

    enum Tab: String, CaseIterable, Identifiable, Hashable {
        case material, cast, style, bubble, draw
        var id: String { rawValue }
        var title: String {
            switch self {
            case .material: return "素材"
            case .cast: return "角色"
            case .style: return "风格"
            case .bubble: return "台词"
            case .draw: return "生成"
            }
        }
    }

    @State private var model = MangaModel()
    @State private var tab: Tab = .material
    @State private var picked: [PhotosPickerItem] = []
    @State private var editingNote = false
    @State private var viewing: UIImage?
    @State private var showSettings = false
    @State private var creatingCharacter = false
    @State private var detail: MangaCharacter?
    @State private var toast: String?
    private let settings = AISettings.shared
    private let library = CharacterStore.shared

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar.frame(height: 52)
                content.frame(maxWidth: .infinity, maxHeight: .infinity)
                panel
                BottomTabBar(tabs: Tab.allCases, title: { $0.title }, selection: $tab)
            }
            .ignoresSafeArea(.keyboard)
            if let toast {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(Color(white: 0.2).opacity(0.95), in: Capsule())
                        .padding(.bottom, 250)
                }
                .allowsHitTesting(false)
            }
            if editingNote {
                TextInputOverlay(initial: model.note, placeholder: "补充说明，比如：主角戴墨镜、下雨的夜晚") { value in
                    model.note = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    editingNote = false
                }
            }
            if let viewing {
                ResultViewer(image: viewing) { self.viewing = nil }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .sheet(isPresented: $showSettings) { SettingsView() }
        .fullScreenCover(isPresented: $creatingCharacter) {
            CharacterCreateView { creatingCharacter = false }
        }
        .fullScreenCover(item: $detail) { character in
            CharacterDetailView(characterID: character.id,
                                isInCast: { id in model.cast.contains { $0.id == id } },
                                toggleCast: { c in model.toggleCast(c) },
                                onClose: { detail = nil })
        }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            Task {
                var images: [UIImage] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        images.append(image)
                    }
                }
                model.add(images)
                picked = []
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 4) {
            Button(action: onClose) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            Spacer()
            Text("漫画人生").font(.system(size: 16, weight: .bold))
            Spacer()
            Button {
                guard let page = model.currentPage else { return }
                Task {
                    let ok = await AIOneShot.saveToPhotos(page, source: "漫画人生")
                    toast = ok ? "已保存到相册" : "保存失败，请检查相册权限"
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    toast = nil
                }
            } label: {
                Text("保存")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 8)
                    .background(Color.white, in: Capsule())
            }
            .opacity(model.currentPage == nil ? 0 : 1)
            .disabled(model.currentPage == nil)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
    }

    // MARK: 上半部分

    @ViewBuilder
    private var content: some View {
        if let page = model.currentPage {
            VStack(spacing: 8) {
                Image(uiImage: page)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .onTapGesture { viewing = page }
                if model.pages.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(model.pages.indices, id: \.self) { index in
                                Chip(title: "第 \(index + 1) 页", selected: model.pageIndex == index) { model.pageIndex = index }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
            }
            .padding(.bottom, 8)
        } else if model.photos.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "book.pages")
                    .font(.system(size: 44))
                    .foregroundStyle(Theme.dim)
                Text("添加 1 到 8 张生活照片，\n把它们画成一页漫画。")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.dim)
                    .multilineTextAlignment(.center)
            }
        } else {
            let columns = [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)]
            ScrollView {
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(model.photos.indices, id: \.self) { index in
                        Color(white: 0.12)
                            .aspectRatio(1, contentMode: .fit)
                            .overlay(Image(uiImage: model.photos[index]).resizable().scaledToFill())
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
            }
        }
    }

    // MARK: 二、三级选项

    @ViewBuilder
    private var panel: some View {
        VStack(spacing: 10) {
            switch tab {
            case .material: materialPanel
            case .cast: castPanel
            case .style: stylePanel
            case .bubble: bubblePanel
            case .draw: drawPanel
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(Theme.panel)
    }

    private var materialPanel: some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    PhotosPicker(selection: $picked, maxSelectionCount: max(1, 8 - model.photos.count), matching: .images) {
                        VStack(spacing: 4) {
                            Image(systemName: "plus").font(.system(size: 18, weight: .semibold))
                            Text("添加").font(.system(size: 11))
                        }
                        .foregroundStyle(.black)
                        .frame(width: 62, height: 62)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    .disabled(model.photos.count >= 8)
                    ForEach(model.photos.indices, id: \.self) { index in
                        ZStack(alignment: .topTrailing) {
                            Image(uiImage: model.photos[index])
                                .resizable()
                                .scaledToFill()
                                .frame(width: 62, height: 62)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Button { model.photos.remove(at: index) } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 18, height: 18)
                                    .background(Color.black.opacity(0.7), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .padding(3)
                        }
                    }
                }
            }
            HStack(spacing: 8) {
                Chip(title: "续接上一页", selected: model.continueFromLast) { model.continueFromLast.toggle() }
                    .opacity(model.pages.isEmpty ? 0.35 : 1)
                    .disabled(model.pages.isEmpty)
                Text(model.pages.isEmpty ? "画出第一页后，可以让下一页接着画" : "打开后，新的一页会延续上一页的画风和人物")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.dim)
                Spacer()
            }
        }
    }

    /// 角色库：点一下让角色出场（再点取消），长按可以看大图或删除。
    private var castPanel: some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    Button { creatingCharacter = true } label: {
                        VStack(spacing: 6) {
                            VStack(spacing: 4) {
                                Image(systemName: "plus").font(.system(size: 18, weight: .semibold))
                                Text("新建").font(.system(size: 11))
                            }
                            .foregroundStyle(.black)
                            .frame(width: 66, height: 88)
                            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Text("从照片生成").font(.system(size: 10)).foregroundStyle(Theme.dim)
                        }
                    }
                    .buttonStyle(.plain)
                    ForEach(library.characters) { character in
                        let selected = model.cast.contains { $0.id == character.id }
                        Button { model.toggleCast(character) } label: {
                            VStack(spacing: 6) {
                                ZStack(alignment: .topTrailing) {
                                    Color(white: 0.14)
                                    if let image = library.image(for: character) {
                                        Image(uiImage: image).resizable().scaledToFill()
                                    }
                                }
                                .frame(width: 66, height: 88)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .stroke(selected ? Theme.accent : Color.clear, lineWidth: 2.5)
                                )
                                .overlay(alignment: .bottomLeading) {
                                    if character.sheetFile != nil {
                                        Text("三视图")
                                            .font(.system(size: 8, weight: .bold))
                                            .foregroundStyle(.black)
                                            .padding(.horizontal, 4)
                                            .padding(.vertical, 2)
                                            .background(Color.white.opacity(0.9), in: Capsule())
                                            .padding(4)
                                    }
                                }
                                .overlay(alignment: .topTrailing) {
                                    if selected {
                                        Image(systemName: "checkmark.circle.fill")
                                            .font(.system(size: 16))
                                            .foregroundStyle(Theme.accent)
                                            .background(Color.black, in: Circle())
                                            .padding(4)
                                    }
                                }
                                Text(character.name)
                                    .font(.system(size: 10, weight: selected ? .semibold : .regular))
                                    .foregroundStyle(selected ? Theme.accent : Color.white)
                                    .lineLimit(1)
                                    .frame(width: 70)
                            }
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("角色详情 / 三视图") { detail = character }
                            Button("删除角色", role: .destructive) {
                                model.cast.removeAll { $0.id == character.id }
                                library.remove(character)
                            }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            Text(library.characters.isEmpty
                 ? "角色库还是空的。点\u{201C}新建\u{201D}，给一张照片就能生成角色。"
                 : (model.cast.isEmpty ? "点角色让它在这一页出场，最多 4 个；长按进角色详情，可以画三视图或删除。"
                                       : "这一页出场：\(model.cast.map(\.name).joined(separator: "、"))"))
                .font(.system(size: 11))
                .foregroundStyle(Theme.dim)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var stylePanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Chip(title: "黑白", selected: !model.isColor) { model.isColor = false }
                Chip(title: "彩色", selected: model.isColor) { model.isColor = true }
                Spacer()
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(MangaStyle.allCases) { style in
                        let selected = model.style == style
                        Button { model.style = style } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(style.displayName)
                                    .font(.system(size: 13, weight: .heavy))
                                    .foregroundStyle(selected ? Theme.accent : Color.white)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                                Text(style.subtitle)
                                    .font(.system(size: 10))
                                    .foregroundStyle(Theme.dim)
                                    .lineLimit(3)
                            }
                            .frame(width: 118, height: 62, alignment: .topLeading)
                            .padding(10)
                            .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(selected ? Theme.accent : Color.clear, lineWidth: 2)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private var bubblePanel: some View {
        VStack(spacing: 10) {
            ChipRow(items: MangaModel.bubbleModes, title: { $0.title }, isSelected: { model.bubbleMode == $0.id }) { model.bubbleMode = $0.id }
            Button { editingNote = true } label: {
                HStack {
                    Text(model.note.isEmpty ? "补充说明（可不填）" : model.note)
                        .font(.system(size: 13))
                        .foregroundStyle(model.note.isEmpty ? Theme.dim : Color.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Spacer()
                    Image(systemName: "pencil").font(.system(size: 12)).foregroundStyle(Theme.dim)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Theme.chip, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var drawPanel: some View {
        if !settings.isConfigured(settings.active) {
            NeedsKeyView { showSettings = true }
        } else if model.running {
            HStack(spacing: 12) {
                ProgressView().tint(.white)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let seconds = max(0, Int(context.date.timeIntervalSince(model.started)))
                    Text("\(settings.active.shortTitle) 正在画… \(seconds) 秒")
                        .font(.system(size: 14))
                        .monospacedDigit()
                }
                Spacer()
                Button("取消") { model.cancel() }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.accent)
            }
            .padding(.vertical, 22)
        } else {
            HStack(spacing: 8) {
                Text("\(model.style.displayName) · \(model.isColor ? "彩色" : "黑白") · \(model.photos.count) 张照片" + (model.cast.isEmpty ? "" : " · \(model.cast.count) 个角色"))
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.dim)
                    .lineLimit(1)
                Spacer()
                ProviderMenu(openSettings: { showSettings = true }, compact: true)
            }
            Button { model.generate() } label: {
                Label(model.pages.isEmpty ? "画一页漫画" : (model.continueFromLast ? "接着画下一页" : "再画一页"), systemImage: "pencil.and.outline")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Theme.accent, in: Capsule())
            }
            .buttonStyle(.plain)
            if let usage = model.usageText {
                Text(usage)
                    .font(.system(size: 11))
                    .monospacedDigit()
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            AIFailureView(message: model.aiError) { model.generate() }
        }
    }
}
