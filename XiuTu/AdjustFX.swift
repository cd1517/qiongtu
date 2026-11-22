import CoreImage
import Foundation
import simd

/// 曲线的插值：单调三次（Fritsch–Carlson），控制点之间不会过冲出奇怪的鼓包。
struct CurveShape {
    private let xs: [Double]
    private let ys: [Double]
    private let tangents: [Double]

    init(_ points: [CGPoint]) {
        var sorted = points.sorted { $0.x < $1.x }
        if sorted.isEmpty { sorted = CurveSet.identity }
        var px: [Double] = [], py: [Double] = []
        for p in sorted {
            let x = Double(min(max(p.x, 0), 1)), y = Double(min(max(p.y, 0), 1))
            // x 重合的点只留第一个，免得除零。
            if let last = px.last, x - last < 1e-4 { continue }
            px.append(x)
            py.append(y)
        }
        let n = px.count
        var m = [Double](repeating: 0, count: n)
        if n >= 2 {
            var d = [Double](repeating: 0, count: n - 1)
            for i in 0..<(n - 1) { d[i] = (py[i + 1] - py[i]) / (px[i + 1] - px[i]) }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            if n > 2 {
                for i in 1..<(n - 1) { m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2 }
            }
            for i in 0..<(n - 1) {
                if d[i] == 0 {
                    m[i] = 0
                    m[i + 1] = 0
                    continue
                }
                let a = m[i] / d[i], b = m[i + 1] / d[i]
                let s = a * a + b * b
                if s > 9 {
                    let t = 3 / s.squareRoot()
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        xs = px
        ys = py
        tangents = m
    }

    func value(at x: Double) -> Double {
        guard let first = xs.first, let last = xs.last else { return x }
        if xs.count == 1 || x <= first { return ys[0] }
        if x >= last { return ys[ys.count - 1] }
        var i = 0
        while i < xs.count - 2, x > xs[i + 1] { i += 1 }
        let h = xs[i + 1] - xs[i]
        let t = (x - xs[i]) / h
        let t2 = t * t, t3 = t2 * t
        let v = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * tangents[i]
            + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * tangents[i + 1]
        return min(max(v, 0), 1)
    }
}

/// 调色里各项效果的实现。着色器都在 gamma 空间里算亮度和颜色，行为接近常见修图软件的滑块。
enum AdjustFX {
    private static func num(_ v: Float) -> NSNumber { NSNumber(value: v) }

    // MARK: 按区域提亮 / 压暗

    private static let lumaKernel = CIColorKernel(source: """
    kernel vec4 adjLuma(__sample s) {
        float l = dot(pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2)), vec3(0.299, 0.587, 0.114));
        return vec4(vec3(clamp(l, 0.0, 1.0)), 1.0);
    }
    """)

    private static let momentsKernel = CIColorKernel(source: """
    kernel vec4 adjMoments(__sample s) {
        return vec4(s.r, s.r * s.r, 0.0, 1.0);
    }
    """)

    /// 引导滤波的系数：反差大的地方（边缘）跟着原图走，平坦的地方取周围的平均，
    /// 这样"这一带有多亮"不会越过人和背景的交界，提亮压暗时边上不出光晕。
    private static let coefficientsKernel = CIColorKernel(source: """
    kernel vec4 adjCoefficients(__sample m, float eps, float follow) {
        float variance = max(m.g - m.r * m.r, 0.0);
        float a = follow * variance / (variance + eps);
        return vec4(a, (1.0 - a) * m.r, 0.0, 1.0);
    }
    """)

    /// p = 智能补光、逆光、氛围、自然光；even = 校正光。
    /// ab 是引导滤波的系数图，wide 是范围大得多的同一种系数图，avg 是整张图的平均亮度。
    /// 校正光：哪一片比全图平均暗就提亮、比平均亮就压暗（把打光不匀的地方拉平），再把整体往正常曝光靠一点。
    /// 都按"档"在线性光上做增益，所以只改明暗、不改色相，局部的反差也留着。
    private static let localToneKernel = CIColorKernel(source: """
    kernel vec4 adjLocalTone(__sample s, __sample ab, __sample wide, __sample avg, vec4 p, float even) {
        vec3 c = max(s.rgb, vec3(0.0));
        float l = clamp(dot(pow(c, vec3(1.0 / 2.2)), vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        float area = clamp(ab.r * l + ab.g, 0.0, 1.0);
        float e = mix(area, l, 0.15);
        float dark = (1.0 - smoothstep(0.08, 0.55, e)) * (0.35 + 0.65 * smoothstep(0.0, 0.08, e));
        float bright = smoothstep(0.6, 0.95, e);
        float stops = p.x * 1.6 * dark;
        stops += p.y * (1.5 * dark - 0.9 * bright);
        stops += p.z * 0.85 * (1.0 - 2.0 * e);
        stops += p.w * 0.5 * (1.0 - smoothstep(0.25, 0.85, e));
        float whole = clamp(avg.r, 0.03, 1.0);
        float zone = clamp(wide.r * l + wide.g, 0.03, 1.0);
        stops += even * 0.8 * clamp(2.2 * log2(whole / zone), -1.5, 1.5);
        stops += even * 0.3 * clamp(2.2 * log2(0.46 / whole), -1.0, 1.0);
        c = c * pow(2.0, stops);
        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(y), c, 1.0 + 0.22 * p.z);
        return vec4(max(c, vec3(0.0)), s.a);
    }
    """)

    static func localTone(_ image: CIImage, fill: Float, backlight: Float, ambiance: Float,
                          natural: Float, even: Float) -> CIImage {
        guard let lumaKernel, let momentsKernel, let coefficientsKernel, let localToneKernel else { return image }
        let extent = image.extent
        let dim = max(extent.width, extent.height)
        guard let luma = lumaKernel.apply(extent: extent, arguments: [image]) else { return image }
        // 这些都是很平滑的图，缩到 1/4 算，省时间。缩小后的宽高取整，边上才不会有半个像素的暗边。
        let k: CGFloat = 0.25
        let kx = max(1, (extent.width * k).rounded()) / extent.width
        let ky = max(1, (extent.height * k).rounded()) / extent.height
        let small = luma
            .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
            .transformed(by: CGAffineTransform(scaleX: kx, y: ky))
        let smallExtent = CGRect(x: 0, y: 0, width: (extent.width * kx).rounded(), height: (extent.height * ky).rounded())
        func blurred(_ input: CIImage, _ radius: CGFloat) -> CIImage {
            input.cropped(to: smallExtent).clampedToExtent()
                .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
                .cropped(to: smallExtent)
        }
        func enlarged(_ input: CIImage) -> CIImage {
            input.clampedToExtent()
                .transformed(by: CGAffineTransform(scaleX: 1 / kx, y: 1 / ky))
                .transformed(by: CGAffineTransform(translationX: extent.minX, y: extent.minY))
                .cropped(to: extent)
        }
        let radius = dim * k * 0.022
        guard let moments = momentsKernel.apply(extent: smallExtent, arguments: [small]),
              let coefficients = coefficientsKernel.apply(extent: smallExtent, arguments: [
                  blurred(moments, radius), num(0.004), num(0.7),
              ]) else { return image }
        let ab = enlarged(blurred(coefficients, radius))
        var wide = luma
        var average = luma
        if even != 0 {
            let wideRadius = dim * k * 0.12
            if let coarse = coefficientsKernel.apply(extent: smallExtent, arguments: [
                blurred(moments, wideRadius), num(0.006), num(0.6),
            ]) {
                wide = enlarged(blurred(coarse, wideRadius))
            }
            average = small.applyingFilter("CIAreaAverage", parameters: [kCIInputExtentKey: CIVector(cgRect: smallExtent)])
                .clampedToExtent()
        }
        let p = CIVector(x: CGFloat(fill), y: CGFloat(backlight), z: CGFloat(ambiance), w: CGFloat(natural))
        return localToneKernel.apply(extent: extent, arguments: [image, ab, wide, average, p, num(even)]) ?? image
    }

    // MARK: 逐像素的光线

    /// p = 光感、弱光、自然光、氛围光；box = 画面范围（x、y、宽、高）。
    /// 光感：往亮了调是"滤色"式提亮，黑位不动、越亮越透；往暗了调是"正片叠底"式压暗，白位不动。
    /// 弱光：整张提亮，但越亮的地方加得越少，高光不会冲掉。
    /// 自然光：柔和地提亮中间调、降一点反差、带一点暖意，像靠窗的自然光。
    /// 氛围光：亮部染上暖色（正）或冷色（负），再从左上角打进来一片同色的柔光。
    private static let lightKernel = CIColorKernel(source: """
    kernel vec4 adjLight(__sample s, vec4 p, vec4 box) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = dot(g, vec3(0.299, 0.587, 0.114));
        float x = clamp(l, 0.0, 1.0);
        float over = l - x;
        float y = x * pow(2.0, p.y * 1.8 * pow(1.0 - x, 1.2) / 2.2);
        y = min(y, 1.0);
        float lifted = 1.0 - (1.0 - y) * (1.0 - y);
        y = p.x >= 0.0 ? mix(y, lifted, p.x * 0.8) : mix(y, y * y, -p.x * 0.8);
        float airy = 1.0 - pow(1.0 - y, 1.5);
        y = mix(y, airy, p.z * 0.45);
        y = mix(y, 0.5 + (y - 0.5) * 0.86, p.z);
        float target = max(y + over, 0.0);
        float ratio = min(target / max(l, 0.0001), 12.0);
        vec3 o = mix(vec3(target), g * ratio, smoothstep(0.0, 0.03, l));
        o = o * vec3(1.0 + 0.035 * p.z, 1.0 + 0.008 * p.z, 1.0 - 0.04 * p.z);
        float amount = abs(p.w);
        if (amount > 0.0) {
            vec3 tint = p.w > 0.0 ? vec3(1.0, 0.62, 0.25) : vec3(0.35, 0.6, 1.0);
            vec2 uv = (destCoord() - box.xy) / box.zw;
            float glow = 1.0 - smoothstep(0.0, 1.1, distance(uv, vec2(0.12, 0.92)));
            vec3 base = clamp(o, 0.0, 1.0);
            float bl = dot(base, vec3(0.299, 0.587, 0.114));
            float hi = smoothstep(0.2, 0.95, bl);
            vec3 unit = tint / dot(tint, vec3(0.299, 0.587, 0.114));
            vec3 toned = clamp(base * mix(vec3(1.0), unit, 0.28 * amount * hi), 0.0, 1.0);
            o = 1.0 - (1.0 - toned) * (1.0 - tint * (0.3 * amount * glow));
        }
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    static func light(_ image: CIImage, sense: Float, lowLight: Float, natural: Float, mood: Float) -> CIImage {
        guard sense != 0 || lowLight != 0 || natural != 0 || mood != 0, let lightKernel else { return image }
        let e = image.extent
        let p = CIVector(x: CGFloat(sense), y: CGFloat(lowLight), z: CGFloat(natural), w: CGFloat(mood))
        let box = CIVector(x: e.minX, y: e.minY, z: max(e.width, 1), w: max(e.height, 1))
        return lightKernel.apply(extent: e, arguments: [image, p, box]) ?? image
    }

    // MARK: 曲线

    /// 把四条曲线合成一张三通道的一维查找表：先过总曲线，再过各自通道的曲线。
    static func curveTable(_ set: CurveSet, count: Int = 256) -> [Float] {
        let master = CurveShape(set.channels[0])
        let single = (1...3).map { CurveShape(set.channels[$0]) }
        var table = [Float](repeating: 0, count: count * 3)
        for i in 0..<count {
            let x = master.value(at: Double(i) / Double(count - 1))
            for c in 0..<3 { table[i * 3 + c] = Float(single[c].value(at: x)) }
        }
        return table
    }

    static func curves(_ image: CIImage, _ set: CurveSet) -> CIImage {
        guard !set.isIdentity else { return image }
        let table = curveTable(set)
        let data = table.withUnsafeBufferPointer { Data(buffer: $0) }
        return image.applyingFilter("CIColorCurves", parameters: [
            "inputCurvesData": data,
            "inputCurvesDomain": CIVector(x: 0, y: 1),
            "inputColorSpace": ColorCube.space,
        ])
    }

    // MARK: HSL

    /// 按色相把画面分成八个颜色，各自改色相、饱和度、明度。灰色和接近灰色的地方不受影响。
    private static let hslKernel = CIColorKernel(source: """
    vec3 adjRGB2HSV(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }
    vec3 adjHSV2RGB(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }
    float adjBand(float h, float c, float left, float right) {
        float d = fract(h - c + 0.5) - 0.5;
        return d < 0.0 ? max(0.0, 1.0 + d / left) : max(0.0, 1.0 - d / right);
    }
    kernel vec4 adjHSL(__sample s, vec4 h1, vec4 h2, vec4 s1, vec4 s2, vec4 l1, vec4 l2) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        vec3 hsv = adjRGB2HSV(g);
        float h = hsv.x;
        float w0 = adjBand(h, 0.0, 40.0 / 360.0, 30.0 / 360.0);
        float w1 = adjBand(h, 30.0 / 360.0, 30.0 / 360.0, 30.0 / 360.0);
        float w2 = adjBand(h, 60.0 / 360.0, 30.0 / 360.0, 60.0 / 360.0);
        float w3 = adjBand(h, 120.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w4 = adjBand(h, 180.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w5 = adjBand(h, 240.0 / 360.0, 60.0 / 360.0, 40.0 / 360.0);
        float w6 = adjBand(h, 280.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        float w7 = adjBand(h, 320.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        vec4 wa = vec4(w0, w1, w2, w3);
        vec4 wb = vec4(w4, w5, w6, w7);
        float dh = dot(wa, h1) + dot(wb, h2);
        float ds = dot(wa, s1) + dot(wb, s2);
        float dl = dot(wa, l1) + dot(wb, l2);
        float sw = smoothstep(0.03, 0.25, hsv.y);
        hsv.x = fract(hsv.x + dh * sw * (30.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 + ds * sw), 0.0, 1.0);
        vec3 o = adjHSV2RGB(hsv) * (1.0 + 0.6 * dl * sw);
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    static func hsl(_ image: CIImage, _ values: [SIMD3<Float>]) -> CIImage {
        guard values.count >= 8, let hslKernel else { return image }
        func vector(_ channel: Int, _ range: Range<Int>) -> CIVector {
            let n = range.map { CGFloat(values[$0][channel]) }
            return CIVector(x: n[0], y: n[1], z: n[2], w: n[3])
        }
        return hslKernel.apply(extent: image.extent, arguments: [
            image, vector(0, 0..<4), vector(0, 4..<8), vector(1, 0..<4), vector(1, 4..<8),
            vector(2, 0..<4), vector(2, 4..<8),
        ]) ?? image
    }

    // MARK: 肤色

    /// 只动肤色那一段色相（橙红附近、饱和度不高不低、不太暗的地方）。
    /// tone：负 = 偏粉红，正 = 偏暖黄；white：提亮并降一点饱和，显得白皙。
    private static let skinKernel = CIColorKernel(source: """
    vec3 skinRGB2HSV(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }
    vec3 skinHSV2RGB(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }
    kernel vec4 adjSkin(__sample s, float tone, float white) {
        vec3 g = clamp(pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2)), 0.0, 1.0);
        vec3 hsv = skinRGB2HSV(g);
        float d = fract(hsv.x - 0.065 + 0.5) - 0.5;
        float w = smoothstep(0.0, 0.55, max(0.0, 1.0 - abs(d) / 0.085));
        w = w * smoothstep(0.08, 0.2, hsv.y) * (1.0 - smoothstep(0.62, 0.9, hsv.y)) * smoothstep(0.15, 0.4, hsv.z);
        hsv.x = fract(hsv.x + tone * w * (10.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 - 0.32 * white * w), 0.0, 1.0);
        hsv.z = hsv.z + (1.0 - hsv.z) * 0.22 * white * w;
        vec3 o = mix(g, skinHSV2RGB(hsv), step(0.0001, w));
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    static func skin(_ image: CIImage, tone: Float, whiten: Float) -> CIImage {
        guard tone != 0 || whiten != 0, let skinKernel else { return image }
        return skinKernel.apply(extent: image.extent, arguments: [image, num(tone), num(whiten)]) ?? image
    }

    // MARK: 色调分离

    /// sh / hi 是已经乘好强度的色偏（亮度为零，所以只染色、不改明暗）。
    private static let splitKernel = CIColorKernel(source: """
    kernel vec4 adjSplit(__sample s, vec3 sh, vec3 hi, float balance) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = clamp(dot(g, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        float pivot = 0.5 - balance * 0.3;
        float ws = 1.0 - smoothstep(pivot - 0.4, pivot + 0.1, l);
        float wh = smoothstep(pivot - 0.1, pivot + 0.4, l);
        vec3 o = g + ws * sh + wh * hi;
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    /// 色相（0...1）对应的纯色。
    static func rgb(hue: Double) -> SIMD3<Double> {
        let h = (hue - hue.rounded(.down)) * 6
        let x = 1 - abs(h.truncatingRemainder(dividingBy: 2) - 1)
        switch Int(h) {
        case 0: return SIMD3(1, x, 0)
        case 1: return SIMD3(x, 1, 0)
        case 2: return SIMD3(0, 1, x)
        case 3: return SIMD3(0, x, 1)
        case 4: return SIMD3(x, 0, 1)
        default: return SIMD3(1, 0, x)
        }
    }

    private static func tintOffset(hue: Float, amount: Float) -> CIVector {
        let c = rgb(hue: Double(hue))
        let luma = c.x * 0.299 + c.y * 0.587 + c.z * 0.114
        let k = Double(amount) * 0.2
        return CIVector(x: CGFloat((c.x - luma) * k), y: CGFloat((c.y - luma) * k), z: CGFloat((c.z - luma) * k))
    }

    static func split(_ image: CIImage, _ tone: SplitTone) -> CIImage {
        guard !tone.isIdentity, let splitKernel else { return image }
        return splitKernel.apply(extent: image.extent, arguments: [
            image, tintOffset(hue: tone.shadowHue, amount: tone.shadowAmount),
            tintOffset(hue: tone.highlightHue, amount: tone.highlightAmount), num(tone.balance),
        ]) ?? image
    }

    // MARK: 去灰

    private static let darkKernel = CIColorKernel(source: """
    kernel vec4 adjDark(__sample s) {
        float m = min(min(s.r, s.g), s.b);
        return vec4(vec3(clamp(m, 0.0, 1.0)), 1.0);
    }
    """)

    /// d 是模糊过的"暗通道"：越亮说明这一带蒙的灰雾越厚，就减掉越多。
    private static let dehazeKernel = CIColorKernel(source: """
    kernel vec4 adjDehaze(__sample s, __sample d, float amount) {
        vec3 c = max(s.rgb, vec3(0.0));
        float veil = clamp(d.r, 0.0, 1.0) * amount * 0.55;
        c = (c - vec3(veil)) / max(1.0 - veil, 0.25);
        return vec4(max(c, vec3(0.0)), s.a);
    }
    """)

    static func dehaze(_ image: CIImage, amount: Float) -> CIImage {
        guard amount > 0, let darkKernel, let dehazeKernel else { return image }
        let extent = image.extent
        let dim = max(extent.width, extent.height)
        guard let dark = darkKernel.apply(extent: extent, arguments: [image]) else { return image }
        let veil = dark.clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: dim * 0.045])
            .cropped(to: extent)
        return dehazeKernel.apply(extent: extent, arguments: [image, veil, num(amount)]) ?? image
    }

    // MARK: 清晰度

    /// 大半径的局部反差，只改亮度不改颜色。主要加在中间调上，并限制强边缘两侧的幅度，
    /// 免得建筑、发丝边上出现一圈光晕。
    private static let clarityKernel = CIColorKernel(source: """
    kernel vec4 adjClarity(__sample s, __sample big, float clarity) {
        vec3 W = vec3(0.299, 0.587, 0.114);
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = dot(g, W);
        float lb = dot(pow(max(big.rgb, vec3(0.0)), vec3(1.0 / 2.2)), W);
        float edge = 2.0 * clamp(l, 0.0, 1.0) - 1.0;
        float midtone = 1.0 - edge * edge;
        float delta = clamp(l - lb, -0.18, 0.18);
        float x = max(l + clarity * 1.3 * delta * midtone, 0.0);
        float ratio = min(x / max(l, 0.0001), 8.0);
        vec3 o = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, l));
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    static func clarity(_ image: CIImage, amount: Float) -> CIImage {
        guard amount != 0, let clarityKernel else { return image }
        let extent = image.extent
        let dim = max(extent.width, extent.height)
        let big = image.clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: dim * 0.018])
            .cropped(to: extent)
        return clarityKernel.apply(extent: extent, arguments: [image, big, num(amount)]) ?? image
    }

    // MARK: 局部调色

    /// p = 亮度、对比度、饱和度、色温。圆心附近完全生效，到半径处渐变到零。
    /// 提亮用"滤色"的方式：亮的颜色往白里走，不会某个通道先顶满而变色。
    private static let localKernel = CIColorKernel(source: """
    kernel vec4 adjLocal(__sample s, vec2 center, float radius, vec4 p) {
        float d = distance(destCoord(), center) / radius;
        float w = 1.0 - smoothstep(0.35, 1.0, d);
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        vec3 c = clamp(g, 0.0, 1.0);
        vec3 o = p.x >= 0.0 ? vec3(1.0) - pow(vec3(1.0) - c, vec3(1.0 + 1.4 * p.x)) : g * pow(2.0, p.x * 0.6);
        o = (o - vec3(0.5)) * (1.0 + 0.6 * p.y) + vec3(0.5);
        float l = dot(o, vec3(0.299, 0.587, 0.114));
        o = vec3(l) + (o - vec3(l)) * (1.0 + p.z);
        o = o * vec3(1.0 + 0.16 * p.w, 1.0, 1.0 - 0.16 * p.w);
        o = mix(g, max(o, vec3(0.0)), w);
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    private static func pixel(_ point: CGPoint, in extent: CGRect) -> CIVector {
        CIVector(x: extent.minX + point.x * extent.width, y: extent.minY + (1 - point.y) * extent.height)
    }

    static func local(_ image: CIImage, _ point: LocalAdjust) -> CIImage {
        guard let localKernel else { return image }
        let extent = image.extent
        let radius = max(1, CGFloat(point.radius) * max(extent.width, extent.height))
        let p = CIVector(x: CGFloat(point.brightness), y: CGFloat(point.contrast),
                         z: CGFloat(point.saturation), w: CGFloat(point.temperature))
        return localKernel.apply(extent: extent, arguments: [image, pixel(point.center, in: extent), radius, p]) ?? image
    }

    /// 只在屏幕预览上画的标记：选中的点画出范围圈和圆心，其他点只画圆心。不会进导出的图。
    private static let markerKernel = CIColorKernel(source: """
    kernel vec4 adjMarker(__sample s, vec2 center, float radius, float px, float chosen) {
        float d = distance(destCoord(), center);
        float ring = (1.0 - smoothstep(px * 0.6, px * 1.6, abs(d - radius))) * chosen;
        float shade = (1.0 - smoothstep(px * 1.6, px * 3.2, abs(d - radius))) * chosen;
        float core = 1.0 - smoothstep(px * 2.6, px * 3.8, d);
        float rim = 1.0 - smoothstep(px * 3.8, px * 5.4, d);
        vec3 c = mix(s.rgb, vec3(0.0), 0.4 * max(shade, rim));
        c = mix(c, vec3(1.0), max(ring, core * mix(0.75, 1.0, chosen)));
        return vec4(c, s.a);
    }
    """)

    static func markers(_ image: CIImage, points: [LocalAdjust], selected: UUID?) -> CIImage {
        guard let markerKernel else { return image }
        let extent = image.extent
        let dim = max(extent.width, extent.height)
        var out = image
        for point in points {
            let radius = max(1, CGFloat(point.radius) * dim)
            out = markerKernel.apply(extent: extent, arguments: [
                out, pixel(point.center, in: extent), radius, dim / 700, point.id == selected ? 1 : 0,
            ]) ?? out
        }
        return out
    }

    // MARK: 色散

    /// 红、蓝两个通道以画面中心为准一个放大一点、一个缩小一点，越靠边错开越多，像老镜头的色散。
    private static let fringeKernel = CIKernel(source: """
    kernel vec4 adjFringe(sampler src, vec2 center, float k) {
        vec2 p = destCoord();
        vec2 d = p - center;
        float r = sample(src, samplerTransform(src, center + d * (1.0 - k))).r;
        vec4 g = sample(src, samplerTransform(src, p));
        float b = sample(src, samplerTransform(src, center + d * (1.0 + k))).b;
        return vec4(r, g.g, b, g.a);
    }
    """)

    static func fringe(_ image: CIImage, amount: Float) -> CIImage {
        guard amount > 0, let fringeKernel else { return image }
        let extent = image.extent
        let k = CGFloat(amount) * 0.012
        let pad = hypot(extent.width, extent.height) / 2 * k + 2
        return fringeKernel.apply(extent: extent,
                                  roiCallback: { _, rect in rect.insetBy(dx: -pad, dy: -pad) },
                                  arguments: [image.clampedToExtent(), CIVector(x: extent.midX, y: extent.midY), k])?
            .cropped(to: extent) ?? image
    }

    // MARK: 暗角

    /// 正数压暗四角（和滤镜里的暗角是同一种），负数提亮四角。
    static func vignette(_ image: CIImage, amount: Float) -> CIImage {
        if amount > 0 { return FX.vignette(image, amount: CGFloat(amount)) }
        guard amount < 0 else { return image }
        let extent = image.extent
        let d = hypot(extent.width, extent.height) / 2
        guard let gradient = CIFilter(name: "CIRadialGradient", parameters: [
            "inputCenter": CIVector(x: extent.midX, y: extent.midY),
            "inputRadius0": d * 0.45,
            "inputRadius1": d * 1.05,
            "inputColor0": CIColor(red: 1, green: 1, blue: 1, alpha: 0),
            "inputColor1": CIColor(red: 1, green: 1, blue: 1, alpha: CGFloat(min(-amount, 1)) * 0.6),
        ])?.outputImage else { return image }
        return gradient.cropped(to: extent).composited(over: image)
    }

    // MARK: 自检

    /// 着色器写错时系统只会悄悄返回 nil、滑块拖了没反应，所以留一个能逐个检查的入口。
    static var kernelStatus: [(name: String, ok: Bool)] {
        [
            ("luma", lumaKernel != nil), ("moments", momentsKernel != nil), ("coefficients", coefficientsKernel != nil),
            ("localTone", localToneKernel != nil), ("light", lightKernel != nil), ("hsl", hslKernel != nil),
            ("skin", skinKernel != nil), ("split", splitKernel != nil), ("dark", darkKernel != nil),
            ("dehaze", dehazeKernel != nil), ("clarity", clarityKernel != nil), ("local", localKernel != nil),
            ("marker", markerKernel != nil), ("fringe", fringeKernel != nil),
        ]
    }
}
