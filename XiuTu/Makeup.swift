import CoreGraphics
import CoreImage
import Foundation

// MARK: - 数据

/// 一种化妆品的颜色（sRGB，0...1）。
struct MakeupColor: Equatable, Hashable {
    var r: CGFloat
    var g: CGFloat
    var b: CGFloat

    init(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) {
        self.r = r
        self.g = g
        self.b = b
    }

    init(hex: UInt32) {
        r = CGFloat((hex >> 16) & 0xFF) / 255
        g = CGFloat((hex >> 8) & 0xFF) / 255
        b = CGFloat(hex & 0xFF) / 255
    }

    static let white = MakeupColor(1, 1, 1)
    static let black = MakeupColor(0.07, 0.06, 0.06)

    var luma: CGFloat { 0.299 * r + 0.587 * g + 0.114 * b }

    func mix(_ other: MakeupColor, _ t: CGFloat) -> MakeupColor {
        MakeupColor(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)
    }

    func scaled(_ k: CGFloat) -> MakeupColor { MakeupColor(r * k, g * k, b * k) }

    /// 最亮的通道拉到 1：用来做"正片叠底"的染色，只变色、尽量不压暗。
    var normalized: MakeupColor {
        let m = max(r, g, b, 0.001)
        return MakeupColor(r / m, g / m, b / m)
    }
}

enum MKLipTexture: Int, CaseIterable, Identifiable {
    case matte, velvet, satin, gloss, glaze

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .matte: return "哑光"
        case .velvet: return "丝绒"
        case .satin: return "滋润"
        case .gloss: return "水光"
        case .glaze: return "镜面"
        }
    }
}

enum MKLipStyle: Int, CaseIterable, Identifiable {
    case full, gradient, blurred

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .full: return "满涂"
        case .gradient: return "咬唇"
        case .blurred: return "晕染"
        }
    }
}

struct LipMakeup: Equatable {
    var color: MakeupColor
    var amount: CGFloat = 0.8
    var texture: MKLipTexture = .satin
    var style: MKLipStyle = .full
    /// 唇线的浓淡，0 = 不画。
    var liner: CGFloat = 0
}

enum MKBrowShape: Int, CaseIterable, Identifiable {
    case natural, straight, wild, droop, willow, sword, arch, soft

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .natural: return "自然眉"
        case .straight: return "韩系平眉"
        case .wild: return "野生眉"
        case .droop: return "落尾眉"
        case .willow: return "柳叶眉"
        case .sword: return "剑眉"
        case .arch: return "欧式挑眉"
        case .soft: return "雾感淡眉"
        }
    }
}

struct BrowMakeup: Equatable {
    var shape: MKBrowShape
    var color: MakeupColor
    var amount: CGFloat = 0.55
}

struct ShadowMakeup: Equatable {
    var lid: MakeupColor
    var crease: MakeupColor
    var outer: MakeupColor
    /// 下眼睑带多少颜色。
    var lower: CGFloat = 0.3
    /// 眼皮中间的珠光。
    var shimmer: CGFloat = 0
    /// 亮片。
    var sparkle = false
    /// 整只眼睛周围的烟熏。
    var smoke: CGFloat = 0
    var amount: CGFloat = 0.7
}

enum MKLinerStyle: Int, CaseIterable, Identifiable {
    case thin, natural, wing, puppy, cat, full

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .thin: return "内眼线"
        case .natural: return "自然"
        case .wing: return "上扬"
        case .puppy: return "下垂"
        case .cat: return "猫眼"
        case .full: return "全包"
        }
    }
}

struct LinerMakeup: Equatable {
    var style: MKLinerStyle
    var color = MakeupColor.black
    var amount: CGFloat = 0.85
}

enum MKLashStyle: Int, CaseIterable, Identifiable {
    case natural, dense, sunflower, doll

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .natural: return "自然"
        case .dense: return "浓密"
        case .sunflower: return "太阳花"
        case .doll: return "娃娃"
        }
    }
}

struct LashMakeup: Equatable {
    var style: MKLashStyle
    var amount: CGFloat = 0.85
}

struct LensMakeup: Equatable {
    var color: MakeupColor
    var amount: CGFloat = 0.6
}

enum MKFoldStyle: Int, CaseIterable, Identifiable {
    case fan, parallel

    var id: Int { rawValue }

    var title: String { self == .fan ? "开扇" : "平行" }
}

enum MKCatchlight: Int, CaseIterable, Identifiable {
    case dot, twin, window, star, ring

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .dot: return "圆点"
        case .twin: return "双点"
        case .window: return "窗光"
        case .star: return "星芒"
        case .ring: return "环形"
        }
    }
}

struct EyeMakeup: Equatable {
    var shadow: ShadowMakeup?
    var liner: LinerMakeup?
    var lashes: LashMakeup?
    /// 卧蚕。
    var aegyo: CGFloat = 0
    var lens: LensMakeup?
    var fold: MKFoldStyle = .fan
    /// 双眼皮线的深浅，0 = 不画。
    var foldAmount: CGFloat = 0
    var catchlight: MKCatchlight = .dot
    /// 眼神光，0 = 不画。
    var catchlightAmount: CGFloat = 0

    var isEmpty: Bool {
        (shadow?.amount ?? 0) <= 0 && (liner?.amount ?? 0) <= 0 && (lashes?.amount ?? 0) <= 0 && aegyo <= 0
            && (lens?.amount ?? 0) <= 0 && foldAmount <= 0 && catchlightAmount <= 0
    }
}

enum MKFreckleStyle: Int, CaseIterable, Identifiable {
    case few, natural, sun

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .few: return "点点"
        case .natural: return "自然"
        case .sun: return "晒斑"
        }
    }
}

/// 一颗痣的位置：几个常见位置，或者用户自己点的（坐标是图片的万分比）。
enum MKMoleSpot: Hashable {
    case tearLeft, tearRight, lip, nose, cheek, chin
    case custom(Int, Int)

    static let presets: [MKMoleSpot] = [.tearLeft, .tearRight, .lip, .nose, .cheek, .chin]

    var title: String {
        switch self {
        case .tearLeft: return "左泪痣"
        case .tearRight: return "右泪痣"
        case .lip: return "唇上痣"
        case .nose: return "鼻梁痣"
        case .cheek: return "脸颊痣"
        case .chin: return "下巴痣"
        case .custom: return "自己点的"
        }
    }
}

struct ContourMakeup: Equatable {
    /// 鼻影、鼻梁高光。
    var noseShade: CGFloat = 0
    var noseLight: CGFloat = 0
    // 高光
    var forehead: CGFloat = 0
    var cheekLight: CGFloat = 0
    var chinLight: CGFloat = 0
    var cupid: CGFloat = 0
    var underEye: CGFloat = 0
    // 阴影
    var jaw: CGFloat = 0
    var cheekShade: CGFloat = 0
    var temple: CGFloat = 0
    // 雀斑和痣
    var freckles: MKFreckleStyle = .natural
    var freckleAmount: CGFloat = 0
    var moles: [MKMoleSpot] = []

    var isEmpty: Bool {
        noseShade <= 0 && noseLight <= 0 && forehead <= 0 && cheekLight <= 0 && chinLight <= 0 && cupid <= 0
            && underEye <= 0 && jaw <= 0 && cheekShade <= 0 && temple <= 0 && freckleAmount <= 0 && moles.isEmpty
    }

    /// 立体、高光、阴影这三组（不含雀斑和痣）。
    var sculptIsEmpty: Bool {
        noseShade <= 0 && noseLight <= 0 && forehead <= 0 && cheekLight <= 0 && chinLight <= 0 && cupid <= 0
            && underEye <= 0 && jaw <= 0 && cheekShade <= 0 && temple <= 0
    }
}

enum MKBlushPlacement: Int, CaseIterable, Identifiable {
    case apple, sweep, underEye, sunburn, high, doll, drunk

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .apple: return "苹果肌"
        case .sweep: return "斜扫"
        case .underEye: return "眼下"
        case .sunburn: return "晒伤"
        case .high: return "高位"
        case .doll: return "团状"
        case .drunk: return "微醺"
        }
    }
}

struct BlushMakeup: Equatable {
    var color: MakeupColor
    var placement: MKBlushPlacement = .apple
    /// 光泽质地：颧骨上带一点亮。
    var glow = false
    var amount: CGFloat = 0.55
}

/// 这张脸原本的明暗：上妆时要参照它，口红和美瞳才不会在亮脸、暗脸上差很多。
struct MKTone: Equatable {
    /// 嘴唇、瞳孔虹膜原来的平均亮度（sRGB，0...1）。
    var lip: CGFloat = 0.5
    var iris: CGFloat = 0.2
}

/// 一张脸上的全部妆。
struct FaceMakeup: Equatable {
    var face: FaceInfo
    var tone = MKTone()
    var lip: LipMakeup?
    var brow: BrowMakeup?
    var eye = EyeMakeup()
    var contour = ContourMakeup()
    var blush: BlushMakeup?

    var isEmpty: Bool {
        (lip?.amount ?? 0) <= 0 && (brow?.amount ?? 0) <= 0 && eye.isEmpty && contour.isEmpty && (blush?.amount ?? 0) <= 0
    }
}

/// 美妆：按人脸关键点画口红、眉毛、眼妆、修容、腮红，作为编辑历史里的一步。全部在本机完成。
struct MakeupParams: Equatable {
    var faces: [FaceMakeup] = []

    var isIdentity: Bool { faces.allSatisfy { $0.isEmpty || !$0.face.hasReliableLandmarks } }

    func apply(to image: CIImage) -> CIImage {
        guard !isIdentity else { return image }
        var out = image
        for face in faces where !face.isEmpty {
            out = MakeupRenderer.render(face, over: out)
        }
        return out.cropped(to: image.extent)
    }
}

// MARK: - 上妆

/// 一层妆：一张蒙版 + 怎么和皮肤混在一起。
private struct MakeupLayer {
    enum Mode {
        /// 正片叠底：像粉质的彩妆，叠上去变色、略微变深，皮肤纹理还在。
        case multiply(MakeupColor, CGFloat)
        /// 滤色：高光、珠光、眼神光。
        case screen(MakeupColor, CGFloat)
        /// 换色：口红、美瞳。保留原来的明暗纹理，颜色换成指定的。base = 这块地方原来的平均亮度，
        /// shine = 光泽（-1 哑光 … 1 水光），lift = 平均亮度往新颜色的亮度靠多少（1 = 完全变成新颜色的深浅）。
        case tint(MakeupColor, amount: CGFloat, base: CGFloat, shine: CGFloat, lift: CGFloat)
    }

    let shape: MKShape
    /// 蒙版边缘再羽化多少（这张脸方框的比例）。
    var blur: CGFloat = 0
    let mode: Mode
}

enum MakeupRenderer {
    // Core Image 的核函数写错了不会报错，只会拿到 nil，所以每一个都在 Mac 上单独编译验证过。
    static let multiplyKernel = CIColorKernel(source: """
    kernel vec4 mkMultiply(__sample s, __sample m, vec4 color) {
        float a = clamp(m.r * color.a, 0.0, 1.0);
        return vec4(s.rgb * mix(vec3(1.0), color.rgb, a), s.a);
    }
    """)

    static let screenKernel = CIColorKernel(source: """
    kernel vec4 mkScreen(__sample s, __sample m, vec4 color) {
        vec3 add = color.rgb * clamp(m.r * color.a, 0.0, 1.0);
        vec3 room = 1.0 - clamp(s.rgb, 0.0, 1.0);
        return vec4(s.rgb + add * room, s.a);
    }
    """)

    static let tintKernel = CIColorKernel(source: """
    kernel vec4 mkTint(__sample s, __sample m, vec4 color, vec4 p) {
        float a = clamp(m.r * color.a, 0.0, 1.0);
        vec3 c = s.rgb;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float tl = max(dot(color.rgb, vec3(0.299, 0.587, 0.114)), 0.02);
        float shine = p.y;
        float target = p.x + (tl - p.x) * p.z;
        float delta = l - p.x;
        float k = delta > 0.0 ? (0.62 + 0.3 * shine) : 0.9;
        float ln = clamp(target + delta * k, 0.0, 1.0);
        vec3 col = ln <= tl ? color.rgb * (ln / tl) : mix(color.rgb, vec3(1.0), (ln - tl) / max(1.0 - tl, 0.001));
        float spec = smoothstep(0.78, 0.97, l) * (0.25 + 0.75 * max(shine, 0.0));
        col = mix(col, max(col, c), spec);
        return vec4(mix(c, col, a), s.a);
    }
    """)

    static var kernelsReady: Bool { multiplyKernel != nil && screenKernel != nil && tintKernel != nil }

    static func render(_ makeup: FaceMakeup, over image: CIImage) -> CIImage {
        let extent = image.extent
        guard let g = MKFace(face: makeup.face, extent: extent.size) else { return image }
        // 这张脸的方框换到 Core Image 的坐标（原点左下角）。
        let roi = CGRect(x: extent.minX + g.roi.minX, y: extent.minY + extent.height - g.roi.maxY,
                         width: g.roi.width, height: g.roi.height)
        let work = roi.intersection(extent).integral
        guard work.width >= 4, work.height >= 4 else { return image }

        let layers = self.layers(for: makeup, g)
        guard !layers.isEmpty else { return image }
        let faceKey = key(for: makeup.face, size: extent.size)
        let fine = roi.width <= 1400 ? 1024 : 2048

        // 颜色的运算放在 sRGB（伽马）空间里做，和化妆品给人的观感一致。
        var out = image.cropped(to: work).applyingFilter("CILinearToSRGBToneCurve")
        for layer in layers {
            let size = layer.shape.raster(fine: fine)
            guard let cg = MKMaskCache.shared.image(.init(face: faceKey, shape: layer.shape, size: size), make: {
                g.drawMask(layer.shape, size: size, imageSize: extent.size)
            }) else { continue }
            var mask = CIImage(cgImage: cg, options: [.colorSpace: NSNull()])
            let k = roi.width / CGFloat(size)
            mask = mask.transformed(by: CGAffineTransform(scaleX: k, y: k))
                .transformed(by: CGAffineTransform(translationX: roi.minX, y: roi.minY))
            if layer.blur > 0 {
                mask = mask.clampedToExtent()
                    .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: max(0.5, layer.blur * roi.width)])
                    .cropped(to: roi)
            }
            switch layer.mode {
            case .multiply(let c, let a):
                guard a > 0, let kernel = multiplyKernel else { continue }
                out = kernel.apply(extent: work, arguments: [out, mask, CIVector(x: c.r, y: c.g, z: c.b, w: a)]) ?? out
            case .screen(let c, let a):
                guard a > 0, let kernel = screenKernel else { continue }
                out = kernel.apply(extent: work, arguments: [out, mask, CIVector(x: c.r, y: c.g, z: c.b, w: a)]) ?? out
            case .tint(let c, let a, let base, let shine, let lift):
                guard a > 0, let kernel = tintKernel else { continue }
                out = kernel.apply(extent: work, arguments: [out, mask, CIVector(x: c.r, y: c.g, z: c.b, w: a),
                                                             CIVector(x: base, y: shine, z: lift, w: 0)]) ?? out
            }
        }
        return out.applyingFilter("CISRGBToneCurveToLinear").composited(over: image)
    }

    /// 给缓存用的"这是哪张脸"：关键点和图片比例都一样才算同一张。
    private static func key(for face: FaceInfo, size: CGSize) -> Int {
        var hasher = Hasher()
        func add(_ points: [CGPoint]) {
            for p in points {
                hasher.combine(Int((p.x * 100_000).rounded()))
                hasher.combine(Int((p.y * 100_000).rounded()))
            }
        }
        add(face.contour); add(face.leftEye); add(face.rightEye); add(face.leftBrow); add(face.rightBrow)
        add(face.outerLips); add(face.innerLips); add(face.nose); add(face.noseCrest ?? [])
        add([face.leftPupil, face.rightPupil].compactMap { $0 })
        hasher.combine(Int((size.width / max(size.height, 1) * 10_000).rounded()))
        return hasher.finalize()
    }

    // MARK: 每一样妆拆成哪几层

    private static let shade = MakeupColor(0.60, 0.46, 0.38)
    private static let light = MakeupColor(1.0, 0.96, 0.90)

    private static func layers(for m: FaceMakeup, _ g: MKFace) -> [MakeupLayer] {
        var list: [MakeupLayer] = []
        let d = g.eyeDist, fw = g.faceWidth
        let eyeWidth = max(g.eyes[0].width, g.eyes[1].width)

        // 修容：阴影
        let c = m.contour
        if c.noseShade > 0 {
            list.append(.init(shape: .noseShade, blur: d * 0.06, mode: .multiply(shade, 0.85 * c.noseShade)))
        }
        if c.jaw > 0 { list.append(.init(shape: .shJaw, blur: fw * 0.045, mode: .multiply(shade, 0.90 * c.jaw))) }
        if c.cheekShade > 0 {
            list.append(.init(shape: .shCheek, blur: fw * 0.05, mode: .multiply(shade, 0.85 * c.cheekShade)))
        }
        if c.temple > 0 { list.append(.init(shape: .shTemple, mode: .multiply(shade, 0.75 * c.temple))) }

        // 腮红
        if let blush = m.blush, blush.amount > 0 {
            list.append(.init(shape: .blush(blush.placement),
                              mode: .multiply(blush.color.normalized.mix(.white, 0.2), 0.62 * blush.amount)))
            // 很浅的粉色光靠叠底显不出来，再提一点亮。
            let pale = mkSmoothstep(0.55, 0.8, blush.color.luma)
            if pale > 0 {
                list.append(.init(shape: .blush(blush.placement), mode: .screen(blush.color, 0.16 * pale * blush.amount)))
            }
            if blush.glow {
                list.append(.init(shape: .blushGlow(blush.placement), mode: .screen(light, 0.24 * blush.amount)))
            }
        }

        // 雀斑
        if c.freckleAmount > 0 {
            list.append(.init(shape: .freckles(c.freckles), blur: d * 0.004,
                              mode: .multiply(MakeupColor(0.70, 0.50, 0.38), 0.55 * c.freckleAmount)))
        }

        // 修容：高光
        if c.noseLight > 0 {
            list.append(.init(shape: .noseLight, blur: d * 0.035, mode: .screen(light, 0.44 * c.noseLight)))
        }
        if c.forehead > 0 { list.append(.init(shape: .hlForehead, mode: .screen(light, 0.32 * c.forehead))) }
        if c.cheekLight > 0 { list.append(.init(shape: .hlCheek, mode: .screen(light, 0.27 * c.cheekLight))) }
        if c.chinLight > 0 { list.append(.init(shape: .hlChin, mode: .screen(light, 0.32 * c.chinLight))) }
        if c.cupid > 0 { list.append(.init(shape: .hlCupid, mode: .screen(light, 0.2 * c.cupid))) }
        if c.underEye > 0 { list.append(.init(shape: .hlUnderEye, mode: .screen(light, 0.28 * c.underEye))) }

        // 眉毛
        if let brow = m.brow, brow.amount > 0, let length = g.brows.compactMap({ $0?.length }).max() {
            let soft = brow.shape == .soft
            list.append(.init(shape: .browFill(brow.shape), blur: length * (soft ? 0.07 : 0.035),
                              mode: .multiply(brow.color.mix(.white, 0.18), (soft ? 0.6 : 0.82) * brow.amount)))
            list.append(.init(shape: .browHair(brow.shape), blur: length * 0.004,
                              mode: .multiply(brow.color, (soft ? 0.2 : 0.5) * brow.amount)))
        }

        // 眼影
        if let s = m.eye.shadow, s.amount > 0 {
            list.append(.init(shape: .shadowCrease, blur: eyeWidth * 0.14, mode: .multiply(s.crease, 0.55 * s.amount)))
            list.append(.init(shape: .shadowLid, blur: eyeWidth * 0.07, mode: .multiply(s.lid, 0.75 * s.amount)))
            list.append(.init(shape: .shadowOuter, mode: .multiply(s.outer, 0.48 * s.amount)))
            if s.lower > 0 {
                list.append(.init(shape: .shadowLower, blur: eyeWidth * 0.05,
                                  mode: .multiply(s.crease.mix(s.outer, 0.4), 0.6 * s.lower * s.amount)))
            }
            if s.smoke > 0 {
                list.append(.init(shape: .shadowSmoke, blur: eyeWidth * 0.07, mode: .multiply(s.outer, 0.5 * s.smoke * s.amount)))
            }
            if s.shimmer > 0 {
                list.append(.init(shape: .shimmer, mode: .screen(s.lid.mix(.white, 0.6), 0.42 * s.shimmer * s.amount)))
            }
            if s.sparkle {
                list.append(.init(shape: .sparkle, blur: eyeWidth * 0.004, mode: .screen(.white, 0.85 * s.amount)))
            }
        }
        if m.eye.foldAmount > 0 {
            list.append(.init(shape: .fold(m.eye.fold), blur: eyeWidth * 0.012,
                              mode: .multiply(MakeupColor(0.45, 0.31, 0.27), 0.6 * m.eye.foldAmount)))
        }
        if m.eye.aegyo > 0 {
            list.append(.init(shape: .aegyoLight, blur: eyeWidth * 0.045, mode: .screen(MakeupColor(1, 0.94, 0.90), 0.46 * m.eye.aegyo)))
            list.append(.init(shape: .aegyoShade, blur: eyeWidth * 0.035,
                              mode: .multiply(MakeupColor(0.72, 0.57, 0.53), 0.6 * m.eye.aegyo)))
        }
        if let lens = m.eye.lens, lens.amount > 0 {
            list.append(.init(shape: .lens, blur: eyeWidth * 0.012,
                              mode: .tint(lens.color, amount: 0.85 * lens.amount, base: m.tone.iris, shine: 0.3, lift: 0.34)))
            list.append(.init(shape: .lensRays, blur: eyeWidth * 0.004, mode: .screen(lens.color.mix(.white, 0.5), 0.15 * lens.amount)))
            list.append(.init(shape: .lensRing, blur: eyeWidth * 0.008, mode: .multiply(lens.color.scaled(0.25), 0.62 * lens.amount)))
        }
        if let liner = m.eye.liner, liner.amount > 0 {
            list.append(.init(shape: .liner(liner.style), blur: eyeWidth * 0.006, mode: .multiply(liner.color, liner.amount)))
        }
        if let lashes = m.eye.lashes, lashes.amount > 0 {
            list.append(.init(shape: .lashes(lashes.style), blur: eyeWidth * 0.002, mode: .multiply(.black, lashes.amount)))
        }
        if m.eye.catchlightAmount > 0 {
            list.append(.init(shape: .catchlight(m.eye.catchlight), blur: eyeWidth * 0.006,
                              mode: .screen(.white, m.eye.catchlightAmount)))
        }

        // 口红
        if let lip = m.lip, lip.amount > 0 {
            let w = g.mouthWidth
            let gain: CGFloat, shine: CGFloat
            switch lip.texture {
            case .matte: gain = 1; shine = -1
            case .velvet: gain = 0.95; shine = -0.6
            case .satin: gain = 0.88; shine = 0
            case .gloss: gain = 0.78; shine = 0.7
            case .glaze: gain = 0.84; shine = 1
            }
            let shape: MKShape = lip.style == .gradient ? .lipGradient : .lipFull
            let blur = lip.style == .blurred ? w * 0.09 : (lip.style == .gradient ? w * 0.05 : w * 0.032)
            list.append(.init(shape: shape, blur: blur,
                              mode: .tint(lip.color, amount: gain * lip.amount, base: m.tone.lip, shine: shine, lift: 0.9)))
            if lip.liner > 0 {
                list.append(.init(shape: .lipLine, blur: w * 0.014, mode: .multiply(lip.color.scaled(0.72), 0.6 * lip.liner)))
            }
            let gloss: CGFloat = lip.texture == .glaze ? 0.5 : (lip.texture == .gloss ? 0.3 : (lip.texture == .satin ? 0.1 : 0))
            if gloss > 0 {
                list.append(.init(shape: .lipGloss(lip.texture == .glaze), blur: w * 0.012, mode: .screen(.white, gloss * lip.amount)))
            }
        }

        // 痣
        if !c.moles.isEmpty {
            list.append(.init(shape: .moles(c.moles), blur: d * 0.004, mode: .multiply(MakeupColor(0.30, 0.20, 0.16), 0.9)))
        }
        return list
    }
}

// MARK: - 量一下这张脸原本的明暗

extension MKTone {
    /// 在照片上量出嘴唇和虹膜原来的平均亮度。量不出来的项保持默认值。
    static func measure(face: FaceInfo, in image: CGImage) -> MKTone {
        var tone = MKTone()
        let size = CGSize(width: image.width, height: image.height)
        guard let g = MKFace(face: face, extent: size) else { return tone }
        let n = 192
        var pixels = [UInt8](repeating: 0, count: n * n * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            let k = CGFloat(n) / g.roi.width
            ctx.interpolationQuality = .medium
            // 把这张脸的方框铺满整张小图（位图的原点在左下角，所以 y 要翻一下）。
            ctx.draw(image, in: CGRect(x: -g.roi.minX * k, y: -(size.height - g.roi.maxY) * k,
                                       width: size.width * k, height: size.height * k))
            return true
        }
        guard drawn else { return tone }
        func average(_ shape: MKShape) -> CGFloat? {
            guard let mask = g.drawMask(shape, size: n, imageSize: size) else { return nil }
            var weights = [UInt8](repeating: 0, count: n * n)
            let ok: Bool = weights.withUnsafeMutableBytes { raw in
                guard let ctx = CGContext(data: raw.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
                ctx.draw(mask, in: CGRect(x: 0, y: 0, width: n, height: n))
                return true
            }
            guard ok else { return nil }
            var sum: CGFloat = 0, total: CGFloat = 0
            for i in 0..<(n * n) where weights[i] > 128 {
                let r = CGFloat(pixels[i * 4]), gr = CGFloat(pixels[i * 4 + 1]), b = CGFloat(pixels[i * 4 + 2])
                sum += (0.299 * r + 0.587 * gr + 0.114 * b) / 255
                total += 1
            }
            return total >= 6 ? sum / total : nil
        }
        if let lip = average(.lipFull) { tone.lip = min(max(lip, 0.2), 0.8) }
        if let iris = average(.lens) { tone.iris = min(max(iris, 0.08), 0.5) }
        return tone
    }
}
