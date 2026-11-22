import CoreImage
import UIKit

/// 本机美颜的各个项目。全部用 Core Image 完成，不联网、实时预览。
enum BeautyKey: String, CaseIterable, Identifiable {
    case smooth, whiten, slim, eyes, lips, blush

    var id: String { rawValue }

    var title: String {
        switch self {
        case .smooth: return "磨皮"
        case .whiten: return "美白"
        case .slim: return "瘦脸"
        case .eyes: return "大眼"
        case .lips: return "口红"
        case .blush: return "腮红"
        }
    }

    var symbol: String {
        switch self {
        case .smooth: return "wand.and.stars"
        case .whiten: return "sun.max"
        case .slim: return "arrow.right.and.line.vertical.and.arrow.left"
        case .eyes: return "eye"
        case .lips: return "mouth"
        case .blush: return "heart.circle"
        }
    }
}

struct BeautyPreset: Identifiable {
    let title: String
    let values: [BeautyKey: CGFloat]
    var lipColor = 0
    var id: String { title }

    static let all: [BeautyPreset] = [
        BeautyPreset(title: "自然", values: [.smooth: 0.35, .whiten: 0.2, .slim: 0.15, .eyes: 0.1]),
        BeautyPreset(title: "清透", values: [.smooth: 0.5, .whiten: 0.45, .slim: 0.15, .eyes: 0.15, .blush: 0.2]),
        BeautyPreset(title: "精致", values: [.smooth: 0.55, .whiten: 0.3, .slim: 0.35, .eyes: 0.3, .lips: 0.35, .blush: 0.25], lipColor: 0),
        BeautyPreset(title: "网感", values: [.smooth: 0.7, .whiten: 0.5, .slim: 0.5, .eyes: 0.45, .lips: 0.5, .blush: 0.4], lipColor: 2),
    ]
}

struct BeautyParams: Equatable {
    var faces: [FaceInfo] = []
    private(set) var values: [BeautyKey: CGFloat] = [:]
    var lipColor = 0

    static let lipColors: [(name: String, color: UIColor)] = [
        ("豆沙", UIColor(red: 0.72, green: 0.36, blue: 0.38, alpha: 1)),
        ("正红", UIColor(red: 0.80, green: 0.12, blue: 0.18, alpha: 1)),
        ("珊瑚", UIColor(red: 0.95, green: 0.42, blue: 0.36, alpha: 1)),
        ("莓果", UIColor(red: 0.62, green: 0.13, blue: 0.30, alpha: 1)),
        ("裸粉", UIColor(red: 0.85, green: 0.55, blue: 0.55, alpha: 1)),
    ]

    subscript(key: BeautyKey) -> CGFloat {
        get { values[key] ?? 0 }
        set { values[key] = newValue <= 0 ? nil : min(newValue, 1) }
    }

    mutating func set(_ preset: BeautyPreset?) {
        values = preset?.values ?? [:]
        if let preset { lipColor = preset.lipColor }
    }

    var isIdentity: Bool { values.isEmpty || faces.isEmpty }

    // MARK: 应用

    func apply(to image: CIImage) -> CIImage {
        guard !isIdentity else { return image }
        let extent = image.extent
        let faceWidth = faces.map { Self.width(of: $0, in: extent) }.max() ?? extent.width * 0.3
        var out = image

        let smooth = self[.smooth]
        let whiten = self[.whiten]
        if smooth > 0 || whiten > 0, let skin = skinMask(extent: extent, feather: faceWidth * 0.03) {
            if smooth > 0 {
                // 先把皮肤上的色块抹匀，再把细纹理补回来一些，避免磨成塑料脸。
                let a = 0.75 * smooth
                let radius = max(2, faceWidth * 0.03)
                let blurred = out.clampedToExtent()
                    .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
                    .cropped(to: extent)
                var soft = FX.mix(out, blurred, t: a)
                // 只在亮度上补锐，避免在明暗交界处锐化出彩色的小点。
                soft = soft.clampedToExtent()
                    .applyingFilter("CISharpenLuminance", parameters: [
                        kCIInputSharpnessKey: min(1.2, a / (1 - a)) * 0.45,
                        kCIInputRadiusKey: max(1, radius / 5),
                    ])
                    .cropped(to: extent)
                out = Self.blend(soft, over: out, mask: skin)
            }
            if whiten > 0 {
                var c = ColorParams()
                c.brightness = Float(0.3 * whiten)
                c.shadows = Float(0.2 * whiten)
                c.saturation = Float(-0.08 * whiten)
                out = Self.blend(ColorCube.apply(c, to: out), over: out, mask: skin)
            }
        }

        let lips = self[.lips]
        if lips > 0, let mask = lipMask(extent: extent) {
            let color = Self.lipColors[min(max(lipColor, 0), Self.lipColors.count - 1)].color
            let layer = CIImage(color: CIColor(color: color)).cropped(to: extent)
            // 用口红的色相和饱和度，保留嘴唇原本的明暗和纹理。
            let tinted = layer.applyingFilter("CIColorBlendMode", parameters: [kCIInputBackgroundImageKey: out])
            out = Self.blend(tinted, over: out, mask: Self.scaled(mask, by: lips * 0.85))
        }

        let blush = self[.blush]
        if blush > 0, let mask = blushMask(extent: extent) {
            let layer = CIImage(color: CIColor(red: 1.0, green: 0.42, blue: 0.48)).cropped(to: extent)
            out = Self.blend(layer, over: out, mask: Self.scaled(mask, by: blush * 0.32))
        }

        // 变形放在最后做，这样前面的蒙版还和五官位置对得上。
        let slim = self[.slim]
        let eyes = self[.eyes]
        if slim > 0 || eyes > 0 {
            func px(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * extent.width, y: (1 - p.y) * extent.height) }
            for face in faces where face.hasReliableLandmarks {
                let w = Self.width(of: face, in: extent)
                if slim > 0, face.contour.count >= 8 {
                    let all = face.contour.map(px)
                    let center = Self.centroid(face.nose.isEmpty ? all : face.nose.map(px))
                    for index in [all.count / 4, all.count * 3 / 4] {
                        let cheek = all[index]
                        // 收缩中心放在脸颊内侧，脸的边缘就会被往里带。
                        let c = CGPoint(x: cheek.x + (center.x - cheek.x) * 0.3, y: cheek.y + (center.y - cheek.y) * 0.3)
                        out = Self.bump(out, center: c, radius: w * 0.4, scale: -0.28 * slim, extent: extent)
                    }
                }
                if eyes > 0 {
                    for (points, pupil) in [(face.leftEye, face.leftPupil), (face.rightEye, face.rightPupil)] where points.count >= 3 {
                        let all = points.map(px)
                        let c = pupil.map(px) ?? Self.centroid(all)
                        let eyeWidth = (all.map(\.x).max() ?? 0) - (all.map(\.x).min() ?? 0)
                        out = Self.bump(out, center: c, radius: max(4, eyeWidth * 1.1), scale: 0.28 * eyes, extent: extent)
                    }
                }
            }
        }
        return out
    }

    // MARK: 工具

    private static func width(of face: FaceInfo, in extent: CGRect) -> CGFloat {
        if let a = face.contour.first, let b = face.contour.last {
            return hypot((a.x - b.x) * extent.width, (a.y - b.y) * extent.height)
        }
        return face.box.width * extent.width
    }

    private static func centroid(_ points: [CGPoint]) -> CGPoint {
        guard !points.isEmpty else { return .zero }
        let sum = points.reduce(CGPoint.zero) { CGPoint(x: $0.x + $1.x, y: $0.y + $1.y) }
        return CGPoint(x: sum.x / CGFloat(points.count), y: sum.y / CGFloat(points.count))
    }

    private static func blend(_ top: CIImage, over bottom: CIImage, mask: CIImage) -> CIImage {
        top.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: bottom,
            kCIInputMaskImageKey: mask,
        ]).cropped(to: bottom.extent)
    }

    private static func scaled(_ mask: CIImage, by k: CGFloat) -> CIImage {
        mask.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: k, y: 0, z: 0, w: 0),
            "inputGVector": CIVector(x: 0, y: k, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: k, w: 0),
        ])
    }

    private static func bump(_ image: CIImage, center: CGPoint, radius: CGFloat, scale: CGFloat, extent: CGRect) -> CIImage {
        image.clampedToExtent()
            .applyingFilter("CIBumpDistortion", parameters: [
                kCIInputCenterKey: CIVector(x: center.x, y: center.y),
                kCIInputRadiusKey: radius,
                kCIInputScaleKey: scale,
            ])
            .cropped(to: extent)
    }

    // MARK: 蒙版

    /// 在一张灰度位图上画蒙版。位图最长边限制在 1536，之后放大到图片尺寸并羽化。
    private final class MaskCanvas {
        let ctx: CGContext
        let w: CGFloat
        let h: CGFloat
        let scale: CGFloat

        init?(extent: CGRect) {
            scale = min(1, 1536 / max(extent.width, extent.height))
            let pw = max(1, Int((extent.width * scale).rounded()))
            let ph = max(1, Int((extent.height * scale).rounded()))
            guard let c = CGContext(data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0,
                                    space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
            ctx = c
            w = CGFloat(pw)
            h = CGFloat(ph)
            ctx.setFillColor(gray: 0, alpha: 1)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.setLineJoin(.round)
            ctx.setLineCap(.round)
        }

        func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * w, y: (1 - p.y) * h) }

        /// 填充多边形，grow > 0 时再向外描一圈把形状撑大。
        func fill(_ points: [CGPoint], white: Bool, grow: CGFloat = 0) {
            guard points.count >= 3 else { return }
            let gray: CGFloat = white ? 1 : 0
            ctx.setFillColor(gray: gray, alpha: 1)
            ctx.setStrokeColor(gray: gray, alpha: 1)
            ctx.beginPath()
            ctx.addLines(between: points)
            ctx.closePath()
            if grow > 0 {
                ctx.setLineWidth(grow * 2)
                ctx.drawPath(using: .fillStroke)
            } else {
                ctx.fillPath()
            }
        }

        func image(extent: CGRect, feather: CGFloat) -> CIImage? {
            guard let cg = ctx.makeImage() else { return nil }
            var m = CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
            m = m.transformed(by: CGAffineTransform(scaleX: extent.width / m.extent.width, y: extent.height / m.extent.height))
            if feather > 0 {
                m = m.clampedToExtent()
                    .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: feather])
                    .cropped(to: extent)
            }
            return m
        }
    }

    private static func span(_ points: [CGPoint]) -> CGFloat {
        guard let minX = points.map(\.x).min(), let maxX = points.map(\.x).max() else { return 0 }
        return maxX - minX
    }

    /// 皮肤区域：下颌轮廓加上估算的额头，挖掉眼睛、眉毛和嘴唇。
    private func skinMask(extent: CGRect, feather: CGFloat) -> CIImage? {
        guard let canvas = MaskCanvas(extent: extent) else { return nil }
        var drew = false
        for face in faces where !face.hasReliableLandmarks {
            // 五官不可信时，用人脸框估一个椭圆当皮肤范围。
            let o = canvas.point(CGPoint(x: face.box.minX - face.box.width * 0.05, y: face.box.maxY))
            canvas.ctx.setFillColor(gray: 1, alpha: 1)
            canvas.ctx.fillEllipse(in: CGRect(x: o.x, y: o.y, width: face.box.width * 1.1 * canvas.w,
                                              height: face.box.height * 1.35 * canvas.h))
            drew = true
        }
        for face in faces where face.hasReliableLandmarks {
            let contour = face.contour.map(canvas.point)
            let a = contour[0]
            let b = contour[contour.count - 1]
            let chin = contour[contour.count / 2]
            let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            let half = hypot(a.x - mid.x, a.y - mid.y)
            let upLength = max(hypot(mid.x - chin.x, mid.y - chin.y), 1)
            let up = CGPoint(x: (mid.x - chin.x) / upLength, y: (mid.y - chin.y) / upLength)
            // Vision 不给额头的点，用一段半椭圆补上。
            var polygon = contour
            for step in stride(from: 11, through: 1, by: -1) {
                let t = CGFloat(step) / 12 * .pi
                polygon.append(CGPoint(x: mid.x + cos(t) * (a.x - mid.x) + sin(t) * up.x * half * 1.1,
                                       y: mid.y + cos(t) * (a.y - mid.y) + sin(t) * up.y * half * 1.1))
            }
            canvas.fill(polygon, white: true)
            for eye in [face.leftEye, face.rightEye] {
                let p = eye.map(canvas.point)
                canvas.fill(p, white: false, grow: Self.span(p) * 0.3)
            }
            for brow in [face.leftBrow, face.rightBrow] {
                let p = brow.map(canvas.point)
                canvas.fill(p, white: false, grow: Self.span(p) * 0.08)
            }
            let lips = face.outerLips.map(canvas.point)
            canvas.fill(lips, white: false, grow: Self.span(lips) * 0.06)
            drew = true
        }
        return drew ? canvas.image(extent: extent, feather: feather) : nil
    }

    private func lipMask(extent: CGRect) -> CIImage? {
        guard let canvas = MaskCanvas(extent: extent) else { return nil }
        var widest: CGFloat = 0
        for face in faces where face.hasReliableLandmarks && face.outerLips.count >= 3 {
            let outer = face.outerLips.map(canvas.point)
            canvas.fill(outer, white: true)
            canvas.fill(face.innerLips.map(canvas.point), white: false)
            widest = max(widest, Self.span(outer))
        }
        guard widest > 0 else { return nil }
        return canvas.image(extent: extent, feather: widest / canvas.scale * 0.035)
    }

    private func blushMask(extent: CGRect) -> CIImage? {
        // 中心白、边缘黑的不透明渐变，用"滤色"叠加，两团腮红重叠时不会互相盖掉。
        guard let canvas = MaskCanvas(extent: extent),
              let gradient = CGGradient(colorSpace: CGColorSpaceCreateDeviceGray(),
                                        colorComponents: [1, 1, 0, 1], locations: [0, 1], count: 2) else { return nil }
        canvas.ctx.setBlendMode(.screen)
        var drew = false
        for face in faces where face.hasReliableLandmarks && face.outerLips.count >= 3 {
            let l = Self.centroid(face.leftEye.map(canvas.point))
            let r = Self.centroid(face.rightEye.map(canvas.point))
            let mouth = Self.centroid(face.outerLips.map(canvas.point))
            let eyeMid = CGPoint(x: (l.x + r.x) / 2, y: (l.y + r.y) / 2)
            let eyeDistance = max(hypot(l.x - r.x, l.y - r.y), 1)
            let down = CGPoint(x: mouth.x - eyeMid.x, y: mouth.y - eyeMid.y)
            let ends = [canvas.point(face.contour[0]), canvas.point(face.contour[face.contour.count - 1])]
            // 每只眼睛到同侧脸边缘的距离 = 这半边脸露出来多宽。侧脸时远离镜头的那边很窄，就不画。
            func sideWidth(_ eye: CGPoint) -> CGFloat { ends.map { hypot($0.x - eye.x, $0.y - eye.y) }.min() ?? 0 }
            let wl = sideWidth(l)
            let wr = sideWidth(r)
            let widest = max(wl, wr, 1)
            for (eye, other, side) in [(l, r, wl), (r, l, wr)] where side / widest > 0.55 {
                let out = CGPoint(x: (eye.x - other.x) / eyeDistance, y: (eye.y - other.y) / eyeDistance)
                let c = CGPoint(x: eye.x + down.x * 0.55 + out.x * side * 0.15,
                                y: eye.y + down.y * 0.55 + out.y * side * 0.15)
                canvas.ctx.drawRadialGradient(gradient, startCenter: c, startRadius: 0,
                                              endCenter: c, endRadius: side * 0.8, options: [])
            }
            drew = true
        }
        canvas.ctx.setBlendMode(.normal)
        return drew ? canvas.image(extent: extent, feather: 0) : nil
    }
}

// MARK: - AI 美颜的风格

struct BeautyStyle: Identifiable {
    let title: String
    let prompt: String
    var id: String { title }

    /// 所有风格共用的前提：只做修饰，必须还是同一个人。
    static let preamble = """
    This is a close-up crop from a portrait photo. Retouch the face like a top professional beauty retoucher. \
    The person must remain clearly the same individual: keep their identity, face shape, facial proportions, eye color, \
    expression, gaze, head pose, hairstyle, hair color, accessories, clothing, lighting direction and background. \
    Keep the exact same framing and position of every feature so the result can be overlaid on the original. \
    Keep realistic skin texture with visible pores; no plastic or airbrushed look. Photorealistic result. Style:
    """

    /// 满意脸型：第二张图是用户自己修满意的脸，照着它修。
    static let favorite = BeautyStyle(title: "满意脸型", prompt: """
    match the user's own favorite retouch. The SECOND image is a reference: a face this user retouched before and is happy with. \
    Retouch the face in the FIRST image so it has the same look as the reference — the same face shape and jaw contour, face slimness, \
    eye size and shape, nose and lip refinement, skin smoothness and glow, and the same makeup style and colors. \
    Keep the identity of the person in the FIRST image: if the reference looks like a different person, transfer only the retouching \
    (slimness, eye enlargement, skin finish, makeup), never their facial identity. Return only the edited first image.
    """)

    static let all: [BeautyStyle] = [
        BeautyStyle(title: "自然", prompt: "natural retouch. Even out skin tone, remove blemishes and reduce dark circles, slightly brighter eyes. Very subtle; it should look like a great photo of them on a good day."),
        BeautyStyle(title: "韩系", prompt: "Korean beauty look. Luminous dewy glass skin, clear and bright complexion, soft straight brows, gentle gradient lips, soft rosy cheeks, delicate and fresh."),
        BeautyStyle(title: "减龄", prompt: "fresh youthful look. Smooth fine lines and under-eye hollows, slightly fuller cheeks, brighter clearer eyes, soft healthy glow. Still clearly the same adult."),
        BeautyStyle(title: "精致", prompt: "refined polished look. Flawless but textured skin, crisp well-defined features, subtle contour and highlight, neat brows, elegant understated makeup."),
        BeautyStyle(title: "骨相", prompt: "enhanced bone structure. Use light and shadow to bring out cheekbones, a cleaner jawline, brow bone and nose bridge. Sculpted and elegant, without changing who they are."),
        BeautyStyle(title: "消肿", prompt: "de-puffed look. Reduce facial puffiness and swelling, less puffy under-eyes and cheeks, slightly tighter contour, more awake and fresh."),
        BeautyStyle(title: "立体", prompt: "more three-dimensional face. Stronger contour and highlight so the features have more depth, defined nose bridge and cheekbones."),
        BeautyStyle(title: "紧致", prompt: "firm lifted look. Tighter jawline, softened nasolabial folds, firmer smoother skin, lifted cheeks."),
        BeautyStyle(title: "补光", prompt: "beauty lighting. Add soft flattering frontal fill light on the face, brighten it evenly, gentle catchlights in the eyes, reduce harsh shadows."),
        BeautyStyle(title: "欧美", prompt: "Western glam makeup. Sculpted contour, warm bronzed skin, defined arched brows, smoky neutral eyeshadow, fuller matte lips."),
        BeautyStyle(title: "浓颜", prompt: "bold striking look. Deeper eye makeup, stronger brows, more saturated lips, high-contrast defined features."),
        BeautyStyle(title: "网感", prompt: "social-media influencer look. Bright flawless skin, big bright sparkling eyes, small refined face, rosy cheeks and glossy lips."),
        BeautyStyle(title: "裸感", prompt: "no-makeup makeup look. Bare healthy skin with natural glow, barely-there lip tint, soft natural brows."),
        BeautyStyle(title: "甜妹", prompt: "sweet cute look. Soft pink blush, glossy pink lips, bright round eyes, gentle and adorable."),
    ]
}
