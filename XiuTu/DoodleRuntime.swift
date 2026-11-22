import CoreGraphics
import Foundation

/// 画的过程中用的两块位图（和预览图一样大）：一块存已经画完的笔画，一块给正在画的这一笔。
/// 手指每动一下只重画正在画的这一笔，画完的那些不用再算。
final class DoodleRuntime {
    let size: CGSize
    private let committed: CGContext
    private let scratch: CGContext
    private(set) var committedImage: CGImage?
    /// 手指还没抬起来的这一笔。
    var active: DoodleStroke?

    init?(size: CGSize) {
        let w = Int(size.width.rounded()), h = Int(size.height.rounded())
        guard let a = DoodleRenderer.makeContext(width: w, height: h),
              let b = DoodleRenderer.makeContext(width: w, height: h) else { return nil }
        committed = a
        scratch = b
        self.size = CGSize(width: w, height: h)
    }

    /// 撤销、清空之后，把剩下的笔画从头画一遍。
    func rebuild(_ strokes: [DoodleStroke]) {
        DoodleRenderer.clear(committed)
        for stroke in strokes { DoodleRenderer.draw(stroke, in: committed, size: size) }
        committedImage = strokes.contains { $0.brush != .spot } ? committed.makeImage() : nil
    }

    /// 一笔画完：画到"已完成"那块位图上。
    func commit(_ stroke: DoodleStroke) {
        guard stroke.brush != .spot else { return }
        DoodleRenderer.draw(stroke, in: committed, size: size)
        committedImage = committed.makeImage()
    }

    /// 现在要叠到照片上的层（从下往上），第二项是那一层整体的不透明度。
    func layers() -> [(CGImage, CGFloat)] {
        let done: [(CGImage, CGFloat)] = committedImage.map { [($0, 1)] } ?? []
        guard let active, active.brush != .spot else { return done }
        if active.brush == .eraser {
            // 橡皮只擦笔画：把画完的那层拷一份，在副本上擦给用户看。
            guard let committedImage else { return [] }
            DoodleRenderer.copy(committedImage, into: scratch)
            DoodleRenderer.draw(active, in: scratch, size: size)
            return scratch.makeImage().map { [($0, 1)] } ?? done
        }
        // 这一笔先按不透明画，整层再统一调淡：一笔之内自己叠到自己不会变深，和画完以后的样子一致。
        DoodleRenderer.clear(scratch)
        var solid = active
        solid.opacity = 1
        DoodleRenderer.draw(solid, in: scratch, size: size)
        guard let top = scratch.makeImage() else { return done }
        return done + [(top, active.opacity)]
    }
}
