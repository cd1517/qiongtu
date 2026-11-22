import CoreImage
import UIKit

/// 图层的混合方式。
enum LayerBlend: String, CaseIterable, Identifiable {
    case normal, multiply, screen, overlay, softLight, lighten, darken, colorDodge, difference, luminosity, color

    var id: String { rawValue }

    var title: String {
        switch self {
        case .normal: return "正常"
        case .multiply: return "正片叠底"
        case .screen: return "滤色"
        case .overlay: return "叠加"
        case .softLight: return "柔光"
        case .lighten: return "变亮"
        case .darken: return "变暗"
        case .colorDodge: return "颜色减淡"
        case .difference: return "差值"
        case .luminosity: return "明度"
        case .color: return "颜色"
        }
    }

    /// 对应的 Core Image 滤镜；正常混合不用滤镜。
    var filter: String? {
        switch self {
        case .normal: return nil
        case .multiply: return "CIMultiplyBlendMode"
        case .screen: return "CIScreenBlendMode"
        case .overlay: return "CIOverlayBlendMode"
        case .softLight: return "CISoftLightBlendMode"
        case .lighten: return "CILightenBlendMode"
        case .darken: return "CIDarkenBlendMode"
        case .colorDodge: return "CIColorDodgeBlendMode"
        case .difference: return "CIDifferenceBlendMode"
        case .luminosity: return "CILuminosityBlendMode"
        case .color: return "CIColorBlendMode"
        }
    }
}

/// 叠在照片上面的一个图层：一张图，加上它自己的修改、抠图和摆放方式。
struct ImageLayer: Identifiable {
    /// 导入时最长边缩到多少。
    static let maxSide: CGFloat = 3072
    /// 拖动时用的小图最长边。
    static let previewSide: CGFloat = 1280

    var id = UUID()
    var name: String
    /// 导入时的图。
    let original: CGImage
    /// 这个图层自己的修改步骤（在单独的编辑器里做的）。
    var ops: [EditOp] = []
    /// original 套上 ops 之后的样子；没有修改时就是 original。
    var edited: CGImage
    /// 抠图蒙版（对应 edited）。
    var mask: CGImage?
    /// 是否去掉背景。
    var cutout = false
    /// 抠图边缘的羽化，0...1。
    var feather: CGFloat = 0.25
    /// 最终贴到画面上的图（抠过图的带透明通道）。
    var content: CGImage
    /// content 的小图，拖动、缩放时实时预览用。
    var preview: CGImage

    /// 中心点，归一化坐标，原点在左上角。
    var center = CGPoint(x: 0.5, y: 0.5)
    /// 图层的宽度占画面宽度的比例。
    var width: CGFloat = 0.6
    /// 弧度，正数 = 顺时针。
    var rotation: CGFloat = 0
    var flipped = false
    var opacity: CGFloat = 1
    var blend: LayerBlend = .normal
    var hidden = false

    /// 高 / 宽。
    var aspect: CGFloat { CGFloat(content.height) / CGFloat(max(content.width, 1)) }

    /// 从一张图建一个图层。耗时，放后台线程调。
    static func make(name: String, image: CIImage) -> ImageLayer? {
        let moved = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        let scaled = Renderer.shared.downscaled(moved, maxDimension: maxSide)
        guard let cg = Renderer.shared.cgImage(scaled) else { return nil }
        return ImageLayer(name: name, original: cg, edited: cg, content: cg, preview: makePreview(cg))
    }

    private static func makePreview(_ image: CGImage) -> CGImage {
        let ci = CIImage(cgImage: image)
        guard max(ci.extent.width, ci.extent.height) > previewSide else { return image }
        return Renderer.shared.cgImage(Renderer.shared.downscaled(ci, maxDimension: previewSide)) ?? image
    }

    /// 修改步骤或抠图设置变了之后，重新算 edited / mask / content / preview。耗时，放后台线程调。
    /// 返回 false = 要抠图但没识别到主体（这时 cutout 会被关掉）。
    @discardableResult
    mutating func rebuild(editedChanged: Bool) -> Bool {
        var ok = true
        if editedChanged {
            mask = nil
            if ops.isEmpty {
                edited = original
            } else {
                let out = EditOp.apply(ops, to: CIImage(cgImage: original))
                let moved = out.transformed(by: CGAffineTransform(translationX: -out.extent.minX, y: -out.extent.minY))
                edited = Renderer.shared.cgImage(Renderer.shared.downscaled(moved, maxDimension: Self.maxSide)) ?? original
            }
        }
        if cutout {
            if mask == nil { mask = Cutout.mask(for: edited) }
            if let mask, let cut = Cutout.cut(edited, mask: mask, feather: feather * 0.012) {
                content = cut
            } else {
                cutout = false
                content = edited
                ok = false
            }
        } else {
            content = edited
        }
        preview = Self.makePreview(content)
        return ok
    }

    /// 摆到画面里：缩放、翻转、旋转、平移、不透明度都算好。extent 是画面的范围。
    func placed(in extent: CGRect, usePreview: Bool) -> CIImage {
        var image = CIImage(cgImage: usePreview ? preview : content)
        var scale = width * extent.width / max(image.extent.width, 1)
        if !usePreview, scale < 0.6 {
            // 缩得多时先做一次高质量缩小，直接用仿射变换缩会出锯齿和摩尔纹。
            image = image.applyingFilter("CILanczosScaleTransform", parameters: [
                kCIInputScaleKey: scale,
                kCIInputAspectRatioKey: 1.0,
            ])
            scale = width * extent.width / max(image.extent.width, 1)
        }
        let box = image.extent
        var t = CGAffineTransform(translationX: -box.midX, y: -box.midY)
        if flipped { t = t.concatenating(CGAffineTransform(scaleX: -1, y: 1)) }
        t = t.concatenating(CGAffineTransform(scaleX: scale, y: scale))
        t = t.concatenating(CGAffineTransform(rotationAngle: -rotation))
        t = t.concatenating(CGAffineTransform(translationX: extent.minX + center.x * extent.width,
                                              y: extent.minY + (1 - center.y) * extent.height))
        image = image.transformed(by: t)
        if opacity < 1 {
            image = image.applyingFilter("CIColorMatrix", parameters: [
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: max(opacity, 0)),
            ])
        }
        return image
    }
}

/// 叠在照片上面的一组图层（从下到上），作为编辑历史里的一步。
struct LayerStack {
    var layers: [ImageLayer] = []

    var isEmpty: Bool { layers.isEmpty }

    func apply(to image: CIImage) -> CIImage {
        composite(over: image, usePreview: false)
    }

    /// usePreview = true 时用每个图层的小图，拖动时才跟得上手。
    func composite(over image: CIImage, usePreview: Bool) -> CIImage {
        let extent = image.extent
        var out = image
        for layer in layers where !layer.hidden && layer.opacity > 0 {
            let top = layer.placed(in: extent, usePreview: usePreview)
            if let filter = layer.blend.filter {
                out = top.applyingFilter(filter, parameters: [kCIInputBackgroundImageKey: out]).cropped(to: extent)
            } else {
                out = top.composited(over: out).cropped(to: extent)
            }
        }
        return out
    }
}

/// 请求单独编辑一个图层的内容（或者图层下面的照片）。
struct LayerEditRequest: Identifiable {
    let id = UUID()
    let title: String
    /// 从这张图开始。
    let source: CIImage
    /// 已经做过的修改。
    let ops: [EditOp]
    /// 点"完成"时把新的修改步骤交回来。
    var onDone: ([EditOp]) -> Void
}
