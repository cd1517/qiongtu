package com.taoo.xiutu.ml

import android.graphics.Bitmap
import android.graphics.RectF
import com.taoo.xiutu.core.Img
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 本机抠图：把画面里的主体从背景里分出来，不联网。
 * 蒙版都是不透明灰度图（白 = 主体，黑 = 背景，读红色通道）。全部函数都会阻塞，在后台线程调用。
 */
object Cutout {
    /** 高斯模糊的半径换成 Mask8.blur 的半径。 */
    private const val GAUSS = 1.7f

    /** 灰度蒙版。识别不到主体时返回 null。安卓上用人像分割（iOS 还会先试系统的"主体识别"）。 */
    fun mask(image: Bitmap): Bitmap? = PersonSegmenter.mask(image)

    // MARK: AI 去背景

    /** 让 AI 把背景换成纯绿幕；拿到结果后只用它来算蒙版，贴到画面上的仍然是原图的像素。 */
    const val AI_PROMPT = "把背景全部换成纯绿色（#00FF00）的绿幕：一整块均匀的纯色，没有渐变、阴影、纹理和地面。" +
        "画面里的主体（人物、宠物或主要物品，连同他们手里拿的、身上带的东西）保持完全不变：" +
        "位置、大小、轮廓、姿势和颜色一点都不能动，要和原图逐像素对齐，画面比例不变。主体身上不要沾上绿色。"

    /** 从 AI 给的"绿幕图"里算出蒙版：绿幕的地方是黑，其余是白。主体几乎占不到画面（或者根本没换背景）时返回 nil。 */
    fun maskFromKeyed(image: Bitmap): Bitmap? {
        val src = Img.soft(image)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val greenness = g - max(r, b)
            val m = 1f - smoothstep(0.20f, 0.38f, greenness)
            out[i] = (m * 255f + 0.5f).toInt().toByte()
        }
        val mask = Mask8(out, w, h)
        // 去掉零星的小孔和毛边
        Mask8.blur(mask, max(w, h) * 0.0012f * GAUSS)
        val share = coverage(mask)
        return if (share > 0.01f && share < 0.97f) mask.toGrayBitmap() else null
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** 蒙版里白色占多少。 */
    private fun coverage(mask: Mask8): Float {
        val s = Mask8.resized(mask, 64, 64)
        var white = 0
        for (i in 0 until 64 * 64) if (s[i] > 128) white++
        return white / (64f * 64f)
    }

    /**
     * 把蒙版缩放到 w×h。feather 是边缘羽化的宽度（按长边的比例，0 = 不羽化），
     * 羽化只往主体里面收，不会把背景的颜色带出来。
     */
    fun scaled(mask: Bitmap, w: Int, h: Int, feather: Float = 0f): Mask8 {
        // grayOf 每次都是新数组，可以直接原地改
        val m = Mask8.resized(Mask8.grayOf(mask), w, h)
        if (feather <= 0f) return m
        Mask8.blur(m, max(w, h) * feather * GAUSS)
        Mask8.remap(m) { it * 2 - 255 }
        return m
    }

    /** 按蒙版把背景变透明。 */
    fun apply(mask: Bitmap, image: Bitmap, feather: Float = 0f): Bitmap {
        val src = Img.soft(image)
        val w = src.width
        val h = src.height
        val m = scaled(mask, w, h, feather)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = ((c ushr 24) * m[i] + 127) / 255
            px[i] = (a shl 24) or (c and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 抠出来的图（带透明通道）。trim = true 时把四周的空白裁掉。 */
    fun cut(image: Bitmap, mask: Bitmap, feather: Float = 0f, trim: Boolean = false): Bitmap? = runCatching {
        var out = apply(mask, image, feather)
        if (trim) {
            val box = bounds(mask)
            if (box != null) {
                val x0 = (box.left * out.width).roundToInt().coerceIn(0, out.width - 1)
                val y0 = (box.top * out.height).roundToInt().coerceIn(0, out.height - 1)
                val x1 = (box.right * out.width).roundToInt().coerceIn(x0 + 1, out.width)
                val y1 = (box.bottom * out.height).roundToInt().coerceIn(y0 + 1, out.height)
                if (x1 - x0 >= 2 && y1 - y0 >= 2) out = Bitmap.createBitmap(out, x0, y0, x1 - x0, y1 - y0)
            }
        }
        out
    }.getOrNull()

    /** 主体的外接框（归一化坐标，原点左上角），四周留一点余量。 */
    fun bounds(mask: Bitmap): RectF? {
        val w = 128
        val h = 128
        val m = Mask8.resized(Mask8.grayOf(mask), w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (m[y * w + x] > 40) {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return null
        val pad = 0.015f
        val r = RectF(
            minX.toFloat() / w - pad, minY.toFloat() / h - pad,
            (maxX + 1).toFloat() / w + pad, (maxY + 1).toFloat() / h + pad,
        )
        return if (r.intersect(0f, 0f, 1f, 1f)) r else null
    }
}
