package com.taoo.xiutu.ai

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 创意玩法按钮上的小图：能在本机大致模拟的画风（漫画、像素、素描、水彩……）用当前照片算一张，只是给个印象。
 * 模拟不了的（加分身、加东西、换场景）返回 null，按钮上画色块和图标。照 iOS CreativePreview.swift，小图直接在 CPU 上算。
 */
object CreativePreview {
    /** 小图上的一张浮点图（RGB 0…1）。 */
    private class Px(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        val n get() = w * h
        fun copy() = Px(w, h, r.copyOf(), g.copyOf(), b.copyOf())
        inline fun map(f: (Float, Float, Float, FloatArray) -> Unit): Px {
            val o = Px(w, h, FloatArray(n), FloatArray(n), FloatArray(n))
            val t = FloatArray(3)
            for (i in 0 until n) {
                f(r[i], g[i], b[i], t)
                o.r[i] = t[0]; o.g[i] = t[1]; o.b[i] = t[2]
            }
            return o
        }
    }

    fun render(title: String, bitmap: Bitmap): Bitmap? {
        val img = from(bitmap)
        val long = max(img.w, img.h).toFloat()
        val out = when (title) {
            "日漫" -> anime(img, 8, 1.45f, 0.06f, 1f)
            "韩漫" -> anime(img, 14, 1.1f, 0.12f, 0.5f)
            "温暖动画" -> warm(anime(img, 10, 1.35f, 0.08f, 0.7f), 0.25f)
            "少女漫画" -> {
                val soft = mix(mono(img), paper(img), 0.35f)
                multiply(ink(img, 1f), tone(soft, 0.12f, 0.95f))
            }
            "黑白漫画" -> {
                val m = tone(mono(img), 0.05f, 1.3f)
                multiply(ink(img, 1f), mix(m, dots(m, long / 55f, 0.6f), 0.5f))
            }
            "美漫" -> multiply(ink(img, 1f), posterize(saturate(smooth(img, 1), 1.7f, 1.15f, 0.04f), 5))
            "城市流行" -> {
                val base = posterize(saturate(img, 1.6f, 1.2f), 6)
                val tint = falseColor(img, floatArrayOf(0.18f, 0.08f, 0.4f), floatArrayOf(1f, 0.6f, 0.78f))
                mix(base, tint, 0.55f)
            }
            "厚涂插画" -> unsharp(saturate(smooth(img, 3), 1.3f, 1.1f), 2.5f, 0.8f)
            "像素小人" -> pixellate(saturate(img, 1.35f, 1.1f, 0.03f), long / 42f)
            "蜡笔涂鸦" -> multiply(ink(img, 0.9f), mix(paper(img), saturate(blur(img, 1.5f), 1.6f, 1.05f, 0.08f), 0.55f))
            "潦草涂鸦" -> multiply(ink(img, 0.85f), mix(paper(img), saturate(img, 1.8f, 1f), 0.18f))
            "简笔画" -> ink(img, 1f)
            "治愈绘本" -> {
                val soft = posterize(saturate(blur(img, 1.5f), 1.25f, 0.95f, 0.1f), 10)
                warm(multiply(ink(img, 0.45f), mix(soft, paper(img), 0.15f)), 0.15f)
            }
            "水彩" -> {
                val wash = mix(saturate(smooth(img, 2), 1.3f, 0.95f, 0.08f), paper(img), 0.25f)
                multiply(ink(img, 0.5f), wash)
            }
            "油画" -> unsharp(saturate(smooth(img, 4), 1.25f, 1.15f), 3f, 1f)
            "印象派" -> pointillize(saturate(img, 1.3f, 1f, 0.04f), long / 70f, blur(img, 3f))
            "素描" -> sketch(img)
            "国风水墨" -> warm(mix(sketch(img), paper(img), 0.25f), 0.12f)
            "彩铅" -> mix(sketch(img), saturate(img, 1.1f, 1f), 0.45f)
            "浮世绘" -> warm(multiply(ink(img, 1f), posterize(saturate(img, 1.2f, 1.05f, 0.06f), 5)), 0.2f)
            "波普" -> posterize(saturate(img, 2.2f, 1.3f, 0.05f), 3)
            "版画" -> mono(img).map { r, _, _, o -> val v = if (r > 0.32f) 1f else 0f; o[0] = v; o[1] = v; o[2] = v }
            else -> return null
        }
        return to(out)
    }

    // MARK: 小工具

    private fun from(bm: Bitmap): Px {
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val o = Px(w, h, FloatArray(w * h), FloatArray(w * h), FloatArray(w * h))
        for (i in px.indices) {
            val c = px[i]
            o.r[i] = ((c shr 16) and 0xFF) / 255f
            o.g[i] = ((c shr 8) and 0xFF) / 255f
            o.b[i] = (c and 0xFF) / 255f
        }
        return o
    }

    private fun to(p: Px): Bitmap {
        val px = IntArray(p.n)
        fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        for (i in px.indices) px[i] = (0xFF shl 24) or (q(p.r[i]) shl 16) or (q(p.g[i]) shl 8) or q(p.b[i])
        return Bitmap.createBitmap(px, p.w, p.h, Bitmap.Config.ARGB_8888)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun anime(img: Px, levels: Int, s: Float, bright: Float, k: Float): Px =
        multiply(ink(img, k), posterize(saturate(smooth(img, 2), s, 1.05f, bright), levels))

    /** 白底黑线的线稿，k 越小线越淡。 */
    private fun ink(img: Px, k: Float): Px {
        val lines = mono(tone(sketch(img), 0.08f, 1.8f))
        return if (k >= 1f) lines else mix(paper(img), lines, k)
    }

    private fun mono(img: Px) = img.map { r, g, b, o -> val v = luma(r, g, b); o[0] = v; o[1] = v; o[2] = v }

    private fun multiply(top: Px, base: Px) = Px(
        base.w, base.h,
        FloatArray(base.n) { top.r[it] * base.r[it] }, FloatArray(base.n) { top.g[it] * base.g[it] }, FloatArray(base.n) { top.b[it] * base.b[it] },
    )

    /** CIColorControls：饱和度 → 亮度 → 对比度。 */
    private fun saturate(img: Px, s: Float, contrast: Float, bright: Float = 0f) = img.map { r, g, b, o ->
        val l = luma(r, g, b)
        o[0] = ((l + (r - l) * s + bright) - 0.5f) * contrast + 0.5f
        o[1] = ((l + (g - l) * s + bright) - 0.5f) * contrast + 0.5f
        o[2] = ((l + (b - l) * s + bright) - 0.5f) * contrast + 0.5f
    }

    private fun tone(img: Px, bright: Float, contrast: Float) = saturate(img, 1f, contrast, bright)

    /** 铅笔素描：灰度和它反相模糊后做颜色减淡。 */
    private fun sketch(img: Px): Px {
        val gray = mono(img)
        val inv = blur(gray.map { r, _, _, o -> val v = 1f - r; o[0] = v; o[1] = v; o[2] = v }, max(img.w, img.h) / 60f)
        val dodge = Px(img.w, img.h, FloatArray(img.n), FloatArray(img.n), FloatArray(img.n))
        for (i in 0 until img.n) {
            val v = min(1f, gray.r[i] / max(1e-3f, 1f - inv.r[i]))
            val c = (v - 0.5f) * 1.25f + 0.5f
            dodge.r[i] = c; dodge.g[i] = c; dodge.b[i] = c
        }
        return dodge
    }

    private fun posterize(img: Px, levels: Int): Px {
        val n = (levels - 1).coerceAtLeast(1).toFloat()
        return img.map { r, g, b, o ->
            o[0] = (r.coerceIn(0f, 1f) * n).roundToInt() / n
            o[1] = (g.coerceIn(0f, 1f) * n).roundToInt() / n
            o[2] = (b.coerceIn(0f, 1f) * n).roundToInt() / n
        }
    }

    /** 3×3 中值滤波做几遍。 */
    private fun smooth(img: Px, times: Int): Px {
        var cur = img
        repeat(times) { cur = median(cur) }
        return cur
    }

    private fun median(img: Px): Px {
        val w = img.w
        val h = img.h
        val o = img.copy()
        val win = FloatArray(9)
        for (ch in 0..2) {
            val src = when (ch) { 0 -> img.r; 1 -> img.g; else -> img.b }
            val dst = when (ch) { 0 -> o.r; 1 -> o.g; else -> o.b }
            for (y in 0 until h) for (x in 0 until w) {
                var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    win[k++] = src[yy * w + xx]
                }
                win.sort()
                dst[y * w + x] = win[4]
            }
        }
        return o
    }

    /** 三次盒式模糊近似高斯（r 约等于 sigma）。 */
    private fun blur(img: Px, sigma: Float): Px {
        val rad = max(1, (sigma * 0.9f).roundToInt())
        val o = img.copy()
        for (arr in listOf(o.r, o.g, o.b)) repeat(3) { box(arr, img.w, img.h, rad) }
        return o
    }

    private fun box(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(max(w, h))
        val d = 2f * r + 1f
        for (y in 0 until h) {
            var s = 0f
            for (i in -r..r) s += a[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = s / d
                s += a[y * w + (x + r + 1).coerceIn(0, w - 1)] - a[y * w + (x - r).coerceIn(0, w - 1)]
            }
            System.arraycopy(tmp, 0, a, y * w, w)
        }
        for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) s += a[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = s / d
                s += a[(y + r + 1).coerceIn(0, h - 1) * w + x] - a[(y - r).coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }

    private fun unsharp(img: Px, radius: Float, amount: Float): Px {
        val bl = blur(img, radius)
        return Px(
            img.w, img.h,
            FloatArray(img.n) { img.r[it] + amount * (img.r[it] - bl.r[it]) },
            FloatArray(img.n) { img.g[it] + amount * (img.g[it] - bl.g[it]) },
            FloatArray(img.n) { img.b[it] + amount * (img.b[it] - bl.b[it]) },
        )
    }

    private fun warm(img: Px, k: Float) = img.map { r, g, b, o ->
        o[0] = r * (1f + 0.16f * k)
        o[1] = g * (1f + 0.03f * k)
        o[2] = b * (1f - 0.2f * k)
    }

    private fun paper(img: Px) = Px(img.w, img.h, FloatArray(img.n) { 0.98f }, FloatArray(img.n) { 0.96f }, FloatArray(img.n) { 0.92f })

    private fun mix(a: Px, b: Px, t: Float) = Px(
        a.w, a.h,
        FloatArray(a.n) { a.r[it] * (1 - t) + b.r[it] * t },
        FloatArray(a.n) { a.g[it] * (1 - t) + b.g[it] * t },
        FloatArray(a.n) { a.b[it] * (1 - t) + b.b[it] * t },
    )

    private fun falseColor(img: Px, c0: FloatArray, c1: FloatArray) = img.map { r, g, b, o ->
        val l = luma(r, g, b).coerceIn(0f, 1f)
        o[0] = c0[0] + (c1[0] - c0[0]) * l
        o[1] = c0[1] + (c1[1] - c0[1]) * l
        o[2] = c0[2] + (c1[2] - c0[2]) * l
    }

    private fun pixellate(img: Px, size: Float): Px {
        val s = max(2, size.roundToInt())
        val o = img.copy()
        var by = 0
        while (by < img.h) {
            var bx = 0
            while (bx < img.w) {
                var sr = 0f; var sg = 0f; var sb = 0f; var c = 0
                for (y in by until min(by + s, img.h)) for (x in bx until min(bx + s, img.w)) {
                    val i = y * img.w + x
                    sr += img.r[i]; sg += img.g[i]; sb += img.b[i]; c++
                }
                for (y in by until min(by + s, img.h)) for (x in bx until min(bx + s, img.w)) {
                    val i = y * img.w + x
                    o.r[i] = sr / c; o.g[i] = sg / c; o.b[i] = sb / c
                }
                bx += s
            }
            by += s
        }
        return o
    }

    /** 半调网点：每格一个圆点，越暗点越大。 */
    private fun dots(img: Px, cell: Float, angle: Float): Px {
        val c = cos(angle)
        val s = sin(angle)
        val o = img.copy()
        for (y in 0 until img.h) for (x in 0 until img.w) {
            val i = y * img.w + x
            val u = (x * c + y * s) / cell
            val v = (-x * s + y * c) / cell
            val du = u - floor(u) - 0.5f
            val dv = v - floor(v) - 0.5f
            val d = sqrt(du * du + dv * dv)
            val dark = 1f - img.r[i].coerceIn(0f, 1f)
            val rad = sqrt(dark) * 0.62f
            val inside = ((rad - d) * 6f + 0.5f).coerceIn(0f, 1f)
            val vOut = 1f - inside
            o.r[i] = vOut; o.g[i] = vOut; o.b[i] = vOut
        }
        return o
    }

    /** 点彩：一格一个取中心颜色的圆点，空隙露出底图。 */
    private fun pointillize(img: Px, radius: Float, under: Px): Px {
        val o = under.copy()
        val step = max(2f, radius * 1.6f)
        var cy = step / 2
        var row = 0
        while (cy < img.h + step) {
            var cx = if (row % 2 == 0) step / 2 else step
            while (cx < img.w + step) {
                val sx = cx.toInt().coerceIn(0, img.w - 1)
                val sy = cy.toInt().coerceIn(0, img.h - 1)
                val si = sy * img.w + sx
                val r0 = radius
                for (y in max(0, (cy - r0).toInt())..min(img.h - 1, (cy + r0).toInt())) {
                    for (x in max(0, (cx - r0).toInt())..min(img.w - 1, (cx + r0).toInt())) {
                        val dx = x - cx
                        val dy = y - cy
                        if (abs(dx) + abs(dy) > r0 * 1.42f || dx * dx + dy * dy > r0 * r0) continue
                        val i = y * img.w + x
                        o.r[i] = img.r[si]; o.g[i] = img.g[si]; o.b[i] = img.b[si]
                    }
                }
                cx += step
            }
            cy += step * 0.87f
            row++
        }
        return o
    }
}
