import CoreImage
import SwiftUI

extension EditorModel {
    // MARK: 调色

    func setAdjust(_ key: AdjustKey, _ value: Float) {
        adjust[key] = abs(value) < 0.015 ? 0 : value
        renderAdjust()
    }

    /// 改滑块以外的调色参数（HSL、曲线、色调分离、局部调色……），改完刷新预览。
    func changeAdjust(_ body: (inout AdjustParams) -> Void) {
        body(&adjust)
        renderAdjust()
    }

    /// 打开或关闭调色里的子面板。
    func setAdjustSub(_ sub: AdjustSub?) {
        adjust.ui.sub = sub
        if sub == .local, adjust.locals.first(where: { $0.id == adjust.ui.localID }) == nil {
            adjust.ui.localID = adjust.locals.last?.id
        }
        renderAdjust()
    }

    func renderAdjust() {
        guard tool == .adjust, let base = basePreview else { return }
        if adjust.ui.sub == .local, !adjust.locals.isEmpty {
            // 局部调色时在预览上标出每个点和选中点的范围；标记只画在屏幕预览上，不会进最后的图。
            showLive(AdjustFX.markers(adjust.apply(to: base), points: adjust.locals, selected: adjust.ui.localID))
        } else {
            submit(.adjust(base, adjust))
        }
    }

    /// 局部调色最多放几个点。
    static let localLimit = 8

    /// 调色时单击了画面。point 是归一化图片坐标（原点左上角）。
    /// 只在"局部调色"里有用：点在已有的点附近就选中它，否则在这里新放一个点。
    func adjustTapped(at point: CGPoint) {
        guard tool == .adjust, adjust.ui.sub == .local, let base = basePreview else { return }
        let size = base.extent.size
        let dim = max(size.width, size.height)
        guard dim > 0 else { return }
        func distance(_ p: LocalAdjust) -> CGFloat {
            hypot((p.center.x - point.x) * size.width, (p.center.y - point.y) * size.height) / dim
        }
        if let near = adjust.locals.min(by: { distance($0) < distance($1) }), distance(near) < 0.045 {
            adjust.ui.localID = near.id
        } else if adjust.locals.count < Self.localLimit {
            let added = LocalAdjust(center: CGPoint(x: min(max(point.x, 0), 1), y: min(max(point.y, 0), 1)))
            adjust.locals.append(added)
            adjust.ui.localID = added.id
        }
        renderAdjust()
    }

    /// 当前选中的局部调色点。
    var selectedLocal: LocalAdjust? {
        guard let id = adjust.ui.localID else { return nil }
        return adjust.locals.first { $0.id == id }
    }

    func changeSelectedLocal(_ body: (inout LocalAdjust) -> Void) {
        guard let id = adjust.ui.localID, let index = adjust.locals.firstIndex(where: { $0.id == id }) else { return }
        body(&adjust.locals[index])
        renderAdjust()
    }

    func removeSelectedLocal() {
        guard let id = adjust.ui.localID else { return }
        adjust.locals.removeAll { $0.id == id }
        adjust.ui.localID = adjust.locals.last?.id
        renderAdjust()
    }
}
