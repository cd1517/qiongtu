import CoreImage
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// RAW 冲洗的一级菜单，对应桌面 RAW 软件里的各个面板。
enum RawGroup: String, CaseIterable, Identifiable, Hashable {
    case light, curve, color, mixer, grading, effects, detail, optics
    var id: String { rawValue }
    var title: String {
        switch self {
        case .light: return "光线"
        case .curve: return "曲线"
        case .color: return "色彩"
        case .mixer: return "混色"
        case .grading: return "分级"
        case .effects: return "效果"
        case .detail: return "细节"
        case .optics: return "光学"
        }
    }
}

/// 混色器里的八个颜色。
enum MixColor: Int, CaseIterable, Identifiable {
    case red, orange, yellow, green, aqua, blue, purple, magenta
    var id: Int { rawValue }
    var title: String { ["红", "橙", "黄", "绿", "浅绿", "蓝", "紫", "洋红"][rawValue] }
    var swatch: Color {
        [Color(red: 0.93, green: 0.22, blue: 0.2), Color(red: 0.97, green: 0.55, blue: 0.15),
         Color(red: 0.96, green: 0.84, blue: 0.2), Color(red: 0.3, green: 0.75, blue: 0.3),
         Color(red: 0.25, green: 0.8, blue: 0.78), Color(red: 0.22, green: 0.45, blue: 0.95),
         Color(red: 0.6, green: 0.35, blue: 0.9), Color(red: 0.9, green: 0.3, blue: 0.75)][rawValue]
    }
}

/// 颜色分级的四个区。
enum GradeZone: Int, CaseIterable, Identifiable {
    case shadows, midtones, highlights, global
    var id: Int { rawValue }
    var title: String { ["阴影", "中间调", "高光", "全局"][rawValue] }
}

enum ColorChannel: String, CaseIterable {
    case hue, sat, lum
    var title: String {
        switch self {
        case .hue: return "色相"
        case .sat: return "饱和度"
        case .lum: return "明亮度"
        }
    }
    var symbol: String {
        switch self {
        case .hue: return "paintpalette"
        case .sat: return "drop"
        case .lum: return "sun.max"
        }
    }
}

/// RAW 冲洗里的一个滑块。
struct RawKey: Hashable, Identifiable {
    enum Style { case stops, percent, kelvin, signed, degrees }

    let id: String
    let title: String
    let symbol: String
    let range: ClosedRange<Float>
    var style: Style = .percent
    /// 文件本身没有规定时的默认值。
    var neutral: Float = 0

    static func == (a: RawKey, b: RawKey) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    func format(_ value: Float) -> String {
        switch style {
        case .stops: return String(format: "%+.2f", value)
        case .kelvin: return "\(Int(value.rounded()))K"
        case .signed: return String(format: "%+.0f", value)
        case .degrees: return "\(Int(value.rounded()))°"
        case .percent: return String(format: "%.0f", value * 100)
        }
    }

    private static func pm(_ id: String, _ title: String, _ symbol: String) -> RawKey {
        RawKey(id: id, title: title, symbol: symbol, range: -1...1)
    }
    private static func unit(_ id: String, _ title: String, _ symbol: String, max: Float = 1, neutral: Float = 0) -> RawKey {
        RawKey(id: id, title: title, symbol: symbol, range: 0...max, neutral: neutral)
    }

    // 光线
    static let exposure = RawKey(id: "exposure", title: "曝光", symbol: "plusminus.circle", range: -5...5, style: .stops)
    static let contrast = pm("contrast", "对比度", "circle.lefthalf.filled")
    static let highlights = pm("highlights", "高光", "sun.haze")
    static let shadows = pm("shadows", "阴影", "moon")
    static let whites = pm("whites", "白色色阶", "circle")
    static let blacks = pm("blacks", "黑色色阶", "circle.fill")
    // 曲线（参数曲线的四段）
    static let curveHighlights = pm("curve.highlights", "高光区", "sun.max")
    static let curveLights = pm("curve.lights", "亮调", "sun.min")
    static let curveDarks = pm("curve.darks", "暗调", "cloud")
    static let curveShadows = pm("curve.shadows", "阴影区", "moon.stars")
    // 色彩
    static let temperature = RawKey(id: "temperature", title: "色温", symbol: "thermometer.medium", range: 2000...15000, style: .kelvin, neutral: 5500)
    static let tint = RawKey(id: "tint", title: "色调", symbol: "eyedropper", range: -150...150, style: .signed)
    static let vibrance = pm("vibrance", "自然饱和度", "sparkles")
    static let saturation = pm("saturation", "饱和度", "drop")
    // 效果
    static let texture = pm("texture", "纹理", "square.grid.3x3")
    static let clarity = pm("clarity", "清晰度", "diamond")
    static let dehaze = pm("dehaze", "去朦胧", "cloud.fog")
    static let vignette = pm("vignette", "暗角", "smallcircle.filled.circle")
    static let vignetteMid = unit("vignette.mid", "暗角中点", "circle.dashed", neutral: 0.5)
    static let grain = unit("grain", "颗粒", "aqi.medium")
    static let grainSize = unit("grain.size", "颗粒大小", "circle.grid.3x3", neutral: 0.25)
    // 细节（都在 RAW 解码阶段做）
    static let sharpness = unit("sharpness", "锐化", "triangle")
    static let detail = unit("detail", "细节", "diamond", max: 3)
    static let luminanceNoise = unit("noise.luminance", "明亮度降噪", "wand.and.stars")
    static let colorNoise = unit("noise.color", "颜色降噪", "paintbrush")
    static let moire = unit("moire", "去摩尔纹", "waveform")
    static let rawContrast = unit("raw.contrast", "微对比", "circle.righthalf.filled")
    static let localTone = unit("raw.localtone", "局部影调", "square.stack.3d.up")
    static let boost = unit("raw.boost", "相机曲线", "point.topleft.down.curvedto.point.bottomright.up", neutral: 1)
    static let boostShadow = unit("raw.boostshadow", "暗部增益", "moon.haze", max: 2)
    // 颜色分级的全局项
    static let gradeBlending = unit("grade.blending", "混合", "circle.lefthalf.striped.horizontal", neutral: 0.5)
    static let gradeBalance = pm("grade.balance", "平衡", "scalemass")

    static func mix(_ color: MixColor, _ channel: ColorChannel) -> RawKey {
        RawKey(id: "mix.\(color.rawValue).\(channel.rawValue)", title: channel.title, symbol: channel.symbol, range: -1...1)
    }

    static func grade(_ zone: GradeZone, _ channel: ColorChannel) -> RawKey {
        let id = "grade.\(zone.rawValue).\(channel.rawValue)"
        switch channel {
        case .hue: return RawKey(id: id, title: "色相", symbol: channel.symbol, range: 0...360, style: .degrees)
        case .sat: return RawKey(id: id, title: "饱和度", symbol: channel.symbol, range: 0...1)
        case .lum: return RawKey(id: id, title: "明亮度", symbol: channel.symbol, range: -1...1)
        }
    }

    static let lightKeys = [exposure, contrast, highlights, shadows, whites, blacks]
    static let curveKeys = [curveHighlights, curveLights, curveDarks, curveShadows]
    static let colorKeys = [temperature, tint, vibrance, saturation]
    static let effectKeys = [texture, clarity, dehaze, vignette, vignetteMid, grain, grainSize]
    static let detailKeys = [sharpness, detail, luminanceNoise, colorNoise, moire, rawContrast, localTone, boost, boostShadow]
}

/// 白平衡预设。
struct WhiteBalancePreset: Identifiable {
    let title: String
    let temperature: Float
    let tint: Float
    var id: String { title }

    static let all = [
        WhiteBalancePreset(title: "日光", temperature: 5500, tint: 10),
        WhiteBalancePreset(title: "阴天", temperature: 6500, tint: 10),
        WhiteBalancePreset(title: "阴影", temperature: 7500, tint: 10),
        WhiteBalancePreset(title: "白炽灯", temperature: 2850, tint: 0),
        WhiteBalancePreset(title: "荧光灯", temperature: 3800, tint: 21),
        WhiteBalancePreset(title: "闪光灯", temperature: 5500, tint: 0),
    ]
}

/// RAW 解码和调节。解码用系统自带的 RAW 引擎（各家相机的 RAW 和 DNG 都由它负责），
/// 曝光、白平衡、降噪、锐化在解码阶段做，所以能找回 JPEG 里已经丢掉的高光和暗部；
/// 其余的色调、曲线、混色、分级、效果在解码后的高动态数据上做。
final class RawDeveloper {
    private let filter: CIRAWFilter
    let nativeSize: CGSize
    /// 这个文件"原样冲洗"时各滑块的值（色温是相机记录的拍摄白平衡）。
    private(set) var defaults: [RawKey: Float] = [:]
    let lensCorrectionSupported: Bool

    /// 对比度和曲线：在套完相机曲线之后的成品影调上做。按亮度算、再按比例缩放三个通道，所以不改变色相。
    /// 高光、阴影、白色色阶、黑色色阶不在这里——它们在 RAW 的线性空间里做（见 RawLinearFilter）。
    /// curve = 高光区、亮调、暗调、阴影区。
    private static let toneKernel = CIColorKernel(source: """
    float rawBump(float x, float c) {
        float d = (x - c) / 0.21;
        return exp(-d * d) * smoothstep(0.0, 0.06, x) * (1.0 - smoothstep(0.94, 1.0, x));
    }
    kernel vec4 rawTone(__sample s, float contrast, vec4 curve) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = dot(g, vec3(0.299, 0.587, 0.114));
        float xc = clamp(l, 0.0, 1.0);
        float over = l - xc;
        float sCurve = xc * xc * (3.0 - 2.0 * xc);
        float softer = 0.5 + (xc - 0.5) * 0.55;
        float curved = contrast >= 0.0 ? mix(xc, sCurve, contrast) : mix(xc, softer, -contrast);
        curved = curved + 0.11 * (curve.x * rawBump(curved, 0.88) + curve.y * rawBump(curved, 0.64)
                                  + curve.z * rawBump(curved, 0.36) + curve.w * rawBump(curved, 0.12));
        float x = max(curved + over, 0.0);
        float ratio = min(x / max(l, 0.0001), 12.0);
        vec3 o = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, l));
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    /// 混色器：按色相把画面分成八个颜色，各自改色相、饱和度、明亮度。灰色和接近灰色的地方不受影响。
    private static let mixerKernel = CIColorKernel(source: """
    vec3 rawRGB2HSV(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }
    vec3 rawHSV2RGB(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }
    float rawBand(float h, float c, float left, float right) {
        float d = fract(h - c + 0.5) - 0.5;
        return d < 0.0 ? max(0.0, 1.0 + d / left) : max(0.0, 1.0 - d / right);
    }
    kernel vec4 rawMixer(__sample s, vec4 h1, vec4 h2, vec4 s1, vec4 s2, vec4 l1, vec4 l2) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        vec3 hsv = rawRGB2HSV(g);
        float h = hsv.x;
        float w0 = rawBand(h, 0.0, 40.0 / 360.0, 30.0 / 360.0);
        float w1 = rawBand(h, 30.0 / 360.0, 30.0 / 360.0, 30.0 / 360.0);
        float w2 = rawBand(h, 60.0 / 360.0, 30.0 / 360.0, 60.0 / 360.0);
        float w3 = rawBand(h, 120.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w4 = rawBand(h, 180.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w5 = rawBand(h, 240.0 / 360.0, 60.0 / 360.0, 40.0 / 360.0);
        float w6 = rawBand(h, 280.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        float w7 = rawBand(h, 320.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        vec4 wa = vec4(w0, w1, w2, w3);
        vec4 wb = vec4(w4, w5, w6, w7);
        float dh = dot(wa, h1) + dot(wb, h2);
        float ds = dot(wa, s1) + dot(wb, s2);
        float dl = dot(wa, l1) + dot(wb, l2);
        float sw = smoothstep(0.03, 0.25, hsv.y);
        hsv.x = fract(hsv.x + dh * sw * (30.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 + ds * sw), 0.0, 1.0);
        vec3 o = rawHSV2RGB(hsv) * (1.0 + 0.6 * dl * sw);
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    /// 颜色分级：给阴影、中间调、高光各染一个颜色。sh / mid / hi / all 是已经乘好饱和度的色偏，
    /// lum 是四个区的明亮度，opts = 混合、平衡。
    private static let gradingKernel = CIColorKernel(source: """
    kernel vec4 rawGrading(__sample s, vec3 sh, vec3 mid, vec3 hi, vec3 all, vec4 lum, vec2 opts) {
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = clamp(dot(g, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        float pivot = 0.5 - opts.y * 0.3;
        float width = mix(0.08, 0.5, opts.x);
        float ws = 1.0 - smoothstep(pivot - width, pivot + width * 0.25, l);
        float wh = smoothstep(pivot - width * 0.25, pivot + width, l);
        float wm = max(0.0, 1.0 - ws - wh);
        vec3 o = g + ws * sh + wm * mid + wh * hi + all;
        o = o * (1.0 + 0.5 * (ws * lum.x + wm * lum.y + wh * lum.z + lum.w));
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    private static let darkKernel = CIColorKernel(source: """
    kernel vec4 rawDark(__sample s) {
        float m = min(min(s.r, s.g), s.b);
        return vec4(vec3(clamp(m, 0.0, 1.0)), 1.0);
    }
    """)

    /// 去朦胧：d 是模糊过的"暗通道"，越亮说明这一带蒙的雾越厚。
    private static let dehazeKernel = CIColorKernel(source: """
    kernel vec4 rawDehaze(__sample s, __sample d, float amount) {
        vec3 c = max(s.rgb, vec3(0.0));
        if (amount >= 0.0) {
            float veil = clamp(d.r, 0.0, 1.0) * amount * 0.6;
            c = (c - vec3(veil)) / max(1.0 - veil, 0.25);
        } else {
            c = mix(c, vec3(0.75), -amount * 0.5);
        }
        return vec4(max(c, vec3(0.0)), s.a);
    }
    """)

    /// 清晰度（big = 大半径模糊）和纹理（small = 小半径模糊）：都只改亮度，不改颜色。
    /// 清晰度主要加在中间调上，并且限制强边缘两侧的幅度，免得建筑、发丝边上出现一圈光晕。
    private static let localKernel = CIColorKernel(source: """
    kernel vec4 rawLocal(__sample s, __sample big, __sample small, float clarity, float texture) {
        vec3 W = vec3(0.299, 0.587, 0.114);
        vec3 g = pow(max(s.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float l = dot(g, W);
        float lb = dot(pow(max(big.rgb, vec3(0.0)), vec3(1.0 / 2.2)), W);
        float lf = dot(pow(max(small.rgb, vec3(0.0)), vec3(1.0 / 2.2)), W);
        float edge = 2.0 * clamp(l, 0.0, 1.0) - 1.0;
        float mid = 1.0 - edge * edge;
        float dBig = clamp(l - lb, -0.18, 0.18);
        float dSmall = clamp(l - lf, -0.25, 0.25);
        float x = max(l + clarity * 1.0 * dBig * mid + texture * 1.3 * dSmall, 0.0);
        float ratio = min(x / max(l, 0.0001), 8.0);
        vec3 o = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, l));
        return vec4(pow(max(o, vec3(0.0)), vec3(2.2)), s.a);
    }
    """)

    /// 暗角：amount < 0 压暗四角，> 0 提亮四角（也可以用来补偿镜头本身的暗角）。
    private static let vignetteKernel = CIColorKernel(source: """
    kernel vec4 rawVignette(__sample s, vec2 center, float radius, float amount, float mid) {
        float d = distance(destCoord(), center) / radius;
        float w = smoothstep(mix(0.05, 0.85, mid), 1.05, d);
        float gain = amount < 0.0 ? 1.0 + amount * 0.9 * w : 1.0 + amount * 1.4 * w;
        return vec4(s.rgb * gain, s.a);
    }
    """)

    /// 系统的 RAW 引擎靠"文件类型"认格式：只给一段数据、不说是什么文件时，
    /// DNG 这类外壳长得像 TIFF 的会认不出来。所以先按扩展名给出类型，不行再落成文件按路径打开。
    private static func makeFilter(data: Data, name: String) -> CIRAWFilter? {
        func usable(_ f: CIRAWFilter?) -> CIRAWFilter? {
            guard let f, f.nativeSize.width > 0, f.nativeSize.height > 0 else { return nil }
            return f
        }
        let ext = (name as NSString).pathExtension.lowercased()
        var hints: [String] = []
        if !ext.isEmpty, let type = UTType(filenameExtension: ext) { hints.append(type.identifier) }
        if !hints.contains("com.adobe.raw-image") { hints.append("com.adobe.raw-image") }
        for hint in hints {
            if let f = usable(CIRAWFilter(imageData: data, identifierHint: hint)) { return f }
        }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("raw-input", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(ext.isEmpty ? "input.dng" : "input.\(ext)")
        if (try? data.write(to: url, options: .atomic)) != nil, let f = usable(CIRAWFilter(imageURL: url)) { return f }
        return usable(CIRAWFilter(imageData: data, identifierHint: nil))
    }

    init?(data: Data, name: String) {
        guard let filter = Self.makeFilter(data: data, name: name) else { return nil }
        self.filter = filter
        nativeSize = filter.nativeSize
        lensCorrectionSupported = filter.isLensCorrectionSupported
        var d: [RawKey: Float] = [:]
        d[.temperature] = filter.neutralTemperature
        d[.tint] = filter.neutralTint
        d[.sharpness] = filter.sharpnessAmount
        d[.detail] = filter.detailAmount
        d[.luminanceNoise] = filter.luminanceNoiseReductionAmount
        d[.colorNoise] = filter.colorNoiseReductionAmount
        d[.moire] = filter.moireReductionAmount
        d[.rawContrast] = filter.contrastAmount
        d[.localTone] = filter.localToneMapAmount
        d[.boost] = filter.boostAmount
        d[.boostShadow] = filter.boostShadowAmount
        defaults = d
    }

    func supports(_ key: RawKey) -> Bool {
        switch key {
        case .sharpness: return filter.isSharpnessSupported
        case .detail: return filter.isDetailSupported
        case .luminanceNoise: return filter.isLuminanceNoiseReductionSupported
        case .colorNoise: return filter.isColorNoiseReductionSupported
        case .moire: return filter.isMoireReductionSupported
        case .rawContrast: return filter.isContrastSupported
        case .localTone: return filter.isLocalToneMapSupported
        default: return true
        }
    }

    func defaultValue(_ key: RawKey) -> Float { defaults[key] ?? key.neutral }

    /// 白平衡吸管：把画面上的一个点当作中性灰，返回对应的色温和色调。point 是归一化坐标，原点在左上角。
    func whiteBalance(at point: CGPoint) -> (temperature: Float, tint: Float)? {
        let oldTemperature = filter.neutralTemperature
        let oldTint = filter.neutralTint
        filter.scaleFactor = 1
        guard let extent = filter.outputImage?.extent, extent.width > 0 else { return nil }
        filter.neutralLocation = CGPoint(x: extent.minX + point.x * extent.width,
                                         y: extent.minY + (1 - point.y) * extent.height)
        let result = (filter.neutralTemperature, filter.neutralTint)
        filter.neutralTemperature = oldTemperature
        filter.neutralTint = oldTint
        guard result.0.isFinite, result.1.isFinite, result.0 > 0 else { return nil }
        return result
    }

    private static func tintOffset(hue: Float, saturation: Float) -> CIVector {
        guard saturation > 0 else { return CIVector(x: 0, y: 0, z: 0) }
        let color = UIColor(hue: CGFloat(hue / 360).truncatingRemainder(dividingBy: 1), saturation: 1, brightness: 1, alpha: 1)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        let luma = 0.299 * r + 0.587 * g + 0.114 * b
        let k = CGFloat(saturation) * 0.3
        return CIVector(x: (r - luma) * k, y: (g - luma) * k, z: (b - luma) * k)
    }

    /// 按给定的滑块值冲洗。maxDimension 不为空时先缩小再处理（实时预览用），为空就是原始分辨率。
    func image(_ values: [RawKey: Float], lensCorrection: Bool, maxDimension: CGFloat?) -> CIImage? {
        func v(_ key: RawKey) -> Float { values[key] ?? defaultValue(key) }
        func num(_ value: Float) -> NSNumber { NSNumber(value: value) }
        let longest = max(nativeSize.width, nativeSize.height)
        filter.scaleFactor = maxDimension.map { Float(min(1, $0 / longest)) } ?? 1
        filter.exposure = v(.exposure)
        // 高光、阴影、白色色阶、黑色色阶：接在 RAW 的线性空间里，在套相机曲线和截断高光之前做。
        let linear = RawLinearFilter()
        linear.highlights = v(.highlights)
        linear.shadows = v(.shadows)
        linear.whites = v(.whites)
        linear.blacks = v(.blacks)
        filter.linearSpaceFilter = linear.isIdentity ? nil : linear
        filter.neutralTemperature = v(.temperature)
        filter.neutralTint = v(.tint)
        filter.boostAmount = v(.boost)
        filter.boostShadowAmount = v(.boostShadow)
        if filter.isSharpnessSupported { filter.sharpnessAmount = v(.sharpness) }
        if filter.isDetailSupported { filter.detailAmount = v(.detail) }
        if filter.isLuminanceNoiseReductionSupported { filter.luminanceNoiseReductionAmount = v(.luminanceNoise) }
        if filter.isColorNoiseReductionSupported { filter.colorNoiseReductionAmount = v(.colorNoise) }
        if filter.isMoireReductionSupported { filter.moireReductionAmount = v(.moire) }
        if filter.isContrastSupported { filter.contrastAmount = v(.rawContrast) }
        if filter.isLocalToneMapSupported { filter.localToneMapAmount = v(.localTone) }
        if filter.isLensCorrectionSupported { filter.isLensCorrectionEnabled = lensCorrection }
        guard var out = filter.outputImage else { return nil }
        let extent = out.extent
        let dim = max(extent.width, extent.height)

        // 对比度 + 曲线
        let curve = CIVector(x: CGFloat(v(.curveHighlights)), y: CGFloat(v(.curveLights)),
                             z: CGFloat(v(.curveDarks)), w: CGFloat(v(.curveShadows)))
        let toneActive = ([RawKey.contrast] + RawKey.curveKeys).contains { v($0) != 0 }
        if toneActive, let toned = Self.toneKernel?.apply(extent: extent, arguments: [out, num(v(.contrast)), curve]) {
            out = toned
        }

        // 去朦胧
        let dehaze = v(.dehaze)
        if dehaze != 0, let dark = Self.darkKernel?.apply(extent: extent, arguments: [out]) {
            let veil = dark.clampedToExtent()
                .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: dim * 0.045])
                .cropped(to: extent)
            if let cleared = Self.dehazeKernel?.apply(extent: extent, arguments: [out, veil, num(dehaze)]) { out = cleared }
        }

        // 清晰度（大半径的局部反差）和纹理（中等半径的细节反差）
        let clarity = v(.clarity), texture = v(.texture)
        if clarity != 0 || texture != 0 {
            func blurred(_ radius: CGFloat) -> CIImage {
                out.clampedToExtent()
                    .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
                    .cropped(to: extent)
            }
            let big = clarity != 0 ? blurred(dim * 0.018) : out
            let small = texture != 0 ? blurred(dim * 0.003) : out
            if let local = Self.localKernel?.apply(extent: extent, arguments: [out, big, small, num(clarity), num(texture)]) {
                out = local
            }
        }

        if v(.vibrance) != 0 {
            out = out.applyingFilter("CIVibrance", parameters: ["inputAmount": v(.vibrance)])
        }
        if v(.saturation) != 0 {
            out = out.applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1 + v(.saturation)])
        }

        // 混色器
        func mixVector(_ channel: ColorChannel, _ colors: [MixColor]) -> CIVector {
            let n = colors.map { CGFloat(v(.mix($0, channel))) }
            return CIVector(x: n[0], y: n[1], z: n[2], w: n[3])
        }
        let first: [MixColor] = [.red, .orange, .yellow, .green]
        let second: [MixColor] = [.aqua, .blue, .purple, .magenta]
        let mixActive = MixColor.allCases.contains { color in ColorChannel.allCases.contains { v(.mix(color, $0)) != 0 } }
        if mixActive, let mixed = Self.mixerKernel?.apply(extent: extent, arguments: [
            out, mixVector(.hue, first), mixVector(.hue, second), mixVector(.sat, first), mixVector(.sat, second),
            mixVector(.lum, first), mixVector(.lum, second),
        ]) {
            out = mixed
        }

        // 颜色分级
        let gradeActive = GradeZone.allCases.contains { v(.grade($0, .sat)) != 0 || v(.grade($0, .lum)) != 0 }
        if gradeActive {
            let tints = GradeZone.allCases.map { Self.tintOffset(hue: v(.grade($0, .hue)), saturation: v(.grade($0, .sat))) }
            let lum = CIVector(x: CGFloat(v(.grade(.shadows, .lum))), y: CGFloat(v(.grade(.midtones, .lum))),
                               z: CGFloat(v(.grade(.highlights, .lum))), w: CGFloat(v(.grade(.global, .lum))))
            let opts = CIVector(x: CGFloat(v(.gradeBlending)), y: CGFloat(v(.gradeBalance)))
            if let graded = Self.gradingKernel?.apply(extent: extent, arguments: [out, tints[0], tints[1], tints[2], tints[3], lum, opts]) {
                out = graded
            }
        }

        // 暗角
        let vignette = v(.vignette)
        if vignette != 0, let shaded = Self.vignetteKernel?.apply(extent: extent, arguments: [
            out, CIVector(x: extent.midX, y: extent.midY), num(Float(hypot(extent.width, extent.height) / 2)),
            num(vignette), num(v(.vignetteMid)),
        ]) {
            out = shaded
        }

        // 颗粒
        let grain = v(.grain)
        if grain > 0, let noise = CIFilter(name: "CIRandomGenerator")?.outputImage {
            let scale = max(1, dim / 1100) * CGFloat(0.6 + v(.grainSize) * 2.4)
            let mono = noise
                .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                .applyingFilter("CIColorMatrix", parameters: [
                    "inputRVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                    "inputGVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                    "inputBVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                    "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                    "inputBiasVector": CIVector(x: 0, y: 0, z: 0, w: CGFloat(min(grain, 1)) * 0.45),
                ])
                .cropped(to: extent)
            out = mono.applyingFilter("CISoftLightBlendMode", parameters: [kCIInputBackgroundImageKey: out])
        }
        return out.cropped(to: extent)
    }

    /// 直方图（红绿蓝三条曲线叠在一起）。
    static func histogram(of image: CGImage) -> UIImage? {
        let ci = CIImage(cgImage: image)
        let data = ci.applyingFilter("CIAreaHistogram", parameters: [
            kCIInputExtentKey: CIVector(cgRect: ci.extent),
            "inputCount": 256,
            "inputScale": 40.0,
        ])
        let picture = data.applyingFilter("CIHistogramDisplayFilter", parameters: [
            "inputHeight": 100.0, "inputHighLimit": 1.0, "inputLowLimit": 0.0,
        ])
        guard let cg = Renderer.shared.deepContext.createCGImage(picture, from: picture.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// RAW 冲洗页面的状态。
@MainActor
@Observable
final class RawModel {
    let name: String
    var preview: UIImage?
    var histogram: UIImage?
    var values: [RawKey: Float] = [:]
    var lensCorrection = true
    var group: RawGroup = .light
    var key: RawKey = .exposure
    var mixColor: MixColor = .red
    var gradeZone: GradeZone = .shadows
    /// 白平衡吸管是否打开：打开时点一下画面里本该是灰色或白色的地方。
    var pickingWhiteBalance = false
    var comparing = false
    var busy = false
    var errorText: String?

    @ObservationIgnored private var developer: RawDeveloper?
    @ObservationIgnored private var original: UIImage?
    @ObservationIgnored private let live = LiveRenderer()

    var shown: UIImage? { comparing ? (original ?? preview) : preview }
    var sizeText: String {
        guard let d = developer else { return "" }
        let mp = d.nativeSize.width * d.nativeSize.height / 1_000_000
        return "\(Int(d.nativeSize.width))×\(Int(d.nativeSize.height)) · \(String(format: "%.0f", mp)) MP"
    }
    var lensCorrectionSupported: Bool { developer?.lensCorrectionSupported ?? false }
    var isModified: Bool { !values.isEmpty || !lensCorrection }

    init(name: String) { self.name = name }

    func load(data: Data) {
        guard let d = RawDeveloper(data: data, name: name) else {
            errorText = "这个文件系统解不开：可能不是 RAW，或者这款相机 / 这种压缩方式的 RAW 系统还不支持。\n可以先用 Adobe DNG Converter 转成标准 DNG 再试。"
            return
        }
        developer = d
        render()
    }

    /// 当前一级菜单下要显示的滑块。
    func keys(in group: RawGroup) -> [RawKey] {
        switch group {
        case .light: return RawKey.lightKeys
        case .curve: return RawKey.curveKeys
        case .color: return RawKey.colorKeys
        case .mixer: return ColorChannel.allCases.map { RawKey.mix(mixColor, $0) }
        case .grading:
            let zone = ColorChannel.allCases.map { RawKey.grade(gradeZone, $0) }
            return gradeZone == .global ? zone + [.gradeBlending, .gradeBalance] : zone
        case .effects: return RawKey.effectKeys
        case .detail: return RawKey.detailKeys.filter { developer?.supports($0) ?? true }
        case .optics: return []
        }
    }

    func select(group: RawGroup) {
        self.group = group
        pickingWhiteBalance = false
        if let first = keys(in: group).first { key = first }
    }

    func select(mixColor color: MixColor) {
        let channel = ColorChannel.allCases.first { RawKey.mix(mixColor, $0) == key } ?? .hue
        mixColor = color
        key = .mix(color, channel)
    }

    func select(gradeZone zone: GradeZone) {
        let channel = ColorChannel.allCases.first { RawKey.grade(gradeZone, $0) == key } ?? .hue
        gradeZone = zone
        key = .grade(zone, channel)
    }

    func isChanged(mixColor color: MixColor) -> Bool { ColorChannel.allCases.contains { isChanged(.mix(color, $0)) } }
    func isChanged(gradeZone zone: GradeZone) -> Bool { ColorChannel.allCases.contains { isChanged(.grade(zone, $0)) } }
    func isChanged(group: RawGroup) -> Bool {
        switch group {
        case .mixer: return MixColor.allCases.contains { isChanged(mixColor: $0) }
        case .grading: return GradeZone.allCases.contains { isChanged(gradeZone: $0) } || isChanged(.gradeBlending) || isChanged(.gradeBalance)
        case .optics: return !lensCorrection
        default: return keys(in: group).contains { isChanged($0) }
        }
    }

    func value(_ key: RawKey) -> Float { values[key] ?? defaultValue(key) }
    func defaultValue(_ key: RawKey) -> Float { developer?.defaultValue(key) ?? key.neutral }
    func isChanged(_ key: RawKey) -> Bool { values[key] != nil }

    func set(_ key: RawKey, _ value: Float) {
        let base = defaultValue(key)
        let span = key.range.upperBound - key.range.lowerBound
        // 离默认值很近时吸附回默认值。
        values[key] = abs(value - base) < span * 0.008 ? nil : value
        render()
    }

    func reset(_ key: RawKey) {
        values[key] = nil
        render()
    }

    func resetAll() {
        values = [:]
        lensCorrection = true
        render()
    }

    func setLensCorrection(_ on: Bool) {
        lensCorrection = on
        render()
    }

    func applyWhiteBalance(_ preset: WhiteBalancePreset?) {
        if let preset {
            values[.temperature] = preset.temperature
            values[.tint] = preset.tint
        } else {
            values[.temperature] = nil
            values[.tint] = nil
        }
        render()
    }

    /// 吸管：把点到的地方当作中性灰来定白平衡。
    func pickWhiteBalance(at point: CGPoint) {
        pickingWhiteBalance = false
        guard let developer, let result = developer.whiteBalance(at: point) else { return }
        let t = RawKey.temperature.range, n = RawKey.tint.range
        values[.temperature] = min(max(result.temperature, t.lowerBound), t.upperBound)
        values[.tint] = min(max(result.tint, n.lowerBound), n.upperBound)
        render()
    }

    private func render() {
        guard let developer, let image = developer.image(values, lensCorrection: lensCorrection, maxDimension: 1800) else { return }
        let untouched = values.isEmpty && lensCorrection
        live.submit(.plain(image)) { [weak self] ui in
            guard let self else { return }
            self.preview = ui
            if untouched || self.original == nil { self.original = self.original ?? ui }
            if let cg = ui.cgImage {
                Task.detached(priority: .utility) {
                    let h = RawDeveloper.histogram(of: cg)
                    await MainActor.run { self.histogram = h }
                }
            }
        }
    }

    /// 按原始分辨率冲洗，写成一个 10 位的高质量图片文件，交给图片编辑或存进相册。
    func develop() async -> URL? {
        guard let developer, let image = developer.image(values, lensCorrection: lensCorrection, maxDimension: nil) else { return nil }
        busy = true
        defer { busy = false }
        let base = (name as NSString).deletingPathExtension
        let folder = FileManager.default.temporaryDirectory
        let url = await Task.detached(priority: .userInitiated) { () -> URL? in
            let context = Renderer.shared.deepContext
            let space = Renderer.displaySpace
            if let data = try? context.heif10Representation(of: image, colorSpace: space, options: [:]) {
                let url = folder.appendingPathComponent("\(base)-RAW.heic")
                if (try? data.write(to: url, options: .atomic)) != nil { return url }
            }
            let quality = CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String)
            if let data = context.jpegRepresentation(of: image, colorSpace: space, options: [quality: 0.98]) {
                let url = folder.appendingPathComponent("\(base)-RAW.jpg")
                if (try? data.write(to: url, options: .atomic)) != nil { return url }
            }
            return nil
        }.value
        // 预览回到小尺寸。
        render()
        return url
    }
}
