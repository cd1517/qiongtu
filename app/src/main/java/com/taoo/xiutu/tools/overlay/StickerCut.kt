package com.taoo.xiutu.tools.overlay

import android.graphics.Bitmap
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.ml.Mask8
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 贴纸图片的收尾处理：规整成带透明通道的位图、把纯色背景抠掉、裁掉四周的空白。全部在本机算。 */
object StickerCut {
    private class Px(val data: IntArray, val w: Int, val h: Int)

    /** 读成 ARGB 像素（不预乘），长边不超过 maxSide。 */
    private fun pixels(image: Bitmap, maxSide: Int): Px? {
        if (image.width <= 0 || image.height <= 0) return null
        val b = Img.downscale(Img.soft(image), maxSide)
        val data = IntArray(b.width * b.height)
        b.getPixels(data, 0, b.width, 0, 0, b.width, b.height)
        return Px(data, b.width, b.height)
    }

    private fun image(p: Px, crop: IntArray? = null): Bitmap? = runCatching {
        val whole = Bitmap.createBitmap(p.data, p.w, p.h, Bitmap.Config.ARGB_8888)
        if (crop == null) whole else Bitmap.createBitmap(whole, crop[0], crop[1], crop[2], crop[3])
    }.getOrNull()

    /** 不透明内容的外接框（x, y, w, h），四周留一点余量。全透明返回 null。 */
    private fun contentBox(p: Px): IntArray? {
        var minX = p.w
        var minY = p.h
        var maxX = -1
        var maxY = -1
        for (y in 0 until p.h) {
            val row = y * p.w
            for (x in 0 until p.w) {
                if ((p.data[row + x] ushr 24) > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val pad = max(2, max(p.w, p.h) / 60)
        val x0 = max(0, minX - pad)
        val y0 = max(0, minY - pad)
        val x1 = min(p.w - 1, maxX + pad)
        val y1 = min(p.h - 1, maxY + pad)
        return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** 图里有没有成片的透明区域。 */
    fun hasTransparency(image: Bitmap): Boolean {
        if (!image.hasAlpha()) return false
        val p = pixels(image, 96) ?: return false
        var clear = 0
        for (c in p.data) if ((c ushr 24) < 128) clear++
        return clear * 50 > p.w * p.h
    }

    /** 规整成普通的 ARGB 位图（限制长边），不改内容。 */
    fun normalized(image: Bitmap, maxSide: Int = 1536): Bitmap? = runCatching { Img.downscale(Img.soft(image), maxSide) }.getOrNull()

    /** 已经是透明背景的图：只把四周的空白裁掉。 */
    fun trimmed(image: Bitmap, maxSide: Int = 1536): Bitmap? {
        val p = pixels(image, maxSide) ?: return null
        val box = contentBox(p) ?: return null
        return image(p, box)
    }

    /**
     * AI 生成的贴纸的收尾：已经是透明背景就只裁边；
     * 否则认为它画在一块纯色背景上，把和四边连着的那片背景抠掉再裁边。背景不是纯色时返回 null。
     */
    fun finish(image: Bitmap, maxSide: Int = 1024): Bitmap? {
        if (hasTransparency(image)) return trimmed(image, maxSide)
        val p = pixels(image, maxSide) ?: return null
        if (!keyOutBackground(p)) return null
        val box = contentBox(p) ?: return null
        return image(p, box)
    }

    /** 把和图片四边连着的纯色背景变透明。四边的颜色不统一（不是纯色背景）就不动，返回 false。 */
    private fun keyOutBackground(b: Px): Boolean {
        val w = b.w
        val h = b.h
        if (w < 8 || h < 8) return false
        val d = b.data
        // 背景色 = 四条边上像素的中位数
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { border += x; border += (h - 1) * w + x }
        for (y in 0 until h) { border += y * w; border += y * w + w - 1 }
        fun median(shift: Int): Int {
            val v = border.map { (d[it] shr shift) and 0xFF }.sorted()
            return v[v.size / 2]
        }
        val br = median(16)
        val bg = median(8)
        val bb = median(0)
        val tol = 48
        fun isBackground(p: Int): Boolean {
            val c = d[p]
            return abs(((c shr 16) and 0xFF) - br) <= tol && abs(((c shr 8) and 0xFF) - bg) <= tol && abs((c and 0xFF) - bb) <= tol
        }
        // 四边大部分都得是这个颜色，才算"纯色背景"
        val matched = border.count { isBackground(it) }
        if (matched * 10 < border.size * 7) return false

        // 从四边往里灌：0 = 还没看，1 = 背景
        val mark = ByteArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun push(x: Int, y: Int) {
            val p = y * w + x
            if (mark[p].toInt() == 0 && isBackground(p)) {
                mark[p] = 1
                stack[top++] = p
            }
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            if (x > 0) push(x - 1, y)
            if (x < w - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1)
            if (y < h - 1) push(x, y + 1)
        }
        // 保留的部分 = 255。往里收三个像素（去掉沾了背景色的那圈边），再把边缘柔化一点。
        val keep = ByteArray(w * h) { if (mark[it].toInt() == 0) 255.toByte() else 0 }
        repeat(3) {
            val before = keep.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                if (before[p].toInt() == 0) continue
                val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    before[p - 1].toInt() == 0 || before[p + 1].toInt() == 0 || before[p - w].toInt() == 0 || before[p + w].toInt() == 0
                if (edge) keep[p] = 0
            }
        }
        val soft = Mask8(keep.copyOf(), w, h)
        Mask8.blur(soft, 1f)
        for (p in 0 until w * h) {
            // 只往里柔化：原来就不保留的地方保持透明
            val m = if (keep[p].toInt() == 0) 0 else soft[p]
            if (m >= 255) continue
            val c = d[p]
            val a = (c ushr 24) * m / 255
            d[p] = (a shl 24) or (c and 0xFFFFFF)
        }
        return true
    }
}
