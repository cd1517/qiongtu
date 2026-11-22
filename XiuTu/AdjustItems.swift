import Foundation

/// 调色里要交给 AI 做的功能：按钮上带 "AI" 角标。
/// 点一下会把当前已经调好的保留成一步，转到 AI 修图并直接开始生成（每次计一次费）。
enum AdjustAI: String, Identifiable {
    case exposure, flash, flashPro, colorRestore, hd, denoise, lowLight

    var id: String { rawValue }

    var title: String {
        switch self {
        case .exposure: return "AI曝光"
        case .flash: return "闪光灯"
        case .flashPro: return "增强闪光"
        case .colorRestore: return "色彩还原"
        case .hd: return "画质清晰"
        case .denoise: return "AI降噪"
        case .lowLight: return "暗光修复"
        }
    }

    var symbol: String {
        switch self {
        case .exposure: return "camera.metering.center.weighted.average"
        case .flash: return "bolt"
        case .flashPro: return "bolt.circle"
        case .colorRestore: return "paintbrush.pointed"
        case .hd: return "4k.tv"
        case .denoise: return "wand.and.stars"
        case .lowLight: return "lightbulb.max"
        }
    }

    /// 只改光线和颜色的指令都带这一句，结果才能和原图逐像素对上、只取它的光影色彩。
    private static let keepShape = "只改曝光、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。"

    var preset: AIPreset {
        switch self {
        case .exposure:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "你是一位摄影大师兼调色师。先判断这张照片在曝光上的毛病（过曝或欠曝、主体太暗、高光溢出、暗部死黑、反差不足或过头），然后把它调成曝光准确、明暗层次完整、自然好看的照片，不要改变原来的色调风格。" + Self.keepShape,
                            toneOnly: true)
        case .flash:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "模拟机顶闪光灯正面直打的效果：近处的人物和主体被一束直射的光照亮，肤色明亮通透，背景相对压暗，像夜里用闪光灯拍的快照。只改光线、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。",
                            toneOnly: true)
        case .flashPro:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "模拟专业离机闪光灯加柔光箱的布光：主体被一盏有方向感的柔和主光照亮，脸和身体的轮廓立体，眼神光清晰，背景明显压暗，主体从背景里跳出来，像影棚里拍的大片。只改光线、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。",
                            toneOnly: true)
        case .colorRestore:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "还原这张照片真实准确的颜色：校正白平衡和偏色，让肤色、白色和中性灰看起来自然，把被混合光源、滤镜或褪色带偏的颜色拉回来，饱和度自然不过头。" + Self.keepShape,
                            toneOnly: true)
        case .hd:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "提升这张照片的清晰度和画质：找回细节和纹理，去掉模糊、噪点和压缩痕迹，边缘干净锐利，皮肤保留真实质感，不要涂抹感。人物长相、构图、颜色和光线都不变。")
        case .denoise:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "去掉这张照片的噪点、彩色杂点和压缩痕迹，保留真实的细节、纹理和皮肤质感，不要涂抹感。人物长相、构图、颜色和光线都不变。")
        case .lowLight:
            return AIPreset(title: title, symbol: symbol,
                            prompt: "修复暗光环境下拍的照片：把整体和人物提亮到正常，找回暗部的层次和颜色，压住路灯、招牌这些高光不要溢出，保留夜晚的氛围，不要发灰。" + Self.keepShape,
                            toneOnly: true)
        }
    }
}

/// 调色面板那一排按钮上的一项：一个滑块、一个子面板，或者一个 AI 功能。
enum AdjustItem: Identifiable {
    case key(AdjustKey)
    case sub(AdjustSub)
    case ai(AdjustAI)

    var id: String {
        switch self {
        case .key(let key): return "key-" + key.rawValue
        case .sub(let sub): return "sub-" + sub.rawValue
        case .ai(let ai): return "ai-" + ai.rawValue
        }
    }
}

extension AdjustGroup {
    /// 每一组里按钮的顺序。
    var items: [AdjustItem] {
        switch self {
        case .light:
            return [.sub(.local), .key(.brightness), .key(.contrast), .key(.exposure), .ai(.exposure), .ai(.flash),
                    .key(.lightSense), .key(.highlights), .key(.shadows), .key(.ambiance), .key(.fillLight)]
        case .color:
            return [.key(.saturation), .key(.vibrance), .key(.temperature), .key(.tint), .ai(.colorRestore), .sub(.hsl),
                    .key(.dehaze), .key(.fade), .sub(.skin), .sub(.split), .sub(.curve)]
        case .detail:
            return [.key(.sharpen), .ai(.hd), .key(.clarity), .key(.denoise), .ai(.denoise), .key(.grain),
                    .key(.fringe), .key(.vignette)]
        case .tone:
            return [.ai(.flash), .ai(.flashPro), .key(.naturalLight), .key(.lowLight), .key(.backlight), .ai(.lowLight),
                    .key(.evenLight), .key(.moodLight), .key(.bloom)]
        }
    }
}
