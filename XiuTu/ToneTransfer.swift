import CoreImage

/// 只采用 AI 结果的光影和颜色，细节全部用原图。
/// 做法：比较"AI 图"和"原图"各自模糊之后的样子，得到每个位置该提亮/压暗/偏色多少，再把这个增益乘回原图。
/// 五官的形状、皮肤纹理、发丝这些细节来自原图，所以脸不会变，分辨率也是原图的。
enum ToneTransfer {
    private static let kernel = CIColorKernel(source: """
    kernel vec4 toneGain(__sample o, __sample bo, __sample br) {
        vec3 a = max(br.rgb, vec3(0.0)) + vec3(0.004);
        vec3 b = max(bo.rgb, vec3(0.0)) + vec3(0.004);
        vec3 gain = clamp(a / b, vec3(0.0), vec3(6.0));
        return vec4(o.rgb * gain, o.a);
    }
    """)

    static func apply(original: CIImage, graded: CIImage) -> CIImage {
        let extent = original.extent
        let radius = max(extent.width, extent.height) * 0.012
        func soft(_ image: CIImage) -> CIImage {
            image.clampedToExtent()
                .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
                .cropped(to: extent)
        }
        guard let kernel,
              let out = kernel.apply(extent: extent, arguments: [original, soft(original), soft(graded)]) else {
            return graded
        }
        return out.cropped(to: extent)
    }
}
