import CoreImage
import Foundation
import simd

/// 一组颜色调整参数。所有计算在 gamma 空间做，行为接近常见修图软件的滑块。
/// 数值范围基本是 -1...1（fade 是 0...1）。
struct ColorParams: Equatable {
    var exposure: Float = 0
    var brightness: Float = 0
    var contrast: Float = 0
    var highlights: Float = 0
    var shadows: Float = 0
    var saturation: Float = 0
    var vibrance: Float = 0
    var temperature: Float = 0
    var tint: Float = 0
    var fade: Float = 0
    var mono = false
    var monoWeights = SIMD3<Float>(0.299, 0.587, 0.114)
    /// 分离色调：暗部和亮部各自叠加的颜色偏移。
    var shadowTint = SIMD3<Float>(0, 0, 0)
    var highlightTint = SIMD3<Float>(0, 0, 0)

    var isIdentity: Bool { self == ColorParams() }

    @inline(__always)
    private static func luma(_ c: SIMD3<Float>) -> Float {
        c.x * 0.299 + c.y * 0.587 + c.z * 0.114
    }

    @inline(__always)
    func map(_ input: SIMD3<Float>) -> SIMD3<Float> {
        let zero = SIMD3<Float>(repeating: 0)
        let one = SIMD3<Float>(repeating: 1)
        var c = input

        if exposure != 0 {
            c *= powf(2, exposure * 0.9)
        }
        if temperature != 0 || tint != 0 {
            let r: Float = 1 + 0.16 * temperature + 0.04 * tint
            let g: Float = 1 - 0.10 * tint
            let b: Float = 1 - 0.16 * temperature + 0.04 * tint
            c *= SIMD3<Float>(r, g, b)
        }
        c = simd_clamp(c, zero, one)

        if mono {
            let m = simd_dot(c, monoWeights)
            c = SIMD3<Float>(repeating: min(max(m, 0), 1))
        }
        if brightness != 0 {
            let g = powf(2, -brightness * 0.8)
            c = SIMD3<Float>(powf(c.x, g), powf(c.y, g), powf(c.z, g))
        }
        if contrast > 0 {
            let lo = c * c * 2
            let inv = one - c
            let hi = one - inv * inv * 2
            let s = SIMD3<Float>(c.x < 0.5 ? lo.x : hi.x,
                                 c.y < 0.5 ? lo.y : hi.y,
                                 c.z < 0.5 ? lo.z : hi.z)
            c = c + (s - c) * (contrast * 0.75)
        } else if contrast < 0 {
            let half = SIMD3<Float>(repeating: 0.5)
            c = half + (c - half) * (1 + contrast * 0.6)
        }
        if highlights != 0 || shadows != 0 {
            let l = max(Self.luma(c), 0.0001)
            let il = 1 - l
            let ws: Float = 9.48 * l * il * il * il
            let wh: Float = 9.48 * il * l * l * l
            let l2 = l + 0.1 * shadows * ws + 0.1 * highlights * wh
            c *= (l2 / l)
        }
        if !mono {
            if saturation != 0 {
                let l = SIMD3<Float>(repeating: Self.luma(c))
                c = l + (c - l) * (1 + saturation)
            }
            if vibrance != 0 {
                let spread = c.max() - c.min()
                let amount = vibrance * (1 - min(max(spread, 0), 1))
                let l = SIMD3<Float>(repeating: Self.luma(c))
                c = l + (c - l) * (1 + amount)
            }
        }
        if shadowTint != zero || highlightTint != zero {
            let l = min(max(Self.luma(c), 0), 1)
            let il = 1 - l
            c += shadowTint * (il * il) + highlightTint * (l * l)
        }
        if fade != 0 {
            c = c * (1 - 0.22 * fade) + SIMD3<Float>(repeating: 0.13 * fade)
        }
        return simd_clamp(c, zero, one)
    }
}

/// 把 ColorParams 烘成 3D LUT 交给 Core Image。
enum ColorCube {
    static let size = 32
    static let space = CGColorSpace(name: CGColorSpace.displayP3) ?? CGColorSpaceCreateDeviceRGB()

    private static var cache: [String: Data] = [:]
    private static let lock = NSLock()

    static func data(for params: ColorParams) -> Data {
        let n = size
        var data = Data(count: n * n * n * 4 * MemoryLayout<Float>.size)
        data.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: Float.self)
            let inv = 1 / Float(n - 1)
            var i = 0
            for b in 0..<n {
                for g in 0..<n {
                    for r in 0..<n {
                        let o = params.map(SIMD3<Float>(Float(r) * inv, Float(g) * inv, Float(b) * inv))
                        p[i] = o.x
                        p[i + 1] = o.y
                        p[i + 2] = o.z
                        p[i + 3] = 1
                        i += 4
                    }
                }
            }
        }
        return data
    }

    static func apply(_ params: ColorParams, to image: CIImage, cacheKey: String? = nil) -> CIImage {
        if params.isIdentity { return image }
        let cube: Data
        if let cacheKey {
            lock.lock()
            if let hit = cache[cacheKey] {
                cube = hit
            } else {
                cube = data(for: params)
                cache[cacheKey] = cube
            }
            lock.unlock()
        } else {
            cube = data(for: params)
        }
        return image.applyingFilter("CIColorCubeWithColorSpace", parameters: [
            "inputCubeDimension": size,
            "inputCubeData": cube,
            "inputColorSpace": space,
        ])
    }
}

/// 颗粒、暗角等和分辨率相关的效果。参数都按图片尺寸折算，预览和原图导出效果一致。
enum FX {
    static func grain(_ image: CIImage, amount: CGFloat) -> CIImage {
        guard amount > 0, let noise = CIFilter(name: "CIRandomGenerator")?.outputImage else { return image }
        let extent = image.extent
        let s = max(1, max(extent.width, extent.height) / 1100)
        let mono = noise
            .transformed(by: CGAffineTransform(scaleX: s, y: s))
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputBVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                "inputBiasVector": CIVector(x: 0, y: 0, z: 0, w: min(amount, 1) * 0.4),
            ])
            .cropped(to: extent)
        return mono
            .applyingFilter("CISoftLightBlendMode", parameters: [kCIInputBackgroundImageKey: image])
            .cropped(to: extent)
    }

    static func vignette(_ image: CIImage, amount: CGFloat) -> CIImage {
        guard amount > 0 else { return image }
        let extent = image.extent
        let d = hypot(extent.width, extent.height) / 2
        guard let gradient = CIFilter(name: "CIRadialGradient", parameters: [
            "inputCenter": CIVector(x: extent.midX, y: extent.midY),
            "inputRadius0": d * 0.45,
            "inputRadius1": d * 1.05,
            "inputColor0": CIColor(red: 0, green: 0, blue: 0, alpha: 0),
            "inputColor1": CIColor(red: 0, green: 0, blue: 0, alpha: min(amount, 1) * 0.85),
        ])?.outputImage else { return image }
        return gradient.cropped(to: extent).composited(over: image)
    }

    static func bloom(_ image: CIImage, amount: CGFloat) -> CIImage {
        guard amount > 0 else { return image }
        let extent = image.extent
        let dim = max(extent.width, extent.height)
        return image.clampedToExtent()
            .applyingFilter("CIBloom", parameters: [
                kCIInputRadiusKey: dim * 0.012,
                kCIInputIntensityKey: min(amount, 1),
            ])
            .cropped(to: extent)
    }

    static func unsharp(_ image: CIImage, radius: CGFloat, intensity: CGFloat) -> CIImage {
        guard intensity != 0 else { return image }
        let extent = image.extent
        return image.clampedToExtent()
            .applyingFilter("CIUnsharpMask", parameters: [
                kCIInputRadiusKey: radius,
                kCIInputIntensityKey: intensity,
            ])
            .cropped(to: extent)
    }

    /// t = 0 得到 a，t = 1 得到 b。
    static func mix(_ a: CIImage, _ b: CIImage, t: CGFloat) -> CIImage {
        a.applyingFilter("CIDissolveTransition", parameters: [
            "inputTargetImage": b,
            "inputTime": min(max(t, 0), 1),
        ]).cropped(to: a.extent)
    }
}
