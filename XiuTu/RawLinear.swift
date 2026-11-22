import CoreImage

/// 接在系统 RAW 引擎"线性空间"里的色调处理：这时数据还是传感器的线性光，
/// 没有套相机曲线，也没有按显示白点截断（高光通常还留着一两档余量）。
/// 高光、阴影、白色色阶、黑色色阶都在这里按"档"做增益，所以拉回来的是 RAW 里真实记录的信息，
/// 而不是在已经压成成品的图上拉曲线。
/// 高光和阴影按"这一带整体有多亮"来决定增益（基底层），同一带里像素之间的反差原样保留，
/// 所以压高光不会发灰、提阴影不会发雾。
final class RawLinearFilter: CIFilter {
    @objc dynamic var inputImage: CIImage?
    var highlights: Float = 0
    var shadows: Float = 0
    var whites: Float = 0
    var blacks: Float = 0
    /// 基底层的做法：0 = 只用高斯模糊（强边缘两侧会有光晕），1 = 引导滤波（贴合边缘）。只在对比测试时改。
    static var baseMode = 1
    /// 多强的边缘才算"边缘"（按 14 档归一后的方差），以及基底层跟随边缘的程度。
    /// 完全跟随（1）没有光晕，但同一个面上的光影也会被压平；完全不跟随（0）反差保留得最好，但强边缘两侧有光晕。
    static var edgeEpsilon: Float = 0.002
    static var edgeFollow: Float = 0.6

    var isIdentity: Bool { highlights == 0 && shadows == 0 && whites == 0 && blacks == 0 }

    /// 系统如果复制这个滤镜，参数要跟着走。
    override func copy(with zone: NSZone? = nil) -> Any {
        let other = RawLinearFilter()
        other.inputImage = inputImage
        other.highlights = highlights
        other.shadows = shadows
        other.whites = whites
        other.blacks = blacks
        return other
    }

    /// 亮度换算成"比中灰亮几档"，压到 0...1 存进图里。
    private static let logKernel = CIColorKernel(source: """
    kernel vec4 rawLogLuma(__sample s) {
        float l = dot(max(s.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
        float stops = clamp(log2(max(l, 0.00002) / 0.18), -9.0, 5.0);
        float v = (stops + 9.0) / 14.0;
        return vec4(v, v, v, 1.0);
    }
    """)

    /// 引导滤波第一步：把"亮度档数 I"和它的平方放进两个通道，后面一起模糊就得到局部均值和局部方差。
    private static let momentsKernel = CIColorKernel(source: """
    kernel vec4 rawMoments(__sample s) {
        return vec4(s.r, s.r * s.r, 0.0, 1.0);
    }
    """)

    /// 引导滤波第二步：平坦的地方（方差小）a 接近 0，基底层取周围的平均；
    /// 强边缘（方差大）a 接近 1，基底层跟着像素自己走。这样增益的变化正好落在边缘上，不会在边缘两侧晕开。
    private static let coefficientsKernel = CIColorKernel(source: """
    kernel vec4 rawGuideCoefficients(__sample m, float eps, float follow) {
        float variance = max(m.g - m.r * m.r, 0.0);
        float a = follow * variance / (variance + eps);
        return vec4(a, (1.0 - a) * m.r, 0.0, 1.0);
    }
    """)

    /// p = 高光、阴影、白色色阶、黑色色阶（都是 -1...1）。ab 是引导滤波的系数：基底层 = a × 像素档数 + b。
    private static let toneKernel = CIColorKernel(source: """
    kernel vec4 rawLinearTone(__sample s, __sample ab, vec4 p) {
        vec3 c = s.rgb;
        float l = dot(max(c, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
        float pixel = clamp(log2(max(l, 0.00002) / 0.18), -9.0, 5.0);
        float area = (ab.r * ((pixel + 9.0) / 14.0) + ab.g) * 14.0 - 9.0;
        float e = mix(area, pixel, 0.15);
        float wh = smoothstep(-0.3, 2.3, e);
        float ws = 1.0 - smoothstep(-4.5, -0.3, e);
        float stops = p.x * (p.x < 0.0 ? 2.2 : 1.2) * wh + p.y * (p.y > 0.0 ? 2.6 : 1.6) * ws;
        stops += p.z * smoothstep(-1.0, 2.0, pixel);
        stops += p.w * 1.5 * (1.0 - smoothstep(-6.0, -2.2, pixel));
        c = c * pow(2.0, stops);
        float cut = max(-p.w, 0.0) * 0.006;
        c = (c - vec3(cut)) / (1.0 - cut);
        return vec4(c, s.a);
    }
    """)

    override var outputImage: CIImage? {
        guard let input = inputImage else { return nil }
        let extent = input.extent
        guard !isIdentity, !extent.isInfinite, !extent.isEmpty,
              let logImage = Self.logKernel?.apply(extent: extent, arguments: [input]) else { return input }
        let dim = max(extent.width, extent.height)
        // 基底层在 1/4 尺寸上算（它本来就是一张很糊的图），再放大回来，省四分之三以上的计算。
        let k: CGFloat = 0.25
        let smallExtent = CGRect(x: extent.minX * k, y: extent.minY * k, width: extent.width * k, height: extent.height * k).integral
        let radius = dim * k * 0.022
        func blur(_ image: CIImage) -> CIImage {
            image.clampedToExtent()
                .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
                .cropped(to: smallExtent)
        }
        func enlarge(_ image: CIImage) -> CIImage {
            image.clampedToExtent()
                .transformed(by: CGAffineTransform(scaleX: 1 / k, y: 1 / k))
                .cropped(to: extent)
        }
        let small = logImage.clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: k, y: k))
            .cropped(to: smallExtent)
        let ab: CIImage
        if Self.baseMode == 1,
           let moments = Self.momentsKernel?.apply(extent: smallExtent, arguments: [small]),
           let coefficients = Self.coefficientsKernel?.apply(extent: smallExtent, arguments: [blur(moments), Self.edgeEpsilon, Self.edgeFollow]) {
            ab = enlarge(blur(coefficients))
        } else {
            // a = 0、b = 模糊后的亮度：退回成普通的高斯基底层。
            let mean = blur(small).applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 1, y: 0, z: 0, w: 0),
            ])
            ab = enlarge(mean)
        }
        let p = CIVector(x: CGFloat(highlights), y: CGFloat(shadows), z: CGFloat(whites), w: CGFloat(blacks))
        return Self.toneKernel?.apply(extent: extent, arguments: [input, ab, p]) ?? input
    }
}
