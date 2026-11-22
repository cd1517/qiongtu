package com.taoo.xiutu.tools.filter

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 拍立得的画法（iOS InstantParams.apply / applyReal）。坐标原点在左上角，光从左上来。
 * iOS 的合成是在线性光里做的；这里在 sRGB 上画，压黑色、提白色时把不透明度换算一下，看起来接近。
 * 软的东西（影子、虚边、起伏花样）都在缩小的画布上做再放大：大图也不慢，各种尺寸下是同一个样子。
 */
internal object InstantRender {
    private class Layout(
        val crop: Rect, val mm: Float, val w: Int, val h: Int,
        val sheet: RectF, val window: RectF, val g: InstantGeometry,
    )

    private fun layout(p: InstantParams, width: Int, height: Int): Layout? {
        val spec = p.format.spec ?: return null
        if (width < 4 || height < 4) return null
        val g = spec.geometry(p.rotated)
        val aspect = g.imageW / g.imageH
        var cw = width.toFloat()
        var ch = height.toFloat()
        if (width.toFloat() / height > aspect) cw = max(2f, (height * aspect).roundToInt().toFloat())
        else ch = max(2f, (width / aspect).roundToInt().toFloat())
        val t = p.shift.coerceIn(-1f, 1f)
        val x0 = ((width - cw) / 2 * (1 + t)).roundToInt().coerceIn(0, width - cw.toInt())
        val y0 = ((height - ch) / 2 * (1 + t)).roundToInt().coerceIn(0, height - ch.toInt())
        val mm = cw / g.imageW
        val left = max(1f, (g.left * mm).roundToInt().toFloat())
        val right = max(1f, (g.right * mm).roundToInt().toFloat())
        val top = max(1f, (g.top * mm).roundToInt().toFloat())
        val bottom = max(1f, (g.bottom * mm).roundToInt().toFloat())
        val pw = left + cw + right
        val ph = top + ch + bottom
        val short = min(pw, ph)
        val margin = when {
            p.real -> max(3f, (short * 0.075f).roundToInt().toFloat())
            p.shadow -> max(2f, (short * 0.09f).roundToInt().toFloat())
            else -> 0f
        }
        val sheet = RectF(margin, margin, margin + pw, margin + ph)
        val window = RectF(sheet.left + left, sheet.top + top, sheet.left + left + cw, sheet.top + top + ch)
        return Layout(Rect(x0, y0, x0 + cw.toInt(), y0 + ch.toInt()), mm, (pw + margin * 2).toInt(), (ph + margin * 2).toInt(), sheet, window, g)
    }

    fun apply(src: Bitmap, p: InstantParams): Bitmap {
        val l = layout(p, src.width, src.height) ?: return src
        val crop = Bitmap.createBitmap(src, l.crop.left, l.crop.top, l.crop.width(), l.crop.height())
        val shot = p.stock.develop(crop, p.intensity)
        return if (p.real) real(l, p, shot) else flat(l, p, shot)
    }

    // MARK: 普通 / 投影

    private fun flat(l: Layout, p: InstantParams, shot: Bitmap): Bitmap {
        val g = l.g
        val mm = l.mm
        val out = Bitmap.createBitmap(l.w, l.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paper = p.paper
        if (p.shadow) {
            c.drawColor(InstantParams.desk.argb)
            val radius = g.paperRadius * mm
            val drop = RectF(l.sheet).apply { offset(0f, l.sheet.top * 0.22f) }
            soft(c, l.w, l.h, 420f, l.sheet.top * 0.42f, BLACK, black(0.3f)) { mc, pt -> mc.drawRoundRect(drop, rr(drop, radius), rr(drop, radius), pt) }
            c.drawRoundRect(l.sheet, rr(l.sheet, radius), rr(l.sheet, radius), fill(paper.argb))
            toned(c, paper, l.sheet, radius)
        } else {
            c.drawColor(paper.argb)
            toned(c, paper, l.sheet, null)
        }
        // 画面放进相纸的窗口里。
        val radius = g.imageRadius * mm
        placeShot(c, shot, l.window, if (radius > 0.5f) radius else 0f)
        // 相纸的框比画面高出一点：画面边缘一圈很淡的内阴影，再加一道细的压线。
        softRing(c, l.w, l.h, l.window, radius, mm * 1.0f, mm * 0.5f, BLACK, black(0.17f))
        strokeMask(c, l.window, radius, max(1f, mm * 0.1f), BLACK, black(0.2f))
        caption(c, l, p)
        return out
    }

    /** 相纸不是一块死平的颜色：边角比中间略深、略暖一点（深色相纸是一层很淡的反光）。 */
    private fun toned(c: Canvas, paper: PaperColor, sheet: RectF, clipRadius: Float?, strength: Float = 1f) {
        val light = paper.isLight
        val tint = if (light) argb(0.07f * strength, 0.42f, 0.3f, 0.16f) else argb(white(0.014f * strength, paper), 1f, 1f, 1f)
        val clear = tint and 0x00FFFFFF
        val diag = hypot(sheet.width(), sheet.height())
        fun draw(paint: Paint) {
            if (clipRadius != null) c.drawRoundRect(sheet, rr(sheet, clipRadius), rr(sheet, clipRadius), paint)
            else c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), paint)
        }
        if (light) {
            draw(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(sheet.centerX(), sheet.centerY(), diag * 0.62f, intArrayOf(clear, clear, tint), floatArrayOf(0f, 0.3f / 0.62f, 1f), Shader.TileMode.CLAMP)
            })
            val c0 = argb(0.045f * strength, 0.42f, 0.3f, 0.16f)
            draw(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(sheet.right, sheet.bottom, sheet.left, sheet.top, c0, c0 and 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
        } else {
            draw(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(sheet.left, sheet.top, sheet.centerX(), sheet.centerY(), tint, clear, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun placeShot(c: Canvas, shot: Bitmap, window: RectF, radius: Float) {
        if (radius <= 0f) {
            c.drawBitmap(shot, null, window, Paint(Paint.FILTER_BITMAP_FLAG))
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = BitmapShader(shot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(window.width() / shot.width, window.height() / shot.height)
                postTranslate(window.left, window.top)
            })
        }
        c.drawRoundRect(window, rr(window, radius), rr(window, radius), paint)
    }

    // MARK: 写字

    private val captionFace: Typeface by lazy { Typeface.create("casual", Typeface.BOLD) }

    /** 宽边上的字。 */
    private fun caption(c: Canvas, l: Layout, p: InstantParams) {
        val text = p.caption.trim()
        if (text.isEmpty()) return
        val g = l.g
        val sheet = l.sheet
        val window = l.window
        val ink = if (p.paper.isLight) 0xFF2A2D36.toInt() else 0xFFECEAE4.toInt()
        // 字高跟着宽边走；宽边很窄的相纸（撕拉片）字也不能小到看不见。
        val band = if (g.chinOnRight) sheet.right - window.right else sheet.bottom - window.bottom
        val size = min(max(band * 0.42f, min(sheet.width(), sheet.height()) * 0.085f), band * 0.85f)
        if (size < 1f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = captionFace; color = ink }
        val drawH = size.coerceIn(12f, 320f)
        paint.textSize = drawH * 0.74f
        val fm = paint.fontMetrics
        val asc = -fm.ascent
        val desc = fm.descent
        val pad = 3f
        val tw = paint.measureText(text)
        val bw = tw + pad * 2
        val bh = asc + desc + pad * 2
        val maxWidth = if (g.chinOnRight) window.height() * 0.9f else window.width() * 0.92f
        var k = size / bh
        if (bw * k > maxWidth) k = maxWidth / bw
        c.save()
        if (g.chinOnRight) {
            c.translate(window.right + band / 2, window.centerY())
            c.rotate(-90f)
        } else {
            c.translate(window.centerX(), window.bottom + band / 2)
        }
        c.scale(k, k)
        c.drawText(text, -tw / 2, -bh / 2 + pad + asc, paint)
        c.restore()
    }

    // MARK: 实物

    /** 相纸像一张真的照片平放在扫描仪上扫出来的样子：有厚度、纸框比画面高一层、药膜边缘不整齐、贴着台面有影子。 */
    private fun real(l: Layout, p: InstantParams, shot: Bitmap): Bitmap {
        val g = l.g
        val mm = l.mm
        val sheet = l.sheet
        val window = l.window
        val w = l.w
        val h = l.h
        val surface = p.format.spec?.surface ?: InstantSurface.MATTE
        val paper = p.paper
        val light = paper.isLight
        val paperRadius = max(g.paperRadius, 0.55f) * mm
        val imageRadius = max(g.imageRadius, if (surface == InstantSurface.PEEL) 0.15f else 0.3f) * mm
        // 每种相纸的起伏花样错开。
        val n = p.format.ordinal
        val seedA = (11 + n * 37) * 1000 + 5 + n * 19
        val seedB = (301 + n * 13) * 1000 + 211 + n * 29
        val seedC = (97 + n * 23) * 1000 + 409 + n * 7

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // 扫描台：一头略暗一点。
        c.drawColor(p.bed.color.argb)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(w.toFloat(), h / 2f, w * 0.35f, h / 2f, argb(black(0.05f), 0f, 0f, 0f), 0, Shader.TileMode.CLAMP)
        })
        // 相纸贴着台面：四周一圈很窄的影子，背光的右、下两条边更重。
        val around = RectF(sheet).apply { inset(-mm * 0.25f, -mm * 0.25f) }
        soft(c, w, h, 1500f, mm * 0.9f, BLACK, black(0.2f)) { mc, pt -> mc.drawRoundRect(around, rr(around, paperRadius), rr(around, paperRadius), pt) }
        val drop = RectF(sheet).apply { offset(mm * 0.55f, mm * 0.65f) }
        soft(c, w, h, 1500f, mm * 0.4f, BLACK, black(0.5f)) { mc, pt -> mc.drawRoundRect(drop, rr(drop, paperRadius), rr(drop, paperRadius), pt) }

        // 相纸正面：边角发黄，纸面深浅不匀。
        val face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val f = Canvas(face)
        f.drawColor(paper.argb)
        toned(f, paper, sheet, null, if (light) 1.8f else 1f)
        val blot = Blotches(w, h, mm * 11, seedA)
        blot.paint(f, if (light) 0xFF8A7453.toInt() else WHITE, if (light) 0.05f else white(0.006f, paper))
        if (surface == InstantSurface.MATTE) {
            fibre(f, mm, if (light) 0.1f else 0.035f, g.chinOnRight)
            // 窗口外面一圈是压出来的边，比纸面略高。
            val rim = RectF(window).apply { inset(-mm * 0.9f, -mm * 0.9f) }
            val rimRadius = imageRadius + mm * 0.45f
            strokeMask(f, rim, rimRadius, mm * 0.9f, WHITE, if (light) 0.3f else white(0.01f, paper))
            band(f, w, h, rim, rimRadius, mm * 0.14f, lit = true, inside = true, color = WHITE, alpha = if (light) 0.5f else white(0.03f, paper))
            band(f, w, h, rim, rimRadius, mm * 0.16f, lit = false, inside = false, color = BLACK, alpha = black(0.09f))
        }
        // 宽边里药包压出来的那道棱（撕拉片没有）：上沿亮、下沿暗。
        if (surface != InstantSurface.PEEL) {
            val depth = if (surface == InstantSurface.MATTE) 0.27f else 0.17f
            val strength = if (surface == InstantSurface.MATTE) 1f else 0.6f
            val t = max(1f, (mm * 0.14f).roundToInt().toFloat())
            val lit: RectF
            val shade: RectF
            if (g.chinOnRight) {
                val x = (sheet.right - (sheet.right - window.right) * depth).roundToInt().toFloat()
                lit = RectF(x, sheet.top + mm * 3, x + t, sheet.bottom - mm * 3)
                shade = RectF(lit).apply { offset(t, 0f) }
            } else {
                val y = (sheet.bottom - (sheet.bottom - window.bottom) * depth).roundToInt().toFloat() - t
                lit = RectF(sheet.left + mm * 3, y, sheet.right - mm * 3, y + t)
                shade = RectF(lit).apply { offset(0f, t) }
            }
            f.drawRect(lit, fill(argb((if (light) 0.6f else white(0.03f, paper)) * strength, 1f, 1f, 1f)))
            f.drawRect(shade, fill(argb(black(0.11f * strength), 0f, 0f, 0f)))
        }

        // 画面：先整块放上去做完效果，最后按窗口的形状嵌进纸框。
        val pw = window.width().roundToInt()
        val ph = window.height().roundToInt()
        val picture = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val pc = Canvas(picture)
        pc.translate(-window.left, -window.top)
        pc.drawBitmap(face, 0f, 0f, null)
        pc.drawBitmap(shot, null, window, Paint(Paint.FILTER_BITMAP_FLAG))
        // 越靠边越暗一点。
        softRing(pc, w, h, window, imageRadius, mm * 1.8f, mm * 1.1f, BLACK, black(0.2f))
        // 表面的反光：光面相纸更明显。
        val sheenA = if (surface == InstantSurface.GLOSSY) 0.1f else 0.06f
        pc.drawRect(window, Paint().apply {
            shader = LinearGradient(window.left + window.width() * 0.1f, window.top, window.left + window.width() * 0.7f, window.bottom - window.height() * 0.3f,
                argb(sheenA, 1f, 1f, 1f), 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        // 药膜边缘不是一刀切的：一圈宽窄不匀的深色边。
        val wave = Blotches(w, h, mm * 2.4f, seedB)
        when (surface) {
            InstantSurface.MATTE -> {
                fringe(pc, window, wave, mm * 0.2f, mm * 0.2f, mm * 0.2f, mm * 0.16f, mm * 0.07f, 0xFF17120E.toInt(), black(0.7f))
                val streak = Blotches(w, h, mm * 3.4f, seedC)
                val onRight = g.chinOnRight
                fringe(pc, window, streak, 0f, if (onRight) mm * 0.6f else 0f, 0f, if (onRight) 0f else mm * 0.6f, mm * 0.3f, 0xFFFFF0C4.toInt(), 0.3f)
            }
            InstantSurface.GLOSSY -> fringe(pc, window, wave, mm * 0.09f, mm * 0.09f, mm * 0.09f, mm * 0.09f, mm * 0.05f, 0xFF0C0C0E.toInt(), black(0.6f))
            InstantSurface.PEEL -> fringe(pc, window, wave, mm * 0.35f, mm * 1.7f, mm * 0.35f, mm * 0.6f, mm * 0.1f, 0xFF24170C.toInt(), black(0.9f))
        }
        f.drawRoundRect(window, rr(window, imageRadius), rr(window, imageRadius), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(window.left, window.top) })
            }
        })

        // 纸框压在画面上，画面比纸框低一层：靠光的两条边纸框的影子落在画面里；背光的两条边露出切口的亮边。
        band(f, w, h, window, imageRadius, mm * 0.5f, lit = true, inside = true, color = BLACK, alpha = black(0.46f), blur = mm * 0.2f)
        strokeMask(f, window, imageRadius, max(1f, mm * 0.09f), BLACK, black(0.3f))
        band(f, w, h, window, imageRadius, mm * 0.16f, lit = false, inside = false, color = WHITE, alpha = if (light) 0.75f else white(0.07f, paper))
        band(f, w, h, window, imageRadius, mm * 0.18f, lit = true, inside = false, color = BLACK, alpha = black(0.08f))

        caption(f, l, p)

        // 相纸本身的厚度：靠光的两条边一线亮，背光的两条边一线暗，再往里是面纸和底纸压合的那道缝。
        band(f, w, h, sheet, paperRadius, mm * 0.28f, lit = true, inside = true, color = WHITE, alpha = if (light) 0.7f else white(0.06f, paper))
        band(f, w, h, sheet, paperRadius, mm * 0.42f, lit = false, inside = true, color = BLACK, alpha = black(0.17f), blur = mm * 0.12f)
        val seam = RectF(sheet).apply { inset(mm * 0.65f, mm * 0.65f) }
        strokeMask(f, seam, max(paperRadius - mm * 0.65f, 0f), max(1f, mm * 0.09f), BLACK, black(if (surface == InstantSurface.MATTE) 0.07f else 0.04f))
        if (surface == InstantSurface.GLOSSY) {
            // 整张都是光面塑料，从一角扫过来一片很淡的反光。
            f.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(sheet.left, sheet.top, sheet.left + sheet.width() * 0.6f, sheet.bottom - sheet.height() * 0.4f,
                    argb(0.07f, 1f, 1f, 1f), 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
        }
        strokeMask(f, sheet, paperRadius, max(1f, mm * 0.07f), BLACK, black(0.22f))

        c.drawRoundRect(sheet, rr(sheet, paperRadius), rr(sheet, paperRadius), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        face.recycle()
        picture.recycle()
        return out
    }

    // MARK: 作图工具

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** iOS 在线性光里压黑：换算成 sRGB 上的不透明度。 */
    private fun black(a: Float) = 1f - (1f - a.coerceIn(0f, 1f)).pow(1f / 2.2f)

    /** iOS 在线性光里叠白：按底色换算成 sRGB 上的不透明度。 */
    private fun white(a: Float, base: PaperColor): Float {
        val g = base.luma.coerceIn(0f, 0.999f)
        val lin = g.pow(2.2f)
        val target = (lin + a * (1 - lin)).pow(1f / 2.2f)
        return ((target - g) / (1 - g)).coerceIn(0f, 1f)
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
        ((a.coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 24) or ((r * 255 + 0.5f).toInt() shl 16) or ((g * 255 + 0.5f).toInt() shl 8) or (b * 255 + 0.5f).toInt()

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** 圆角不超过短边一半。 */
    private fun rr(r: RectF, radius: Float) = radius.coerceIn(0f, min(r.width(), r.height()) / 2)

    private fun blurRadius(sigma: Float) = max(0.1f, (sigma - 0.5f) / 0.57735f)

    /** 圆角矩形的一圈线，线画在边界以内。 */
    private fun strokeMask(c: Canvas, rect: RectF, radius: Float, lineWidth: Float, color: Int, alpha: Float) {
        val lw = max(lineWidth, 0.01f)
        val r = RectF(rect).apply { inset(lw / 2, lw / 2) }
        val rad = max(0f, rr(rect, radius) - lw / 2)
        c.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = lw; this.color = color; this.alpha = (alpha * 255).roundToInt()
        })
    }

    /** 在长边不超过 limit 的小蒙版上画一个虚化的形状，再放大叠到 c 上。 */
    private fun soft(c: Canvas, w: Int, h: Int, limit: Float, blur: Float, color: Int, alpha: Float, clip: Path? = null, draw: (Canvas, Paint) -> Unit) {
        val k = min(1f, limit / max(w, h))
        val lw = max(1, (w * k).roundToInt())
        val lh = max(1, (h * k).roundToInt())
        val mask = Bitmap.createBitmap(lw, lh, Bitmap.Config.ALPHA_8)
        val mc = Canvas(mask)
        mc.scale(lw.toFloat() / w, lh.toFloat() / h)
        if (clip != null) mc.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = WHITE
            if (blur > 0f) maskFilter = BlurMaskFilter(blurRadius(blur), BlurMaskFilter.Blur.NORMAL)
        }
        draw(mc, paint)
        c.drawBitmap(mask, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            this.color = color; this.alpha = (alpha * 255).roundToInt()
        })
        mask.recycle()
    }

    /** 贴着窗口内侧的一圈虚边（内阴影用），只留在窗口里面。 */
    private fun softRing(c: Canvas, w: Int, h: Int, rect: RectF, radius: Float, width: Float, blur: Float, color: Int, alpha: Float) {
        if (width <= 0f || blur <= 0f) return
        val inside = Path().apply { addRoundRect(rect, rr(rect, radius), rr(rect, radius), Path.Direction.CW) }
        soft(c, w, h, 900f, blur, color, alpha, clip = inside) { mc, pt ->
            pt.style = Paint.Style.STROKE
            pt.strokeWidth = width
            val r = RectF(rect).apply { inset(width / 2, width / 2) }
            val rad = max(0f, rr(rect, radius) - width / 2)
            mc.drawRoundRect(r, rad, rad, pt)
        }
    }

    /** rect 边上的一条窄带。lit = 靠光的上、左两条边，否则是背光的下、右；inside = 带子在 rect 里面还是外面。 */
    private fun band(
        c: Canvas, w: Int, h: Int, rect: RectF, radius: Float, depth: Float, lit: Boolean, inside: Boolean,
        color: Int, alpha: Float, blur: Float = 0f,
    ) {
        val rad = rr(rect, radius)
        val toShade = depth
        val toLight = -depth
        val a: RectF
        val b: RectF
        if (inside) {
            a = RectF(rect)
            b = RectF(rect).apply { if (lit) offset(toShade, toShade) else offset(toLight, toLight) }
        } else {
            a = RectF(rect).apply { if (lit) offset(toLight, toLight) else offset(toShade, toShade) }
            b = RectF(rect)
        }
        val path = Path().apply { addRoundRect(a, rad, rad, Path.Direction.CW) }
        path.op(Path().apply { addRoundRect(b, rad, rad, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        if (blur > 0f) {
            soft(c, w, h, 1800f, blur, color, alpha) { mc, pt -> mc.drawPath(path, pt) }
        } else {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = (alpha * 255).roundToInt() })
        }
    }

    /** 纸框上很淡的纸纹：顺着一个方向的细丝。只有明暗，位置固定。 */
    private fun fibre(c: Canvas, mm: Float, amount: Float, vertical: Boolean) {
        if (amount <= 0f) return
        val along = max(mm * 0.5f, 1f)
        val across = max(mm * 0.085f, 1f)
        val sx = if (vertical) across else along
        val sy = if (vertical) along else across
        val nw = max(1, ceil(c.width / sx).toInt())
        val nh = max(1, ceil(c.height / sy).toInt())
        val rnd = Random(17)
        val px = IntArray(nw * nh) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val noise = Bitmap.createBitmap(px, nw, nh, Bitmap.Config.ARGB_8888)
        c.drawBitmap(noise, null, RectF(0f, 0f, nw * sx, nh * sy), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (amount * 255).roundToInt()
            blendMode = BlendMode.SOFT_LIGHT
        })
        noise.recycle()
    }

    /**
     * 药膜边缘不整齐的那一圈。四条边各自的宽度（像素），noise 让宽窄起伏。
     * 只在窗口里面有用（之后会按窗口形状嵌进纸框），所以只算贴着四条边的那几条带子。c 已经平移到整张画布的坐标。
     */
    private fun fringe(c: Canvas, rect: RectF, noise: Blotches, left: Float, right: Float, top: Float, bottom: Float, soft: Float, color: Int, alpha: Float) {
        val s = max(soft, 0.5f)
        val w = rect.width().roundToInt()
        val h = rect.height().roundToInt()
        if (w <= 0 || h <= 0) return
        val reach = { v: Float -> if (v > 0.001f) ceil(v * 1.9f + s + 1).toInt() else 0 }
        val rl = min(w, reach(left))
        val rr = min(w, reach(right))
        val rt = min(h, reach(top))
        val rb = min(h, reach(bottom))
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val stride = mask.rowBytes
        val bytes = ByteArray(stride * h)
        fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
        fun at(x: Int, y: Int) {
            val gx = rect.left + x + 0.5f
            val gy = rect.top + y + 0.5f
            val k = (noise.at(gx, gy) * 2.3f - 0.4f).coerceIn(0f, 1.9f)
            var m = 0f
            if (left > 0.001f) m = max(m, 1 - smooth(left * k - s, left * k + s, x + 0.5f))
            if (right > 0.001f) m = max(m, 1 - smooth(right * k - s, right * k + s, w - x - 0.5f))
            if (top > 0.001f) m = max(m, 1 - smooth(top * k - s, top * k + s, y + 0.5f))
            if (bottom > 0.001f) m = max(m, 1 - smooth(bottom * k - s, bottom * k + s, h - y - 0.5f))
            bytes[y * stride + x] = (m * 255 + 0.5f).toInt().toByte()
        }
        for (y in 0 until h) {
            if (y < rt || y >= h - rb) {
                for (x in 0 until w) at(x, y)
            } else {
                for (x in 0 until rl) at(x, y)
                for (x in max(rl, w - rr) until w) at(x, y)
            }
        }
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        c.drawBitmap(mask, rect.left, rect.top, Paint().apply { this.color = color; this.alpha = (alpha * 255).roundToInt() })
        mask.recycle()
    }

    /**
     * 低频的不规则起伏（0...1，一格大约 cell 像素）。在长边不超过 640 的小画布上做，
     * 花样只跟 seed 和画布比例有关，和分辨率无关。
     */
    private class Blotches(val w: Int, val h: Int, cell: Float, seed: Int) {
        private val k = min(1f, 640f / max(w, h))
        private val lw = max(1, (w * k).roundToInt())
        private val lh = max(1, (h * k).roundToInt())
        private val step = max(cell * k, 1.5f)
        // 起伏本来就很平滑：在更粗的格子上算（一格起伏大约 4 个点），模糊也快。
        private val q = max(1f, step / 4f)
        private val fw = max(1, ceil(lw / q).toInt())
        private val fh = max(1, ceil(lh / q).toInt())
        private val v = FloatArray(fw * fh)

        init {
            val gw = ceil(lw / step).toInt() + 3
            val gh = ceil(lh / step).toInt() + 3
            val rnd = Random(seed.toLong())
            val grid = FloatArray(gw * gh) { rnd.nextFloat() }
            for (y in 0 until fh) for (x in 0 until fw) {
                val fx = (x + 0.5f) * q / step
                val fy = (y + 0.5f) * q / step
                val x0 = floor(fx).toInt().coerceIn(0, gw - 2)
                val y0 = floor(fy).toInt().coerceIn(0, gh - 2)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val ty = (fy - y0).coerceIn(0f, 1f)
                val a = grid[y0 * gw + x0] * (1 - tx) + grid[y0 * gw + x0 + 1] * tx
                val b = grid[(y0 + 1) * gw + x0] * (1 - tx) + grid[(y0 + 1) * gw + x0 + 1] * tx
                v[y * fw + x] = a * (1 - ty) + b * ty
            }
            blur(step * 0.55f / q)
            // 糊开以后起伏变小了，拉回来。
            val c = 2.6f
            for (i in v.indices) v[i] = (0.5f + (v[i] - 0.5f) * c).coerceIn(0f, 1f)
        }

        private fun blur(sigma: Float) {
            if (sigma < 0.4f) return
            val r = ceil(sigma * 3).toInt()
            val kern = FloatArray(r * 2 + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
            val sum = kern.sum()
            for (i in kern.indices) kern[i] /= sum
            val tmp = FloatArray(v.size)
            for (y in 0 until fh) for (x in 0 until fw) {
                var acc = 0f
                for (i in -r..r) acc += v[y * fw + (x + i).coerceIn(0, fw - 1)] * kern[i + r]
                tmp[y * fw + x] = acc
            }
            for (y in 0 until fh) for (x in 0 until fw) {
                var acc = 0f
                for (i in -r..r) acc += tmp[(y + i).coerceIn(0, fh - 1) * fw + x] * kern[i + r]
                v[y * fw + x] = acc
            }
        }

        /** 整张画布坐标上的值（双线性）。 */
        fun at(x: Float, y: Float): Float {
            val fx = (x * fw / w - 0.5f).coerceIn(0f, fw - 1f)
            val fy = (y * fh / h - 0.5f).coerceIn(0f, fh - 1f)
            val x0 = fx.toInt().coerceAtMost(fw - 1)
            val y0 = fy.toInt().coerceAtMost(fh - 1)
            val x1 = min(x0 + 1, fw - 1)
            val y1 = min(y0 + 1, fh - 1)
            val tx = fx - x0
            val ty = fy - y0
            val a = v[y0 * fw + x0] * (1 - tx) + v[y0 * fw + x1] * tx
            val b = v[y1 * fw + x0] * (1 - tx) + v[y1 * fw + x1] * tx
            return a * (1 - ty) + b * ty
        }

        /** 当蒙版画一层颜色。 */
        fun paint(c: Canvas, color: Int, alpha: Float) {
            val mask = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8)
            val stride = mask.rowBytes
            val bytes = ByteArray(stride * fh)
            for (y in 0 until fh) for (x in 0 until fw) bytes[y * stride + x] = (v[y * fw + x] * 255 + 0.5f).toInt().toByte()
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            c.drawBitmap(mask, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                this.color = color; this.alpha = (alpha * 255).roundToInt()
            })
            mask.recycle()
        }
    }
}
