import CoreImage
import UIKit

// MARK: - 文字 / 贴纸：渲染入口
//
// 数据在 OverlayModel.swift，实际的绘制在 OverlayDrawing.swift（Core Graphics + Core Text）。
// 画布上的预览和最终导出都走这里，保证所见即所得。

enum OverlayRenderer {
    /// 按"图片宽度 = imageWidth 像素"渲染一个文字/贴纸。
    /// 位图太大时会按 k 缩小渲染，调用方显示时再除以 k 放大回去。
    static func render(_ item: OverlayItem, imageWidth: CGFloat, maxPixel: CGFloat = 4096) -> (image: UIImage, k: CGFloat)? {
        guard imageWidth > 0 else { return nil }
        var fontSize = max(2, item.size * item.scale * imageWidth)
        var k: CGFloat = 1
        guard var layout = OverlayDrawing.layout(item, fontSize: fontSize) else { return nil }
        let m = max(layout.size.width, layout.size.height)
        if m > maxPixel {
            k = maxPixel / m
            fontSize *= k
            guard let smaller = OverlayDrawing.layout(item, fontSize: fontSize) else { return nil }
            layout = smaller
        }
        guard let cg = OverlayDrawing.draw(layout) else { return nil }
        return (UIImage(cgImage: cg), k)
    }

    /// 面板上的小样：固定像素大小渲染一项。
    static func thumbnail(_ item: OverlayItem, fontSize: CGFloat) -> UIImage? {
        guard let layout = OverlayDrawing.layout(item, fontSize: fontSize), let cg = OverlayDrawing.draw(layout) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func composite(_ items: [OverlayItem], over image: CIImage) -> CIImage {
        let extent = image.extent
        var out = image
        for item in items {
            guard let rendered = render(item, imageWidth: extent.width), let cg = rendered.image.cgImage else { continue }
            let ci = CIImage(cgImage: cg)
            let inv = 1 / rendered.k
            var t = CGAffineTransform(translationX: -ci.extent.width / 2, y: -ci.extent.height / 2)
            t = t.concatenating(CGAffineTransform(scaleX: inv, y: inv))
            t = t.concatenating(CGAffineTransform(rotationAngle: -item.rotation))
            t = t.concatenating(CGAffineTransform(translationX: item.center.x * extent.width,
                                                  y: (1 - item.center.y) * extent.height))
            out = ci.transformed(by: t).composited(over: out)
        }
        return out.cropped(to: extent)
    }
}

extension OverlayColor {
    init(_ color: UIColor) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        if !color.getRed(&r, green: &g, blue: &b, alpha: &a) {
            var w: CGFloat = 0
            color.getWhite(&w, alpha: &a)
            r = w; g = w; b = w
        }
        self.init(r, g, b)
    }

    var uiColor: UIColor { UIColor(red: r, green: g, blue: b, alpha: 1) }
}
