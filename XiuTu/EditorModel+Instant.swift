import CoreImage
import SwiftUI
import UIKit

/// 滤镜工具里"拍立得"这一类的状态。
struct InstantState {
    var params = InstantParams()
    /// 每种相纸的缩略图：用当前这张照片做出来的。
    var thumbs: [String: UIImage] = [:]
    var thumbsRequested = false
}

/// EditorModel 在这些时机调用下面的函数（工具是 .filter 时）。
extension EditorModel {
    /// 进入滤镜工具。
    func beginInstant() {
        instantState = InstantState()
    }

    /// 选了普通滤镜（或"原图"）：拍立得要取消选中。
    func clearInstant() {
        if !instantState.params.isIdentity { instantState.params.format = .none }
    }

    /// 点 ✓ 时要写进历史的那一步；没选拍立得返回 nil。选了拍立得时普通滤镜不生效（lookID 是 nil）。
    func instantOp() -> EditOp? {
        let params = instantState.params
        return params.isIdentity ? nil : .instant(params)
    }

    /// 选一种相纸；nil = 不要拍立得。
    func selectInstant(_ preset: InstantPreset?) {
        if let preset {
            // 换了相纸，裁掉的方向可能也变了，取景回到正中。
            if instantState.params.format != preset.format || instantState.params.rotated != preset.rotated {
                instantState.params.shift = 0
            }
            instantState.params.format = preset.format
            instantState.params.rotated = preset.rotated
        } else {
            instantState.params.format = .none
        }
        renderInstant()
    }

    func updateInstant(_ change: (inout InstantParams) -> Void) {
        change(&instantState.params)
        renderInstant()
    }

    private func renderInstant() {
        guard tool == .filter else { return }
        let params = instantState.params
        guard !params.isIdentity, let base = basePreview else {
            renderLook()
            return
        }
        // 拍立得和普通滤镜二选一。
        if lookID != nil { lookID = nil }
        showLive(params.apply(to: base))
    }

    /// 相纸缩略图：第一次翻到"拍立得"这一类时在后台做一遍。整张相纸都要放进按钮里，四周垫深色底。
    func prepareInstantThumbs() {
        guard tool == .filter, !instantState.thumbsRequested, let base = basePreview else { return }
        instantState.thumbsRequested = true
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let small = Renderer.shared.rasterize(base, maxDimension: 240) else { return }
            let tile = CGRect(x: 0, y: 0, width: 204, height: 252)
            let room = tile.insetBy(dx: 12, dy: 12)
            let back = CIImage(color: CIColor(red: 0.14, green: 0.14, blue: 0.14)).cropped(to: tile)
            var thumbs: [String: UIImage] = [:]
            for preset in InstantPreset.all {
                var params = InstantParams()
                params.format = preset.format
                params.rotated = preset.rotated
                let made = params.apply(to: small.ci)
                let e = made.extent
                let k = min(room.width / e.width, room.height / e.height)
                let fitted = made
                    .transformed(by: CGAffineTransform(scaleX: k, y: k))
                    .transformed(by: CGAffineTransform(translationX: (tile.midX - e.width * k / 2).rounded(),
                                                       y: (tile.midY - e.height * k / 2).rounded()))
                if let cg = Renderer.shared.cgImage(fitted.composited(over: back).cropped(to: tile)) {
                    thumbs[preset.id] = UIImage(cgImage: cg)
                }
            }
            await self?.setInstantThumbs(thumbs)
        }
    }

    private func setInstantThumbs(_ thumbs: [String: UIImage]) {
        guard tool == .filter else { return }
        instantState.thumbs = thumbs
    }
}
