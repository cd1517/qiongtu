import CoreGraphics
import CoreImage
import Foundation
import simd

/// 皮肤工具的分类（底部确认栏中间的那排标签）。
enum SkinTab: String, CaseIterable, Identifiable {
    case skin, repair, plump, lift, eyes, brush

    var id: String { rawValue }

    var title: String {
        switch self {
        case .skin: return "皮肤"
        case .repair: return "祛皱祛痘"
        case .plump: return "丰盈"
        case .lift: return "提拉"
        case .eyes: return "眼部牙齿"
        case .brush: return "修容笔"
        }
    }
}

/// 皮肤工具里每一个带滑块的项目。除了"冷暖"是 -1...1，其余都是 0...1。
enum SkinKey: String, CaseIterable, Identifiable {
    // 皮肤
    case smooth, even, texture, white, warmth, rosy, deshine
    // 祛皱祛痘
    case foreheadLines, eyeLines, nasolabial, neckLines, acne
    // 丰盈
    case plumpForehead, plumpTear, plumpApple, plumpCheek, plumpNoseBase, plumpTemple
    // 提拉
    case liftContour, liftForehead, liftEye, liftMid, liftMouth
    // 眼部牙齿
    case eyeBright, darkCircle, eyeBag, redEye, eyeWhite, teeth

    var id: String { rawValue }

    var title: String {
        switch self {
        case .smooth: return "磨皮"
        case .even: return "匀肤"
        case .texture: return "肤质"
        case .white: return "美白"
        case .warmth: return "冷暖"
        case .rosy: return "红润"
        case .deshine: return "去油光"
        case .foreheadLines: return "抬头纹"
        case .eyeLines: return "眼周纹"
        case .nasolabial: return "法令纹"
        case .neckLines: return "颈纹"
        case .acne: return "祛痘"
        case .plumpForehead: return "额头"
        case .plumpTear: return "泪沟"
        case .plumpApple: return "苹果肌"
        case .plumpCheek: return "面颊"
        case .plumpNoseBase: return "鼻基底"
        case .plumpTemple: return "太阳穴"
        case .liftContour: return "轮廓"
        case .liftForehead: return "额头"
        case .liftEye: return "眼周"
        case .liftMid: return "面中"
        case .liftMouth: return "口周"
        case .eyeBright: return "亮眼"
        case .darkCircle: return "黑眼圈"
        case .eyeBag: return "眼袋"
        case .redEye: return "红血丝"
        case .eyeWhite: return "眼白"
        case .teeth: return "牙齿"
        }
    }

    var symbol: String {
        switch self {
        case .smooth: return "wand.and.stars"
        case .even: return "circle.lefthalf.filled"
        case .texture: return "aqi.medium"
        case .white: return "sun.max"
        case .warmth: return "thermometer.medium"
        case .rosy: return "heart"
        case .deshine: return "drop"
        case .foreheadLines: return "line.3.horizontal"
        case .eyeLines: return "eye"
        case .nasolabial: return "mouth"
        case .neckLines: return "person.bust"
        case .acne: return "circle.dotted"
        case .eyeBright: return "sparkle"
        case .darkCircle: return "moon"
        case .eyeBag: return "eye.trianglebadge.exclamationmark"
        case .redEye: return "eye.slash"
        case .eyeWhite: return "eye.circle"
        case .teeth: return "face.smiling"
        default: return "face.dashed"
        }
    }

    var tab: SkinTab {
        switch self {
        case .smooth, .even, .texture, .white, .warmth, .rosy, .deshine: return .skin
        case .foreheadLines, .eyeLines, .nasolabial, .neckLines, .acne: return .repair
        case .plumpForehead, .plumpTear, .plumpApple, .plumpCheek, .plumpNoseBase, .plumpTemple: return .plump
        case .liftContour, .liftForehead, .liftEye, .liftMid, .liftMouth: return .lift
        case .eyeBright, .darkCircle, .eyeBag, .redEye, .eyeWhite, .teeth: return .eyes
        }
    }

    /// true = 滑块范围 -100...100。
    var bipolar: Bool { self == .warmth }

    /// 五官关键点不可信时（脸太小、太糊，或者在模拟器里）还能不能用。只有按肤色和人脸框就能做的几项可以。
    var needsLandmarks: Bool {
        switch self {
        case .smooth, .even, .texture, .white, .warmth, .rosy, .deshine, .acne: return false
        default: return true
        }
    }

    static func keys(in tab: SkinTab) -> [SkinKey] { allCases.filter { $0.tab == tab } }
}

/// 一键美肤 / 一键丰盈 / 一键提拉：一组搭配好的数值，取的是"看得出来但不过头"的量。
struct SkinPreset: Identifiable, Equatable {
    let title: String
    let symbol: String
    let values: [SkinKey: CGFloat]
    var id: String { title }

    static let skin = SkinPreset(title: "一键美肤", symbol: "sparkles",
                                 values: [.smooth: 0.45, .even: 0.4, .white: 0.2, .deshine: 0.25, .acne: 0.3])
    static let plump = SkinPreset(title: "一键丰盈", symbol: "sparkles",
                                  values: [.plumpForehead: 0.35, .plumpTear: 0.45, .plumpApple: 0.4,
                                           .plumpCheek: 0.3, .plumpNoseBase: 0.35, .plumpTemple: 0.25])
    static let lift = SkinPreset(title: "一键提拉", symbol: "sparkles",
                                 values: [.liftContour: 0.4, .liftForehead: 0.35, .liftEye: 0.35,
                                          .liftMid: 0.4, .liftMouth: 0.35])
}

/// 肤色的几种现成搭配（美白 / 冷暖 / 红润）。
struct SkinTonePreset: Identifiable, Equatable {
    let title: String
    let white: CGFloat
    let warmth: CGFloat
    let rosy: CGFloat
    var id: String { title }

    static let all: [SkinTonePreset] = [
        SkinTonePreset(title: "自然白", white: 0.25, warmth: 0, rosy: 0.1),
        SkinTonePreset(title: "冷白皮", white: 0.5, warmth: -0.4, rosy: 0),
        SkinTonePreset(title: "暖白", white: 0.4, warmth: 0.3, rosy: 0.1),
        SkinTonePreset(title: "粉白", white: 0.4, warmth: -0.1, rosy: 0.55),
        SkinTonePreset(title: "象牙", white: 0.3, warmth: 0.15, rosy: 0.2),
        SkinTonePreset(title: "小麦", white: 0, warmth: 0.55, rosy: 0.15),
    ]
}

/// 一张脸上的全部皮肤调整。
struct SkinFace: Equatable {
    var face: FaceInfo
    /// 这张脸的平均肤色（Core Image 工作空间里的线性 RGB），进工具时量一次。按颜色认皮肤、统一肤色都靠它。
    var tone: SIMD3<Float>
    private(set) var values: [SkinKey: CGFloat] = [:]

    init(face: FaceInfo, tone: SIMD3<Float>) {
        self.face = face
        self.tone = tone
    }

    subscript(key: SkinKey) -> CGFloat {
        get { values[key] ?? 0 }
        set {
            let v = key.bipolar ? min(max(newValue, -1), 1) : min(max(newValue, 0), 1)
            values[key] = abs(v) < 0.005 ? nil : v
        }
    }

    var isIdentity: Bool { values.isEmpty }

    func matches(_ preset: SkinPreset) -> Bool {
        preset.values.allSatisfy { abs(self[$0.key] - $0.value) < 0.005 }
    }

    mutating func set(_ preset: SkinPreset, on: Bool) {
        for (key, value) in preset.values { self[key] = on ? value : 0 }
    }
}

/// 画笔的一笔：修容笔的高光 / 阴影，遮瑕笔，点痘（只有一个点的遮瑕），橡皮。
struct SkinStroke: Equatable {
    enum Kind: Int {
        case highlight, shadow, conceal
        /// 橡皮：只擦修容笔画的高光和阴影。
        case erase
    }

    var kind: Kind
    /// 归一化图片坐标，原点左上角。
    var points: [CGPoint]
    /// 笔的粗细，以图片长边为 1。
    var width: CGFloat
    /// 0...1。
    var strength: CGFloat
}

/// 皮肤工具的全部修改，作为编辑历史里的一步。所有位置和大小都是相对值，预览图和原图效果一致。
struct SkinParams: Equatable {
    var faces: [SkinFace] = []
    var strokes: [SkinStroke] = []
    /// 肤色统一：把脖子、身体的肤色往脸上靠。0...1。
    var unify: CGFloat = 0
    /// 人像蒙版（白 = 人）。有它的话"肤色统一"只动人身上的皮肤，不会碰到颜色相近的背景；没有就只按颜色认。
    var bodyMask: CGImage? = nil

    static func == (a: SkinParams, b: SkinParams) -> Bool {
        a.faces == b.faces && a.strokes == b.strokes && a.unify == b.unify && a.bodyMask === b.bodyMask
    }

    var isIdentity: Bool {
        strokes.allSatisfy { $0.kind == .erase } && faces.allSatisfy(\.isIdentity) && (unify <= 0 || faces.isEmpty)
    }

    func apply(to image: CIImage) -> CIImage {
        guard !isIdentity else { return image }
        var out = image
        // 先遮瑕 / 点痘，后面的磨皮看到的就是干净的皮肤。
        out = SkinEngine.conceal(out, strokes: strokes)
        for face in faces where !face.isIdentity {
            out = SkinEngine.retouch(out, face: face)
        }
        if unify > 0, let reference = faces.first {
            out = SkinEngine.unify(out, reference: reference, amount: unify, body: bodyMask)
        }
        out = SkinEngine.contour(out, strokes: strokes)
        // 变形放在最后做，这样前面的蒙版还和五官位置对得上。
        for face in faces where !face.isIdentity {
            out = SkinEngine.lift(out, face: face)
        }
        return out.cropped(to: image.extent)
    }
}
