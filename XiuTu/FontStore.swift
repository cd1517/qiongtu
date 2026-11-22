import CoreText
import SwiftUI
import UIKit

/// 字体列表里的一项。
struct FontChoice: Identifiable, Equatable {
    enum Kind { case builtin, download, custom }

    /// 稳定标识：内置的系统字是 "sys.xxx"，其余是字体的 PostScript 名。
    let id: String
    let title: String
    let kind: Kind
    /// 自己导入的字体：对应 Documents/Fonts 里的文件名。
    var fileName: String? = nil
}

enum FontCategory: String, CaseIterable, Identifiable {
    case common, chinese, latin, mine

    var id: String { rawValue }

    var title: String {
        switch self {
        case .common: return "常用"
        case .chinese: return "中文"
        case .latin: return "英文"
        case .mine: return "我的字体"
        }
    }
}

/// 按标识找字体。渲染时用，哪个线程都可以调；找不到就退回系统字体。
enum OverlayFonts {
    static func ctFont(id: String, size: CGFloat) -> CTFont {
        uiFont(id: id, size: size) as CTFont
    }

    static func uiFont(id: String, size: CGFloat) -> UIFont {
        func design(_ d: UIFontDescriptor.SystemDesign, _ weight: UIFont.Weight) -> UIFont {
            let base = UIFont.systemFont(ofSize: size, weight: weight)
            if let descriptor = base.fontDescriptor.withDesign(d) { return UIFont(descriptor: descriptor, size: size) }
            return base
        }
        switch id {
        case "sys.semibold": return .systemFont(ofSize: size, weight: .semibold)
        case "sys.black": return .systemFont(ofSize: size, weight: .black)
        case "sys.light": return .systemFont(ofSize: size, weight: .light)
        case "sys.rounded": return design(.rounded, .bold)
        case "sys.serif": return design(.serif, .semibold)
        case "sys.mono": return .monospacedSystemFont(ofSize: size, weight: .semibold)
        default:
            if !id.hasPrefix("sys."), let font = UIFont(name: id, size: size) { return font }
            return .systemFont(ofSize: size, weight: .semibold)
        }
    }

    static func exists(_ id: String) -> Bool {
        id.hasPrefix("sys.") || UIFont(name: id, size: 12) != nil
    }

    static let system: [FontChoice] = [
        FontChoice(id: "sys.semibold", title: "常规", kind: .builtin),
        FontChoice(id: "sys.black", title: "粗黑", kind: .builtin),
        FontChoice(id: "sys.light", title: "细体", kind: .builtin),
        FontChoice(id: "sys.rounded", title: "圆体", kind: .builtin),
        FontChoice(id: "sys.serif", title: "衬线", kind: .builtin),
        FontChoice(id: "sys.mono", title: "等宽", kind: .builtin),
    ]

    /// 系统里可能自带的中文 / 日文字体。实际有没有，运行时再查。
    static let chineseBuiltin: [FontChoice] = [
        FontChoice(id: "PingFangSC-Thin", title: "苹方 极细", kind: .builtin),
        FontChoice(id: "PingFangSC-Regular", title: "苹方 常规", kind: .builtin),
        FontChoice(id: "PingFangSC-Semibold", title: "苹方 中粗", kind: .builtin),
        FontChoice(id: "STHeitiSC-Medium", title: "黑体", kind: .builtin),
        FontChoice(id: "HiraMinProN-W6", title: "明朝 粗", kind: .builtin),
        FontChoice(id: "HiraMinProN-W3", title: "明朝 细", kind: .builtin),
        FontChoice(id: "HiraginoSans-W7", title: "冬青黑 粗", kind: .builtin),
        FontChoice(id: "HiraMaruProN-W4", title: "丸黑", kind: .builtin),
    ]

    /// 苹果提供的可下载字体（免费，由系统下载和保管）。第一次用要联网下载一次。
    static let chineseDownload: [FontChoice] = [
        FontChoice(id: "STSongti-SC-Bold", title: "宋体 粗", kind: .download),
        FontChoice(id: "STSongti-SC-Regular", title: "宋体", kind: .download),
        FontChoice(id: "STSongti-SC-Black", title: "宋体 特粗", kind: .download),
        FontChoice(id: "STKaitiSC-Regular", title: "楷体", kind: .download),
        FontChoice(id: "STKaitiSC-Bold", title: "楷体 粗", kind: .download),
        FontChoice(id: "STYuanti-SC-Bold", title: "圆体 粗", kind: .download),
        FontChoice(id: "STYuanti-SC-Regular", title: "圆体 细", kind: .download),
        FontChoice(id: "STXingkaiSC-Bold", title: "行楷", kind: .download),
        FontChoice(id: "STLibianSC-Regular", title: "隶变", kind: .download),
        FontChoice(id: "STBaoliSC-Regular", title: "报隶", kind: .download),
        FontChoice(id: "WeibeiSC-Bold", title: "魏碑", kind: .download),
        FontChoice(id: "HanziPenSC-W5", title: "翩翩体", kind: .download),
        FontChoice(id: "HannotateSC-W7", title: "手札体", kind: .download),
        FontChoice(id: "DFWaWaSC-W5", title: "娃娃体", kind: .download),
        FontChoice(id: "YuppySC-Regular", title: "雅痞", kind: .download),
        FontChoice(id: "FZLTZHK--GBK1-0", title: "兰亭黑", kind: .download),
        FontChoice(id: "FZLTTHK--GBK1-0", title: "兰亭特黑", kind: .download),
        FontChoice(id: "MLingWaiMedium-SC", title: "凌慧体", kind: .download),
        FontChoice(id: "Klee-Demibold", title: "Klee 手写", kind: .download),
        FontChoice(id: "TsukuARdGothic-Bold", title: "筑紫丸黑", kind: .download),
    ]

    static let latin: [FontChoice] = [
        FontChoice(id: "AvenirNext-Heavy", title: "Avenir", kind: .builtin),
        FontChoice(id: "Futura-Bold", title: "Futura", kind: .builtin),
        FontChoice(id: "Futura-CondensedExtraBold", title: "Futura Cond", kind: .builtin),
        FontChoice(id: "HelveticaNeue-CondensedBlack", title: "Helvetica Cond", kind: .builtin),
        FontChoice(id: "DINCondensed-Bold", title: "DIN Cond", kind: .builtin),
        FontChoice(id: "GillSans-UltraBold", title: "Gill Ultra", kind: .builtin),
        FontChoice(id: "Didot-Bold", title: "Didot", kind: .builtin),
        FontChoice(id: "BodoniSvtyTwoITCTT-Bold", title: "Bodoni", kind: .builtin),
        FontChoice(id: "Baskerville-Bold", title: "Baskerville", kind: .builtin),
        FontChoice(id: "Georgia-Bold", title: "Georgia", kind: .builtin),
        FontChoice(id: "Palatino-Bold", title: "Palatino", kind: .builtin),
        FontChoice(id: "Optima-ExtraBlack", title: "Optima", kind: .builtin),
        FontChoice(id: "Rockwell-Bold", title: "Rockwell", kind: .builtin),
        FontChoice(id: "Copperplate-Bold", title: "Copperplate", kind: .builtin),
        FontChoice(id: "AmericanTypewriter-Bold", title: "Typewriter", kind: .builtin),
        FontChoice(id: "CourierNewPS-BoldMT", title: "Courier", kind: .builtin),
        FontChoice(id: "Menlo-Bold", title: "Menlo", kind: .builtin),
        FontChoice(id: "MarkerFelt-Wide", title: "Marker Felt", kind: .builtin),
        FontChoice(id: "ChalkboardSE-Bold", title: "Chalkboard", kind: .builtin),
        FontChoice(id: "Chalkduster", title: "Chalkduster", kind: .builtin),
        FontChoice(id: "Noteworthy-Bold", title: "Noteworthy", kind: .builtin),
        FontChoice(id: "BradleyHandITCTT-Bold", title: "Bradley Hand", kind: .builtin),
        FontChoice(id: "SnellRoundhand-Bold", title: "Snell", kind: .builtin),
        FontChoice(id: "SavoyeLetPlain", title: "Savoye", kind: .builtin),
        FontChoice(id: "Zapfino", title: "Zapfino", kind: .builtin),
        FontChoice(id: "Papyrus", title: "Papyrus", kind: .builtin),
        FontChoice(id: "PartyLetPlain", title: "Party", kind: .builtin),
        FontChoice(id: "AcademyEngravedLetPlain", title: "Engraved", kind: .builtin),
    ]
}

/// 字体的管理：哪些能用、下载苹果提供的字体、导入和删除自己的字体。
@MainActor
@Observable
final class FontStore {
    static let shared = FontStore()

    /// 自己导入的字体。
    private(set) var custom: [FontChoice] = []
    /// 可下载字体里，现在已经能用的。
    private(set) var ready: Set<String> = []
    /// 正在下载的：PostScript 名 → 进度 0...1。
    private(set) var downloading: [String: Double] = [:]
    /// 给用户看的一句提示（导入失败、下载失败等）。
    var note: String?

    @ObservationIgnored private let folder: URL
    @ObservationIgnored private let rememberKey = "xiutu.fonts.downloaded"
    @ObservationIgnored private lazy var builtinChinese = OverlayFonts.chineseBuiltin.filter { OverlayFonts.exists($0.id) }
    @ObservationIgnored private lazy var builtinLatin = OverlayFonts.latin.filter { OverlayFonts.exists($0.id) }

    private init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        folder = documents.appendingPathComponent("Fonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        reloadCustom()
        ready = Set(OverlayFonts.chineseDownload.map(\.id).filter { OverlayFonts.exists($0) })
        // 以前下载过的字体，系统里还留着，但每次启动要重新"点名"一次才能按名字用。
        let remembered = UserDefaults.standard.stringArray(forKey: rememberKey) ?? []
        for name in remembered where !ready.contains(name) {
            if let choice = OverlayFonts.chineseDownload.first(where: { $0.id == name }) {
                download(choice, quiet: true) { _ in }
            }
        }
    }

    func fonts(in category: FontCategory) -> [FontChoice] {
        switch category {
        case .common:
            return OverlayFonts.system
        case .chinese:
            return builtinChinese + OverlayFonts.chineseDownload
        case .latin:
            return builtinLatin
        case .mine:
            return custom
        }
    }

    /// 这个字体现在能不能直接用。
    func isReady(_ choice: FontChoice) -> Bool {
        choice.kind != .download || ready.contains(choice.id)
    }

    /// 标识对应的名字（面板上显示当前字体用）。
    func title(for id: String) -> String {
        let all = OverlayFonts.system + OverlayFonts.chineseBuiltin + OverlayFonts.chineseDownload + OverlayFonts.latin + custom
        return all.first { $0.id == id }?.title ?? id
    }

    // MARK: 下载苹果提供的字体

    func download(_ choice: FontChoice, quiet: Bool = false, then done: @escaping @MainActor (Bool) -> Void) {
        let name = choice.id
        guard downloading[name] == nil else { return }
        if OverlayFonts.exists(name) {
            ready.insert(name)
            done(true)
            return
        }
        if !quiet { downloading[name] = 0 }
        let descriptor = CTFontDescriptorCreateWithAttributes([kCTFontNameAttribute: name] as CFDictionary)
        let quietly = quiet
        CTFontDescriptorMatchFontDescriptorsWithProgressHandler([descriptor] as CFArray, nil) { state, info in
            switch state {
            case .downloading:
                let percent = ((info as NSDictionary)[kCTFontDescriptorMatchingPercentage] as? NSNumber)?.doubleValue ?? 0
                if !quietly {
                    Task { @MainActor in
                        if FontStore.shared.downloading[name] != nil { FontStore.shared.downloading[name] = percent / 100 }
                    }
                }
            case .didFinish:
                Task { @MainActor in FontStore.shared.finishDownload(name, title: choice.title, quiet: quietly, done: done) }
            default:
                break
            }
            return true
        }
    }

    private func finishDownload(_ name: String, title: String, quiet: Bool, done: @MainActor (Bool) -> Void) {
        downloading[name] = nil
        let ok = OverlayFonts.exists(name)
        if ok {
            ready.insert(name)
            var remembered = UserDefaults.standard.stringArray(forKey: rememberKey) ?? []
            if !remembered.contains(name) {
                remembered.append(name)
                UserDefaults.standard.set(remembered, forKey: rememberKey)
            }
        } else if !quiet {
            note = "\u{201C}\(title)\u{201D}下载失败：没有联网，或者这台设备上没有这款字体。"
        }
        done(ok)
    }

    // MARK: 自己的字体

    private static let extensions: Set<String> = ["ttf", "otf", "ttc", "otc"]

    private func reloadCustom() {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        var list: [FontChoice] = []
        for url in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
        where Self.extensions.contains(url.pathExtension.lowercased()) {
            list.append(contentsOf: register(url))
        }
        custom = list
    }

    /// 向系统登记这个文件里的字体（只对本 App 生效），返回里面有哪些字体。
    private func register(_ url: URL) -> [FontChoice] {
        // 已经登记过会返回失败，不用管。
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor] ?? []
        var seen = Set<String>()
        var out: [FontChoice] = []
        for descriptor in descriptors {
            guard let name = CTFontDescriptorCopyAttribute(descriptor, kCTFontNameAttribute) as? String,
                  seen.insert(name).inserted, UIFont(name: name, size: 12) != nil else { continue }
            let shown = CTFontDescriptorCopyLocalizedAttribute(descriptor, kCTFontDisplayNameAttribute, nil) as? String
            out.append(FontChoice(id: name, title: shown ?? name, kind: .custom, fileName: url.lastPathComponent))
        }
        return out
    }

    /// 从"文件"里选来的字体：复制进 App 自己的文件夹并登记。返回新加进来的第一款，方便直接用上。
    @discardableResult
    func importFonts(from urls: [URL]) -> FontChoice? {
        var first: FontChoice?
        var failed = 0
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard Self.extensions.contains(url.pathExtension.lowercased()) else {
                failed += 1
                continue
            }
            var target = folder.appendingPathComponent(url.lastPathComponent)
            if FileManager.default.fileExists(atPath: target.path) {
                // 同名文件：如果内容一样就直接用现有的，不一样就换个名字存。
                if FileManager.default.contentsEqual(atPath: target.path, andPath: url.path) {
                    if first == nil { first = custom.first { $0.fileName == target.lastPathComponent } }
                    continue
                }
                let stem = url.deletingPathExtension().lastPathComponent
                target = folder.appendingPathComponent("\(stem)-\(UUID().uuidString.prefix(6)).\(url.pathExtension)")
            }
            do {
                try FileManager.default.copyItem(at: url, to: target)
            } catch {
                failed += 1
                continue
            }
            let added = register(target)
            if added.isEmpty {
                try? FileManager.default.removeItem(at: target)
                failed += 1
            } else if first == nil {
                first = added.first
            }
        }
        reloadCustom()
        if failed > 0 {
            note = failed == urls.count
                ? "没能读出字体：只支持 TTF、OTF、TTC 这几种字体文件。"
                : "有 \(failed) 个文件不是能用的字体，已跳过。"
        } else {
            note = nil
        }
        return first
    }

    /// 删掉一款自己导入的字体（同一个文件里的其他字重也会一起删掉）。
    func delete(_ choice: FontChoice) {
        guard choice.kind == .custom, let fileName = choice.fileName else { return }
        let url = folder.appendingPathComponent(fileName)
        CTFontManagerUnregisterFontsForURL(url as CFURL, .process, nil)
        try? FileManager.default.removeItem(at: url)
        reloadCustom()
    }
}
