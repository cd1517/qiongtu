import SwiftUI
import UIKit

/// 封神、摄影棚共用：这张照片的主体是人，还是一个场景。
/// 人物 = 原来的玩法；场景 = 照片里的场景本身就是主体，AI 不许往里加人。
enum SubjectMode: String, CaseIterable, Identifiable {
    case person, scene

    var id: String { rawValue }

    var title: String { self == .person ? "人物" : "场景" }

    var symbol: String { self == .person ? "person.fill" : "mountain.2.fill" }

    /// 在本机判断：画面里有够大的人脸或人形算人物，否则算场景。远处的路人不算。
    static func detect(in image: UIImage) async -> SubjectMode {
        guard let cg = AIOneShot.prepared(image, maxSide: 1024).cgImage else { return .scene }
        return await Task.detached(priority: .userInitiated) {
            // 最大的那张脸占画面 0.15% 以上（全身照里的脸大约就这么大）。
            if let face = FaceDetector.detect(in: cg).first, face.box.width * face.box.height >= 0.0015 {
                return SubjectMode.person
            }
            // 没露脸的人（背影、侧身）：人形占画面 3% 以上。
            if let mask = PersonSegmenter.mask(for: cg), coverage(of: mask) >= 0.03 {
                return SubjectMode.person
            }
            return SubjectMode.scene
        }.value
    }

    /// 蒙版里白色占多大比例。
    private static func coverage(of mask: CGImage) -> Double {
        let w = 64, h = 64
        var pixels = [UInt8](repeating: 0, count: w * h)
        let white: Int = pixels.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return 0 }
            ctx.draw(mask, in: CGRect(x: 0, y: 0, width: w, height: h))
            return raw.reduce(0) { $0 + ($1 > 128 ? 1 : 0) }
        }
        return Double(white) / Double(w * h)
    }
}

/// 人物 / 场景 的两段开关，放在面板最上面。
struct SubjectModeBar: View {
    let selected: SubjectMode
    /// 标签前面加的字（摄影棚用"拍"，免得和底部的"场景""人物"菜单混在一起）。
    var prefix = ""
    var disabled = false
    var select: (SubjectMode) -> Void

    var body: some View {
        SegmentBar(items: SubjectMode.allCases, selected: selected, title: { prefix + $0.title },
                   symbol: { $0.symbol }, select: select)
            .frame(width: 196)
            .frame(maxWidth: .infinity)
            .disabled(disabled)
            .opacity(disabled ? 0.4 : 1)
            // 换主体时整块面板的内容和高度一起变，这个开关自己不要带过渡动画。
            .transaction { $0.animation = nil }
    }
}
