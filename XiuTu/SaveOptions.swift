import SwiftUI
import UniformTypeIdentifiers

/// 保存到相册时的画质和格式。
struct SaveOptions: Equatable {
    enum Size: String, CaseIterable, Identifiable {
        case standard, high, original

        var id: String { rawValue }

        var title: String {
            switch self {
            case .standard: return "标准"
            case .high: return "高清"
            case .original: return "原画质"
            }
        }

        /// 长边上限；nil = 保持原尺寸。
        var maxSide: CGFloat? {
            switch self {
            case .standard: return 2048
            case .high: return 4096
            case .original: return nil
            }
        }

        /// JPG / HEIC 的压缩质量。
        var quality: Double {
            switch self {
            case .standard: return 0.86
            case .high: return 0.95
            case .original: return 1
            }
        }
    }

    enum Format: String, CaseIterable, Identifiable {
        case jpeg, heic, png

        var id: String { rawValue }

        var title: String {
            switch self {
            case .jpeg: return "JPG"
            case .heic: return "HEIC"
            case .png: return "PNG"
            }
        }

        var typeIdentifier: String {
            switch self {
            case .jpeg: return UTType.jpeg.identifier
            case .heic: return UTType.heic.identifier
            case .png: return UTType.png.identifier
            }
        }
    }

    var size: Size = .original
    var format: Format = .jpeg

    /// 上次选的，下次默认还是它。
    static var saved: SaveOptions {
        get {
            let d = UserDefaults.standard
            return SaveOptions(size: Size(rawValue: d.string(forKey: "save.size") ?? "") ?? .original,
                               format: Format(rawValue: d.string(forKey: "save.format") ?? "") ?? .jpeg)
        }
        set {
            UserDefaults.standard.set(newValue.size.rawValue, forKey: "save.size")
            UserDefaults.standard.set(newValue.format.rawValue, forKey: "save.format")
        }
    }

    /// 原尺寸是 full 时，这一档存出来是多大。
    func pixels(for full: CGSize) -> CGSize {
        guard let side = size.maxSide else { return full }
        let long = max(full.width, full.height)
        guard long > side else { return full }
        let k = side / long
        return CGSize(width: max(1, (full.width * k).rounded(.down)), height: max(1, (full.height * k).rounded(.down)))
    }
}

/// 点"保存"后从底部出来的画质选择。
struct SaveSheet: View {
    let model: EditorModel
    var onSave: (SaveOptions) -> Void

    @State private var options = SaveOptions.saved
    @State private var full: CGSize?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                ForEach(SaveOptions.Size.allCases) { item in
                    let on = options.size == item
                    Button { options.size = item } label: {
                        VStack(spacing: 6) {
                            Text(item.title).font(.system(size: 16, weight: .semibold))
                            Text(pixels(item))
                                .font(.system(size: 11))
                                .monospacedDigit()
                                .opacity(0.65)
                        }
                        .foregroundStyle(on ? Color.black : Color.white)
                        .frame(maxWidth: .infinity, minHeight: 66)
                        .background(on ? Theme.accent : Theme.chip, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(PressStyle())
                }
            }
            SegmentBar(items: SaveOptions.Format.allCases, selected: options.format, title: { $0.title }) {
                options.format = $0
            }
            Toggle("同时存入历史图库", isOn: Binding(get: { HistoryStore.shared.enabled },
                                                   set: { HistoryStore.shared.setEnabled($0) }))
                .font(.system(size: 15))
                .tint(Theme.accent)
                .padding(.horizontal, 4)
            Button {
                SaveOptions.saved = options
                dismiss()
                onSave(options)
            } label: {
                Text("保存到相册")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Color.white, in: Capsule())
            }
            .buttonStyle(PressStyle())
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .padding(.bottom, 8)
        .presentationDetents([.height(300)])
        .presentationBackground(Theme.panel)
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
        .task { full = await model.outputSize() }
    }

    private func pixels(_ item: SaveOptions.Size) -> String {
        guard let full else { return "…" }
        var o = options
        o.size = item
        let p = o.pixels(for: full)
        return "\(Int(p.width)) × \(Int(p.height))"
    }
}
