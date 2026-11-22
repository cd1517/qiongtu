package com.taoo.xiutu.studio

import android.graphics.Bitmap
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.Shaders
import kotlin.math.max

/**
 * 摄影棚里摄影师、器材格子上的小图：用户自己这张照片在本机套一组近似的色调，只是给个印象。
 * 真正拍出来的是 AI 按指令重新处理的结果，和缩略图不会完全一样。不加颗粒。
 * 颜色部分照 iOS 的 ColorParams.map 写成着色器，再叠柔光和暗角。
 */
object StudioLooks {
    /** 格子的尺寸（dp）。 */
    const val TILE_WIDTH = 64
    const val TILE_HEIGHT = 72

    /** 一组颜色调整参数，数值范围基本是 -1...1（fade 是 0...1）。 */
    class ColorParams {
        var exposure = 0f
        var brightness = 0f
        var contrast = 0f
        var highlights = 0f
        var shadows = 0f
        var saturation = 0f
        var vibrance = 0f
        var temperature = 0f
        var tint = 0f
        var fade = 0f
        var mono = false
        var monoWeights = floatArrayOf(0.299f, 0.587f, 0.114f)
        /** 分离色调：暗部和亮部各自叠加的颜色偏移。 */
        var shadowTint = floatArrayOf(0f, 0f, 0f)
        var highlightTint = floatArrayOf(0f, 0f, 0f)

        fun uniforms(): Map<String, Any> = mapOf(
            "exposure" to exposure, "brightness" to brightness, "contrast" to contrast,
            "highlights" to highlights, "shadows" to shadows, "saturation" to saturation, "vibrance" to vibrance,
            "temperature" to temperature, "tint" to tint, "fade" to fade, "mono" to mono,
            "monoW" to monoWeights, "shadowTint" to shadowTint, "highlightTint" to highlightTint,
        )
    }

    class Look(val color: ColorParams, val vignette: Float, val bloom: Float)

    private fun look(key: String, vignette: Float = 0f, bloom: Float = 0f, configure: ColorParams.() -> Unit): Pair<String, Look> =
        key to Look(ColorParams().apply(configure), vignette, bloom)

    private val photographers = listOf(
    // 牛顿：高反差黑白，硬光，深重的阴影。
    look("p.newton", vignette = 0.2f) {
        mono = true; contrast = 0.85f; shadows = -0.45f; highlights = 0.25f
    },
    // 阿维顿：明亮干净的高调黑白。
    look("p.avedon") {
        mono = true; exposure = 0.28f; contrast = 0.4f; highlights = 0.35f; shadows = 0.15f
    },
    // 林德伯格：柔和细腻的灰阶黑白。
    look("p.lindbergh", vignette = 0.15f) {
        mono = true; monoWeights = floatArrayOf(0.4f, 0.45f, 0.15f); contrast = 0.08f; fade = 0.18f
        highlights = -0.15f; shadows = 0.12f
    },
    // 莱博维茨：浓郁沉稳、绘画感的色彩，有方向的光。
    look("p.leibovitz", vignette = 0.5f) {
        exposure = -0.15f; contrast = 0.45f; saturation = 0.15f; temperature = 0.15f; shadows = -0.3f
        shadowTint = floatArrayOf(-0.02f, 0.015f, 0.03f); highlightTint = floatArrayOf(0.04f, 0.02f, -0.02f)
    },
    // 克莱因：阴郁的暗调，冷的暗部。
    look("p.klein", vignette = 0.7f) {
        exposure = -0.55f; contrast = 0.6f; shadows = -0.6f; saturation = -0.45f; temperature = -0.3f
        shadowTint = floatArrayOf(0.0f, 0.015f, 0.05f)
    },
    // 拉切贝尔：超饱和的糖果色。
    look("p.lachapelle") {
        saturation = 0.9f; vibrance = 0.4f; contrast = 0.4f; exposure = 0.15f; tint = 0.05f; temperature = -0.05f
        highlightTint = floatArrayOf(0.0f, 0.02f, 0.04f)
    },
    // 蒂姆·沃克：梦幻的暖粉彩。
    look("p.walker", bloom = 0.35f) {
        exposure = 0.25f; contrast = -0.3f; saturation = -0.05f; fade = 0.35f; temperature = 0.12f; tint = 0.2f
        highlightTint = floatArrayOf(0.05f, 0.01f, 0.03f)
    },
    // 尼克·奈特：大胆的青品红实验色。
    look("p.knight", vignette = 0.2f) {
        saturation = 0.7f; contrast = 0.55f; temperature = -0.3f; tint = 0.25f
        shadowTint = floatArrayOf(0.05f, -0.03f, 0.09f); highlightTint = floatArrayOf(0.0f, 0.05f, 0.03f)
    },
    // Mert & Marcus：光洁浓烈的高光泽。
    look("p.mertmarcus", vignette = 0.25f) {
        contrast = 0.55f; saturation = 0.45f; highlights = 0.2f; shadows = -0.2f; temperature = 0.18f
        highlightTint = floatArrayOf(0.05f, 0.025f, 0.0f)
    },
    // 特斯蒂诺：明亮温暖的金色阳光。
    look("p.testino", bloom = 0.15f) {
        exposure = 0.2f; temperature = 0.45f; saturation = 0.25f; contrast = 0.2f
        highlightTint = floatArrayOf(0.07f, 0.04f, -0.04f)
    },
    // 德马舍利耶：柔美的淡棕褐黑白。
    look("p.demarchelier", bloom = 0.1f) {
        mono = true; contrast = 0.15f; shadows = 0.1f; exposure = 0.08f
        shadowTint = floatArrayOf(0.02f, 0.01f, -0.01f); highlightTint = floatArrayOf(0.05f, 0.03f, -0.015f)
    },
    // Inez & Vinoodh：干净明亮、纯净的颜色。
    look("p.inezvinoodh") {
        exposure = 0.25f; contrast = 0.3f; saturation = -0.3f; temperature = -0.2f; shadows = 0.3f; highlights = -0.1f
    },
    // 布鲁斯·韦伯：暖棕褐的怀旧黑白。
    look("p.weber", vignette = 0.2f) {
        mono = true; contrast = 0.45f; shadows = -0.1f
        shadowTint = floatArrayOf(0.04f, 0.02f, -0.02f); highlightTint = floatArrayOf(0.09f, 0.05f, -0.04f)
    },
    // 于尔根·泰勒：直闪的生硬快照感。
    look("p.teller", vignette = 0.65f) {
        exposure = 0.35f; contrast = 0.75f; shadows = -0.55f; saturation = 0.2f; temperature = -0.15f
    },
    // 蜷川实花：极高饱和的红粉。
    look("p.ninagawa") {
        saturation = 0.8f; vibrance = 0.3f; contrast = 0.35f; tint = 0.2f; temperature = 0.08f
        shadowTint = floatArrayOf(0.05f, -0.02f, 0.04f); highlightTint = floatArrayOf(0.05f, 0.0f, 0.02f)
    },
    // 奥山由之：低饱和的暖粉彩，怀旧日记感。
    look("p.okuyama", bloom = 0.15f) {
        exposure = 0.2f; contrast = -0.3f; saturation = -0.3f; temperature = 0.35f; tint = 0.12f; fade = 0.45f
        highlightTint = floatArrayOf(0.05f, 0.02f, 0.0f)
    },
    // 滨田英明：通透、低反差、淡青绿。
    look("p.hamada", bloom = 0.15f) {
        exposure = 0.3f; contrast = -0.35f; shadows = 0.4f; saturation = -0.2f; temperature = -0.15f; tint = -0.15f
        fade = 0.3f; shadowTint = floatArrayOf(-0.04f, 0.04f, 0.04f); highlightTint = floatArrayOf(0.0f, 0.02f, 0.01f)
    },
    // 川内伦子：明亮轻微过曝的浅粉彩。
    look("p.kawauchi", bloom = 0.3f) {
        exposure = 0.45f; contrast = -0.35f; shadows = 0.45f; saturation = -0.25f; fade = 0.45f; tint = 0.15f
        shadowTint = floatArrayOf(0.01f, 0.01f, 0.04f); highlightTint = floatArrayOf(0.03f, 0.0f, 0.03f)
    },
    // 森山大道：极高反差黑白，暗部压死、高光烧白。
    look("p.moriyama", vignette = 0.35f) {
        mono = true; monoWeights = floatArrayOf(0.3f, 0.5f, 0.2f); contrast = 1.0f; shadows = -0.6f; highlights = 0.35f
    },
    // 植田正治：淡雅明亮的灰调黑白。
    look("p.uedashoji") {
        mono = true; exposure = 0.2f; contrast = -0.1f; fade = 0.3f; shadows = 0.2f
        shadowTint = floatArrayOf(0.0f, 0.01f, 0.03f)
    },
    // 荒木经惟：偏红偏橙的暖调彩色。
    look("p.araki") {
        temperature = 0.4f; tint = 0.12f; saturation = 0.25f; contrast = 0.25f
        shadowTint = floatArrayOf(0.04f, 0.0f, -0.02f); highlightTint = floatArrayOf(0.06f, 0.015f, -0.03f)
    },
    // 上田义彦：安静低饱和，柔和暗部。
    look("p.uedayoshihiko", vignette = 0.4f) {
        exposure = -0.25f; contrast = 0.15f; saturation = -0.6f; highlights = -0.25f; temperature = 0.06f; fade = 0.18f
    },
    // 王家卫 × 杜可风：青绿暗部、橙红高光。
    look("p.wongdoyle", vignette = 0.4f) {
        exposure = -0.15f; contrast = 0.5f; saturation = 0.4f
        shadowTint = floatArrayOf(-0.06f, 0.06f, 0.05f); highlightTint = floatArrayOf(0.09f, 0.03f, -0.06f)
    },
    // 夏永康：褪色的柔和粉彩。
    look("p.shya", bloom = 0.3f) {
        fade = 0.55f; saturation = -0.3f; contrast = -0.25f; exposure = 0.12f; tint = 0.15f
        highlightTint = floatArrayOf(0.04f, 0.0f, 0.035f)
    },
    // 侯孝贤：朴素低饱和的暖大地色。
    look("p.hou") {
        saturation = -0.45f; temperature = 0.25f; fade = 0.15f; contrast = 0.05f
        shadowTint = floatArrayOf(0.02f, 0.015f, 0.0f); highlightTint = floatArrayOf(0.035f, 0.025f, 0.0f)
    },
    // 杨德昌：冷静的蓝灰。
    look("p.yang") {
        temperature = -0.35f; saturation = -0.4f; contrast = 0.2f
        shadowTint = floatArrayOf(-0.015f, 0.01f, 0.05f); highlightTint = floatArrayOf(0.0f, 0.01f, 0.025f)
    },
    // 蔡明亮：昏暗冷清，偏绿。
    look("p.tsai", vignette = 0.45f) {
        exposure = -0.35f; saturation = -0.4f; contrast = 0.25f; shadows = -0.3f; temperature = -0.08f; tint = -0.25f
        shadowTint = floatArrayOf(-0.02f, 0.035f, 0.015f)
    },
    // 李安：冷暖平衡、细腻的自然色。
    look("p.anglee", vignette = 0.25f) {
        contrast = 0.2f; vibrance = 0.3f; saturation = 0.05f; temperature = 0.05f; highlights = -0.25f; shadows = 0.1f
        shadowTint = floatArrayOf(-0.02f, 0.015f, 0.03f); highlightTint = floatArrayOf(0.03f, 0.015f, 0.0f)
    },
    // 关锦鹏：温暖的琥珀色，浓郁暗部。
    look("p.kwan", vignette = 0.4f, bloom = 0.25f) {
        temperature = 0.4f; exposure = -0.1f; contrast = 0.3f; saturation = -0.05f; shadows = -0.3f
        shadowTint = floatArrayOf(0.02f, 0.005f, -0.01f); highlightTint = floatArrayOf(0.07f, 0.035f, -0.03f)
    },
    )

    // MARK: 器材

        private val equipment = listOf(
    look("e.portra400") {
        contrast = -0.15f; saturation = -0.1f; temperature = 0.2f; fade = 0.15f; shadows = 0.25f
        shadowTint = floatArrayOf(-0.01f, 0.012f, 0.02f); highlightTint = floatArrayOf(0.04f, 0.02f, -0.015f)
    },
    look("e.portra800") {
        contrast = 0.2f; saturation = 0.15f; temperature = 0.28f; shadows = -0.1f
        shadowTint = floatArrayOf(-0.02f, 0.02f, 0.03f); highlightTint = floatArrayOf(0.05f, 0.025f, -0.02f)
    },
    look("e.gold200") {
        temperature = 0.4f; contrast = 0.25f; saturation = 0.2f; fade = 0.1f
        shadowTint = floatArrayOf(0.01f, 0.0f, -0.02f); highlightTint = floatArrayOf(0.06f, 0.035f, -0.05f)
    },
    look("e.ektar100") {
        contrast = 0.45f; saturation = 0.6f; vibrance = 0.15f
        shadowTint = floatArrayOf(-0.015f, 0.0f, 0.03f); highlightTint = floatArrayOf(0.025f, 0.01f, -0.01f)
    },
    look("e.fuji400h") {
        exposure = 0.15f; contrast = -0.2f; saturation = -0.15f; temperature = -0.15f; tint = -0.18f; fade = 0.18f
        shadowTint = floatArrayOf(-0.03f, 0.035f, 0.03f)
    },
    look("e.velvia50") {
        contrast = 0.4f; saturation = 0.6f; vibrance = 0.2f; shadows = -0.1f
        shadowTint = floatArrayOf(0.0f, 0.0f, 0.02f)
    },
    look("e.cinestill800t", bloom = 0.5f) {
        temperature = -0.45f; contrast = 0.3f; saturation = 0.15f
        shadowTint = floatArrayOf(-0.03f, 0.03f, 0.07f); highlightTint = floatArrayOf(0.09f, 0.02f, -0.03f)
    },
    look("e.cinestill50d", bloom = 0.15f) {
        temperature = 0.15f; contrast = 0.15f; saturation = 0.1f; highlights = -0.2f
        shadowTint = floatArrayOf(-0.01f, 0.01f, 0.02f); highlightTint = floatArrayOf(0.03f, 0.02f, 0.0f)
    },
    look("e.ilfordhp5") {
        mono = true; contrast = 0.3f; shadows = 0.05f
    },
    look("e.ilforddelta3200", vignette = 0.25f) {
        mono = true; contrast = 0.75f; shadows = -0.4f; highlights = 0.15f
    },
    look("e.trix400", vignette = 0.15f) {
        mono = true; monoWeights = floatArrayOf(0.35f, 0.5f, 0.15f); contrast = 0.45f; shadows = -0.1f
    },
    look("e.canon5d") {
        exposure = 0.12f; temperature = 0.2f; tint = 0.06f; saturation = 0.15f; shadows = 0.15f
    },
    look("e.sonya7") {
        contrast = 0.25f; vibrance = 0.25f; temperature = -0.18f; highlights = -0.15f
    },
    look("e.fujifilmx") {
        saturation = -0.5f; contrast = 0.3f; temperature = -0.08f
        shadowTint = floatArrayOf(-0.02f, 0.02f, 0.03f); highlightTint = floatArrayOf(0.03f, 0.02f, -0.01f)
    },
    look("e.leicam", vignette = 0.4f) {
        contrast = 0.5f; saturation = -0.15f; shadows = -0.3f; highlights = -0.12f; temperature = 0.12f
    },
    look("e.hasselblad") {
        contrast = 0.1f; saturation = 0.1f; vibrance = 0.1f; highlights = -0.35f; shadows = 0.25f; temperature = 0.06f
    },
    look("e.phaseone") {
        contrast = 0.3f; vibrance = 0.3f; highlights = -0.25f; shadows = 0.15f; temperature = -0.08f
    },
    look("e.polaroidsx70", vignette = 0.4f) {
        fade = 0.45f; contrast = -0.15f; saturation = -0.3f; temperature = 0.25f
        shadowTint = floatArrayOf(0.0f, 0.02f, 0.04f); highlightTint = floatArrayOf(0.05f, 0.03f, 0.0f)
    },
    look("e.polaroid600", vignette = 0.35f) {
        saturation = 0.35f; contrast = 0.25f; temperature = 0.1f; fade = 0.15f
        shadowTint = floatArrayOf(-0.02f, 0.03f, 0.05f); highlightTint = floatArrayOf(0.05f, 0.015f, -0.015f)
    },
    look("e.instaxmini") {
        exposure = 0.35f; contrast = -0.15f; saturation = -0.15f; fade = 0.3f; temperature = -0.12f
    },
    look("e.instaxwide") {
        exposure = 0.12f; contrast = -0.1f; saturation = -0.4f; fade = 0.25f; temperature = 0.1f
    },
    look("e.disposable", vignette = 0.45f) {
        exposure = 0.2f; contrast = 0.5f; saturation = -0.15f; temperature = -0.18f; shadows = -0.4f
    },
    look("e.lomo", vignette = 0.7f) {
        contrast = 0.5f; saturation = 0.45f; tint = -0.05f
        shadowTint = floatArrayOf(0.0f, 0.01f, 0.05f); highlightTint = floatArrayOf(0.04f, 0.03f, -0.03f)
    },
    look("e.iphone") {
        contrast = 0.2f; vibrance = 0.4f; saturation = 0.15f; shadows = 0.35f; highlights = -0.35f
    },
    look("e.vhs", bloom = 0.3f) {
        contrast = -0.2f; saturation = 0.25f; fade = 0.3f; tint = 0.15f
        shadowTint = floatArrayOf(-0.02f, 0.03f, 0.06f); highlightTint = floatArrayOf(0.05f, -0.02f, 0.05f)
    },
    )

    /** key：摄影师是 "p.<id>"，器材是 "e.<id>"，和目录里的 id 一致。 */
    val all: Map<String, Look> = (photographers + equipment).toMap()

    private const val COLOR = Shaders.HEADER + """
uniform sampler2D src;
uniform float exposure, brightness, contrast, highlights, shadows, saturation, vibrance, temperature, tint, fade;
uniform bool mono;
uniform vec3 monoW, shadowTint, highlightTint;
float lm(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec4 s = texture(src, uv);
    vec3 c = s.rgb;
    c *= pow(2.0, exposure * 0.9);
    c *= vec3(1.0 + 0.16 * temperature + 0.04 * tint, 1.0 - 0.10 * tint, 1.0 - 0.16 * temperature + 0.04 * tint);
    c = clamp(c, 0.0, 1.0);
    if (mono) c = vec3(clamp(dot(c, monoW), 0.0, 1.0));
    if (brightness != 0.0) c = pow(c, vec3(pow(2.0, -brightness * 0.8)));
    if (contrast > 0.0) {
        vec3 lo = c * c * 2.0;
        vec3 inv = 1.0 - c;
        vec3 hi = 1.0 - inv * inv * 2.0;
        vec3 sc = mix(lo, hi, step(0.5, c));
        c = c + (sc - c) * (contrast * 0.75);
    } else if (contrast < 0.0) {
        c = 0.5 + (c - 0.5) * (1.0 + contrast * 0.6);
    }
    if (highlights != 0.0 || shadows != 0.0) {
        float l = max(lm(c), 0.0001);
        float il = 1.0 - l;
        float ws = 9.48 * l * il * il * il;
        float wh = 9.48 * il * l * l * l;
        float l2 = l + 0.1 * shadows * ws + 0.1 * highlights * wh;
        c *= l2 / l;
    }
    if (!mono) {
        if (saturation != 0.0) {
            float l = lm(c);
            c = l + (c - l) * (1.0 + saturation);
        }
        if (vibrance != 0.0) {
            float spread = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
            float amount = vibrance * (1.0 - clamp(spread, 0.0, 1.0));
            float l = lm(c);
            c = l + (c - l) * (1.0 + amount);
        }
    }
    float l = clamp(lm(c), 0.0, 1.0);
    float il = 1.0 - l;
    c += shadowTint * (il * il) + highlightTint * (l * l);
    c = c * (1.0 - 0.22 * fade) + vec3(0.13 * fade);
    o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /** 柔光（和模糊后的自己做滤色混合）+ 暗角（从半径 0.45 到 1.05 倍半对角线渐黑）。 */
    private const val FINISH = Shaders.HEADER + """
uniform sampler2D src;
uniform sampler2D glow;
uniform float bloom;
uniform float vignette;
void main() {
    vec3 c = texture(src, uv).rgb;
    if (bloom > 0.0) {
        vec3 g = texture(glow, uv).rgb * min(bloom, 1.0);
        c = 1.0 - (1.0 - c) * (1.0 - g);
    }
    if (vignette > 0.0) {
        vec2 p = uv * size;
        float d = length(size) * 0.5;
        float t = clamp((length(p - size * 0.5) - d * 0.45) / (d * 0.6), 0.0, 1.0);
        c *= 1.0 - t * min(vignette, 1.0) * 0.85;
    }
    o = vec4(c, 1.0);
}
"""

    /** 把照片居中裁成格子的比例（长边 240），按每个 key 套一遍。"original" 是原图。后台线程调用。 */
    fun render(photo: Bitmap): Map<String, Bitmap> {
        val base = Img.thumb(photo, 240, TILE_WIDTH.toFloat() / TILE_HEIGHT)
        val out = HashMap<String, Bitmap>()
        out["original"] = base
        runCatching {
            Gpu.run {
                val src = upload(base)
                for ((key, look) in all) {
                    val colored = pass(COLOR, mapOf("src" to src), look.color.uniforms(), src.width, src.height)
                    val needsFinish = look.bloom > 0f || look.vignette > 0f
                    val final = if (!needsFinish) colored else {
                        val glow = if (look.bloom > 0f) blur(colored, max(src.width, src.height) * 0.012f) else colored
                        pass(FINISH, mapOf("src" to colored, "glow" to glow), mapOf("bloom" to look.bloom, "vignette" to look.vignette), src.width, src.height)
                    }
                    out[key] = read(final)
                }
            }
        }
        return out
    }
}
