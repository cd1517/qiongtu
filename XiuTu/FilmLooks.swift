import CoreImage
import Foundation
import simd

enum LookCategory: String, CaseIterable, Identifiable {
    case camera, filmcam, instant, negative, slide, mono, cinema, vintage, master

    var id: String { rawValue }

    var title: String {
        switch self {
        case .camera: return "相机"
        case .filmcam: return "胶片相机"
        case .instant: return "拍立得"
        case .master: return "大师 AI"
        case .negative: return "彩色负片"
        case .slide: return "反转片"
        case .mono: return "黑白"
        case .cinema: return "电影"
        case .vintage: return "复古"
        }
    }
}

/// 一款胶片风格：颜色部分烘成 LUT，再叠加暗角、柔光。
/// 这些都是自己调的参数，不是任何厂商的官方配方。
/// 滤镜不自带颗粒：想要颗粒，用户自己在"调色 → 颗粒"里加。
struct FilmLook: Identifiable {
    let id: String
    let name: String
    let category: LookCategory
    var color = ColorParams()
    var vignette: CGFloat = 0
    var bloom: CGFloat = 0

    func apply(to image: CIImage, intensity: CGFloat) -> CIImage {
        guard intensity > 0 else { return image }
        var out = ColorCube.apply(color, to: image, cacheKey: id)
        if intensity < 1 { out = FX.mix(image, out, t: intensity) }
        out = FX.bloom(out, amount: bloom * intensity)
        out = FX.vignette(out, amount: vignette * intensity)
        return out
    }

    private static func make(_ id: String, _ name: String, _ category: LookCategory,
                             vignette: CGFloat = 0, bloom: CGFloat = 0,
                             _ configure: (inout ColorParams) -> Void) -> FilmLook {
        var color = ColorParams()
        configure(&color)
        return FilmLook(id: id, name: name, category: category, color: color,
                        vignette: vignette, bloom: bloom)
    }

    static let all: [FilmLook] = [
        // 相机风格：按各家相机公认的成像特点自己调的近似效果，不是厂商的官方色彩配置。
        make("leica", "徕卡风", .camera, vignette: 0.22) { c in
            // 反差扎实、暗部深、颜色克制偏暖。
            c.contrast = 0.38; c.saturation = -0.12; c.shadows = -0.18; c.highlights = -0.12; c.temperature = 0.06
            c.shadowTint = SIMD3(-0.008, 0.0, 0.012); c.highlightTint = SIMD3(0.02, 0.012, -0.012)
        },
        make("leicabw", "徕卡黑白", .camera, vignette: 0.25) { c in
            c.mono = true; c.monoWeights = SIMD3(0.36, 0.52, 0.12); c.contrast = 0.6; c.shadows = -0.25; c.highlights = -0.1
        },
        make("hassel", "哈苏风", .camera) { c in
            // 自然色彩：干净、过渡柔和、高光不刺眼。
            c.contrast = 0.1; c.saturation = 0.04; c.vibrance = 0.1; c.highlights = -0.3; c.shadows = 0.18; c.temperature = 0.03
            c.highlightTint = SIMD3(0.008, 0.006, 0.0)
        },
        make("contax", "康泰时 T2 风", .camera, vignette: 0.18) { c in
            // 蔡司味：通透偏冷、蓝色浓、反差高。
            c.contrast = 0.32; c.saturation = 0.14; c.temperature = -0.08; c.tint = -0.03
            c.shadowTint = SIMD3(-0.015, 0.0, 0.03); c.highlightTint = SIMD3(0.012, 0.01, 0.0)
        },
        make("grpos", "理光 GR 正片", .camera, vignette: 0.15) { c in
            c.contrast = 0.48; c.saturation = 0.22; c.shadows = -0.22; c.temperature = -0.04
            c.shadowTint = SIMD3(-0.01, 0.005, 0.025)
        },
        make("grbw", "理光 GR 黑白", .camera, vignette: 0.3) { c in
            c.mono = true; c.monoWeights = SIMD3(0.45, 0.45, 0.1); c.contrast = 0.9; c.shadows = -0.5; c.highlights = 0.2
        },
        make("fujicn", "富士经典负片", .camera) { c in
            c.contrast = 0.3; c.saturation = -0.18; c.highlights = -0.2; c.tint = -0.05; c.fade = 0.03
            c.shadowTint = SIMD3(-0.012, 0.018, 0.02); c.highlightTint = SIMD3(0.03, 0.012, -0.02)
        },
        make("rollei", "禄来双反", .camera, vignette: 0.3, bloom: 0.25) { c in
            c.contrast = -0.05; c.saturation = -0.2; c.fade = 0.1; c.temperature = 0.1; c.brightness = 0.06
            c.shadowTint = SIMD3(0.012, 0.006, -0.01); c.highlightTint = SIMD3(0.025, 0.018, -0.015)
        },
        make("seagull", "海鸥老相机", .camera, vignette: 0.42, bloom: 0.2) { c in
            c.contrast = -0.12; c.saturation = -0.35; c.fade = 0.2; c.temperature = 0.2; c.tint = 0.06
            c.shadowTint = SIMD3(0.02, 0.01, -0.015); c.highlightTint = SIMD3(0.04, 0.03, -0.03)
        },
        make("lomo", "LOMO", .camera, vignette: 0.7) { c in
            c.contrast = 0.5; c.saturation = 0.38; c.tint = -0.08
            c.shadowTint = SIMD3(-0.02, 0.01, 0.05); c.highlightTint = SIMD3(0.05, 0.04, -0.04)
        },
        make("ccd", "CCD 数码", .camera, bloom: 0.2) { c in
            // 2000 年代小数码：偏冷、偏亮、颜色艳、高光容易溢出。
            c.exposure = 0.12; c.contrast = 0.22; c.saturation = 0.2; c.temperature = -0.14; c.highlights = 0.25
            c.shadowTint = SIMD3(-0.01, 0.0, 0.025)
        },
        make("disposable", "一次性相机", .camera, vignette: 0.35) { c in
            c.contrast = 0.25; c.saturation = 0.12; c.temperature = 0.16; c.tint = -0.06; c.fade = 0.1
            c.shadowTint = SIMD3(-0.01, 0.02, 0.01); c.highlightTint = SIMD3(0.04, 0.02, -0.03)
        },
        // 胶片相机：按这些老相机常见的成片感觉调的近似效果。只能模拟色彩、影调和暗角，镜头的虚化模拟不了。
        make("nikonf", "尼康 F", .filmcam, vignette: 0.22) { c in
            // 60 年代新闻纪实的味道：扎实的反差，偏暖。
            c.contrast = 0.36; c.saturation = 0.06; c.temperature = 0.1; c.shadows = -0.15; c.fade = 0.04
            c.shadowTint = SIMD3(0.0, 0.004, 0.012); c.highlightTint = SIMD3(0.03, 0.015, -0.02)
        },
        make("nikonfm2", "尼康 FM2", .filmcam, vignette: 0.12) { c in
            c.contrast = 0.26; c.saturation = 0.14; c.temperature = 0.03
            c.shadowTint = SIMD3(-0.008, 0.004, 0.015); c.highlightTint = SIMD3(0.015, 0.01, -0.008)
        },
        make("canonae1", "佳能 AE-1", .filmcam, vignette: 0.15, bloom: 0.15) { c in
            // 暖、柔、红色讨喜。
            c.contrast = 0.12; c.saturation = 0.1; c.temperature = 0.16; c.fade = 0.07; c.highlights = -0.1
            c.highlightTint = SIMD3(0.035, 0.015, -0.02)
        },
        make("minoltax700", "美能达 X-700", .filmcam, vignette: 0.14) { c in
            c.contrast = 0.15; c.saturation = 0.05; c.temperature = 0.08; c.tint = -0.04; c.fade = 0.06
            c.shadowTint = SIMD3(-0.006, 0.012, 0.008); c.highlightTint = SIMD3(0.02, 0.015, -0.01)
        },
        make("olympusom1", "奥林巴斯 OM-1", .filmcam, vignette: 0.1) { c in
            c.contrast = 0.22; c.saturation = 0.02; c.temperature = -0.05; c.highlights = -0.12
            c.shadowTint = SIMD3(-0.01, 0.0, 0.02)
        },
        make("mju", "奥林巴斯 mju", .filmcam, vignette: 0.32) { c in
            // 傻瓜机直闪：亮、硬、四角暗。
            c.exposure = 0.08; c.contrast = 0.32; c.saturation = 0.16; c.temperature = 0.06; c.tint = -0.05
            c.shadowTint = SIMD3(-0.01, 0.015, 0.02)
        },
        make("yashicat4", "雅西卡 T4", .filmcam, vignette: 0.26) { c in
            c.contrast = 0.4; c.saturation = 0.22; c.temperature = -0.04
            c.shadowTint = SIMD3(-0.015, 0.005, 0.03); c.highlightTint = SIMD3(0.02, 0.012, -0.01)
        },
        make("pentax67", "宾得 67", .filmcam, vignette: 0.1, bloom: 0.12) { c in
            // 中画幅：过渡细腻，肤色奶油感。
            c.contrast = 0.08; c.saturation = 0.02; c.temperature = 0.08; c.highlights = -0.28; c.shadows = 0.16; c.fade = 0.05
            c.shadowTint = SIMD3(0.0, 0.008, 0.01); c.highlightTint = SIMD3(0.025, 0.015, -0.01)
        },
        make("rz67", "玛米亚 RZ67", .filmcam, bloom: 0.1) { c in
            c.contrast = 0.02; c.saturation = -0.06; c.brightness = 0.1; c.highlights = -0.25; c.shadows = 0.2; c.fade = 0.08
            c.shadowTint = SIMD3(-0.004, 0.01, 0.014); c.highlightTint = SIMD3(0.02, 0.012, 0.0)
        },
        make("holga", "Holga 玩具机", .filmcam, vignette: 0.8, bloom: 0.45) { c in
            c.contrast = 0.3; c.saturation = 0.25; c.fade = 0.1; c.tint = 0.05
            c.shadowTint = SIMD3(0.01, -0.005, 0.035); c.highlightTint = SIMD3(0.04, 0.03, -0.03)
        },
        make("lf45", "大画幅 4×5", .filmcam, vignette: 0.18) { c in
            // 抽插片的座机：影调极其平滑，颜色沉稳。
            c.contrast = 0.1; c.saturation = -0.04; c.highlights = -0.35; c.shadows = 0.25; c.vibrance = 0.08; c.temperature = 0.03
            c.shadowTint = SIMD3(0.0, 0.004, 0.008); c.highlightTint = SIMD3(0.012, 0.008, 0.0)
        },
        make("lf810bw", "大画幅黑白", .filmcam, vignette: 0.22) { c in
            c.mono = true; c.monoWeights = SIMD3(0.3, 0.55, 0.15); c.contrast = 0.3; c.highlights = -0.25; c.shadows = 0.18
            c.shadowTint = SIMD3(0.004, 0.002, 0.0); c.highlightTint = SIMD3(0.01, 0.008, 0.0)
        },
        make("wetplate", "湿版老照片", .filmcam, vignette: 0.75, bloom: 0.2) { c in
            // 19 世纪火棉胶湿版：只对蓝光敏感，肤色发深，反差硬，四角很暗。
            c.mono = true; c.monoWeights = SIMD3(0.08, 0.32, 0.6); c.contrast = 0.55; c.fade = 0.08; c.shadows = -0.2
            c.shadowTint = SIMD3(0.02, 0.012, 0.0); c.highlightTint = SIMD3(0.03, 0.025, 0.01)
        },
        // 彩色负片
        make("ektar", "柯达 Ektar 风", .negative) { c in
            c.contrast = 0.3; c.saturation = 0.32; c.vibrance = 0.15
            c.shadowTint = SIMD3(-0.012, 0.0, 0.025); c.highlightTint = SIMD3(0.02, 0.008, -0.01)
        },
        make("gold200", "暖金 200", .negative) { c in
            c.temperature = 0.25; c.contrast = 0.2; c.saturation = 0.12; c.fade = 0.08
            c.shadowTint = SIMD3(0.01, 0.0, -0.02); c.highlightTint = SIMD3(0.03, 0.015, -0.03)
        },
        make("portrait160", "人像 160", .negative) { c in
            c.contrast = -0.1; c.saturation = -0.08; c.fade = 0.1; c.temperature = 0.08; c.brightness = 0.1
            c.shadowTint = SIMD3(0.0, 0.01, 0.015); c.highlightTint = SIMD3(0.02, 0.01, 0.0)
        },
        make("portrait400", "人像 400", .negative) { c in
            c.contrast = 0.1; c.saturation = 0.02; c.temperature = 0.12; c.fade = 0.06; c.shadows = 0.2
            c.shadowTint = SIMD3(-0.005, 0.008, 0.012); c.highlightTint = SIMD3(0.025, 0.012, -0.01)
        },
        make("green400", "翠绿 400", .negative) { c in
            c.temperature = -0.08; c.tint = -0.12; c.contrast = 0.25; c.saturation = 0.1
            c.shadowTint = SIMD3(-0.015, 0.02, 0.01); c.highlightTint = SIMD3(0.01, 0.01, -0.01)
        },
        make("fresh", "清新日系", .negative) { c in
            c.brightness = 0.25; c.contrast = -0.2; c.fade = 0.14; c.saturation = -0.12; c.temperature = -0.1
            c.shadowTint = SIMD3(-0.01, 0.015, 0.03)
        },
        make("night800", "夜景 800", .negative, bloom: 0.5) { c in
            c.temperature = -0.3; c.contrast = 0.25; c.saturation = 0.1
            c.shadowTint = SIMD3(-0.02, 0.01, 0.04); c.highlightTint = SIMD3(0.05, 0.0, -0.01)
        },
        // 反转片
        make("kchrome", "柯达克罗姆风", .slide, vignette: 0.15) { c in
            // 红和黄浓郁、反差大、暗部深。
            c.contrast = 0.42; c.saturation = 0.2; c.temperature = 0.1; c.shadows = -0.25; c.highlights = -0.1
            c.shadowTint = SIMD3(-0.005, -0.005, 0.02); c.highlightTint = SIMD3(0.035, 0.015, -0.025)
        },
        make("vivid50", "浓郁 50", .slide) { c in
            c.contrast = 0.45; c.saturation = 0.4; c.vibrance = 0.2; c.shadows = -0.2
            c.shadowTint = SIMD3(0.0, 0.0, 0.015)
        },
        make("slide100", "正片 100", .slide) { c in
            c.contrast = 0.3; c.saturation = 0.15; c.temperature = -0.04
        },
        make("chrome", "经典铬", .slide) { c in
            c.contrast = 0.3; c.saturation = -0.25; c.fade = 0.04; c.highlights = -0.3
            c.shadowTint = SIMD3(-0.01, 0.01, 0.02); c.highlightTint = SIMD3(0.015, 0.01, -0.01)
        },
        // 黑白
        make("trix", "柯达 Tri-X 风", .mono, vignette: 0.15) { c in
            c.mono = true; c.monoWeights = SIMD3(0.34, 0.5, 0.16); c.contrast = 0.62; c.shadows = -0.2
        },
        make("hp5", "伊尔福 HP5 风", .mono) { c in
            c.mono = true; c.contrast = 0.25; c.shadows = 0.15; c.highlights = -0.15; c.fade = 0.03
        },
        make("bw400", "黑白 400", .mono) { c in
            c.mono = true; c.contrast = 0.45
        },
        make("bwsoft", "柔和黑白", .mono) { c in
            c.mono = true; c.contrast = -0.05; c.fade = 0.1; c.brightness = 0.1
        },
        make("bwhard", "高反差", .mono) { c in
            c.mono = true; c.monoWeights = SIMD3(0.5, 0.4, 0.1); c.contrast = 0.8; c.shadows = -0.4
        },
        make("sepia", "棕褐", .mono) { c in
            c.mono = true; c.fade = 0.06; c.contrast = 0.1
            c.shadowTint = SIMD3(0.03, 0.01, -0.02); c.highlightTint = SIMD3(0.04, 0.02, -0.03)
        },
        // 电影
        make("cine800t", "电影卷 800T", .cinema, bloom: 0.6) { c in
            // 灯光片：整体偏青蓝，亮部带一点红晕。
            c.temperature = -0.22; c.tint = -0.04; c.contrast = 0.2; c.saturation = 0.08; c.fade = 0.05
            c.shadowTint = SIMD3(-0.025, 0.012, 0.04); c.highlightTint = SIMD3(0.06, 0.01, 0.0)
        },
        make("tealorange", "青橙", .cinema) { c in
            c.contrast = 0.3; c.saturation = 0.05
            c.shadowTint = SIMD3(-0.04, 0.01, 0.045); c.highlightTint = SIMD3(0.05, 0.02, -0.04)
        },
        make("print", "影院拷贝", .cinema, vignette: 0.25) { c in
            c.contrast = 0.4; c.saturation = -0.1; c.fade = 0.03
            c.shadowTint = SIMD3(-0.02, 0.0, 0.03); c.highlightTint = SIMD3(0.03, 0.02, -0.03)
        },
        make("lowkey", "暗调", .cinema, vignette: 0.4) { c in
            c.exposure = -0.2; c.contrast = 0.35; c.saturation = -0.2
            c.shadowTint = SIMD3(-0.01, 0.015, 0.02)
        },
        // 复古
        make("faded", "褪色", .vintage) { c in
            c.fade = 0.22; c.contrast = -0.15; c.saturation = -0.25; c.temperature = 0.1
        },
        make("expired", "过期胶片", .vintage, vignette: 0.3) { c in
            c.tint = 0.2; c.temperature = 0.1; c.fade = 0.15
            c.shadowTint = SIMD3(0.02, -0.01, 0.03); c.highlightTint = SIMD3(0.02, 0.03, -0.02)
        },
        make("instant", "拍立得", .vintage, vignette: 0.2) { c in
            c.contrast = -0.1; c.fade = 0.12; c.brightness = 0.1; c.saturation = -0.05
            c.shadowTint = SIMD3(-0.02, 0.01, 0.03); c.highlightTint = SIMD3(0.04, 0.03, -0.02)
        },
    ]

    static let byID: [String: FilmLook] = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
}
