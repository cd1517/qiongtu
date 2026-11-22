package com.taoo.xiutu.tools.color

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 一块图在整张图里的位置（含外扩边）。着色器拼上 [ColorGL.TILE_HEAD] 后用 `gpix()` 取整图像素坐标、
 * `guv()` 取整图归一化坐标（原点左上），暗角、局部调色这些按整图位置算的效果在分块时也对得上。
 */
class TileInfo(val fullW: Int, val fullH: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    val dim: Float get() = max(fullW, fullH).toFloat()

    fun uniforms(): Map<String, Any> = mapOf(
        "full" to floatArrayOf(fullW.toFloat(), fullH.toFloat()),
        "orig" to floatArrayOf(x.toFloat(), y.toFloat()),
    )

    companion object {
        fun whole(w: Int, h: Int) = TileInfo(w, h, 0, 0, w, h)
        fun whole(t: Tex) = whole(t.width, t.height)
    }
}

/**
 * 调色 / 滤镜 / 美颜共用的 GPU 小工具。
 *
 * 约定：中间纹理存"未预乘"的 gamma 颜色（sRGB 值）和 alpha；链条第一步除掉 alpha，最后一步再乘回去。
 * iOS 版在线性光里做的运算（曝光档位、去灰、暗角合成）在着色器里用 pow 2.2 换算。
 */
object ColorGL {
    /** 分块着色器的公共头：`size` 是这一块输出尺寸，`full` 整图尺寸，`orig` 这一块左上角在整图里的位置。 */
    const val TILE_HEAD = """
uniform vec2 size;
uniform vec2 full;
uniform vec2 orig;
vec2 gpix() { return orig + uv * size; }
vec2 guv() { return (orig + uv * size) / full; }
"""

    /**
     * 常用效果（要先拼 TILE_HEAD）：
     * - fxVignette(g, gp, amount)：正数压暗四角，负数提亮四角（iOS FX.vignette / AdjustFX.vignette）。
     * - fxBloom(g, glow, amount)：glow 是大半径模糊后的图（iOS 用 dim * 0.012）。
     * - fxGrain(g, gp, amount)：颗粒，花样和分辨率无关。
     */
    const val FX_FUNCS = """
vec3 fxVignette(vec3 g, vec2 gp, float amount) {
    if (amount == 0.0) return g;
    float d = length(full) * 0.5;
    float t = clamp((distance(gp, full * 0.5) - d * 0.45) / (d * 0.6), 0.0, 1.0);
    vec3 lin = pow(max(g, 0.0), vec3(2.2));
    if (amount > 0.0) lin *= 1.0 - t * min(amount, 1.0) * 0.85;
    else lin = mix(lin, vec3(1.0), t * min(-amount, 1.0) * 0.6);
    return pow(lin, vec3(1.0 / 2.2));
}
vec3 fxBloom(vec3 g, vec3 glow, float amount) {
    if (amount <= 0.0) return g;
    vec3 b = clamp(g, 0.0, 1.0);
    vec3 scr = 1.0 - (1.0 - b) * (1.0 - clamp(glow, 0.0, 1.0));
    return mix(g, scr, min(amount, 1.0) * 0.7);
}
float fxHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
vec3 fxSoftLight(vec3 b, float n) {
    vec3 dd = mix(sqrt(b), ((16.0 * b - 12.0) * b + 4.0) * b, step(b, vec3(0.25)));
    return n <= 0.5 ? b - (1.0 - 2.0 * n) * b * (1.0 - b) : b + (2.0 * n - 1.0) * (dd - b);
}
vec3 fxGrain(vec3 g, vec2 gp, float amount) {
    if (amount <= 0.0) return g;
    float s = max(1.0, max(full.x, full.y) / 1100.0);
    float n = fxHash(floor(gp / s));
    vec3 b = clamp(g, 0.0, 1.0);
    return mix(g, fxSoftLight(b, n), min(amount, 1.0) * 0.4);
}
"""

    /** 除掉预乘：输出 (未预乘颜色, alpha)。 */
    val UNPREMUL = Shaders.HEADER + """
uniform sampler2D src;
void main() { vec4 s = texture(src, uv); o = vec4(s.a > 0.0 ? s.rgb / s.a : vec3(0.0), s.a); }
"""

    /** 乘回预乘。 */
    val PREMUL = Shaders.HEADER + """
uniform sampler2D src;
void main() { vec4 s = texture(src, uv); o = vec4(clamp(s.rgb, 0.0, 1.0) * s.a, s.a); }
"""

    /** CIGaussianBlur 的 radius 基本就是 sigma；框架的 blur 用的是"窗口半径"（sigma × 2.5）。 */
    fun GpuScope.gauss(t: Tex, sigma: Float): Tex = blur(t, sigma * 2.5f)

    /** 缩到长边 side（每次最多缩一半，线性采样相当于 2×2 平均）。 */
    fun GpuScope.shrink(t: Tex, side: Int): Tex {
        var cur = t
        val long = max(t.width, t.height)
        if (long <= side) return t
        val k = side.toFloat() / long
        val tw = max(1, (t.width * k).toInt())
        val th = max(1, (t.height * k).toInt())
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            cur = pass(Shaders.COPY, mapOf("src" to cur), emptyMap(), max(1, cur.width / 2), max(1, cur.height / 2))
        }
        if (cur.width != tw || cur.height != th) cur = pass(Shaders.COPY, mapOf("src" to cur), emptyMap(), tw, th)
        return cur
    }

    /** 整张图的平均值，缩成 1×1。 */
    fun GpuScope.average(t: Tex): Tex {
        var cur = t
        while (cur.width > 1 || cur.height > 1) {
            cur = pass(Shaders.COPY, mapOf("src" to cur), emptyMap(), max(1, (cur.width + 1) / 2), max(1, (cur.height + 1) / 2))
        }
        return cur
    }

    // MARK: 缩小版（全图共用的平滑图在它上面算）

    /** 全图共用的低频图（引导滤波系数、去灰的雾、清晰度的大模糊、柔光）在多大的缩小版上算。 */
    const val CTX_SIDE = 800

    private var smallSrc = WeakReference<Bitmap>(null)
    private var smallSide = 0
    private var smallOut: Bitmap? = null

    /** 缩到长边 side 的版本。拖滑块时同一张底图反复用，缓存最近一张。 */
    fun small(src: Bitmap, side: Int = CTX_SIDE): Bitmap = synchronized(this) {
        val hit = smallOut
        if (hit != null && smallSrc.get() === src && smallSide == side) return hit
        val out = Img.downscale(src, side)
        smallSrc = WeakReference(src)
        smallSide = side
        smallOut = out
        out
    }
}

/**
 * 大图分块跑 GPU：每块不超过 TILE（外加 margin 的外扩边，给模糊、色散这些要看邻居的效果用）。
 * prepare 在外层作用域里跑一次，返回各块共用的东西（一般是在缩小版上算好的平滑图）；
 * tile 处理一块，返回同尺寸的结果纹理（已经乘回预乘）。每块用自己的作用域，用完就释放显存。
 * 预览图（长边 1600）只有一块。
 */
object Tiler {
    const val TILE = 2048

    fun <C> render(
        src: Bitmap,
        margin: Int,
        prepare: GpuScope.() -> C,
        tile: GpuScope.(Tex, TileInfo, C) -> Tex,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val single = max(w, h) <= max(TILE, 2600) && max(w, h) <= Gpu.maxTextureSize
        if (single) {
            return Gpu.run {
                val c = prepare()
                val t = upload(src)
                read(tile(t, TileInfo.whole(w, h), c))
            }
        }
        val m = max(0, margin)
        val core = min(TILE, Gpu.maxTextureSize - 2 * m).coerceAtLeast(256)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        Gpu.run {
            val c = prepare()
            val nx = ceil(w / core.toFloat()).toInt()
            val ny = ceil(h / core.toFloat()).toInt()
            for (ty in 0 until ny) for (tx in 0 until nx) {
                val x0 = tx * core
                val y0 = ty * core
                val cw = min(core, w - x0)
                val ch = min(core, h - y0)
                val ex0 = max(0, x0 - m)
                val ey0 = max(0, y0 - m)
                val ex1 = min(w, x0 + cw + m)
                val ey1 = min(h, y0 + ch + m)
                val sub = Bitmap.createBitmap(src, ex0, ey0, ex1 - ex0, ey1 - ey0)
                val piece = Gpu.run {
                    val t = upload(sub)
                    read(tile(t, TileInfo(w, h, ex0, ey0, ex1 - ex0, ey1 - ey0), c))
                }
                canvas.drawBitmap(piece, Rect(x0 - ex0, y0 - ey0, x0 - ex0 + cw, y0 - ey0 + ch), Rect(x0, y0, x0 + cw, y0 + ch), null)
                if (sub !== src) sub.recycle()
                piece.recycle()
            }
        }
        return out
    }
}
