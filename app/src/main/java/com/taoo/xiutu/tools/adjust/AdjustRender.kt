package com.taoo.xiutu.tools.adjust

import android.graphics.Bitmap
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import com.taoo.xiutu.tools.color.ColorGL
import com.taoo.xiutu.tools.color.ColorGL.average
import com.taoo.xiutu.tools.color.ColorGL.gauss
import com.taoo.xiutu.tools.color.ColorParams
import com.taoo.xiutu.tools.color.CurveLut
import com.taoo.xiutu.tools.color.TileInfo
import com.taoo.xiutu.tools.color.Tiler
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 调色的 GPU 实现（iOS AdjustParams.apply + AdjustFX）。顺序和 iOS 一致：
 * 降噪 → 按区域提亮压暗 → 逐像素光线 → 颜色 → 曲线 → HSL → 肤色 → 色调分离（一个 pass）
 * → 去灰 → 清晰度、锐化、局部调色 → 色散、柔光、暗角、颗粒（、局部调色标记）。
 *
 * 大半径的平滑图（引导滤波系数、去灰的雾、清晰度的大模糊、柔光）都在长边 800 的缩小版上算，
 * 按整图比例取样，所以预览和原图导出效果一致；大图自动分块。
 */
object AdjustRender {
    /** markers 不为空时在结果上画局部调色的点（只给屏幕预览用）。 */
    fun apply(src: Bitmap, p: AdjustParams, markers: List<LocalAdjust>? = null, selected: Long? = null): Bitmap {
        if (p.isIdentity && markers.isNullOrEmpty()) return src
        val dim = max(src.width, src.height)
        val plan = Plan(p, markers, selected)
        val small = if (plan.needCtx) ColorGL.small(src) else null
        val margin = if (plan.denoise > 0 || plan.sharpen != 0f || plan.fringe > 0) ceil(dim * 0.016f).toInt() + 8 else 0
        return Tiler.render(src, margin, prepare = { plan.prepare(this, small) }) { t, info, ctx -> plan.tile(this, t, info, ctx) }
    }

    /** 各块共用的东西。 */
    private class Ctx(val ab: Tex?, val wide: Tex?, val avg: Tex?, val veil: Tex?, val big: Tex?, val glow: Tex?, val curve: Tex?)

    private class Plan(val p: AdjustParams, markers: List<LocalAdjust>?, selected: Long?) {
        val denoise = p[AdjustKey.DENOISE]
        val dehaze = p[AdjustKey.DEHAZE]
        val clarity = p[AdjustKey.CLARITY]
        val sharpen = p[AdjustKey.SHARPEN]
        val fringe = p[AdjustKey.FRINGE]
        val even = p[AdjustKey.EVEN_LIGHT]
        val localTone = AdjustKey.localToneKeys.any { p[it] != 0f }
        val locals = p.locals.filter { !it.isNeutral }.take(8)
        val glowAmount = min(p[AdjustKey.BLOOM] + 0.18f * p[AdjustKey.NATURAL_LIGHT] + 0.2f * abs(p[AdjustKey.MOOD_LIGHT]), 1f)
        val needD = clarity != 0f || sharpen != 0f || locals.isNotEmpty()
        val needCtx = localTone || dehaze > 0 || clarity != 0f || glowAmount > 0
        val marks = markers.orEmpty().take(8)
        val selectedIndex = marks.indexOfFirst { it.id == selected }

        val bUniforms: Map<String, Any> = run {
            val m = HashMap<String, Any>()
            val c: ColorParams = p.color
            m += c.uniforms("cp")
            m["colorOn"] = if (c.isIdentity) 0 else 1
            m["ltOn"] = if (localTone) 1 else 0
            m["lt"] = floatArrayOf(p[AdjustKey.FILL_LIGHT], p[AdjustKey.BACKLIGHT], p[AdjustKey.AMBIANCE], p[AdjustKey.NATURAL_LIGHT])
            m["even"] = even
            val lp = floatArrayOf(p[AdjustKey.LIGHT_SENSE], p[AdjustKey.LOW_LIGHT], p[AdjustKey.NATURAL_LIGHT], p[AdjustKey.MOOD_LIGHT])
            m["lightOn"] = if (lp.any { it != 0f }) 1 else 0
            m["lp"] = lp
            m["curveOn"] = if (p.curves.isIdentity) 0 else 1
            m["hslOn"] = if (p.hslChanged) 1 else 0
            fun vec(ch: Int, from: Int) = FloatArray(4) { i -> p.hsl[from + i].let { v -> if (ch == 0) v.x else if (ch == 1) v.y else v.z } }
            m["h1"] = vec(0, 0); m["h2"] = vec(0, 4)
            m["s1"] = vec(1, 0); m["s2"] = vec(1, 4)
            m["l1"] = vec(2, 0); m["l2"] = vec(2, 4)
            val tone = p[AdjustKey.SKIN_TONE]
            val white = p[AdjustKey.SKIN_WHITEN]
            m["skinOn"] = if (tone != 0f || white != 0f) 1 else 0
            m["skTone"] = tone; m["skWhite"] = white
            m["splitOn"] = if (p.split.isIdentity) 0 else 1
            m["spSh"] = tintOffset(p.split.shadowHue, p.split.shadowAmount)
            m["spHi"] = tintOffset(p.split.highlightHue, p.split.highlightAmount)
            m["spBal"] = p.split.balance
            m
        }

        val dUniforms: Map<String, Any> = mapOf(
            "clarity" to clarity,
            "locN" to locals.size,
            "locX" to List(8) { locals.getOrNull(it)?.cx ?: 0f },
            "locY" to List(8) { locals.getOrNull(it)?.cy ?: 0f },
            "locR" to List(8) { locals.getOrNull(it)?.radius ?: 0f },
            "locB" to List(8) { locals.getOrNull(it)?.brightness ?: 0f },
            "locC" to List(8) { locals.getOrNull(it)?.contrast ?: 0f },
            "locS" to List(8) { locals.getOrNull(it)?.saturation ?: 0f },
            "locT" to List(8) { locals.getOrNull(it)?.temperature ?: 0f },
        )

        val eUniforms: Map<String, Any> = mapOf(
            "fringeK" to fringe * 0.012f,
            "vig" to p[AdjustKey.VIGNETTE],
            "grainAmt" to p[AdjustKey.GRAIN],
            "mkN" to marks.size,
            "mkX" to List(8) { marks.getOrNull(it)?.cx ?: 0f },
            "mkY" to List(8) { marks.getOrNull(it)?.cy ?: 0f },
            "mkR" to List(8) { marks.getOrNull(it)?.radius ?: 0f },
            "mkSel" to selectedIndex,
        )

        fun prepare(s: GpuScope, small: Bitmap?): Ctx { with(s) {
            val curve = if (p.curves.isIdentity) null else upload(CurveLut.bitmap(p.curves.pointFs()))
            if (small == null) return Ctx(null, null, null, null, null, null, curve)
            val t = upload(small)
            val info = TileInfo.whole(t)
            val dimS = info.dim
            var ab: Tex? = null
            var wide: Tex? = null
            var avg: Tex? = null
            if (localTone) {
                // 引导滤波：反差大的地方跟着原图走，平坦的地方取周围平均，提亮压暗时边上不出光晕。
                val moments = pass(MOMENTS, mapOf("src" to t), emptyMap(), t.width, t.height)
                val radius = dimS * 0.022f
                val coef = pass(COEF, mapOf("src" to gauss(moments, radius)), mapOf("eps" to 0.004f, "follow" to 0.7f), t.width, t.height)
                ab = gauss(coef, radius)
                if (even != 0f) {
                    val wr = dimS * 0.12f
                    val coarse = pass(COEF, mapOf("src" to gauss(moments, wr)), mapOf("eps" to 0.006f, "follow" to 0.6f), t.width, t.height)
                    wide = gauss(coarse, wr)
                    avg = average(moments)
                }
            }
            if (dehaze <= 0 && clarity == 0f && glowAmount <= 0) return Ctx(ab, wide, avg, null, null, null, curve)
            var cur = passB(s, t, info, ab, wide, avg, curve)
            var veil: Tex? = null
            if (dehaze > 0) {
                val dark = pass(DARK, mapOf("src" to cur), emptyMap(), cur.width, cur.height)
                veil = gauss(dark, dimS * 0.045f)
                cur = passC(s, cur, info, veil)
            }
            val big = if (clarity != 0f) gauss(cur, dimS * 0.018f) else null
            var glow: Tex? = null
            if (glowAmount > 0) {
                if (needD) cur = passD(s, cur, info, big, null)
                glow = gauss(cur, dimS * 0.012f)
            }
            return Ctx(ab, wide, avg, veil, big, glow, curve)
        } }

        fun tile(s: GpuScope, t: Tex, info: TileInfo, ctx: Ctx): Tex { with(s) {
            var cur = t
            if (denoise > 0) cur = pass(DENOISE, mapOf("src" to cur), mapOf("level" to 0.01f + denoise * 0.07f), cur.width, cur.height)
            cur = passB(s, cur, info, ctx.ab, ctx.wide, ctx.avg, ctx.curve)
            if (dehaze > 0 && ctx.veil != null) cur = passC(s, cur, info, ctx.veil)
            if (needD) {
                val smallBlur = if (sharpen != 0f) gauss(cur, max(0.4f, info.dim * 0.0013f)) else null
                cur = passD(s, cur, info, ctx.big, smallBlur)
            }
            val inputs = HashMap<String, Tex>()
            inputs["src"] = cur
            if (ctx.glow != null) inputs["glowT"] = ctx.glow
            return pass(FINAL, inputs, eUniforms + info.uniforms() + mapOf("bloomAmt" to (if (ctx.glow != null) glowAmount else 0f)), cur.width, cur.height)
        } }

        fun passB(s: GpuScope, t: Tex, info: TileInfo, ab: Tex?, wide: Tex?, avg: Tex?, curve: Tex?): Tex {
            val inputs = HashMap<String, Tex>()
            inputs["src"] = t
            ab?.let { inputs["abT"] = it }
            wide?.let { inputs["wideT"] = it }
            avg?.let { inputs["avgT"] = it }
            curve?.let { inputs["curveT"] = it }
            val u = bUniforms + info.uniforms() + mapOf(
                "ltOn" to (if (localTone && ab != null) 1 else 0),
                "even" to (if (wide != null && avg != null) even else 0f),
                "curveOn" to (if (curve != null) 1 else 0),
            )
            return s.pass(PASS_B, inputs, u, t.width, t.height)
        }

        fun passC(s: GpuScope, t: Tex, info: TileInfo, veil: Tex): Tex =
            s.pass(DEHAZE, mapOf("src" to t, "veilT" to veil), info.uniforms() + mapOf("amount" to dehaze), t.width, t.height)

        fun passD(s: GpuScope, t: Tex, info: TileInfo, big: Tex?, smallBlur: Tex?): Tex {
            val inputs = HashMap<String, Tex>()
            inputs["src"] = t
            big?.let { inputs["bigT"] = it }
            smallBlur?.let { inputs["smallT"] = it }
            val u = dUniforms + info.uniforms() + mapOf(
                "clarity" to (if (big != null) clarity else 0f),
                "sharpK" to (if (smallBlur != null) sharpen * 1.6f else 0f),
            )
            return s.pass(PASS_D, inputs, u, t.width, t.height)
        }
    }

    /** 色相（0...1）对应的纯色。 */
    fun rgb(hue: Float): FloatArray {
        val h = (hue - floor(hue)) * 6
        val x = 1 - abs(h % 2 - 1)
        return when (h.toInt()) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f)
            else -> floatArrayOf(1f, 0f, x)
        }
    }

    /** 色调分离的色偏：亮度为零，只染色不改明暗。 */
    private fun tintOffset(hue: Float, amount: Float): FloatArray {
        val c = rgb(hue)
        val luma = c[0] * 0.299f + c[1] * 0.587f + c[2] * 0.114f
        val k = amount * 0.2f
        return floatArrayOf((c[0] - luma) * k, (c[1] - luma) * k, (c[2] - luma) * k)
    }

    // MARK: 着色器

    private const val W6 = "const vec3 W6 = vec3(0.299, 0.587, 0.114);\n"

    /** 预乘输入 → (亮度, 亮度², 0, 1)。 */
    private val MOMENTS = Shaders.HEADER + W6 + """
uniform sampler2D src;
void main() {
    vec4 s = texture(src, uv);
    vec3 g = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
    float l = clamp(dot(g, W6), 0.0, 1.0);
    o = vec4(l, l * l, 0.0, 1.0);
}
"""

    /** 引导滤波的系数。 */
    private val COEF = Shaders.HEADER + """
uniform sampler2D src;
uniform float eps;
uniform float follow;
void main() {
    vec4 m = texture(src, uv);
    float variance = max(m.g - m.r * m.r, 0.0);
    float a = follow * variance / (variance + eps);
    o = vec4(a, (1.0 - a) * m.r, 0.0, 1.0);
}
"""

    /** 暗通道（线性光）。 */
    private val DARK = Shaders.HEADER + """
uniform sampler2D src;
void main() {
    vec3 g = texture(src, uv).rgb;
    float m = min(min(g.r, g.g), g.b);
    o = vec4(vec3(pow(clamp(m, 0.0, 1.0), 2.2)), 1.0);
}
"""

    /** 降噪：按亮度差加权的 5×5 平均（预乘进、预乘出）。 */
    private val DENOISE = Shaders.HEADER + """
uniform sampler2D src;
uniform vec2 src_size;
uniform float level;
void main() {
    vec4 c0 = texture(src, uv);
    float l0 = dot(c0.rgb, vec3(0.299, 0.587, 0.114));
    float sr = level * 1.5;
    vec4 acc = vec4(0.0);
    float ws = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec4 c = texture(src, uv + vec2(float(i), float(j)) / src_size);
            float d = dot(c.rgb, vec3(0.299, 0.587, 0.114)) - l0;
            float w = exp(-float(i * i + j * j) / 4.5) * exp(-d * d / (2.0 * sr * sr));
            acc += c * w;
            ws += w;
        }
    }
    o = acc / ws;
}
"""

    private val PASS_B = Shaders.HEADER + ColorGL.TILE_HEAD + Shaders.COLOR_FUNCS + ColorParams.glsl("cp") + W6 + """
uniform sampler2D src;
uniform sampler2D abT;
uniform sampler2D wideT;
uniform sampler2D avgT;
uniform sampler2D curveT;
uniform int ltOn;
uniform vec4 lt;
uniform float even;
uniform int lightOn;
uniform vec4 lp;
uniform int colorOn;
uniform int curveOn;
uniform int hslOn;
uniform vec4 h1; uniform vec4 h2; uniform vec4 s1; uniform vec4 s2; uniform vec4 l1; uniform vec4 l2;
uniform int skinOn;
uniform float skTone;
uniform float skWhite;
uniform int splitOn;
uniform vec3 spSh;
uniform vec3 spHi;
uniform float spBal;

float hband(float h, float c, float left, float right) {
    float d = fract(h - c + 0.5) - 0.5;
    return d < 0.0 ? max(0.0, 1.0 + d / left) : max(0.0, 1.0 - d / right);
}

void main() {
    vec4 s = texture(src, uv);
    float a = s.a;
    vec3 g = a > 0.0 ? s.rgb / a : vec3(0.0);
    vec2 gu = guv();

    // 按区域提亮 / 压暗：智能补光、逆光、氛围、自然光、校正光。在线性光上按"档"增益。
    if (ltOn == 1) {
        vec3 c = pow(max(g, 0.0), vec3(2.2));
        float l = clamp(dot(max(g, 0.0), W6), 0.0, 1.0);
        vec4 ab = texture(abT, gu);
        float area = clamp(ab.r * l + ab.g, 0.0, 1.0);
        float e = mix(area, l, 0.15);
        float dark = (1.0 - smoothstep(0.08, 0.55, e)) * (0.35 + 0.65 * smoothstep(0.0, 0.08, e));
        float bright = smoothstep(0.6, 0.95, e);
        float stops = lt.x * 1.6 * dark;
        stops += lt.y * (1.5 * dark - 0.9 * bright);
        stops += lt.z * 0.85 * (1.0 - 2.0 * e);
        stops += lt.w * 0.5 * (1.0 - smoothstep(0.25, 0.85, e));
        if (even != 0.0) {
            float whole = clamp(texture(avgT, vec2(0.5)).r, 0.03, 1.0);
            vec4 wd = texture(wideT, gu);
            float zone = clamp(wd.r * l + wd.g, 0.03, 1.0);
            stops += even * 0.8 * clamp(2.2 * log2(whole / zone), -1.5, 1.5);
            stops += even * 0.3 * clamp(2.2 * log2(0.46 / whole), -1.0, 1.0);
        }
        c = c * pow(2.0, stops);
        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(y), c, 1.0 + 0.22 * lt.z);
        g = pow(max(c, 0.0), vec3(1.0 / 2.2));
    }

    // 逐像素的光线：光感、弱光、自然光、氛围光。
    if (lightOn == 1) {
        vec3 gg = max(g, 0.0);
        float l = dot(gg, W6);
        float x = clamp(l, 0.0, 1.0);
        float over = l - x;
        float y = x * pow(2.0, lp.y * 1.8 * pow(1.0 - x, 1.2) / 2.2);
        y = min(y, 1.0);
        float lifted = 1.0 - (1.0 - y) * (1.0 - y);
        y = lp.x >= 0.0 ? mix(y, lifted, lp.x * 0.8) : mix(y, y * y, -lp.x * 0.8);
        float airy = 1.0 - pow(1.0 - y, 1.5);
        y = mix(y, airy, lp.z * 0.45);
        y = mix(y, 0.5 + (y - 0.5) * 0.86, lp.z);
        float target = max(y + over, 0.0);
        float ratio = min(target / max(l, 0.0001), 12.0);
        vec3 oo = mix(vec3(target), gg * ratio, smoothstep(0.0, 0.03, l));
        oo = oo * vec3(1.0 + 0.035 * lp.z, 1.0 + 0.008 * lp.z, 1.0 - 0.04 * lp.z);
        float amount = abs(lp.w);
        if (amount > 0.0) {
            vec3 tint = lp.w > 0.0 ? vec3(1.0, 0.62, 0.25) : vec3(0.35, 0.6, 1.0);
            float glow = 1.0 - smoothstep(0.0, 1.1, distance(gu, vec2(0.12, 0.08)));
            vec3 base = clamp(oo, 0.0, 1.0);
            float bl = dot(base, W6);
            float hi = smoothstep(0.2, 0.95, bl);
            vec3 unit = tint / dot(tint, W6);
            vec3 toned = clamp(base * mix(vec3(1.0), unit, 0.28 * amount * hi), 0.0, 1.0);
            oo = 1.0 - (1.0 - toned) * (1.0 - tint * (0.3 * amount * glow));
        }
        g = max(oo, 0.0);
    }

    if (colorOn == 1) g = cp_map(g);

    if (curveOn == 1) {
        vec3 q = clamp(g, 0.0, 1.0) * (255.0 / 256.0) + 0.5 / 256.0;
        g = vec3(texture(curveT, vec2(q.r, 0.5)).r, texture(curveT, vec2(q.g, 0.5)).g, texture(curveT, vec2(q.b, 0.5)).b);
    }

    // HSL：按色相分成八个颜色，各自改色相、饱和度、明度。接近灰色的地方不受影响。
    if (hslOn == 1) {
        vec3 hsv = rgb2hsv(max(g, 0.0));
        float h = hsv.x;
        vec4 wa = vec4(hband(h, 0.0, 40.0 / 360.0, 30.0 / 360.0), hband(h, 30.0 / 360.0, 30.0 / 360.0, 30.0 / 360.0),
                       hband(h, 60.0 / 360.0, 30.0 / 360.0, 60.0 / 360.0), hband(h, 120.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0));
        vec4 wb = vec4(hband(h, 180.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0), hband(h, 240.0 / 360.0, 60.0 / 360.0, 40.0 / 360.0),
                       hband(h, 280.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0), hband(h, 320.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0));
        float dh = dot(wa, h1) + dot(wb, h2);
        float ds = dot(wa, s1) + dot(wb, s2);
        float dl = dot(wa, l1) + dot(wb, l2);
        float sw = smoothstep(0.03, 0.25, hsv.y);
        hsv.x = fract(hsv.x + dh * sw * (30.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 + ds * sw), 0.0, 1.0);
        g = max(hsv2rgb(hsv) * (1.0 + 0.6 * dl * sw), 0.0);
    }

    // 肤色：只动橙红附近、饱和度不高不低、不太暗的地方。
    if (skinOn == 1) {
        vec3 gc = clamp(g, 0.0, 1.0);
        vec3 hsv = rgb2hsv(gc);
        float d = fract(hsv.x - 0.065 + 0.5) - 0.5;
        float w = smoothstep(0.0, 0.55, max(0.0, 1.0 - abs(d) / 0.085));
        w = w * smoothstep(0.08, 0.2, hsv.y) * (1.0 - smoothstep(0.62, 0.9, hsv.y)) * smoothstep(0.15, 0.4, hsv.z);
        hsv.x = fract(hsv.x + skTone * w * (10.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 - 0.32 * skWhite * w), 0.0, 1.0);
        hsv.z = hsv.z + (1.0 - hsv.z) * 0.22 * skWhite * w;
        g = mix(gc, hsv2rgb(hsv), step(0.0001, w));
    }

    // 色调分离。
    if (splitOn == 1) {
        vec3 gg = max(g, 0.0);
        float l = clamp(dot(gg, W6), 0.0, 1.0);
        float pivot = 0.5 - spBal * 0.3;
        float ws = 1.0 - smoothstep(pivot - 0.4, pivot + 0.1, l);
        float wh = smoothstep(pivot - 0.1, pivot + 0.4, l);
        g = max(gg + ws * spSh + wh * spHi, 0.0);
    }

    o = vec4(clamp(g, 0.0, 1.0), a);
}
"""

    /** 去灰：减掉"暗通道"估出来的灰雾（线性光）。 */
    private val DEHAZE = Shaders.HEADER + ColorGL.TILE_HEAD + """
uniform sampler2D src;
uniform sampler2D veilT;
uniform float amount;
void main() {
    vec4 s = texture(src, uv);
    vec3 c = pow(max(s.rgb, 0.0), vec3(2.2));
    float veil = clamp(texture(veilT, guv()).r, 0.0, 1.0) * amount * 0.55;
    c = (c - vec3(veil)) / max(1.0 - veil, 0.25);
    o = vec4(clamp(pow(max(c, 0.0), vec3(1.0 / 2.2)), 0.0, 1.0), s.a);
}
"""

    /** 清晰度、锐化、局部调色。 */
    private val PASS_D = Shaders.HEADER + ColorGL.TILE_HEAD + W6 + """
uniform sampler2D src;
uniform sampler2D bigT;
uniform sampler2D smallT;
uniform float clarity;
uniform float sharpK;
uniform int locN;
uniform float locX[8]; uniform float locY[8]; uniform float locR[8];
uniform float locB[8]; uniform float locC[8]; uniform float locS[8]; uniform float locT[8];
void main() {
    vec4 s = texture(src, uv);
    vec3 g = s.rgb;
    vec2 gp = gpix();
    // 清晰度：大半径的局部反差，只改亮度，主要加在中间调上。
    if (clarity != 0.0) {
        vec3 gg = max(g, 0.0);
        float l = dot(gg, W6);
        float lb = dot(max(texture(bigT, guv()).rgb, 0.0), W6);
        float edge = 2.0 * clamp(l, 0.0, 1.0) - 1.0;
        float midtone = 1.0 - edge * edge;
        float delta = clamp(l - lb, -0.18, 0.18);
        float x = max(l + clarity * 1.3 * delta * midtone, 0.0);
        float ratio = min(x / max(l, 0.0001), 8.0);
        g = mix(vec3(x), gg * ratio, smoothstep(0.0, 0.03, l));
    }
    if (sharpK != 0.0) g = g + sharpK * (s.rgb - texture(smallT, uv).rgb);
    // 局部调色：圆心附近完全生效，到半径处渐变到零。
    float dim = max(full.x, full.y);
    for (int i = 0; i < 8; i++) {
        if (i >= locN) break;
        vec2 cen = vec2(locX[i], locY[i]) * full;
        float rad = max(1.0, locR[i] * dim);
        float w = 1.0 - smoothstep(0.35, 1.0, distance(gp, cen) / rad);
        if (w <= 0.0) continue;
        vec4 p = vec4(locB[i], locC[i], locS[i], locT[i]);
        vec3 gg = max(g, 0.0);
        vec3 c = clamp(gg, 0.0, 1.0);
        vec3 oo = p.x >= 0.0 ? vec3(1.0) - pow(vec3(1.0) - c, vec3(1.0 + 1.4 * p.x)) : gg * pow(2.0, p.x * 0.6);
        oo = (oo - vec3(0.5)) * (1.0 + 0.6 * p.y) + vec3(0.5);
        float l = dot(oo, W6);
        oo = vec3(l) + (oo - vec3(l)) * (1.0 + p.z);
        oo = oo * vec3(1.0 + 0.16 * p.w, 1.0, 1.0 - 0.16 * p.w);
        g = mix(gg, max(oo, 0.0), w);
    }
    o = vec4(clamp(g, 0.0, 1.0), s.a);
}
"""

    /** 色散、柔光、暗角、颗粒、标记；乘回预乘。 */
    private val FINAL = Shaders.HEADER + ColorGL.TILE_HEAD + ColorGL.FX_FUNCS + """
uniform sampler2D src;
uniform sampler2D glowT;
uniform float fringeK;
uniform float bloomAmt;
uniform float vig;
uniform float grainAmt;
uniform int mkN;
uniform float mkX[8]; uniform float mkY[8]; uniform float mkR[8];
uniform int mkSel;
void main() {
    vec4 s = texture(src, uv);
    vec3 g = s.rgb;
    float a = s.a;
    vec2 gp = gpix();
    // 色散：红、蓝两个通道以画面中心为准一个放大一点、一个缩小一点。
    if (fringeK > 0.0) {
        vec2 cen = full * 0.5;
        vec2 d = gp - cen;
        g.r = texture(src, (cen + d * (1.0 - fringeK) - orig) / size).r;
        g.b = texture(src, (cen + d * (1.0 + fringeK) - orig) / size).b;
    }
    if (bloomAmt > 0.0) g = fxBloom(g, texture(glowT, guv()).rgb, bloomAmt);
    g = fxVignette(g, gp, vig);
    g = fxGrain(g, gp, grainAmt);
    float dim = max(full.x, full.y);
    float px = dim / 700.0;
    for (int i = 0; i < 8; i++) {
        if (i >= mkN) break;
        vec2 cen = vec2(mkX[i], mkY[i]) * full;
        float radius = max(1.0, mkR[i] * dim);
        float chosen = i == mkSel ? 1.0 : 0.0;
        float d = distance(gp, cen);
        float ring = (1.0 - smoothstep(px * 0.6, px * 1.6, abs(d - radius))) * chosen;
        float shade = (1.0 - smoothstep(px * 1.6, px * 3.2, abs(d - radius))) * chosen;
        float core = 1.0 - smoothstep(px * 2.6, px * 3.8, d);
        float rim = 1.0 - smoothstep(px * 3.8, px * 5.4, d);
        g = mix(g, vec3(0.0), 0.4 * max(shade, rim));
        g = mix(g, vec3(1.0), max(ring, core * mix(0.75, 1.0, chosen)));
    }
    g = clamp(g, 0.0, 1.0);
    o = vec4(g * a, a);
}
"""
}
