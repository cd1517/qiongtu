import CoreGraphics
import Foundation
import ImageIO

/// 草稿箱：编辑中每做完一步就把当前画面存一份，App 意外退出后下次打开可以接着编辑。
/// 只存一份：原图 + 当前画面。正常退出编辑器（保存后返回、或者放弃修改）就删掉。
final class DraftStore: @unchecked Sendable {
    static let shared = DraftStore()

    private let folder: URL
    private let lock = NSLock()
    /// 每次清空加一；清空之前排队的写入发现对不上就不写了。
    private var generation = 0

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("Draft", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private var originalURL: URL { folder.appendingPathComponent("original.bin") }
    private var snapshotURL: URL { folder.appendingPathComponent("snapshot.heic") }

    var exists: Bool {
        FileManager.default.fileExists(atPath: originalURL.path) && FileManager.default.fileExists(atPath: snapshotURL.path)
    }

    var date: Date? {
        (try? FileManager.default.attributesOfItem(atPath: snapshotURL.path))?[.modificationDate] as? Date
    }

    /// 当前的写入批次号，排队写之前先拿一个。
    var token: Int {
        lock.lock(); defer { lock.unlock() }
        return generation
    }

    /// 写入草稿。original 为 nil 时保留已有的原图（同一次编辑原图只写一次）。
    func write(original: Data?, snapshot: Data, token: Int) {
        lock.lock(); defer { lock.unlock() }
        guard token == generation else { return }
        if let original {
            try? original.write(to: originalURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        try? snapshot.write(to: snapshotURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        generation += 1
        try? FileManager.default.removeItem(at: originalURL)
        try? FileManager.default.removeItem(at: snapshotURL)
    }

    /// 读出原图和当前画面。
    func load() -> (original: Data, snapshot: CGImage)? {
        guard let original = try? Data(contentsOf: originalURL),
              let source = CGImageSourceCreateWithURL(snapshotURL as CFURL, nil),
              let snapshot = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        return (original, snapshot)
    }

    /// 弹窗里显示的小图。
    func thumbnail(maxPixel: Int = 600) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(snapshotURL as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
