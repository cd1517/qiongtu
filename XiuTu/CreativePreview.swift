import CoreImage

/// 创意玩法按钮上的小图：能在本机大致模拟的画风（漫画、像素、素描、水彩……）用当前照片算一张，只是给个印象。
/// 模拟不了的（加分身、加东西、换场景）返回 nil，按钮上画色块和图标。
enum CreativePreview {
    static func render(_ title: String, _ image: CIImage) -> CIImage? {
        let e = image.extent
        switch title {
        case "日漫": return anime(image, levels: 8, saturation: 1.45, bright: 0.06, ink: 1)
        case "韩漫": return anime(image, levels: 14, saturation: 1.1, bright: 0.12, ink: 0.5)
        case "温暖动画": return warm(anime(image, levels: 10, saturation: 1.35, bright: 0.08, ink: 0.7), 0.25)
        case "少女漫画":
            let soft = mix(image.applyingFilter("CIPhotoEffectMono"), paper(e), 0.35)
            return multiply(ink(image, 1), tone(soft, bright: 0.12, contrast: 0.95))
        case "黑白漫画":
            let mono = tone(image.applyingFilter("CIPhotoEffectMono"), bright: 0.05, contrast: 1.3)
            let dots = mono.applyingFilter("CIDotScreen", parameters: [
                kCIInputWidthKey: max(e.width, e.height) / 55, kCIInputAngleKey: 0.6, kCIInputSharpnessKey: 0.6,
                kCIInputCenterKey: CIVector(x: 0, y: 0),
            ]).cropped(to: e)
            return multiply(ink(image, 1), mix(mono, dots, 0.5))
        case "美漫":
            let flat = posterize(saturate(smooth(image, times: 1), 1.7, contrast: 1.15, bright: 0.04), 5)
            return multiply(ink(image, 1), flat)
        case "城市流行":
            let base = posterize(saturate(image, 1.6, contrast: 1.2), 6)
            let tint = image.applyingFilter("CIFalseColor", parameters: [
                "inputColor0": CIColor(red: 0.18, green: 0.08, blue: 0.4),
                "inputColor1": CIColor(red: 1, green: 0.6, blue: 0.78),
            ])
            return mix(base, tint, 0.55).cropped(to: e)
        case "厚涂插画":
            return saturate(smooth(image, times: 3), 1.3, contrast: 1.1)
                .applyingFilter("CIUnsharpMask", parameters: [kCIInputRadiusKey: 2.5, kCIInputIntensityKey: 0.8]).cropped(to: e)
        case "像素小人":
            let px = max(e.width, e.height) / 42
            return saturate(image, 1.35, contrast: 1.1, bright: 0.03).clampedToExtent()
                .applyingFilter("CIPixellate", parameters: [kCIInputScaleKey: px, kCIInputCenterKey: CIVector(x: 0, y: 0)])
                .cropped(to: e)
        case "蜡笔涂鸦":
            return multiply(ink(image, 0.9), mix(paper(e), saturate(blur(image, 1.5), 1.6, contrast: 1.05, bright: 0.08), 0.55))
        case "潦草涂鸦":
            return multiply(ink(image, 0.85), mix(paper(e), saturate(image, 1.8, contrast: 1), 0.18))
        case "简笔画":
            return ink(image, 1)
        case "治愈绘本":
            let soft = posterize(saturate(blur(image, 1.5), 1.25, contrast: 0.95, bright: 0.1), 10)
            return warm(multiply(ink(image, 0.45), mix(soft, paper(e), 0.15)), 0.15)
        case "水彩":
            let wash = mix(saturate(smooth(image, times: 2), 1.3, contrast: 0.95, bright: 0.08), paper(e), 0.25)
            return multiply(ink(image, 0.5), wash)
        case "油画":
            return saturate(smooth(image, times: 4), 1.25, contrast: 1.15)
                .applyingFilter("CIUnsharpMask", parameters: [kCIInputRadiusKey: 3, kCIInputIntensityKey: 1]).cropped(to: e)
        case "印象派":
            return saturate(image, 1.3, contrast: 1, bright: 0.04).clampedToExtent()
                .applyingFilter("CIPointillize", parameters: [kCIInputRadiusKey: max(e.width, e.height) / 70,
                                                              kCIInputCenterKey: CIVector(x: 0, y: 0)])
                .cropped(to: e)
                .composited(over: blur(image, 3))
        case "素描":
            return sketch(image)
        case "国风水墨":
            return warm(mix(sketch(image), paper(e), 0.25), 0.12)
        case "彩铅":
            return mix(sketch(image), saturate(image, 1.1, contrast: 1), 0.45).cropped(to: e)
        case "浮世绘":
            return warm(multiply(ink(image, 1), posterize(saturate(image, 1.2, contrast: 1.05, bright: 0.06), 5)), 0.2)
        case "波普":
            return posterize(saturate(image, 2.2, contrast: 1.3, bright: 0.05), 3)
        case "版画":
            return image.applyingFilter("CIPhotoEffectMono").applyingFilter("CIColorThreshold", parameters: ["inputThreshold": 0.32])
                .cropped(to: e)
        default:
            return nil
        }
    }

    // MARK: 小工具

    private static func anime(_ image: CIImage, levels: Int, saturation: CGFloat, bright: CGFloat, ink k: CGFloat) -> CIImage {
        let flat = posterize(saturate(smooth(image, times: 2), saturation, contrast: 1.05, bright: bright), levels)
        return multiply(ink(image, k), flat)
    }

    /// 白底黑线的线稿（素描再提一下反差），k 越小线越淡。
    private static func ink(_ image: CIImage, _ k: CGFloat) -> CIImage {
        let lines = sketch(image).applyingFilter("CIColorControls", parameters: [kCIInputContrastKey: 1.8, kCIInputBrightnessKey: 0.08])
            .applyingFilter("CIPhotoEffectMono").cropped(to: image.extent)
        return k >= 1 ? lines : mix(paper(image.extent), lines, k)
    }

    private static func multiply(_ top: CIImage, _ base: CIImage) -> CIImage {
        top.applyingFilter("CIMultiplyBlendMode", parameters: [kCIInputBackgroundImageKey: base]).cropped(to: base.extent)
    }

    private static func tone(_ image: CIImage, bright: CGFloat, contrast: CGFloat) -> CIImage {
        image.applyingFilter("CIColorControls", parameters: [kCIInputBrightnessKey: bright, kCIInputContrastKey: contrast])
            .cropped(to: image.extent)
    }

    /// 铅笔素描：灰度和它反相模糊后做颜色减淡。
    private static func sketch(_ image: CIImage) -> CIImage {
        let e = image.extent
        let gray = image.applyingFilter("CIPhotoEffectMono")
        let inverted = blur(gray.applyingFilter("CIColorInvert"), max(e.width, e.height) / 60)
        return inverted.applyingFilter("CIColorDodgeBlendMode", parameters: [kCIInputBackgroundImageKey: gray])
            .applyingFilter("CIColorControls", parameters: [kCIInputContrastKey: 1.25])
            .cropped(to: e)
    }

    private static func posterize(_ image: CIImage, _ levels: Int) -> CIImage {
        image.applyingFilter("CIColorPosterize", parameters: ["inputLevels": levels]).cropped(to: image.extent)
    }

    private static func saturate(_ image: CIImage, _ s: CGFloat, contrast: CGFloat, bright: CGFloat = 0) -> CIImage {
        image.applyingFilter("CIColorControls", parameters: [
            kCIInputSaturationKey: s, kCIInputContrastKey: contrast, kCIInputBrightnessKey: bright,
        ]).cropped(to: image.extent)
    }

    private static func smooth(_ image: CIImage, times: Int) -> CIImage {
        var out = image
        for _ in 0..<times { out = out.applyingFilter("CIMedianFilter") }
        return out.cropped(to: image.extent)
    }

    private static func blur(_ image: CIImage, _ r: CGFloat) -> CIImage {
        image.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: r]).cropped(to: image.extent)
    }

    private static func warm(_ image: CIImage, _ k: CGFloat) -> CIImage {
        image.applyingFilter("CITemperatureAndTint", parameters: [
            "inputNeutral": CIVector(x: 6500, y: 0), "inputTargetNeutral": CIVector(x: 6500 - 2200 * k, y: 0),
        ]).cropped(to: image.extent)
    }

    private static func paper(_ e: CGRect) -> CIImage {
        CIImage(color: CIColor(red: 0.98, green: 0.96, blue: 0.92)).cropped(to: e)
    }

    private static func mix(_ a: CIImage, _ b: CIImage, _ t: CGFloat) -> CIImage {
        b.applyingFilter("CIColorMatrix", parameters: ["inputAVector": CIVector(x: 0, y: 0, z: 0, w: t)])
            .composited(over: a).cropped(to: a.extent)
    }
}
