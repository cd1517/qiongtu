import CoreImage
import SwiftUI
import UIKit

/// 边框 / 背景工具打开期间的全部状态。
struct FrameState {
    // 边框
    var frame = FrameParams()
    /// 当前选中的样式（FramePreset.id）。
    var presetID = "none"
    /// 每个样式的缩略图：用当前这张照片套上边框画出来的。
    var thumbs: [String: UIImage] = [:]

    // 背景
    var backdrop = BackdropParams()
}

/// EditorModel 在这些时机调用下面的函数（工具是 .frame 或 .backdrop 时）。
extension EditorModel {
    func beginFrame(_ tool: Tool) {
        frameState = FrameState()
        if tool == .frame { makeFrameThumbs() }
    }

    /// 点 ✓ 时要写进历史的那一步；没改返回 nil。
    func frameOp(_ tool: Tool) -> EditOp? {
        switch tool {
        case .frame:
            return frameState.frame.isIdentity ? nil : .frame(frameState.frame)
        case .backdrop:
            return frameState.backdrop.isIdentity ? nil : .backdrop(frameState.backdrop)
        default:
            return nil
        }
    }

    /// 还没确认的修改（放大后渲染高清贴片用）。
    func framePendingOps(_ tool: Tool) -> [EditOp]? {
        if let op = frameOp(tool) { return [op] }
        return []
    }

    // MARK: 边框

    /// 换一个边框样式：用它的默认参数，已经写好的字留着。
    func selectFramePreset(_ preset: FramePreset) {
        var next = preset.params
        next.caption = frameState.frame.caption
        frameState.presetID = preset.id
        frameState.frame = next
        renderFrame()
    }

    func updateFrame(_ change: (inout FrameParams) -> Void) {
        change(&frameState.frame)
        renderFrame()
    }

    private func renderFrame() {
        guard tool == .frame, let base = basePreview else { return }
        showLive(frameState.frame.apply(to: base))
    }

    /// 样式缩略图：把照片居中裁一小块，套上每种边框，再整个放进按钮那么大的深色底里（边框不能被裁掉）。
    private func makeFrameThumbs() {
        guard let base = basePreview else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let cropped = EditorModel.centerCrop(base, aspect: 0.82)
            guard let small = Renderer.shared.rasterize(cropped, maxDimension: 170) else { return }
            let tile = CGRect(x: 0, y: 0, width: 204, height: 252)
            let room = tile.insetBy(dx: 14, dy: 14)
            let back = CIImage(color: CIColor(red: 0.14, green: 0.14, blue: 0.14)).cropped(to: tile)
            var thumbs: [String: UIImage] = [:]
            for preset in FramePreset.all {
                let framed = FrameKit.normalized(preset.params.apply(to: small.ci))
                let e = framed.extent
                let k = min(room.width / e.width, room.height / e.height)
                let fitted = framed
                    .transformed(by: CGAffineTransform(scaleX: k, y: k))
                    .transformed(by: CGAffineTransform(translationX: (tile.midX - e.width * k / 2).rounded(),
                                                       y: (tile.midY - e.height * k / 2).rounded()))
                if let cg = Renderer.shared.cgImage(fitted.composited(over: back).cropped(to: tile)) {
                    thumbs[preset.id] = UIImage(cgImage: cg)
                }
            }
            await self?.setFrameThumbs(thumbs)
        }
    }

    private func setFrameThumbs(_ thumbs: [String: UIImage]) {
        guard tool == .frame else { return }
        frameState.thumbs = thumbs
    }

    // MARK: 背景

    func updateBackdrop(_ change: (inout BackdropParams) -> Void) {
        change(&frameState.backdrop)
        renderBackdrop()
    }

    /// 换背景的填充方式或颜色。画布和照片一样大时背景露不出来，这时先把照片缩小一点，改了才看得见。
    func updateBackdropFill(_ change: (inout BackdropParams) -> Void) {
        change(&frameState.backdrop)
        if frameState.backdrop.ratio == .original, frameState.backdrop.scale >= 0.999 {
            frameState.backdrop.scale = 0.86
        }
        renderBackdrop()
    }

    private func renderBackdrop() {
        guard tool == .backdrop, let base = basePreview else { return }
        let params = frameState.backdrop
        // 画布是往外放大的（照片不缩），竖长比例加上小照片时预览图会变得很大。
        // 参数全是相对值，所以预览时先把照片缩小一点再算，样子不变，拖滑块才跟手。
        var input = base
        let size = params.canvasSize(for: base.extent.size)
        let long = max(size.width, size.height)
        if !params.isIdentity, long > 2000 {
            let k = 2000 / long
            let e = base.extent
            let target = CGRect(x: 0, y: 0, width: max(8, (e.width * k).rounded(.down)),
                                height: max(8, (e.height * k).rounded(.down)))
            input = FrameKit.normalized(FrameKit.lanczos(base, k)).cropped(to: target)
        }
        showLive(params.apply(to: input))
    }
}

extension EditorModel {
    /// 把历史里已有的边框重新打开接着改（紧跟在 beginFrame 之后调用）。
    func reopenFrame(_ params: FrameParams) {
        frameState.frame = params
        if let preset = FramePreset.all.first(where: { $0.params.style == params.style }) {
            frameState.presetID = preset.id
        }
        renderFrame()
    }

    /// 把历史里已有的背景重新打开接着改。
    func reopenBackdrop(_ params: BackdropParams) {
        frameState.backdrop = params
        renderBackdrop()
    }
}
