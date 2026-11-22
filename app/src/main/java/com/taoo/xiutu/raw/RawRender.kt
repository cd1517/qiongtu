package com.taoo.xiutu.raw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Half
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Tex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * RAW 冲洗的 GPU 管线（对应 iOS RawDeveloper.image + RawLinearFilter）。
 *
 * 全图共用的低频图（引导滤波系数、去朦胧的雾、清晰度的大模糊）总是在预览尺寸上算；
 * 原尺寸输出时分块（每块 2048 + 外扩边），每块只上传它需要的那一块传感器数据，
 * 低频图按整图坐标采样，所以预览和原尺寸的效果一致。
 */
object RawRender {
    private const val TILE = 2048

    /** 解好的滑块值。 */
    private class Values(dev: RawDeveloper, private val map: Map<RawKey, Float>) {
        private val d = dev
        operator fun get(k: RawKey): Float = map[k] ?: d.defaultValue(k)
    }

    fun render(dev: RawDeveloper, values: Map<RawKey, Float>, lens: Boolean, full: RawImage?): Bitmap {
        val v = Values(dev, values)
        val pv = dev.preview
        val info = dev.info
        val decodeU = decodeUniforms(dev, v, values, lens)
        val gainData = dev.opcodes.gain
        val nativeDim = max(dev.nativeWidth, dev.nativeHeight).toFloat().coerceAtLeast(1f)

        return Gpu.run {
            val dummy = uploadHalf(halfBuffer(floatArrayOf(1f, 1f, 1f)), 1, 1, 3)
            val gainTex = gainData?.let { uploadHalf(halfBuffer(it.rgb), it.cols, it.rows, 3) } ?: dummy

            // —— 预览尺寸上的分析 ——
            val pW = pv.orientedWidth
            val pH = pv.orientedHeight
            val pDim = max(pW, pH).toFloat()
            val srcP = uploadHalf(pv.pixels, pv.width, pv.height, 3)
            val decP = passFloat(RawShaders.DECODE, mapOf("src" to srcP, "gainTex" to gainTex),
                decodeU + tileU(0, 0, pW, pH) + ("srcRect" to floatArrayOf(0f, 0f, 1f, 1f)), pW, pH)

            val linearOn = v[RawKey.highlights] != 0f || v[RawKey.shadows] != 0f || v[RawKey.whites] != 0f || v[RawKey.blacks] != 0f
            val needAb = linearOn || v[RawKey.localTone] > 0f
            val ab = if (needAb) guided(decP, pDim) else dummy
            val toneU = toneUniforms(v, linearOn)

            val dehaze = v[RawKey.dehaze]
            val clarity = v[RawKey.clarity]
            var veil = dummy
            var big = dummy
            if (dehaze != 0f || clarity != 0f) {
                val toneP = passFloat(RawShaders.TONE, mapOf("src" to decP, "ab" to ab), toneU + tileU(0, 0, pW, pH), pW, pH)
                var cur = toneP
                if (dehaze != 0f) {
                    val dark = passFloat(RawShaders.DARK, mapOf("src" to toneP), emptyMap(), pW, pH)
                    veil = gauss(dark, pDim * 0.045f)
                    cur = passFloat(RawShaders.DEHAZE, mapOf("src" to toneP, "veil" to veil), tileU(0, 0, pW, pH) + ("amount" to dehaze), pW, pH)
                }
                if (clarity != 0f) big = gauss(cur, pDim * 0.018f)
            }
            val shared = Shared(ab, veil, big, dummy, toneU)

            if (full == null) {
                read(chain(decP, 0, 0, pW, pH, pW, pH, v, shared, pDim / nativeDim))
            } else {
                renderFull(dev, full, v, decodeU, gainTex, shared, lens)
            }
        }
    }

    private class Shared(val ab: Tex, val veil: Tex, val big: Tex, val dummy: Tex, val toneU: Map<String, Any>)

    /** 原尺寸分块。 */
    private fun GpuScope.renderFull(
        dev: RawDeveloper, full: RawImage, v: Values, decodeU: Map<String, Any>, gainTex: Tex, shared: Shared, lens: Boolean,
    ): Bitmap {
        val W = full.orientedWidth
        val H = full.orientedHeight
        val dim = max(W, H).toFloat()
        val margin = ceil(2.5f * dim * 0.003f).toInt() + 48
        val core = min(TILE, Gpu.maxTextureSize - 2 * margin - 512).coerceAtLeast(512)
        val warpPad = if (lens) warpMargin(dev) else 0f
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        val canvas = Canvas(out)
        val flip = dev.info.flip
        val nx = ceil(W / core.toFloat()).toInt()
        val ny = ceil(H / core.toFloat()).toInt()
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val x0 = tx * core
            val y0 = ty * core
            val cw = min(core, W - x0)
            val ch = min(core, H - y0)
            val ex0 = max(0, x0 - margin)
            val ey0 = max(0, y0 - margin)
            val ex1 = min(W, x0 + cw + margin)
            val ey1 = min(H, y0 + ch + margin)
            // 这一块在传感器上对应的范围（加上畸变的位移和采样余量）
            var u0 = 1f; var v0 = 1f; var u1 = 0f; var v1 = 0f
            for (gx in listOf(ex0.toFloat() / W, ex1.toFloat() / W)) for (gy in listOf(ey0.toFloat() / H, ey1.toFloat() / H)) {
                val s = toSensor(gx, gy, flip)
                u0 = min(u0, s.first); u1 = max(u1, s.first); v0 = min(v0, s.second); v1 = max(v1, s.second)
            }
            val fw = full.width
            val fh = full.height
            val pad = 4
            val sx0 = (floor((u0 - warpPad) * fw).toInt() - pad).coerceIn(0, fw - 1)
            val sy0 = (floor((v0 - warpPad) * fh).toInt() - pad).coerceIn(0, fh - 1)
            val sx1 = (ceil((u1 + warpPad) * fw).toInt() + pad).coerceIn(sx0 + 1, fw)
            val sy1 = (ceil((v1 + warpPad) * fh).toInt() + pad).coerceIn(sy0 + 1, fh)
            val piece = Gpu.run {
                val src = uploadHalf(full.pixels, sx1 - sx0, sy1 - sy0, 3, rowLength = fw, x = sx0, y = sy0)
                val rect = floatArrayOf(sx0.toFloat() / fw, sy0.toFloat() / fh, (sx1 - sx0).toFloat() / fw, (sy1 - sy0).toFloat() / fh)
                val ew = ex1 - ex0
                val eh = ey1 - ey0
                val dec = passFloat(RawShaders.DECODE, mapOf("src" to src, "gainTex" to gainTex),
                    decodeU + tileU(ex0, ey0, W, H) + ("srcRect" to rect), ew, eh)
                read(chain(dec, ex0, ey0, ew, eh, W, H, v, shared, 1f))
            }
            canvas.drawBitmap(piece, Rect(x0 - ex0, y0 - ey0, x0 - ex0 + cw, y0 - ey0 + ch), Rect(x0, y0, x0 + cw, y0 + ch), null)
            piece.recycle()
        }
        return out
    }

    /**
     * 一块（或整张预览）的逐像素链：降噪 → 影调 → 锐化 → 去朦胧 → 清晰度 / 纹理 → 收尾。
     * scale = 当前输出相对原尺寸的比例（降噪、锐化的半径按原尺寸像素给）。
     */
    private fun GpuScope.chain(
        dec: Tex, ox: Int, oy: Int, w: Int, h: Int, W: Int, H: Int, v: Values, shared: Shared, scale: Float,
    ): Tex {
        val t = tileU(ox, oy, W, H)
        val dim = max(W, H).toFloat()
        var cur = dec

        val lum = v[RawKey.luminanceNoise]
        if (lum > 0f) {
            cur = passFloat(RawShaders.LUMA_NR, mapOf("src" to cur), t + mapOf(
                "radius" to max(1f, (2f + 3f * lum) * scale), "sigmaR" to 0.02f + 0.08f * lum, "amount" to sqrt(min(lum, 1f)),
            ), w, h)
        }
        val moire = v[RawKey.moire]
        if (moire > 0f) {
            cur = passFloat(RawShaders.CHROMA_NR, mapOf("src" to cur), t + mapOf(
                "radius" to max(1f, (1.5f + 3f * moire) * scale), "amount" to min(1f, moire),
            ), w, h)
        }
        val colorNR = v[RawKey.colorNoise]
        if (colorNR > 0f) {
            cur = passFloat(RawShaders.CHROMA_NR, mapOf("src" to cur), t + mapOf(
                "radius" to max(1.5f, (3f + 12f * colorNR) * scale), "amount" to min(1f, colorNR * 1.5f),
            ), w, h)
        }

        cur = passFloat(RawShaders.TONE, mapOf("src" to cur, "ab" to shared.ab), shared.toneU + t, w, h)

        val sharp = v[RawKey.sharpness]
        if (sharp > 0f) {
            val detail = v[RawKey.detail]
            val sigma = max(0.6f, (0.7f + 0.15f * detail) * scale)
            val blurred = gauss(cur, sigma)
            cur = passFloat(RawShaders.SHARPEN, mapOf("src" to cur, "blurred" to blurred), t + mapOf(
                "amount" to sharp * 1.6f, "threshold" to 0.012f / (1f + detail),
            ), w, h)
        }

        val dehaze = v[RawKey.dehaze]
        if (dehaze != 0f) {
            cur = passFloat(RawShaders.DEHAZE, mapOf("src" to cur, "veil" to shared.veil), t + ("amount" to dehaze), w, h)
        }

        val clarity = v[RawKey.clarity]
        val texture = v[RawKey.texture]
        if (clarity != 0f || texture != 0f) {
            val small = if (texture != 0f) gauss(cur, dim * 0.003f) else cur
            cur = passFloat(RawShaders.LOCAL, mapOf("src" to cur, "big" to (if (clarity != 0f) shared.big else cur), "small" to small), t + mapOf(
                "clarity" to clarity, "textureAmount" to texture,
            ), w, h)
        }

        return pass(RawShaders.FINISH, mapOf("src" to cur), t + finishUniforms(v, dim), w, h)
    }

    /** iOS CIGaussianBlur 的 radius ≈ sigma；框架的 blur 用窗口半径（sigma × 2.5）。浮点纹理模糊后仍是浮点。 */
    private fun GpuScope.gauss(t: Tex, sigma: Float): Tex = blur(t, sigma * 2.5f)

    /** 引导滤波的系数图（iOS RawLinearFilter 的基底层，在 1/4 尺寸上算）。 */
    private fun GpuScope.guided(dec: Tex, dim: Float): Tex {
        val log = passFloat(RawShaders.LOG_LUMA, mapOf("src" to dec), emptyMap(), dec.width, dec.height)
        val small = shrinkFloat(log, max(1, (dec.width * 0.25f).toInt()), max(1, (dec.height * 0.25f).toInt()))
        val radius = dim * 0.25f * 0.022f
        val moments = passFloat(RawShaders.MOMENTS, mapOf("src" to small), emptyMap(), small.width, small.height)
        val coeff = passFloat(RawShaders.COEFFICIENTS, mapOf("src" to gauss(moments, radius)),
            mapOf("eps" to 0.002f, "follow" to 0.6f), small.width, small.height)
        return gauss(coeff, radius)
    }

    private fun GpuScope.shrinkFloat(t: Tex, tw: Int, th: Int): Tex {
        var cur = t
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            cur = passFloat(RawShaders.COPY, mapOf("src" to cur), emptyMap(), max(1, cur.width / 2), max(1, cur.height / 2))
        }
        if (cur.width != tw || cur.height != th) cur = passFloat(RawShaders.COPY, mapOf("src" to cur), emptyMap(), tw, th)
        return cur
    }

    private fun tileU(ox: Int, oy: Int, W: Int, H: Int): Map<String, Any> = mapOf(
        "tileOrigin" to floatArrayOf(ox.toFloat(), oy.toFloat()),
        "fullSize" to floatArrayOf(W.toFloat(), H.toFloat()),
    )

    private fun toSensor(x: Float, y: Float, flip: Int): Pair<Float, Float> {
        var u = x
        var v = y
        if (flip and 4 != 0) { val t = u; u = v; v = t }
        if (flip and 2 != 0) v = 1 - v
        if (flip and 1 != 0) u = 1 - u
        return u to v
    }

    // —— 各步的参数 ——

    private fun decodeUniforms(dev: RawDeveloper, v: Values, raw: Map<RawKey, Float>, lens: Boolean): Map<String, Any> {
        val info = dev.info
        val ops = dev.opcodes
        val u = HashMap<String, Any>()
        u["flip"] = info.flip
        val g = ops.gain
        u["useGain"] = if (g != null) 1 else 0
        u["gainOrigin"] = floatArrayOf(g?.originX ?: 0f, g?.originY ?: 0f)
        u["gainSpacing"] = floatArrayOf(g?.spacingX ?: 1f, g?.spacingY ?: 1f)
        u["camM"] = RawColor.gl(info.rgbCam)
        u["camMinv"] = RawColor.gl(RawColor.invert(info.rgbCam))
        val warp = ops.warp
        u["warpOn"] = if (lens && warp != null) 1 else 0
        u["warpK"] = warp?.k ?: FloatArray(18).also { it[0] = 1f; it[6] = 1f; it[12] = 1f }
        u["warpC"] = floatArrayOf(warp?.cx ?: 0.5f, warp?.cy ?: 0.5f)
        val vig = ops.vignette
        u["vigOn"] = if (lens && vig != null) 1 else 0
        u["vigK"] = vig?.k ?: FloatArray(5)
        u["vigC"] = floatArrayOf(vig?.cx ?: 0.5f, vig?.cy ?: 0.5f)
        u["vigFirst"] = if (ops.vignetteFirst) 1 else 0
        u["sensorAspect"] = floatArrayOf(info.sensorWidth.toFloat().coerceAtLeast(1f), info.sensorHeight.toFloat().coerceAtLeast(1f))
        u["wb"] = RawColor.gl(dev.whiteBalanceMatrix(raw))
        // 曝光：滑块 + DNG 的基准曝光（其他 RAW 补一点，接近相机直出的亮度）+ 高光余量的归一
        val base = if (!info.linearized) 0.0 else if (info.isDng) info.baselineExposure else 0.35
        u["gainExp"] = (2.0.pow(v[RawKey.exposure] + base) * info.headroom).toFloat()
        return u
    }

    private fun toneUniforms(v: Values, linearOn: Boolean): Map<String, Any> {
        val toneOn = v[RawKey.contrast] != 0f || RawKey.curveKeys.any { v[it] != 0f }
        return mapOf(
            "p" to floatArrayOf(v[RawKey.highlights], v[RawKey.shadows], v[RawKey.whites], v[RawKey.blacks]),
            "linearOn" to if (linearOn) 1 else 0,
            "localTone" to v[RawKey.localTone],
            "rawContrast" to v[RawKey.rawContrast],
            "boost" to v[RawKey.boost],
            "boostShadow" to v[RawKey.boostShadow],
            "toneOn" to if (toneOn) 1 else 0,
            "contrast" to v[RawKey.contrast],
            "curve" to floatArrayOf(v[RawKey.curveHighlights], v[RawKey.curveLights], v[RawKey.curveDarks], v[RawKey.curveShadows]),
        )
    }

    private fun finishUniforms(v: Values, dim: Float): Map<String, Any> {
        val u = HashMap<String, Any>()
        u["vibrance"] = v[RawKey.vibrance]
        u["saturation"] = v[RawKey.saturation]
        val first = listOf(MixColor.RED, MixColor.ORANGE, MixColor.YELLOW, MixColor.GREEN)
        val second = listOf(MixColor.AQUA, MixColor.BLUE, MixColor.PURPLE, MixColor.MAGENTA)
        fun vec(ch: ColorChannel, colors: List<MixColor>) = FloatArray(4) { v[RawKey.mix(colors[it], ch)] }
        val mixOn = MixColor.entries.any { c -> ColorChannel.entries.any { v[RawKey.mix(c, it)] != 0f } }
        u["mixOn"] = if (mixOn) 1 else 0
        u["h1"] = vec(ColorChannel.HUE, first); u["h2"] = vec(ColorChannel.HUE, second)
        u["s1"] = vec(ColorChannel.SAT, first); u["s2"] = vec(ColorChannel.SAT, second)
        u["l1"] = vec(ColorChannel.LUM, first); u["l2"] = vec(ColorChannel.LUM, second)
        val gradeOn = GradeZone.entries.any { v[RawKey.grade(it, ColorChannel.SAT)] != 0f || v[RawKey.grade(it, ColorChannel.LUM)] != 0f }
        u["gradeOn"] = if (gradeOn) 1 else 0
        val tints = GradeZone.entries.map { tintOffset(v[RawKey.grade(it, ColorChannel.HUE)], v[RawKey.grade(it, ColorChannel.SAT)]) }
        u["tSh"] = tints[0]; u["tMid"] = tints[1]; u["tHi"] = tints[2]; u["tAll"] = tints[3]
        u["gLum"] = FloatArray(4) { v[RawKey.grade(GradeZone.entries[it], ColorChannel.LUM)] }
        u["gOpts"] = floatArrayOf(v[RawKey.gradeBlending], v[RawKey.gradeBalance])
        u["vignette"] = v[RawKey.vignette]
        u["vignetteMid"] = v[RawKey.vignetteMid]
        u["grain"] = v[RawKey.grain]
        u["grainScale"] = max(1f, dim / 1100f) * (0.6f + v[RawKey.grainSize] * 2.4f)
        return u
    }

    /** 分级的色偏：色相 hue（度）的纯色减去它的亮度，乘饱和度 × 0.3。 */
    private fun tintOffset(hue: Float, saturation: Float): FloatArray {
        if (saturation <= 0f) return floatArrayOf(0f, 0f, 0f)
        val hsv = floatArrayOf(((hue / 360f) % 1f + 1f) % 1f * 360f, 1f, 1f)
        val c = Color.HSVToColor(hsv)
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val k = saturation * 0.3f
        return floatArrayOf((r - luma) * k, (g - luma) * k, (b - luma) * k)
    }

    /** 镜头畸变最大会把采样点挪多远（传感器归一化坐标），分块取数据时多取这么多。 */
    private fun warpMargin(dev: RawDeveloper): Float {
        val w = dev.opcodes.warp ?: return 0f
        val ax = dev.info.sensorWidth.toFloat().coerceAtLeast(1f)
        val ay = dev.info.sensorHeight.toFloat().coerceAtLeast(1f)
        val cx = w.cx * ax
        val cy = w.cy * ay
        val m = maxOf(hypot(cx, cy), hypot(cx - ax, cy), hypot(cx, cy - ay), hypot(cx - ax, cy - ay))
        var worst = 0f
        for (j in 0..16) for (i in 0..16) {
            val sx = i / 16f
            val sy = j / 16f
            for (p in 0..2) {
                val b = p * 6
                val dx = (sx * ax - cx) / m
                val dy = (sy * ay - cy) / m
                val r2 = dx * dx + dy * dy
                val f = w.k[b] + r2 * (w.k[b + 1] + r2 * (w.k[b + 2] + r2 * w.k[b + 3]))
                val tx = w.k[b + 4] * 2 * dx * dy + w.k[b + 5] * (r2 + 2 * dx * dx)
                val tyv = w.k[b + 5] * 2 * dx * dy + w.k[b + 4] * (r2 + 2 * dy * dy)
                val qx = (cx + (dx * f + tx) * m) / ax
                val qy = (cy + (dy * f + tyv) * m) / ay
                worst = max(worst, max(abs(qx - sx), abs(qy - sy)))
            }
        }
        return worst.coerceAtMost(0.05f) + 0.002f
    }

    private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

    private fun halfBuffer(f: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(f.size * 2).order(ByteOrder.nativeOrder())
        val s = b.asShortBuffer()
        for (x in f) s.put(Half.toHalf(x))
        return b
    }
}
