import CoreGraphics
import Foundation

/// 一个有名字的颜色。
struct MakeupSwatch: Identifiable {
    let name: String
    let color: MakeupColor
    var id: String { name }

    init(_ name: String, _ hex: UInt32) {
        self.name = name
        color = MakeupColor(hex: hex)
    }
}

/// 美妆的全部预设。都是自己调的参数，名字只是描述风格。
enum MakeupCatalog {
    // MARK: 口红

    static let lipColors: [MakeupSwatch] = [
        .init("豆沙", 0xB5626A), .init("正红", 0xC81E2C), .init("枫叶红", 0xB5432E), .init("烂番茄", 0xC8402C),
        .init("蜜桃", 0xF0876E), .init("奶茶", 0xC28A72), .init("裸粉", 0xDC9A96), .init("玫瑰", 0xC2466A),
        .init("莓果", 0x9C2350), .init("珊瑚", 0xF0705A), .init("斩男色", 0xD8545A), .init("姨妈红", 0x8E1B2A),
        .init("复古红", 0xA8232B), .init("西柚", 0xF06858), .init("樱花粉", 0xF2A0B0), .init("芭比粉", 0xE85A92),
        .init("肉桂", 0xA85C4A), .init("焦糖", 0xA65A3C), .init("南瓜", 0xD2603A), .init("酒红", 0x7A1830),
        .init("干枯玫瑰", 0xB0606C), .init("奶杏", 0xD9A08A), .init("蜜橘", 0xEE7A4A), .init("梅子", 0x7E2E52),
    ]

    static func lipColor(_ name: String) -> MakeupColor {
        lipColors.first { $0.name == name }?.color ?? lipColors[0].color
    }

    struct LipPreset: Identifiable {
        let name: String
        let lip: LipMakeup
        var id: String { name }
    }

    private static func lip(_ name: String, _ color: String, _ texture: MKLipTexture, _ style: MKLipStyle = .full,
                            amount: CGFloat = 0.8, liner: CGFloat = 0) -> LipPreset {
        LipPreset(name: name, lip: LipMakeup(color: lipColor(color), amount: amount, texture: texture, style: style, liner: liner))
    }

    static let lipPresets: [LipPreset] = [
        lip("气质裸粉", "裸粉", .satin, amount: 0.75),
        lip("柔感杏仁", "奶杏", .velvet, .blurred, amount: 0.8),
        lip("兔系唇", "樱花粉", .gloss, .gradient, amount: 0.85),
        lip("元气粉", "芭比粉", .gloss, amount: 0.6),
        lip("哑光正红", "正红", .matte, amount: 0.85),
        lip("丝绒豆沙", "豆沙", .velvet, amount: 0.85),
        lip("水光蜜桃", "蜜桃", .gloss, amount: 0.75),
        lip("咬唇西柚", "西柚", .satin, .gradient, amount: 0.85),
        lip("镜面玫瑰", "玫瑰", .glaze, amount: 0.75),
        lip("复古港风", "复古红", .matte, amount: 0.9, liner: 0.5),
        lip("奶茶雾面", "奶茶", .velvet, .blurred, amount: 0.85),
        lip("斩男水红", "斩男色", .gloss, amount: 0.75),
        lip("莓果咬唇", "莓果", .satin, .gradient, amount: 0.85),
        lip("枫叶哑光", "枫叶红", .matte, amount: 0.85),
        lip("烂番茄", "烂番茄", .velvet, amount: 0.85),
        lip("干枯玫瑰", "干枯玫瑰", .velvet, amount: 0.85),
        lip("肉桂奶茶", "肉桂", .matte, .blurred, amount: 0.85),
        lip("酒红镜面", "酒红", .glaze, amount: 0.8),
        lip("樱花果冻", "樱花粉", .glaze, amount: 0.55),
        lip("蜜橘元气", "蜜橘", .gloss, amount: 0.7),
        lip("梅子雾面", "梅子", .velvet, .blurred, amount: 0.85),
        lip("血橙咬唇", "南瓜", .satin, .gradient, amount: 0.85),
        lip("焦糖哑光", "焦糖", .matte, amount: 0.85),
        lip("姨妈红丝绒", "姨妈红", .velvet, amount: 0.9, liner: 0.3),
        lip("珊瑚水光", "珊瑚", .gloss, amount: 0.7),
        lip("裸感润唇", "裸粉", .gloss, amount: 0.45),
    ]

    // MARK: 眉毛

    static let browColors: [MakeupSwatch] = [
        .init("自然黑", 0x2A2422), .init("深棕", 0x3C2A20), .init("浅棕", 0x6A4A36), .init("灰棕", 0x55483F),
        .init("亚麻", 0x8A6E54), .init("红棕", 0x5E3324),
    ]

    static func browPreset(_ shape: MKBrowShape) -> BrowMakeup {
        switch shape {
        case .soft: return BrowMakeup(shape: shape, color: browColors[2].color, amount: 0.55)
        case .willow: return BrowMakeup(shape: shape, color: browColors[0].color, amount: 0.6)
        case .wild, .sword: return BrowMakeup(shape: shape, color: browColors[0].color, amount: 0.65)
        default: return BrowMakeup(shape: shape, color: browColors[1].color, amount: 0.6)
        }
    }

    // MARK: 眼妆

    struct ShadowPreset: Identifiable {
        let name: String
        let shadow: ShadowMakeup
        var id: String { name }
    }

    private static func shadow(_ name: String, _ lid: UInt32, _ crease: UInt32, _ outer: UInt32, lower: CGFloat = 0.3,
                               shimmer: CGFloat = 0, sparkle: Bool = false, smoke: CGFloat = 0) -> ShadowPreset {
        ShadowPreset(name: name, shadow: ShadowMakeup(lid: MakeupColor(hex: lid), crease: MakeupColor(hex: crease),
                                                   outer: MakeupColor(hex: outer), lower: lower, shimmer: shimmer,
                                                   sparkle: sparkle, smoke: smoke))
    }

    static let shadows: [ShadowPreset] = [
        shadow("大地", 0xB98A68, 0x8A5A3C, 0x5A3824),
        shadow("奶茶", 0xD2AE96, 0xA87E68, 0x7A5646, lower: 0.25),
        shadow("蜜桃", 0xF2A68A, 0xE0785C, 0xB85840, lower: 0.35, shimmer: 0.2),
        shadow("珊瑚橘", 0xF5A27E, 0xE8724E, 0xB8502E, lower: 0.35, shimmer: 0.3),
        shadow("樱花", 0xF4B8C4, 0xE890A4, 0xC06A84, lower: 0.4, shimmer: 0.3),
        shadow("玫瑰", 0xE09AA6, 0xC0607A, 0x8A3C56, shimmer: 0.2),
        shadow("酒红", 0xC07A84, 0x96404E, 0x641E30, lower: 0.5),
        shadow("枫叶", 0xD98A5A, 0xB85A34, 0x7E3A22, lower: 0.35, shimmer: 0.1),
        shadow("香槟", 0xEBD2B0, 0xC8A078, 0x9A7250, lower: 0.2, shimmer: 1),
        shadow("金棕", 0xD8B078, 0xA87840, 0x6E4A24, shimmer: 0.8),
        shadow("灰雾", 0xB0A8A8, 0x807880, 0x504A54),
        shadow("烟熏", 0x6A6468, 0x3C383C, 0x1E1C20, lower: 0.8, smoke: 0.8),
        shadow("紫藤", 0xC4A8D8, 0x9A78B8, 0x6A4E8A, shimmer: 0.4),
        shadow("海盐蓝", 0xA8C4DC, 0x7A9CC0, 0x4E6E96, shimmer: 0.3),
        shadow("橄榄", 0xA8A878, 0x7E7E4E, 0x56562E),
        shadow("星河", 0xC8C0E0, 0x8E86B8, 0x4E4878, shimmer: 0.8, sparkle: true),
    ]

    static func shadow(_ name: String, amount: CGFloat = 0.7, lower: CGFloat? = nil) -> ShadowMakeup {
        var s = (shadows.first { $0.name == name } ?? shadows[0]).shadow
        s.amount = amount
        if let lower { s.lower = lower }
        return s
    }

    static let linerColors: [MakeupSwatch] = [
        .init("黑色", 0x121010), .init("深棕", 0x4A2E22), .init("灰棕", 0x5A4C46), .init("酒红", 0x5E1C28), .init("藏蓝", 0x1C2A4E),
    ]

    static let lensColors: [MakeupSwatch] = [
        .init("自然棕", 0x9A6A3A), .init("巧克力", 0x6A4428), .init("蜜糖", 0xC0924E), .init("烟灰", 0x8A9098),
        .init("蓝灰", 0x6E8AA8), .init("冰蓝", 0x7EB0D8), .init("墨绿", 0x6A8A62), .init("雾紫", 0x9A7AB8),
        .init("樱粉", 0xD090A4), .init("黑曜", 0x2A2420),
    ]

    struct EyePreset: Identifiable {
        let name: String
        let eye: EyeMakeup
        var id: String { name }
    }

    private static func eye(_ name: String, _ configure: (inout EyeMakeup) -> Void) -> EyePreset {
        var e = EyeMakeup()
        configure(&e)
        return EyePreset(name: name, eye: e)
    }

    private static let brownLiner = MakeupColor(hex: 0x4A2E22)

    static let eyePresets: [EyePreset] = [
        eye("淡颜") { e in
            e.shadow = shadow("奶茶", amount: 0.5); e.liner = LinerMakeup(style: .thin, amount: 0.6); e.aegyo = 0.6
        },
        eye("温柔") { e in
            e.shadow = shadow("蜜桃", amount: 0.6); e.liner = LinerMakeup(style: .puppy, color: brownLiner, amount: 0.7)
            e.lashes = LashMakeup(style: .natural, amount: 0.7); e.aegyo = 0.5
        },
        eye("淡雅") { e in
            e.shadow = shadow("香槟", amount: 0.6); e.liner = LinerMakeup(style: .thin, color: brownLiner, amount: 0.6)
            e.lashes = LashMakeup(style: .natural, amount: 0.55)
        },
        eye("灰雾电波") { e in
            e.shadow = shadow("灰雾", amount: 0.7); e.liner = LinerMakeup(style: .wing, amount: 0.8)
            e.lashes = LashMakeup(style: .natural, amount: 0.8); e.aegyo = 0.5
        },
        eye("消肿") { e in
            e.shadow = shadow("大地", amount: 0.65, lower: 0.15); e.liner = LinerMakeup(style: .natural, color: brownLiner, amount: 0.7)
        },
        eye("纯欲") { e in
            e.shadow = shadow("樱花", amount: 0.6, lower: 0.5); e.liner = LinerMakeup(style: .thin, color: brownLiner, amount: 0.5)
            e.lashes = LashMakeup(style: .natural, amount: 0.8); e.aegyo = 0.9
            e.catchlight = .dot; e.catchlightAmount = 0.6
        },
        eye("元气") { e in
            e.shadow = shadow("珊瑚橘", amount: 0.6); e.liner = LinerMakeup(style: .natural, amount: 0.7)
            e.lashes = LashMakeup(style: .sunflower, amount: 0.8); e.aegyo = 0.5
            e.catchlight = .twin; e.catchlightAmount = 0.6
        },
        eye("日杂") { e in
            e.shadow = shadow("大地", amount: 0.45); e.liner = LinerMakeup(style: .natural, color: brownLiner, amount: 0.6)
            e.lashes = LashMakeup(style: .sunflower, amount: 0.9)
        },
        eye("狗狗眼") { e in
            e.shadow = shadow("奶茶", amount: 0.6, lower: 0.5); e.liner = LinerMakeup(style: .puppy, amount: 0.8)
            e.lashes = LashMakeup(style: .doll, amount: 0.7); e.aegyo = 0.6
        },
        eye("猫系") { e in
            e.shadow = shadow("玫瑰", amount: 0.6); e.liner = LinerMakeup(style: .cat, amount: 0.85)
            e.lashes = LashMakeup(style: .dense, amount: 0.7)
        },
        eye("港风") { e in
            e.shadow = shadow("金棕", amount: 0.7); e.liner = LinerMakeup(style: .wing, amount: 0.9)
            e.lashes = LashMakeup(style: .dense, amount: 0.85)
        },
        eye("微醺") { e in
            e.shadow = shadow("酒红", amount: 0.6, lower: 0.6); e.liner = LinerMakeup(style: .natural, color: brownLiner, amount: 0.6)
            e.lashes = LashMakeup(style: .natural, amount: 0.6); e.aegyo = 0.4
        },
        eye("小烟熏") { e in
            e.shadow = shadow("烟熏", amount: 0.7); e.liner = LinerMakeup(style: .cat, amount: 0.9)
            e.lashes = LashMakeup(style: .dense, amount: 0.9)
        },
        eye("欧美") { e in
            var s = shadow("大地", amount: 0.9); s.smoke = 0.3
            e.shadow = s; e.liner = LinerMakeup(style: .cat, amount: 0.9); e.lashes = LashMakeup(style: .doll, amount: 0.9)
            e.fold = .parallel; e.foldAmount = 0.5
        },
        eye("清冷") { e in
            e.shadow = shadow("海盐蓝", amount: 0.5); e.liner = LinerMakeup(style: .thin, amount: 0.7)
            e.lashes = LashMakeup(style: .natural, amount: 0.5)
        },
        eye("人鱼姬") { e in
            var s = shadow("紫藤", amount: 0.7); s.shimmer = 0.8; s.sparkle = true
            e.shadow = s; e.liner = LinerMakeup(style: .wing, amount: 0.7); e.lashes = LashMakeup(style: .dense, amount: 0.7)
        },
        eye("星河") { e in
            e.shadow = shadow("星河", amount: 0.7); e.liner = LinerMakeup(style: .wing, amount: 0.8)
            e.lashes = LashMakeup(style: .dense, amount: 0.8); e.catchlight = .star; e.catchlightAmount = 0.7
        },
    ]

    static func eyePreset(_ name: String) -> EyeMakeup {
        (eyePresets.first { $0.name == name } ?? eyePresets[0]).eye
    }

    // MARK: 修容

    struct ContourPreset: Identifiable {
        let name: String
        let contour: ContourMakeup
        var id: String { name }
    }

    private static func contour(_ name: String, _ configure: (inout ContourMakeup) -> Void) -> ContourPreset {
        var c = ContourMakeup()
        configure(&c)
        return ContourPreset(name: name, contour: c)
    }

    static let contourPresets: [ContourPreset] = [
        contour("自然立体") { c in c.noseShade = 0.5; c.noseLight = 0.5; c.cheekLight = 0.4; c.jaw = 0.35 },
        contour("立体鼻") { c in c.noseShade = 0.9; c.noseLight = 0.8 },
        contour("Doll") { c in
            c.forehead = 0.7; c.chinLight = 0.7; c.cheekLight = 0.6; c.underEye = 0.6; c.noseLight = 0.5; c.noseShade = 0.3
        },
        contour("纯欲") { c in c.underEye = 0.8; c.cupid = 0.8; c.noseLight = 0.5; c.cheekLight = 0.4 },
        contour("光泽感") { c in c.forehead = 0.8; c.cheekLight = 0.9; c.chinLight = 0.7; c.cupid = 0.7; c.noseLight = 0.7 },
        contour("小V脸") { c in c.jaw = 0.9; c.cheekShade = 0.7; c.chinLight = 0.5 },
        contour("混血骨相") { c in
            c.noseShade = 0.8; c.noseLight = 0.6; c.cheekShade = 0.7; c.temple = 0.6; c.jaw = 0.5; c.cheekLight = 0.5
        },
        contour("幼态") { c in c.forehead = 0.7; c.chinLight = 0.6; c.underEye = 0.7; c.cheekLight = 0.3 },
        contour("高级脸") { c in c.cheekShade = 0.9; c.jaw = 0.6; c.temple = 0.5; c.noseShade = 0.5; c.cheekLight = 0.6 },
        contour("小头") { c in c.temple = 0.9; c.jaw = 0.7; c.forehead = 0.3 },
    ]

    static func contourPreset(_ name: String) -> ContourMakeup {
        (contourPresets.first { $0.name == name } ?? contourPresets[0]).contour
    }

    // MARK: 腮红

    static let blushColors: [MakeupSwatch] = [
        .init("蜜桃", 0xF7A07E), .init("樱花粉", 0xF7A8C0), .init("玫瑰", 0xD8607A), .init("火龙果", 0xE8457A),
        .init("珊瑚", 0xF58A6E), .init("奶杏", 0xE8B090), .init("元气橘", 0xF58A4A), .init("腮紫", 0xC89AD8),
        .init("砖红", 0xC8604A), .init("豆沙", 0xC87878), .init("肉桂", 0xC88A6A), .init("桃红", 0xF06890),
    ]

    static func blushColor(_ name: String) -> MakeupColor {
        blushColors.first { $0.name == name }?.color ?? blushColors[0].color
    }

    struct BlushPreset: Identifiable {
        let name: String
        let blush: BlushMakeup
        var id: String { name }
    }

    private static func blush(_ name: String, _ color: String, _ placement: MKBlushPlacement, glow: Bool = false,
                              amount: CGFloat = 0.55) -> BlushPreset {
        BlushPreset(name: name, blush: BlushMakeup(color: blushColor(color), placement: placement, glow: glow, amount: amount))
    }

    static let blushPresets: [BlushPreset] = [
        blush("火龙果", "火龙果", .apple),
        blush("光感粉", "樱花粉", .apple, glow: true, amount: 0.65),
        blush("氛围", "珊瑚", .sweep),
        blush("粉团", "樱花粉", .doll, amount: 0.7),
        blush("腮紫", "腮紫", .underEye, amount: 0.6),
        blush("蜜桃", "蜜桃", .apple, amount: 0.6),
        blush("奶杏", "奶杏", .sweep, amount: 0.65),
        blush("纯欲", "桃红", .underEye, amount: 0.5),
        blush("晒伤", "砖红", .sunburn, amount: 0.5),
        blush("微醺", "玫瑰", .drunk, amount: 0.5),
        blush("元气橘", "元气橘", .apple, glow: true),
        blush("高级棕", "肉桂", .sweep, amount: 0.6),
        blush("玫瑰", "玫瑰", .high, amount: 0.5),
        blush("豆沙", "豆沙", .apple, amount: 0.55),
    ]

    static func blushPreset(_ name: String, amount: CGFloat? = nil) -> BlushMakeup {
        var b = (blushPresets.first { $0.name == name } ?? blushPresets[0]).blush
        if let amount { b.amount = amount }
        return b
    }

    // MARK: 妆容（一键整套）

    struct LookPreset: Identifiable {
        let name: String
        var lip: LipMakeup?
        var brow: BrowMakeup?
        var eye = EyeMakeup()
        var contour = ContourMakeup()
        var blush: BlushMakeup?
        var id: String { name }
    }

    private static func look(_ name: String, lip: String?, brow: MKBrowShape?, eye: String?, contour: String?,
                             blush: String?, _ tweak: ((inout LookPreset) -> Void)? = nil) -> LookPreset {
        var l = LookPreset(name: name)
        if let lip { l.lip = (lipPresets.first { $0.name == lip } ?? lipPresets[0]).lip }
        if let brow { l.brow = browPreset(brow) }
        if let eye { l.eye = eyePreset(eye) }
        if let contour { l.contour = contourPreset(contour) }
        if let blush { l.blush = blushPreset(blush) }
        tweak?(&l)
        return l
    }

    static let looks: [LookPreset] = [
        look("白开水", lip: "裸感润唇", brow: .natural, eye: nil, contour: nil, blush: "蜜桃") { l in
            l.brow?.amount = 0.4; l.blush?.amount = 0.35; l.eye.aegyo = 0.4
            l.eye.lashes = LashMakeup(style: .natural, amount: 0.45)
        },
        look("淡颜", lip: "气质裸粉", brow: .soft, eye: "淡颜", contour: "自然立体", blush: "奶杏") { l in
            l.blush?.amount = 0.4
        },
        look("伪素颜", lip: "樱花果冻", brow: .natural, eye: "淡雅", contour: "光泽感", blush: "光感粉") { l in
            l.brow?.amount = 0.45; l.blush?.amount = 0.4; l.contour.forehead = 0.4
        },
        look("纯欲", lip: "兔系唇", brow: .soft, eye: "纯欲", contour: "纯欲", blush: "纯欲"),
        look("蜜桃", lip: "水光蜜桃", brow: .natural, eye: "温柔", contour: "幼态", blush: "蜜桃"),
        look("韩系", lip: "咬唇西柚", brow: .straight, eye: "淡颜", contour: "自然立体", blush: "氛围") { l in
            l.blush?.amount = 0.4
        },
        look("日杂", lip: "斩男水红", brow: .natural, eye: "日杂", contour: nil, blush: "晒伤") { l in
            l.contour.freckles = .few; l.contour.freckleAmount = 0.5
        },
        look("元气", lip: "蜜橘元气", brow: .natural, eye: "元气", contour: "幼态", blush: "元气橘"),
        look("甜妹", lip: "元气粉", brow: .soft, eye: "狗狗眼", contour: "Doll", blush: "粉团"),
        look("微醺", lip: "莓果咬唇", brow: .natural, eye: "微醺", contour: nil, blush: "微醺"),
        look("千金", lip: "镜面玫瑰", brow: .willow, eye: "淡雅", contour: "光泽感", blush: "光感粉") { l in
            l.eye.lashes = LashMakeup(style: .dense, amount: 0.7); l.eye.catchlight = .twin; l.eye.catchlightAmount = 0.5
        },
        look("清冷", lip: "干枯玫瑰", brow: .willow, eye: "清冷", contour: "高级脸", blush: nil),
        look("玫瑰", lip: "丝绒豆沙", brow: .natural, eye: "猫系", contour: "自然立体", blush: "玫瑰"),
        look("枫叶", lip: "枫叶哑光", brow: .straight, eye: "消肿", contour: "自然立体", blush: "高级棕") { l in
            l.eye.shadow = shadow("枫叶", amount: 0.65)
        },
        look("港风", lip: "复古港风", brow: .wild, eye: "港风", contour: "混血骨相", blush: "高级棕") { l in
            l.blush?.amount = 0.4
        },
        look("复古", lip: "哑光正红", brow: .arch, eye: "灰雾电波", contour: "自然立体", blush: nil) { l in
            l.contour.moles = [.lip]
        },
        look("甜酷", lip: "梅子雾面", brow: .sword, eye: "猫系", contour: "小V脸", blush: "腮紫") { l in
            l.blush?.amount = 0.4
        },
        look("欧美", lip: "焦糖哑光", brow: .arch, eye: "欧美", contour: "混血骨相", blush: "高级棕") { l in
            l.lip?.liner = 0.4
        },
        look("小烟熏", lip: "酒红镜面", brow: .sword, eye: "小烟熏", contour: "高级脸", blush: nil),
        look("混血", lip: "奶茶雾面", brow: .wild, eye: "欧美", contour: "混血骨相", blush: "奶杏") { l in
            l.eye.lens = LensMakeup(color: lensColors[4].color, amount: 0.55); l.contour.freckles = .natural; l.contour.freckleAmount = 0.45
        },
        look("人鱼姬", lip: "樱花果冻", brow: .soft, eye: "人鱼姬", contour: "光泽感", blush: "腮紫") { l in
            l.eye.lens = LensMakeup(color: lensColors[7].color, amount: 0.5)
        },
        look("星河", lip: "镜面玫瑰", brow: .natural, eye: "星河", contour: "光泽感", blush: "光感粉"),
    ]
}

extension FaceMakeup {
    /// 套上一整套妆（原有的痣保留）。k 是整体浓淡：1 = 预设本来的样子。
    mutating func apply(_ look: MakeupCatalog.LookPreset, strength k: CGFloat) {
        let keptMoles = contour.moles.filter { if case .custom = $0 { return true } else { return false } }
        lip = look.lip
        brow = look.brow
        eye = look.eye
        contour = look.contour
        blush = look.blush
        contour.moles += keptMoles
        scale(by: k)
    }

    /// 所有浓淡一起乘上 k。
    mutating func scale(by k: CGFloat) {
        func s(_ v: CGFloat) -> CGFloat { min(max(v * k, 0), 1) }
        if lip != nil { lip!.amount = s(lip!.amount); lip!.liner = s(lip!.liner) }
        if brow != nil { brow!.amount = s(brow!.amount) }
        if blush != nil { blush!.amount = s(blush!.amount) }
        eye.scale(by: k)
        contour.scaleSculpt(by: k)
        contour.freckleAmount = s(contour.freckleAmount)
    }
}

extension EyeMakeup {
    mutating func scale(by k: CGFloat) {
        func s(_ v: CGFloat) -> CGFloat { min(max(v * k, 0), 1) }
        if shadow != nil { shadow!.amount = s(shadow!.amount) }
        if liner != nil { liner!.amount = s(liner!.amount) }
        if lashes != nil { lashes!.amount = s(lashes!.amount) }
        if lens != nil { lens!.amount = s(lens!.amount) }
        aegyo = s(aegyo)
        foldAmount = s(foldAmount)
        catchlightAmount = s(catchlightAmount)
    }
}

extension ContourMakeup {
    /// 立体 / 高光 / 阴影这几项一起乘上 k（雀斑和痣不动）。
    mutating func scaleSculpt(by k: CGFloat) {
        func s(_ v: CGFloat) -> CGFloat { min(max(v * k, 0), 1) }
        noseShade = s(noseShade); noseLight = s(noseLight); forehead = s(forehead); cheekLight = s(cheekLight)
        chinLight = s(chinLight); cupid = s(cupid); underEye = s(underEye); jaw = s(jaw); cheekShade = s(cheekShade)
        temple = s(temple)
    }

    /// 换一套立体修容，雀斑和痣保留。
    mutating func setSculpt(_ other: ContourMakeup) {
        noseShade = other.noseShade; noseLight = other.noseLight; forehead = other.forehead; cheekLight = other.cheekLight
        chinLight = other.chinLight; cupid = other.cupid; underEye = other.underEye; jaw = other.jaw
        cheekShade = other.cheekShade; temple = other.temple
    }
}
