package com.taoo.xiutu.tools.color

import android.graphics.Bitmap
import android.graphics.PointF
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 曲线的插值：单调三次（Fritsch–Carlson），控制点之间不会过冲。照搬 iOS CurveShape。 */
class CurveShape(points: List<PointF>) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val m: DoubleArray

    init {
        var sorted = points.sortedBy { it.x }
        if (sorted.isEmpty()) sorted = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        for (p in sorted) {
            val x = p.x.toDouble().coerceIn(0.0, 1.0)
            val y = p.y.toDouble().coerceIn(0.0, 1.0)
            if (px.isNotEmpty() && x - px.last() < 1e-4) continue
            px += x; py += y
        }
        val n = px.size
        val t = DoubleArray(n)
        if (n >= 2) {
            val d = DoubleArray(n - 1) { (py[it + 1] - py[it]) / (px[it + 1] - px[it]) }
            t[0] = d[0]
            t[n - 1] = d[n - 2]
            for (i in 1 until n - 1) t[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
            for (i in 0 until n - 1) {
                if (d[i] == 0.0) { t[i] = 0.0; t[i + 1] = 0.0; continue }
                val a = t[i] / d[i]
                val b = t[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9) {
                    val k = 3 / sqrt(s)
                    t[i] = k * a * d[i]
                    t[i + 1] = k * b * d[i]
                }
            }
        }
        xs = px.toDoubleArray(); ys = py.toDoubleArray(); m = t
    }

    fun value(x: Double): Double {
        if (xs.isEmpty()) return x
        if (xs.size == 1 || x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()
        var i = 0
        while (i < xs.size - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val v = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
        return v.coerceIn(0.0, 1.0)
    }
}

/** 曲线做成的 256×1 查找表纹理（RGB 三条）。着色器里按 (v * 255 + 0.5) / 256 采样。 */
object CurveLut {
    /** channels[0] 总曲线，1..3 红绿蓝。先过总曲线，再过各自通道。 */
    fun bitmap(channels: List<List<PointF>>): Bitmap {
        val master = CurveShape(channels[0])
        val single = (1..3).map { CurveShape(channels[it]) }
        val px = IntArray(256)
        for (i in 0 until 256) {
            val x = master.value(i / 255.0)
            val r = (single[0].value(x) * 255).roundToInt()
            val g = (single[1].value(x) * 255).roundToInt()
            val b = (single[2].value(x) * 255).roundToInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
    }
}

/**
 * 通用 3D LUT：n³ 个格点平铺成 (n·n) × n 的 2D 纹理（蓝色分量选第几块，块内 x = 红、y = 绿）。
 * 给"任意颜色映射"用（比如以后 AI 学到的色调）；ColorParams 本身直接在着色器里算，不用 LUT。
 */
object ColorLut {
    /** GLSL：`vec3 lut3(sampler2D lut, float n, vec3 c)`，c 是 0...1 的颜色，两块之间线性插值。 */
    const val GLSL = """
vec3 lut3(sampler2D lut, float n, vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float b = c.b * (n - 1.0);
    float b0 = floor(b);
    float b1 = min(b0 + 1.0, n - 1.0);
    vec2 inner = (c.rg * (n - 1.0) + 0.5) / vec2(n * n, n);
    vec3 s0 = texture(lut, inner + vec2(b0 / n, 0.0)).rgb;
    vec3 s1 = texture(lut, inner + vec2(b1 / n, 0.0)).rgb;
    return mix(s0, s1, b - b0);
}
"""

    /** 按 map 烘一张 LUT 位图。 */
    fun bake(n: Int = 32, map: (Float, Float, Float) -> FloatArray): Bitmap {
        val w = n * n
        val px = IntArray(w * n)
        val inv = 1f / (n - 1)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = map(r * inv, g * inv, b * inv)
            val ri = (o[0].coerceIn(0f, 1f) * 255).roundToInt()
            val gi = (o[1].coerceIn(0f, 1f) * 255).roundToInt()
            val bi = (o[2].coerceIn(0f, 1f) * 255).roundToInt()
            px[g * w + b * n + r] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return Bitmap.createBitmap(px, w, n, Bitmap.Config.ARGB_8888)
    }

    fun bake(params: ColorParams, n: Int = 32) = bake(n) { r, g, b -> params.map(r, g, b) }

    private val APPLY = Shaders.HEADER + GLSL + """
uniform sampler2D src;
uniform sampler2D lut;
uniform float n;
void main() {
    vec4 s = texture(src, uv);
    vec3 g = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
    o = vec4(lut3(lut, n, g) * s.a, s.a);
}
"""

    /** 预乘进、预乘出。 */
    fun GpuScope.applyLut(t: Tex, lut: Tex): Tex =
        pass(APPLY, mapOf("src" to t, "lut" to lut), mapOf("n" to lut.height.toFloat()), t.width, t.height)

    fun apply(src: Bitmap, lut: Bitmap): Bitmap = Tiler.render(src, 0, prepare = { upload(lut) }) { t, _, l -> applyLut(t, l) }
}
