import CoreImage
import UIKit
import simd

/// 摄影棚里摄影师、器材按钮上的小图：用户自己这张照片在本机套一组近似的色调，只是给个印象。
/// 真正拍出来的是 AI 按指令重新处理的结果，和缩略图不会完全一样。不加颗粒。
enum StudioLooks {
    /// 缩略图格子的宽高比。
    static let tileWidth: CGFloat = 64
    static let tileHeight: CGFloat = 72

    private static func make(_ key: String, vignette: CGFloat = 0, bloom: CGFloat = 0,
                             _ configure: (inout ColorParams) -> Void) -> (String, FilmLook) {
        var color = ColorParams()
        configure(&color)
        return (key, FilmLook(id: "studio-" + key, name: key, category: .master, color: color,
                              vignette: vignette, bloom: bloom))
    }

    /// key：摄影师是 "p.<id>"，器材是 "e.<id>"，和目录里的 id 一致。
    static let all: [String: FilmLook] = Dictionary(uniqueKeysWithValues: photographers + equipment)

    // MARK: 摄影师

    private static let photographers: [(String, FilmLook)] = [
        // 牛顿：高反差黑白，硬光，深重的阴影。
        make("p.newton", vignette: 0.2) { c in
            c.mono = true; c.contrast = 0.85; c.shadows = -0.45; c.highlights = 0.25
        },
        // 阿维顿：明亮干净的高调黑白。
        make("p.avedon") { c in
            c.mono = true; c.exposure = 0.28; c.contrast = 0.4; c.highlights = 0.35; c.shadows = 0.15
        },
        // 林德伯格：柔和细腻的灰阶黑白。
        make("p.lindbergh", vignette: 0.15) { c in
            c.mono = true; c.monoWeights = SIMD3(0.4, 0.45, 0.15); c.contrast = 0.08; c.fade = 0.18
            c.highlights = -0.15; c.shadows = 0.12
        },
        // 莱博维茨：浓郁沉稳、绘画感的色彩，有方向的光。
        make("p.leibovitz", vignette: 0.5) { c in
            c.exposure = -0.15; c.contrast = 0.45; c.saturation = 0.15; c.temperature = 0.15; c.shadows = -0.3
            c.shadowTint = SIMD3(-0.02, 0.015, 0.03); c.highlightTint = SIMD3(0.04, 0.02, -0.02)
        },
        // 克莱因：阴郁的暗调，冷的暗部。
        make("p.klein", vignette: 0.7) { c in
            c.exposure = -0.55; c.contrast = 0.6; c.shadows = -0.6; c.saturation = -0.45; c.temperature = -0.3
            c.shadowTint = SIMD3(0.0, 0.015, 0.05)
        },
        // 拉切贝尔：超饱和的糖果色。
        make("p.lachapelle") { c in
            c.saturation = 0.9; c.vibrance = 0.4; c.contrast = 0.4; c.exposure = 0.15; c.tint = 0.05; c.temperature = -0.05
            c.highlightTint = SIMD3(0.0, 0.02, 0.04)
        },
        // 蒂姆·沃克：梦幻的暖粉彩。
        make("p.walker", bloom: 0.35) { c in
            c.exposure = 0.25; c.contrast = -0.3; c.saturation = -0.05; c.fade = 0.35; c.temperature = 0.12; c.tint = 0.2
            c.highlightTint = SIMD3(0.05, 0.01, 0.03)
        },
        // 尼克·奈特：大胆的青品红实验色。
        make("p.knight", vignette: 0.2) { c in
            c.saturation = 0.7; c.contrast = 0.55; c.temperature = -0.3; c.tint = 0.25
            c.shadowTint = SIMD3(0.05, -0.03, 0.09); c.highlightTint = SIMD3(0.0, 0.05, 0.03)
        },
        // Mert & Marcus：光洁浓烈的高光泽。
        make("p.mertmarcus", vignette: 0.25) { c in
            c.contrast = 0.55; c.saturation = 0.45; c.highlights = 0.2; c.shadows = -0.2; c.temperature = 0.18
            c.highlightTint = SIMD3(0.05, 0.025, 0.0)
        },
        // 特斯蒂诺：明亮温暖的金色阳光。
        make("p.testino", bloom: 0.15) { c in
            c.exposure = 0.2; c.temperature = 0.45; c.saturation = 0.25; c.contrast = 0.2
            c.highlightTint = SIMD3(0.07, 0.04, -0.04)
        },
        // 德马舍利耶：柔美的淡棕褐黑白。
        make("p.demarchelier", bloom: 0.1) { c in
            c.mono = true; c.contrast = 0.15; c.shadows = 0.1; c.exposure = 0.08
            c.shadowTint = SIMD3(0.02, 0.01, -0.01); c.highlightTint = SIMD3(0.05, 0.03, -0.015)
        },
        // Inez & Vinoodh：干净明亮、纯净的颜色。
        make("p.inezvinoodh") { c in
            c.exposure = 0.25; c.contrast = 0.3; c.saturation = -0.3; c.temperature = -0.2; c.shadows = 0.3; c.highlights = -0.1
        },
        // 布鲁斯·韦伯：暖棕褐的怀旧黑白。
        make("p.weber", vignette: 0.2) { c in
            c.mono = true; c.contrast = 0.45; c.shadows = -0.1
            c.shadowTint = SIMD3(0.04, 0.02, -0.02); c.highlightTint = SIMD3(0.09, 0.05, -0.04)
        },
        // 于尔根·泰勒：直闪的生硬快照感。
        make("p.teller", vignette: 0.65) { c in
            c.exposure = 0.35; c.contrast = 0.75; c.shadows = -0.55; c.saturation = 0.2; c.temperature = -0.15
        },
        // 蜷川实花：极高饱和的红粉。
        make("p.ninagawa") { c in
            c.saturation = 0.8; c.vibrance = 0.3; c.contrast = 0.35; c.tint = 0.2; c.temperature = 0.08
            c.shadowTint = SIMD3(0.05, -0.02, 0.04); c.highlightTint = SIMD3(0.05, 0.0, 0.02)
        },
        // 奥山由之：低饱和的暖粉彩，怀旧日记感。
        make("p.okuyama", bloom: 0.15) { c in
            c.exposure = 0.2; c.contrast = -0.3; c.saturation = -0.3; c.temperature = 0.35; c.tint = 0.12; c.fade = 0.45
            c.highlightTint = SIMD3(0.05, 0.02, 0.0)
        },
        // 滨田英明：通透、低反差、淡青绿。
        make("p.hamada", bloom: 0.15) { c in
            c.exposure = 0.3; c.contrast = -0.35; c.shadows = 0.4; c.saturation = -0.2; c.temperature = -0.15; c.tint = -0.15
            c.fade = 0.3; c.shadowTint = SIMD3(-0.04, 0.04, 0.04); c.highlightTint = SIMD3(0.0, 0.02, 0.01)
        },
        // 川内伦子：明亮轻微过曝的浅粉彩。
        make("p.kawauchi", bloom: 0.3) { c in
            c.exposure = 0.45; c.contrast = -0.35; c.shadows = 0.45; c.saturation = -0.25; c.fade = 0.45; c.tint = 0.15
            c.shadowTint = SIMD3(0.01, 0.01, 0.04); c.highlightTint = SIMD3(0.03, 0.0, 0.03)
        },
        // 森山大道：极高反差黑白，暗部压死、高光烧白。
        make("p.moriyama", vignette: 0.35) { c in
            c.mono = true; c.monoWeights = SIMD3(0.3, 0.5, 0.2); c.contrast = 1.0; c.shadows = -0.6; c.highlights = 0.35
        },
        // 植田正治：淡雅明亮的灰调黑白。
        make("p.uedashoji") { c in
            c.mono = true; c.exposure = 0.2; c.contrast = -0.1; c.fade = 0.3; c.shadows = 0.2
            c.shadowTint = SIMD3(0.0, 0.01, 0.03)
        },
        // 荒木经惟：偏红偏橙的暖调彩色。
        make("p.araki") { c in
            c.temperature = 0.4; c.tint = 0.12; c.saturation = 0.25; c.contrast = 0.25
            c.shadowTint = SIMD3(0.04, 0.0, -0.02); c.highlightTint = SIMD3(0.06, 0.015, -0.03)
        },
        // 上田义彦：安静低饱和，柔和暗部。
        make("p.uedayoshihiko", vignette: 0.4) { c in
            c.exposure = -0.25; c.contrast = 0.15; c.saturation = -0.6; c.highlights = -0.25; c.temperature = 0.06; c.fade = 0.18
        },
        // 王家卫 × 杜可风：青绿暗部、橙红高光。
        make("p.wongdoyle", vignette: 0.4) { c in
            c.exposure = -0.15; c.contrast = 0.5; c.saturation = 0.4
            c.shadowTint = SIMD3(-0.06, 0.06, 0.05); c.highlightTint = SIMD3(0.09, 0.03, -0.06)
        },
        // 夏永康：褪色的柔和粉彩。
        make("p.shya", bloom: 0.3) { c in
            c.fade = 0.55; c.saturation = -0.3; c.contrast = -0.25; c.exposure = 0.12; c.tint = 0.15
            c.highlightTint = SIMD3(0.04, 0.0, 0.035)
        },
        // 侯孝贤：朴素低饱和的暖大地色。
        make("p.hou") { c in
            c.saturation = -0.45; c.temperature = 0.25; c.fade = 0.15; c.contrast = 0.05
            c.shadowTint = SIMD3(0.02, 0.015, 0.0); c.highlightTint = SIMD3(0.035, 0.025, 0.0)
        },
        // 杨德昌：冷静的蓝灰。
        make("p.yang") { c in
            c.temperature = -0.35; c.saturation = -0.4; c.contrast = 0.2
            c.shadowTint = SIMD3(-0.015, 0.01, 0.05); c.highlightTint = SIMD3(0.0, 0.01, 0.025)
        },
        // 蔡明亮：昏暗冷清，偏绿。
        make("p.tsai", vignette: 0.45) { c in
            c.exposure = -0.35; c.saturation = -0.4; c.contrast = 0.25; c.shadows = -0.3; c.temperature = -0.08; c.tint = -0.25
            c.shadowTint = SIMD3(-0.02, 0.035, 0.015)
        },
        // 李安：冷暖平衡、细腻的自然色。
        make("p.anglee", vignette: 0.25) { c in
            c.contrast = 0.2; c.vibrance = 0.3; c.saturation = 0.05; c.temperature = 0.05; c.highlights = -0.25; c.shadows = 0.1
            c.shadowTint = SIMD3(-0.02, 0.015, 0.03); c.highlightTint = SIMD3(0.03, 0.015, 0.0)
        },
        // 关锦鹏：温暖的琥珀色，浓郁暗部。
        make("p.kwan", vignette: 0.4, bloom: 0.25) { c in
            c.temperature = 0.4; c.exposure = -0.1; c.contrast = 0.3; c.saturation = -0.05; c.shadows = -0.3
            c.shadowTint = SIMD3(0.02, 0.005, -0.01); c.highlightTint = SIMD3(0.07, 0.035, -0.03)
        },
    ]

    // MARK: 器材

    private static let equipment: [(String, FilmLook)] = [
        make("e.portra400") { c in
            c.contrast = -0.15; c.saturation = -0.1; c.temperature = 0.2; c.fade = 0.15; c.shadows = 0.25
            c.shadowTint = SIMD3(-0.01, 0.012, 0.02); c.highlightTint = SIMD3(0.04, 0.02, -0.015)
        },
        make("e.portra800") { c in
            c.contrast = 0.2; c.saturation = 0.15; c.temperature = 0.28; c.shadows = -0.1
            c.shadowTint = SIMD3(-0.02, 0.02, 0.03); c.highlightTint = SIMD3(0.05, 0.025, -0.02)
        },
        make("e.gold200") { c in
            c.temperature = 0.4; c.contrast = 0.25; c.saturation = 0.2; c.fade = 0.1
            c.shadowTint = SIMD3(0.01, 0.0, -0.02); c.highlightTint = SIMD3(0.06, 0.035, -0.05)
        },
        make("e.ektar100") { c in
            c.contrast = 0.45; c.saturation = 0.6; c.vibrance = 0.15
            c.shadowTint = SIMD3(-0.015, 0.0, 0.03); c.highlightTint = SIMD3(0.025, 0.01, -0.01)
        },
        make("e.fuji400h") { c in
            c.exposure = 0.15; c.contrast = -0.2; c.saturation = -0.15; c.temperature = -0.15; c.tint = -0.18; c.fade = 0.18
            c.shadowTint = SIMD3(-0.03, 0.035, 0.03)
        },
        make("e.velvia50") { c in
            c.contrast = 0.4; c.saturation = 0.6; c.vibrance = 0.2; c.shadows = -0.1
            c.shadowTint = SIMD3(0.0, 0.0, 0.02)
        },
        make("e.cinestill800t", bloom: 0.5) { c in
            c.temperature = -0.45; c.contrast = 0.3; c.saturation = 0.15
            c.shadowTint = SIMD3(-0.03, 0.03, 0.07); c.highlightTint = SIMD3(0.09, 0.02, -0.03)
        },
        make("e.cinestill50d", bloom: 0.15) { c in
            c.temperature = 0.15; c.contrast = 0.15; c.saturation = 0.1; c.highlights = -0.2
            c.shadowTint = SIMD3(-0.01, 0.01, 0.02); c.highlightTint = SIMD3(0.03, 0.02, 0.0)
        },
        make("e.ilfordhp5") { c in
            c.mono = true; c.contrast = 0.3; c.shadows = 0.05
        },
        make("e.ilforddelta3200", vignette: 0.25) { c in
            c.mono = true; c.contrast = 0.75; c.shadows = -0.4; c.highlights = 0.15
        },
        make("e.trix400", vignette: 0.15) { c in
            c.mono = true; c.monoWeights = SIMD3(0.35, 0.5, 0.15); c.contrast = 0.45; c.shadows = -0.1
        },
        make("e.canon5d") { c in
            c.exposure = 0.12; c.temperature = 0.2; c.tint = 0.06; c.saturation = 0.15; c.shadows = 0.15
        },
        make("e.sonya7") { c in
            c.contrast = 0.25; c.vibrance = 0.25; c.temperature = -0.18; c.highlights = -0.15
        },
        make("e.fujifilmx") { c in
            c.saturation = -0.5; c.contrast = 0.3; c.temperature = -0.08
            c.shadowTint = SIMD3(-0.02, 0.02, 0.03); c.highlightTint = SIMD3(0.03, 0.02, -0.01)
        },
        make("e.leicam", vignette: 0.4) { c in
            c.contrast = 0.5; c.saturation = -0.15; c.shadows = -0.3; c.highlights = -0.12; c.temperature = 0.12
        },
        make("e.hasselblad") { c in
            c.contrast = 0.1; c.saturation = 0.1; c.vibrance = 0.1; c.highlights = -0.35; c.shadows = 0.25; c.temperature = 0.06
        },
        make("e.phaseone") { c in
            c.contrast = 0.3; c.vibrance = 0.3; c.highlights = -0.25; c.shadows = 0.15; c.temperature = -0.08
        },
        make("e.polaroidsx70", vignette: 0.4) { c in
            c.fade = 0.45; c.contrast = -0.15; c.saturation = -0.3; c.temperature = 0.25
            c.shadowTint = SIMD3(0.0, 0.02, 0.04); c.highlightTint = SIMD3(0.05, 0.03, 0.0)
        },
        make("e.polaroid600", vignette: 0.35) { c in
            c.saturation = 0.35; c.contrast = 0.25; c.temperature = 0.1; c.fade = 0.15
            c.shadowTint = SIMD3(-0.02, 0.03, 0.05); c.highlightTint = SIMD3(0.05, 0.015, -0.015)
        },
        make("e.instaxmini") { c in
            c.exposure = 0.35; c.contrast = -0.15; c.saturation = -0.15; c.fade = 0.3; c.temperature = -0.12
        },
        make("e.instaxwide") { c in
            c.exposure = 0.12; c.contrast = -0.1; c.saturation = -0.4; c.fade = 0.25; c.temperature = 0.1
        },
        make("e.disposable", vignette: 0.45) { c in
            c.exposure = 0.2; c.contrast = 0.5; c.saturation = -0.15; c.temperature = -0.18; c.shadows = -0.4
        },
        make("e.lomo", vignette: 0.7) { c in
            c.contrast = 0.5; c.saturation = 0.45; c.tint = -0.05
            c.shadowTint = SIMD3(0.0, 0.01, 0.05); c.highlightTint = SIMD3(0.04, 0.03, -0.03)
        },
        make("e.iphone") { c in
            c.contrast = 0.2; c.vibrance = 0.4; c.saturation = 0.15; c.shadows = 0.35; c.highlights = -0.35
        },
        make("e.vhs", bloom: 0.3) { c in
            c.contrast = -0.2; c.saturation = 0.25; c.fade = 0.3; c.tint = 0.15
            c.shadowTint = SIMD3(-0.02, 0.03, 0.06); c.highlightTint = SIMD3(0.05, -0.02, 0.05)
        },
    ]

    /// 把照片居中裁成格子的比例，按每个 key 套一遍。"original" 是原图。
    static func render(_ photo: UIImage) -> [String: UIImage] {
        guard let cg = photo.cgImage else { return [:] }
        let cropped = EditorModel.centerCrop(CIImage(cgImage: cg), aspect: tileWidth / tileHeight)
        guard let raster = Renderer.shared.rasterize(cropped, maxDimension: 240) else { return [:] }
        var out: [String: UIImage] = ["original": UIImage(cgImage: raster.cg)]
        for (key, look) in all {
            if let image = Renderer.shared.cgImage(look.apply(to: raster.ci, intensity: 1)) {
                out[key] = UIImage(cgImage: image)
            }
        }
        return out
    }
}
