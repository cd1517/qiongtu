package com.taoo.xiutu.tools.color

import android.graphics.Bitmap
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import com.taoo.xiutu.tools.color.ColorGL.gauss
import kotlin.math.max

/**
 * 一种"色调"：一组 ColorParams，外加暗角和柔光。滤镜、拍立得色调、大师预览、美颜里的氛围都用它。
 * 不带颗粒。
 */
data class Look(val color: ColorParams, val vignette: Float = 0f, val bloom: Float = 0f)

/**
 * 色调的 GPU 实现。全部参数和分辨率无关：柔光半径按图片长边 × 0.012 换算（照 iOS FX.bloom）。
 *
 * - [apply]：整张 Bitmap 套一个色调（大图自动分块）。
 * - [lookTex]：在自己的 Gpu.run 里对纹理套色调（批量做缩略图时用）。
 * - [colorMap]：只套 ColorParams，不要暗角和柔光。
 */
object ColorFx {
    /**
     * intensity：强度 0...1。
     * mixLast = false（滤镜）：先按强度和原图混合，再加柔光、暗角（柔光暗角也乘强度）。
     * mixLast = true（拍立得）：色调、柔光、暗角都做满，最后整体按强度和原图混合。
     */
    fun apply(src: Bitmap, look: Look, intensity: Float = 1f, mixLast: Boolean = false): Bitmap {
        if (intensity <= 0f || (look.color.isIdentity && look.vignette == 0f && look.bloom == 0f)) return src
        val needGlow = look.bloom * (if (mixLast) 1f else intensity) > 0f
        val small = if (needGlow) ColorGL.small(src) else null
        return Tiler.render(src, 0, prepare = {
            if (small == null) null else {
                val t = upload(small)
                glow(t, TileInfo.whole(t), look, intensity, mixLast)
            }
        }) { t, info, glow -> lookTex(t, info, look, intensity, mixLast, glow) }
    }

    /** 柔光要的大模糊：在缩小版上先做柔光之前那一步，再模糊。 */
    fun GpuScope.glow(t: Tex, info: TileInfo, look: Look, intensity: Float, mixLast: Boolean): Tex {
        val pre = pass(LOOK, mapOf("src" to t), look.color.uniforms() + info.uniforms() + mapOf(
            "intensity" to (if (mixLast) 1f else intensity), "bloomAmt" to 0f, "vig" to 0f, "mixLast" to 0, "premul" to 0,
        ), t.width, t.height)
        return gauss(pre, info.dim * 0.012f)
    }

    /**
     * 套色调，输入是预乘纹理，输出也是预乘纹理。glow 为空时自己在这张纹理上算（只适合整张、不分块的情况）。
     */
    fun GpuScope.lookTex(t: Tex, info: TileInfo, look: Look, intensity: Float = 1f, mixLast: Boolean = false, glow: Tex? = null): Tex {
        val bloom = look.bloom * (if (mixLast) 1f else intensity)
        val g = when {
            bloom <= 0f -> null
            glow != null -> glow
            else -> glow(t, info, look, intensity, mixLast)
        }
        val inputs = if (g != null) mapOf("src" to t, "glowT" to g) else mapOf("src" to t)
        return pass(LOOK, inputs, look.color.uniforms() + info.uniforms() + mapOf(
            "intensity" to intensity, "bloomAmt" to (if (g != null) bloom else 0f),
            "vig" to look.vignette * (if (mixLast) 1f else intensity), "mixLast" to (if (mixLast) 1 else 0), "premul" to 1,
        ), t.width, t.height)
    }

    /** 只套 ColorParams（预乘进、预乘出）。 */
    fun GpuScope.colorMap(t: Tex, params: ColorParams): Tex =
        pass(LOOK, mapOf("src" to t), params.uniforms() + TileInfo.whole(t).uniforms() + mapOf(
            "intensity" to 1f, "bloomAmt" to 0f, "vig" to 0f, "mixLast" to 0, "premul" to 1,
        ), t.width, t.height)

    fun colorMap(src: Bitmap, params: ColorParams): Bitmap =
        if (params.isIdentity) src else Tiler.render(src, 0, prepare = {}) { t, _, _ -> colorMap(t, params) }

    /** 一批缩略图：一张小图套很多个色调，在一个 Gpu.run 里做完。 */
    fun thumbs(small: Bitmap, looks: List<Pair<String, Look>>, intensity: Float = 1f): Map<String, Bitmap> = Gpu.run {
        val t = upload(small)
        val info = TileInfo.whole(t)
        val out = HashMap<String, Bitmap>()
        for ((key, look) in looks) out[key] = read(lookTex(t, info, look, intensity))
        out
    }

    /** 色调着色器：src 是预乘输入；premul = 1 时输出预乘，否则输出未预乘（中间步骤用）。 */
    val LOOK: String = Shaders.HEADER + ColorGL.TILE_HEAD + ColorGL.FX_FUNCS + ColorParams.glsl("cp") + """
uniform sampler2D src;
uniform sampler2D glowT;
uniform float intensity;
uniform float bloomAmt;
uniform float vig;
uniform int mixLast;
uniform int premul;
void main() {
    vec4 s = texture(src, uv);
    float a = s.a;
    vec3 g = a > 0.0 ? s.rgb / a : vec3(0.0);
    vec3 m = cp_map(g);
    vec2 gp = gpix();
    if (mixLast == 0) {
        m = mix(g, m, clamp(intensity, 0.0, 1.0));
        if (bloomAmt > 0.0) m = fxBloom(m, texture(glowT, guv()).rgb, bloomAmt);
        m = fxVignette(m, gp, vig);
    } else {
        if (bloomAmt > 0.0) m = fxBloom(m, texture(glowT, guv()).rgb, bloomAmt);
        m = fxVignette(m, gp, vig);
        m = mix(g, m, clamp(intensity, 0.0, 1.0));
    }
    m = clamp(m, 0.0, 1.0);
    o = premul == 1 ? vec4(m * a, a) : vec4(m, a);
}
"""

    /** 两张图按 t 混合（t = 0 是 a）。 */
    fun mix(a: Bitmap, b: Bitmap, t: Float): Bitmap {
        if (t <= 0f) return a
        if (t >= 1f) return b
        return Gpu.filter(a, Shaders.MIX, mapOf("t" to t), mapOf("fg" to b))
    }

    /** 半径（按长边比例）换算成像素 sigma。 */
    fun sigma(info: TileInfo, k: Float) = max(0.3f, info.dim * k)
}
