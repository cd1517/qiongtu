import SwiftUI
import UniformTypeIdentifiers

@main
struct XiuTuApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

enum Route: Hashable {
    case picker(Tool?)
    case editor(assetID: String, tool: Tool?)
    case coverPicker
    case cover(assetID: String)
    case coverFile(URL)
    case studioPicker
    case studio(assetID: String)
    case studioFile(URL)
    case manga
    case rawPicker
    case editorFile(URL)
    case history
    case favoriteFace
    case draft
}

struct RootView: View {
    @State private var path: [Route] = []
    @State private var dropHover = false

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(start: { tool in path.append(.picker(tool)) },
                     startCover: { path.append(.coverPicker) },
                     startStudio: { path.append(.studioPicker) },
                     startManga: { path.append(.manga) },
                     startRaw: { path.append(.rawPicker) },
                     startHistory: { path.append(.history) },
                     startFavorite: { path.append(.favoriteFace) })
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .picker(let tool):
                        PhotoPickerView(
                            onPick: { id in path.append(.editor(assetID: id, tool: tool)) },
                            onPickFile: { url in path.append(.editorFile(url)) },
                            onClose: { path.removeAll() }
                        )
                    case .editor(let id, let tool):
                        EditorView(assetID: id, initialTool: tool) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .coverPicker:
                        PhotoPickerView(
                            onPick: { id in path.append(.cover(assetID: id)) },
                            onPickFile: { url in path.append(.coverFile(url)) },
                            onClose: { path.removeAll() }
                        )
                    case .cover(let id):
                        GodCoverView(assetID: id) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .coverFile(let url):
                        GodCoverView(assetID: "", fileURL: url) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .studioPicker:
                        PhotoPickerView(
                            onPick: { id in path.append(.studio(assetID: id)) },
                            onPickFile: { url in path.append(.studioFile(url)) },
                            onClose: { path.removeAll() }
                        )
                    case .studio(let id):
                        StudioView(assetID: id) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .studioFile(let url):
                        StudioView(assetID: "", fileURL: url) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .manga:
                        MangaView { path.removeAll() }
                    case .rawPicker:
                        RawPickerView(onEdit: { url in path.append(.editorFile(url)) }, onClose: { path.removeAll() })
                    case .editorFile(let url):
                        EditorView(assetID: "", fileURL: url, initialTool: nil) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .favoriteFace:
                        FavoriteFaceView { path.removeAll() }
                    case .draft:
                        EditorView(assetID: "", restoreDraft: true, initialTool: nil) {
                            if !path.isEmpty { path.removeLast() }
                        }
                    case .history:
                        HistoryView(onEdit: { url in path.append(.editorFile(url)) },
                                    onClose: { path.removeAll() })
                    }
                }
        }
        // Mac 上把访达里的图片直接拖进窗口就能开始编辑（只在主页接收，不打断进行中的编辑）。
        .dropDestination(for: URL.self) { urls, _ in
            guard path.isEmpty, let url = urls.first(where: { $0.isImportableImage }) else { return false }
            path = [.editorFile(url)]
            return true
        } isTargeted: { dropHover = $0 }
        .overlay {
            if dropHover {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(Theme.accent, lineWidth: 4)
                    .padding(10)
                    .allowsHitTesting(false)
            }
        }
        .onAppear(perform: checkDraft)
        .alert("上次有一张图还没编辑完", isPresented: $askDraft) {
            Button("接着编辑", role: .cancel) { path = [.draft] }
            Button("不要了", role: .destructive) { DraftStore.shared.clear() }
        } message: {
            Text(draftMessage)
        }
    }

    @State private var askDraft = false
    @State private var draftMessage = ""
    @State private var draftChecked = false

    /// 启动时看看草稿箱：上次编辑到一半 App 退出了，就问要不要接着编辑。
    private func checkDraft() {
        guard !draftChecked else { return }
        draftChecked = true
        guard DraftStore.shared.exists else { return }
        if let date = DraftStore.shared.date {
            let f = DateFormatter()
            f.locale = Locale(identifier: "zh_CN")
            f.dateFormat = "M月d日 HH:mm"
            draftMessage = "\(f.string(from: date)) 编辑到一半的图，要接着编辑吗？"
        } else {
            draftMessage = "要接着编辑吗？"
        }
        askDraft = true
    }
}

/// 主页：一个大的"开始编辑"，加几个直达某个工具的快捷入口。
struct HomeView: View {
    var start: (Tool?) -> Void
    var startCover: () -> Void = {}
    var startStudio: () -> Void = {}
    var startManga: () -> Void = {}
    var startRaw: () -> Void = {}
    var startHistory: () -> Void = {}
    var startFavorite: () -> Void = {}

    @State private var showSettings = false
    private let history = HistoryStore.shared
    private let settings = AISettings.shared
    private let shortcuts: [Tool] = [.crop, .filter, .adjust, .mosaic]

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("穷图")
                        .font(.system(size: 38, weight: .heavy, design: .rounded))
                    Spacer()
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(Theme.chip, in: Circle())
                    }
                    .accessibilityLabel("设置")
                }
                .padding(.top, 24)

                HStack(spacing: 0) {
                    ForEach(shortcuts) { tool in
                        Button { start(tool) } label: {
                            VStack(spacing: 10) {
                                Image(systemName: tool.symbol)
                                    .font(.system(size: 26))
                                    .frame(height: 32)
                                Text(tool.title).font(.system(size: 13))
                            }
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Button { start(nil) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "plus.app.fill").font(.system(size: 22))
                        Text("开始编辑").font(.system(size: 20, weight: .bold))
                    }
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 72)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
                .buttonStyle(.plain)

                // 其他功能入口都排在"开始编辑"下面。
                VStack(spacing: 10) {
                    featureButton(symbol: "camera.filters", title: "RAW 冲洗", subtitle: "相机 RAW / DNG · 曝光、色阶、白平衡 · 调好再进编辑", action: startRaw)
                    featureButton(symbol: "crown.fill", title: "封神", subtitle: "AI 杂志封面 · 电影海报 · 85 种风格", action: startCover)
                    featureButton(symbol: "camera.aperture", title: "摄影棚", subtitle: "一张照片拍一组套图 · 166 个场景 · 29 位摄影师", action: startStudio)
                    featureButton(symbol: "book.pages.fill", title: "漫画人生", subtitle: "把生活照片画成漫画 · 15 种画风", action: startManga)
                    featureButton(symbol: history.hasPassword ? "lock.fill" : "photo.stack", title: "历史图库",
                                  subtitle: "\(history.items.count) 张", action: startHistory)
                    featureButton(symbol: "star.fill", title: "满意脸型",
                                  subtitle: "\(FavoriteFaceStore.shared.faces.count) 个", action: startFavorite)
                }

                Spacer()

                Button { showSettings = true } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "sparkles").foregroundStyle(Theme.accent)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("AI 修图：\(settings.active.shortTitle)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.white)
                            Text(settings.isConfigured(settings.active)
                                 ? settings.config(settings.active).model
                                 : "还没有填 API Key，点这里设置")
                                .font(.system(size: 12))
                                .foregroundStyle(Theme.dim)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                    }
                    .padding(14)
                    .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                Text("裁剪、调色、滤镜、马赛克、文字、贴纸在本机处理。只有 AI 修图会把图片发给你选的服务商。")
                    .font(.footnote)
                    .foregroundStyle(Theme.dim)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showSettings) { SettingsView() }
    }

    private func featureButton(symbol: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.black)
                    .frame(width: 42, height: 42)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(.white)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.dim)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.dim)
            }
            .padding(12)
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// 拖拽 / 文件选择来的 URL 能否直接当图片打开。
private extension URL {
    var isImportableImage: Bool {
        guard let type = UTType(filenameExtension: pathExtension) else { return false }
        return type.conforms(to: .image)
    }
}
