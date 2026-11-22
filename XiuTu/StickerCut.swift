import Accelerate
import CoreGraphics
import Foundation

/// 贴纸图片的收尾处理：规整成带透明通道的位图、把纯色背景抠掉、裁掉四周的空白。全部在本机算，不依赖 UIKit。
enum StickerCut {
    private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    private struct Bitmap {
        var data: [UInt8]
        let w: Int
        let h: Int
    }

    /// 读成 RGBA（预乘）像素，长边不超过 maxSide。
    private static func bitmap(of image: CGImage, maxSide: Int) -> Bitmap? {
        guard image.width > 0, image.height > 0 else { return nil }
        let k = min(1, CGFloat(maxSide) / CGFloat(max(image.width, image.height)))
        let w = max(1, Int((CGFloat(image.width) * k).rounded()))
        let h = max(1, Int((CGFloat(image.height) * k).rounded()))
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = data.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? Bitmap(data: data, w: w, h: h) : nil
    }

    private static func image(from bitmap: Bitmap, crop: (x: Int, y: Int, w: Int, h: Int)? = nil) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(bitmap.data) as CFData),
              let whole = CGImage(width: bitmap.w, height: bitmap.h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: bitmap.w * 4,
                                  space: sRGB, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        guard let crop else { return whole }
        return whole.cropping(to: CGRect(x: crop.x, y: crop.y, width: crop.w, height: crop.h))
    }

    /// 不透明内容的外接框（像素，第 0 行在最上面），四周留一点余量。全透明返回 nil。
    private static func contentBox(_ b: Bitmap) -> (x: Int, y: Int, w: Int, h: Int)? {
        var minX = b.w, minY = b.h, maxX = -1, maxY = -1
        for y in 0..<b.h {
            let row = y * b.w * 4
            for x in 0..<b.w where b.data[row + x * 4 + 3] > 10 {
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        let pad = max(2, max(b.w, b.h) / 60)
        let x0 = max(0, minX - pad), y0 = max(0, minY - pad)
        let x1 = min(b.w - 1, maxX + pad), y1 = min(b.h - 1, maxY + pad)
        return (x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /// 图里有没有成片的透明区域。
    static func hasTransparency(_ image: CGImage) -> Bool {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: break
        }
        guard let b = bitmap(of: image, maxSide: 96) else { return false }
        var clear = 0
        for i in stride(from: 3, to: b.data.count, by: 4) where b.data[i] < 128 { clear += 1 }
        return clear * 50 > b.w * b.h
    }

    /// 规整成普通的 RGBA 位图（限制长边），不改内容。
    static func normalized(_ image: CGImage, maxSide: Int = 1536) -> CGImage? {
        guard let b = bitmap(of: image, maxSide: maxSide) else { return nil }
        return self.image(from: b)
    }

    /// 已经是透明背景的图：只把四周的空白裁掉。
    static func trimmed(_ image: CGImage, maxSide: Int = 1536) -> CGImage? {
        guard let b = bitmap(of: image, maxSide: maxSide) else { return nil }
        guard let box = contentBox(b) else { return nil }
        return self.image(from: b, crop: box)
    }

    /// AI 生成的贴纸的收尾：已经是透明背景就只裁边；
    /// 否则认为它画在一块纯色背景上，把和四边连着的那片背景抠掉再裁边。背景不是纯色时返回 nil。
    static func finish(_ image: CGImage, maxSide: Int = 1024) -> CGImage? {
        if hasTransparency(image) { return trimmed(image, maxSide: maxSide) }
        guard var b = bitmap(of: image, maxSide: maxSide) else { return nil }
        guard keyOutBackground(&b), let box = contentBox(b) else { return nil }
        return self.image(from: b, crop: box)
    }

    /// 把和图片四边连着的纯色背景变透明。四边的颜色不统一（不是纯色背景）就不动，返回 false。
    private static func keyOutBackground(_ b: inout Bitmap) -> Bool {
        let w = b.w, h = b.h
        guard w >= 8, h >= 8 else { return false }
        // 背景色 = 四条边上像素的中位数。
        var border: [Int] = []
        for x in 0..<w {
            border.append(x * 4)
            border.append(((h - 1) * w + x) * 4)
        }
        for y in 0..<h {
            border.append(y * w * 4)
            border.append((y * w + w - 1) * 4)
        }
        func median(_ offset: Int) -> Int {
            let values = border.map { b.data[$0 + offset] }.sorted()
            return Int(values[values.count / 2])
        }
        let br = median(0), bg = median(1), bb = median(2)
        let tolerance = 48
        func isBackground(_ i: Int) -> Bool {
            abs(Int(b.data[i]) - br) <= tolerance && abs(Int(b.data[i + 1]) - bg) <= tolerance && abs(Int(b.data[i + 2]) - bb) <= tolerance
        }
        // 四边大部分都得是这个颜色，才算"纯色背景"。
        let matched = border.filter { isBackground($0) }.count
        guard matched * 10 >= border.count * 7 else { return false }

        // 从四边往里灌：0 = 还没看，1 = 背景。
        var mark = [UInt8](repeating: 0, count: w * h)
        var stack: [Int] = []
        stack.reserveCapacity(w * 4)
        func push(_ x: Int, _ y: Int) {
            let p = y * w + x
            if mark[p] == 0, isBackground(p * 4) {
                mark[p] = 1
                stack.append(p)
            }
        }
        for x in 0..<w { push(x, 0); push(x, h - 1) }
        for y in 0..<h { push(0, y); push(w - 1, y) }
        while let p = stack.popLast() {
            let x = p % w, y = p / w
            if x > 0 { push(x - 1, y) }
            if x < w - 1 { push(x + 1, y) }
            if y > 0 { push(x, y - 1) }
            if y < h - 1 { push(x, y + 1) }
        }
        // 保留的部分 = 255。往里收三个像素（去掉沾了背景色的那圈边），再把边缘柔化一点。
        var keep = [UInt8](repeating: 0, count: w * h)
        for p in 0..<(w * h) where mark[p] == 0 { keep[p] = 255 }
        for _ in 0..<3 {
            let before = keep
            for y in 0..<h {
                for x in 0..<w {
                    let p = y * w + x
                    guard before[p] != 0 else { continue }
                    let edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || before[p - 1] == 0 || before[p + 1] == 0 || before[p - w] == 0 || before[p + w] == 0
                    if edge { keep[p] = 0 }
                }
            }
        }
        var soft = [UInt8](repeating: 0, count: w * h)
        keep.withUnsafeMutableBytes { a in
            soft.withUnsafeMutableBytes { s in
                var src = vImage_Buffer(data: a.baseAddress, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: w)
                var dst = vImage_Buffer(data: s.baseAddress, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: w)
                vImageTentConvolve_Planar8(&src, &dst, nil, 0, 0, 3, 3, 0, vImage_Flags(kvImageEdgeExtend))
            }
        }
        for p in 0..<(w * h) {
            // 只往里柔化：原来就不保留的地方保持透明。
            let m = keep[p] == 0 ? 0 : Int(soft[p])
            let i = p * 4
            if m >= 255 { continue }
            b.data[i] = UInt8(Int(b.data[i]) * m / 255)
            b.data[i + 1] = UInt8(Int(b.data[i + 1]) * m / 255)
            b.data[i + 2] = UInt8(Int(b.data[i + 2]) * m / 255)
            b.data[i + 3] = UInt8(Int(b.data[i + 3]) * m / 255)
        }
        return true
    }
}
