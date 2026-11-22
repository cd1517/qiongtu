package com.taoo.xiutu.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 位图小工具。所有函数都不改输入图，返回新图（或原图本身）。 */
object Img {
    /** 读图：自动转正方向，转成 ARGB_8888 的软件位图。maxSide 不为空时解码时就缩小。 */
    fun decode(context: Context, uri: Uri, maxSide: Int? = null): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        decodeSource(src, maxSide)
    }.getOrNull()

    fun decode(file: File, maxSide: Int? = null): Bitmap? = runCatching {
        decodeSource(ImageDecoder.createSource(file), maxSide)
    }.getOrNull()

    fun decode(bytes: ByteArray, maxSide: Int? = null): Bitmap? = runCatching {
        decodeSource(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), maxSide)
    }.getOrElse { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { soft(it) } }

    private fun decodeSource(src: ImageDecoder.Source, maxSide: Int?): Bitmap =
        ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = false
            d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            if (maxSide != null) {
                val long = max(info.size.width, info.size.height)
                if (long > maxSide) {
                    val k = maxSide.toFloat() / long
                    d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
                }
            }
        }.let { soft(it) }

    /** 保证是 ARGB_8888 软件位图。 */
    fun soft(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    /** 长边超过 maxSide 时等比缩小（高质量）。 */
    fun downscale(b: Bitmap, maxSide: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxSide) return b
        val k = maxSide.toFloat() / long
        var cur = b
        // 一次缩太多会有锯齿：每次最多缩一半
        while (max(cur.width, cur.height) / 2 > maxSide) {
            cur = Bitmap.createScaledBitmap(cur, (cur.width / 2).coerceAtLeast(1), (cur.height / 2).coerceAtLeast(1), true)
        }
        return Bitmap.createScaledBitmap(cur, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    fun scaled(b: Bitmap, w: Int, h: Int): Bitmap =
        if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w.coerceAtLeast(1), h.coerceAtLeast(1), true)

    /** 按归一化矩形（原点左上）裁一块。 */
    fun cropNormalized(b: Bitmap, r: RectF): Bitmap {
        val x = (r.left * b.width).roundToInt().coerceIn(0, b.width - 1)
        val y = (r.top * b.height).roundToInt().coerceIn(0, b.height - 1)
        val w = (r.width() * b.width).roundToInt().coerceIn(1, b.width - x)
        val h = (r.height() * b.height).roundToInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    /** 居中裁成指定宽高比。 */
    fun centerCrop(b: Bitmap, aspect: Float): Bitmap {
        var w = b.width.toFloat()
        var h = b.height.toFloat()
        if (w / h > aspect) w = h * aspect else h = w / aspect
        val x = ((b.width - w) / 2).roundToInt()
        val y = ((b.height - h) / 2).roundToInt()
        return Bitmap.createBitmap(b, x, y, w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    /** 缩略图：居中裁成 aspect，再缩到长边 side。 */
    fun thumb(b: Bitmap, side: Int, aspect: Float? = null): Bitmap {
        val c = if (aspect != null) centerCrop(b, aspect) else b
        return downscale(c, side)
    }

    /** 把 fg 画到 base 上（新图）。dst 为 null 时铺满。 */
    fun drawOver(base: Bitmap, fg: Bitmap, dst: RectF? = null, alpha: Int = 255): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        c.drawBitmap(fg, null, dst ?: RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), p)
        return out
    }

    fun rotate(b: Bitmap, degrees: Float, flipX: Boolean = false): Bitmap {
        val m = Matrix()
        if (flipX) m.postScale(-1f, 1f)
        m.postRotate(degrees)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun jpeg(b: Bitmap, quality: Int = 92): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun png(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun rect(b: Bitmap) = Rect(0, 0, b.width, b.height)

    fun minSide(b: Bitmap) = min(b.width, b.height)
}
