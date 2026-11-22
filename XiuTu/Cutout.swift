import CoreImage
import CoreGraphics
import Foundation
import ImageIO
import Vision

/// 本机抠图：把画面里的主体（人、宠物、物品）从背景里分出来，不联网。
enum Cutout {
    /// 灰度蒙版：白 = 主体，黑 = 背景。识别不到主体时返回 nil。
    static func mask(for image: CGImage) -> CGImage? {
        #if DEBUG
        // 模拟器里系统识别不到主体，测试时可以放一张在 Mac 上算好的蒙版进来。
        if ProcessInfo.processInfo.environment["XIUTU_TEST_BASE"] != nil,
           let data = FileManager.default.contents(atPath: NSTemporaryDirectory() + "cutout_override.png"),
           let source = CGImageSourceCreateWithData(data as CFData, nil),
           let override = CGImageSourceCreateImageAtIndex(source, 0, nil) {
            return override
        }
        #endif
        if let m = subjectMask(for: image) { return m }
        return PersonSegmenter.mask(for: image)
    }

    private static func subjectMask(for image: CGImage) -> CGImage? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        #if targetEnvironment(simulator)
        request.usesCPUOnly = true
        #endif
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do {
            try handler.perform([request])
            guard let result = request.results?.first, !result.allInstances.isEmpty else { return nil }
            let buffer = try result.generateScaledMaskForImage(forInstances: result.allInstances, from: handler)
            let ci = CIImage(cvPixelBuffer: buffer)
            return Renderer.shared.context.createCGImage(ci, from: ci.extent, format: .L8,
                                                         colorSpace: CGColorSpaceCreateDeviceGray())
        } catch {
            return nil
        }
    }

    // MARK: AI 去背景

    /// 让 AI 把背景换成纯绿幕；拿到结果后只用它来算蒙版，贴到画面上的仍然是原图的像素。
    static let aiPrompt = """
    把背景全部换成纯绿色（#00FF00）的绿幕：一整块均匀的纯色，没有渐变、阴影、纹理和地面。\
    画面里的主体（人物、宠物或主要物品，连同他们手里拿的、身上带的东西）保持完全不变：\
    位置、大小、轮廓、姿势和颜色一点都不能动，要和原图逐像素对齐，画面比例不变。主体身上不要沾上绿色。
    """

    private static let keyKernel = CIColorKernel(source: """
    kernel vec4 xtGreenKeyMask(__sample s) {
        float greenness = s.g - max(s.r, s.b);
        float m = 1.0 - smoothstep(0.20, 0.38, greenness);
        return vec4(m, m, m, 1.0);
    }
    """)

    /// 从 AI 给的"绿幕图"里算出蒙版：绿幕的地方是黑，其余是白。主体几乎占不到画面（或者根本没换背景）时返回 nil。
    static func mask(fromKeyed image: CGImage) -> CGImage? {
        let ci = CIImage(cgImage: image)
        guard let kernel = keyKernel, let keyed = kernel.apply(extent: ci.extent, arguments: [ci]) else { return nil }
        // 去掉零星的小孔和毛边。
        let cleaned = keyed.clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: max(ci.extent.width, ci.extent.height) * 0.0012])
            .cropped(to: ci.extent)
        guard let mask = Renderer.shared.context.createCGImage(cleaned, from: ci.extent, format: .L8,
                                                               colorSpace: CGColorSpaceCreateDeviceGray()) else { return nil }
        let share = coverage(of: mask)
        return share > 0.01 && share < 0.97 ? mask : nil
    }

    /// 蒙版里白色占多少。
    private static func coverage(of mask: CGImage) -> CGFloat {
        let w = 64, h = 64
        var pixels = [UInt8](repeating: 0, count: w * h)
        let white: Int = pixels.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return 0 }
            ctx.draw(mask, in: CGRect(x: 0, y: 0, width: w, height: h))
            return raw.reduce(0) { $0 + ($1 > 128 ? 1 : 0) }
        }
        return CGFloat(white) / CGFloat(w * h)
    }

    /// 把蒙版变成和 extent 一样大的 CIImage。feather 是边缘羽化的宽度（按长边的比例，0 = 不羽化），
    /// 羽化只往主体里面收，不会把背景的颜色带出来。
    static func scaled(_ mask: CGImage, to extent: CGRect, feather: CGFloat = 0) -> CIImage {
        var m = CIImage(cgImage: mask, options: [.colorSpace: NSNull()])
        m = m.transformed(by: CGAffineTransform(scaleX: extent.width / m.extent.width,
                                                y: extent.height / m.extent.height))
            .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
        guard feather > 0 else { return m }
        let radius = max(extent.width, extent.height) * feather
        return m.clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 2, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 2, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 2, w: 0),
                "inputBiasVector": CIVector(x: -1, y: -1, z: -1, w: 0),
            ])
            .applyingFilter("CIColorClamp")
            .cropped(to: extent)
    }

    /// 按蒙版把背景变透明。
    static func apply(_ mask: CGImage, to image: CIImage, feather: CGFloat = 0) -> CIImage {
        let extent = image.extent
        let clear = CIImage(color: CIColor(red: 0, green: 0, blue: 0, alpha: 0)).cropped(to: extent)
        return image.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: clear,
            kCIInputMaskImageKey: scaled(mask, to: extent, feather: feather),
        ]).cropped(to: extent)
    }

    /// 抠出来的图（带透明通道）。trim = true 时把四周的空白裁掉。
    static func cut(_ image: CGImage, mask: CGImage, feather: CGFloat = 0, trim: Bool = false) -> CGImage? {
        var out = apply(mask, to: CIImage(cgImage: image), feather: feather)
        if trim, let box = bounds(of: mask) {
            let e = out.extent
            let r = CGRect(x: box.minX * e.width, y: (1 - box.maxY) * e.height,
                           width: box.width * e.width, height: box.height * e.height).integral.intersection(e)
            if r.width >= 2, r.height >= 2 {
                out = out.cropped(to: r).transformed(by: CGAffineTransform(translationX: -r.minX, y: -r.minY))
            }
        }
        return Renderer.shared.context.createCGImage(out, from: out.extent, format: .RGBA8,
                                                     colorSpace: Renderer.displaySpace)
    }

    /// 主体的外接框（归一化坐标，原点左上角），四周留一点余量。
    static func bounds(of mask: CGImage) -> CGRect? {
        let w = 128, h = 128
        var pixels = [UInt8](repeating: 0, count: w * h)
        let ok: Bool = pixels.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.draw(mask, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h {
            for x in 0..<w where pixels[y * w + x] > 40 {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        // 位图第 0 行在最上面，所以这里已经是"原点左上角"。
        let pad: CGFloat = 0.015
        let r = CGRect(x: CGFloat(minX) / CGFloat(w) - pad, y: CGFloat(minY) / CGFloat(h) - pad,
                       width: CGFloat(maxX - minX + 1) / CGFloat(w) + pad * 2,
                       height: CGFloat(maxY - minY + 1) / CGFloat(h) + pad * 2)
        return r.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
}
