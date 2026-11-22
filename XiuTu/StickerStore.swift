import ImageIO
import SwiftUI
import UIKit

/// "我的贴纸"里的一张。
struct StickerEntry: Identifiable, Equatable {
    /// 文件名。
    let id: String
    let thumb: UIImage

    static func == (a: StickerEntry, b: StickerEntry) -> Bool { a.id == b.id }
}

/// 自己的贴纸：存成 PNG 放在 App 的 Documents/Stickers 里，卸载 App 之前一直在。
@MainActor
@Observable
final class StickerStore {
    static let shared = StickerStore()

    /// 新加的排在前面。
    private(set) var items: [StickerEntry] = []

    @ObservationIgnored private let folder: URL
    @ObservationIgnored private var loaded: [String: CGImage] = [:]

    private init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        folder = documents.appendingPathComponent("Stickers", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.creationDateKey])) ?? []
        let sorted = files.filter { $0.pathExtension.lowercased() == "png" }.sorted {
            let a = (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            return a > b
        }
        items = sorted.compactMap { url in
            Self.thumbnail(at: url).map { StickerEntry(id: url.lastPathComponent, thumb: $0) }
        }
    }

    private static func thumbnail(at url: URL) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 200,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// 存一张新贴纸。
    @discardableResult
    func add(_ image: CGImage) -> StickerEntry? {
        guard let data = UIImage(cgImage: image).pngData() else { return nil }
        let name = "sticker-\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(6)).png"
        let url = folder.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            return nil
        }
        guard let thumb = Self.thumbnail(at: url) else { return nil }
        let entry = StickerEntry(id: name, thumb: thumb)
        loaded[name] = image
        items.insert(entry, at: 0)
        return entry
    }

    func delete(_ entry: StickerEntry) {
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(entry.id))
        loaded[entry.id] = nil
        items.removeAll { $0.id == entry.id }
    }

    /// 原大小的图（贴到画面上用）。
    func image(for entry: StickerEntry) -> CGImage? {
        if let hit = loaded[entry.id] { return hit }
        guard let raw = UIImage(contentsOfFile: folder.appendingPathComponent(entry.id).path)?.cgImage,
              let image = StickerCut.normalized(raw, maxSide: 2048) else { return nil }
        if loaded.count > 12 { loaded.removeAll() }
        loaded[entry.id] = image
        return image
    }
}

/// 从相册选来的照片做成贴纸。
enum StickerImport {
    /// 摆正方向、限制大小；autoCut = true 时在本机把主体抠出来。返回贴纸图，以及要不要提示"没抠出来"。
    static func make(from data: Data, autoCut: Bool) async -> (image: CGImage, cutFailed: Bool)? {
        await Task.detached(priority: .userInitiated) { () -> (image: CGImage, cutFailed: Bool)? in
            guard let source = UIImage(data: data) else { return nil }
            // 本来就带透明背景的图（PNG 贴图）：原样用，只裁掉四周空白。
            if let cg = source.cgImage, StickerCut.hasTransparency(cg), let trimmed = StickerCut.trimmed(cg) {
                return (trimmed, false)
            }
            let upright = AIOneShot.prepared(source, maxSide: 1536)
            guard let cg = upright.cgImage else { return nil }
            guard autoCut else {
                return StickerCut.normalized(cg).map { ($0, false) }
            }
            if let mask = Cutout.mask(for: cg), let cut = Cutout.cut(cg, mask: mask, feather: 0.0015, trim: true) {
                return (cut, false)
            }
            return StickerCut.normalized(cg).map { ($0, true) }
        }.value
    }
}

/// 用 AI 生成贴纸。
enum StickerAI {
    static let styles: [(title: String, prompt: String)] = [
        ("卡通", "cute flat cartoon illustration, bold clean outlines, bright colors"),
        ("手绘", "hand-drawn marker doodle, slightly wobbly lines, playful"),
        ("3D", "glossy 3D clay render, soft studio lighting, rounded shapes"),
        ("水彩", "soft watercolor painting, gentle pastel colors"),
        ("像素", "retro pixel art, crisp pixels"),
        ("复古", "retro 1970s badge style, muted warm colors"),
        ("写实", "realistic photo of a real object, sharp detail"),
    ]

    private static let green = " Put it on a solid, perfectly flat pure green (#00FF00) background with nothing else in the picture."

    /// 用设置里当前选中的服务商生成一张贴纸，并在本机把背景去掉。每调一次计一次费。
    @MainActor
    static func generate(what: String, style: String) async throws -> (image: CGImage, usage: AIUsage?) {
        guard let endpoint = AISettings.shared.endpoint() else {
            throw AIError(text: "还没有设置 \(AISettings.shared.active.shortTitle) 的 API Key。")
        }
        let subject = "A single die-cut sticker of: \(what). Style: \(style). Exactly one subject, centered, fully inside the frame with empty margin around it. A thick clean white border runs around the subject like a vinyl sticker. No drop shadow, no extra text or watermark, no scenery."
        var raw: UIImage?
        var usage: AIUsage?

        if endpoint.provider == .openai || endpoint.provider == .custom {
            let transparent = endpoint.provider == .openai
            _ = AIClient.takeLastUsage()
            do {
                let data: Data
                do {
                    data = try await AIClient.generate(endpoint, prompt: subject + (transparent ? " Transparent background." : green),
                                                       size: "1024x1024", transparent: transparent)
                } catch let error as AIError where transparent && error.status == 400 && rejectsTransparency(error) {
                    // 这个型号不认"透明背景"：改成画在纯色背景上，回头在本机抠（被拒的那次不计费）。
                    data = try await AIClient.generate(endpoint, prompt: subject + green, size: "1024x1024", transparent: false)
                }
                usage = AIClient.takeLastUsage()
                if let cost = usage?.cost { AISettings.shared.addSpend(cost) }
                raw = UIImage(data: data)
            } catch let error as AIError where endpoint.provider == .custom && (error.status == 404 || error.status == 405) {
                // 这个兼容服务没有文生图接口，改走下面的"空白画布"。
                raw = nil
            }
        }
        if raw == nil {
            // 没有文生图接口的服务商：给一张空白画布，让它在上面画。
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let blank = UIGraphicsImageRenderer(size: CGSize(width: 1024, height: 1024), format: format).image { ctx in
                UIColor.white.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
            }
            let result = try await AIOneShot.run(
                prompt: subject + green + " The input image is only an empty canvas; replace it completely.",
                images: [blank], size: "1024x1024")
            raw = result.image
            usage = result.usage
        }
        guard let cg = raw?.cgImage else { throw AIError(text: "服务商返回的不是有效图片。") }
        let finished = await Task.detached(priority: .userInitiated) { () -> CGImage? in
            if let cut = StickerCut.finish(cg) { return cut }
            // 背景不是纯色：退回系统的主体识别；再不行就整张用。
            if let mask = Cutout.mask(for: cg), let cut = Cutout.cut(cg, mask: mask, feather: 0.0015, trim: true) { return cut }
            return StickerCut.normalized(cg, maxSide: 1024)
        }.value
        guard let finished else { throw AIError(text: "生成的图片无法处理。") }
        return (finished, usage)
    }

    private static func rejectsTransparency(_ error: AIError) -> Bool {
        let raw = error.raw.lowercased()
        return raw.contains("background") || raw.contains("transparent") || raw.contains("output_format")
    }
}
