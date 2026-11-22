package com.taoo.xiutu.tools.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 内置的一套图形贴纸，全部用代码画出来（不带任何外部素材）。
 * 每个图形画在 0...1 的方格里，y 向上（和 iOS 版一样）。贴到画面上时由 OverlayDrawing 加白边和阴影。
 */
object StickerArt {
    class Piece(val id: String, val title: String, internal val draw: (Canvas) -> Unit)

    private fun rgb(r: Double, g: Double, b: Double, a: Double = 1.0): Int =
        OverlayColor.of(r, g, b).argb(a.toFloat())

    private val red = rgb(1.0, 0.33, 0.4)
    private val pink = rgb(1.0, 0.62, 0.76)
    private val yellow = rgb(1.0, 0.82, 0.2)
    private val orange = rgb(1.0, 0.6, 0.2)
    private val blue = rgb(0.36, 0.68, 1.0)
    private val green = rgb(0.3, 0.8, 0.5)
    private val purple = rgb(0.68, 0.52, 0.98)
    private val white = rgb(1.0, 1.0, 1.0)

    private class P(val x: Float, val y: Float)
    private fun p(x: Double, y: Double) = P(x.toFloat(), y.toFloat())

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun line(color: Int, width: Double) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width.toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 填充一个多边形，并用同色的圆角描边把尖角磨圆。 */
    private fun blob(c: Canvas, points: List<P>, color: Int, round: Double = 0.06) {
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (q in points.drop(1)) path.lineTo(q.x, q.y)
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = round.toFloat()
        }
        c.drawPath(path, paint)
    }

    private fun circle(c: Canvas, x: Double, y: Double, r: Double, color: Int) {
        c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), fill(color))
    }

    private fun heartPath(): Path = Path().apply {
        moveTo(0.5f, 0.1f)
        cubicTo(0.22f, 0.3f, 0.06f, 0.44f, 0.06f, 0.62f)
        cubicTo(0.06f, 0.79f, 0.16f, 0.9f, 0.29f, 0.9f)
        cubicTo(0.39f, 0.9f, 0.46f, 0.84f, 0.5f, 0.75f)
        cubicTo(0.54f, 0.84f, 0.61f, 0.9f, 0.71f, 0.9f)
        cubicTo(0.84f, 0.9f, 0.94f, 0.79f, 0.94f, 0.62f)
        cubicTo(0.94f, 0.44f, 0.78f, 0.3f, 0.5f, 0.1f)
        close()
    }

    private fun star(n: Int, outer: Double, inner: Double, cx: Double = 0.5, cy: Double = 0.48, start: Double = PI / 2): List<P> =
        (0 until n * 2).map { i ->
            val r = if (i % 2 == 0) outer else inner
            val a = start + i * PI / n
            p(cx + r * cos(a), cy + r * sin(a))
        }

    val all: List<Piece> = listOf(
        Piece("heart", "爱心") { c ->
            c.drawPath(heartPath(), fill(red))
            // 一小块高光
            c.save()
            c.translate(0.27f, 0.72f)
            c.rotate(Math.toDegrees(0.6).toFloat())
            c.drawOval(RectF(-0.07f, -0.035f, 0.07f, 0.035f), fill(rgb(1.0, 1.0, 1.0, 0.55)))
            c.restore()
        },
        Piece("star", "星星") { c -> blob(c, star(5, 0.42, 0.19), yellow, 0.09) },
        Piece("sparkle", "闪光") { c ->
            val path = Path().apply {
                moveTo(0.5f, 0.97f)
                quadTo(0.58f, 0.58f, 0.97f, 0.5f)
                quadTo(0.58f, 0.42f, 0.5f, 0.03f)
                quadTo(0.42f, 0.42f, 0.03f, 0.5f)
                quadTo(0.42f, 0.58f, 0.5f, 0.97f)
                close()
            }
            c.drawPath(path, fill(rgb(1.0, 0.9, 0.45)))
        },
        Piece("flower", "小花") { c ->
            for (i in 0 until 5) {
                val a = PI / 2 + i * 2 * PI / 5
                circle(c, 0.5 + 0.25 * cos(a), 0.5 + 0.25 * sin(a), 0.21, pink)
            }
            circle(c, 0.5, 0.5, 0.15, yellow)
        },
        Piece("cloud", "云朵") { c ->
            val col = rgb(0.86, 0.94, 1.0)
            circle(c, 0.3, 0.44, 0.19, col)
            circle(c, 0.5, 0.56, 0.25, col)
            circle(c, 0.72, 0.46, 0.21, col)
            c.drawRect(RectF(0.3f, 0.25f, 0.72f, 0.5f), fill(col))
        },
        Piece("sun", "太阳") { c ->
            val pen = line(orange, 0.075)
            for (i in 0 until 8) {
                val a = i * PI / 4
                c.drawLine((0.5 + 0.33 * cos(a)).toFloat(), (0.5 + 0.33 * sin(a)).toFloat(),
                    (0.5 + 0.44 * cos(a)).toFloat(), (0.5 + 0.44 * sin(a)).toFloat(), pen)
            }
            circle(c, 0.5, 0.5, 0.24, yellow)
        },
        Piece("moon", "月亮") { c ->
            c.saveLayer(null, null)
            circle(c, 0.47, 0.5, 0.41, rgb(1.0, 0.87, 0.4))
            val clear = fill(white).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            c.drawCircle(0.65f, 0.6f, 0.35f, clear)
            c.restore()
        },
        Piece("bolt", "闪电") { c ->
            blob(c, listOf(p(0.6, 0.93), p(0.22, 0.46), p(0.47, 0.46), p(0.38, 0.07), p(0.78, 0.57), p(0.53, 0.57)), yellow, 0.07)
        },
        Piece("burst", "惊叹") { c ->
            blob(c, star(12, 0.45, 0.34, 0.5, 0.5), orange, 0.04)
            c.drawLine(0.5f, 0.7f, 0.5f, 0.46f, line(white, 0.1))
            circle(c, 0.5, 0.29, 0.055, white)
        },
        Piece("arrow", "箭头") { c ->
            blob(c, listOf(p(0.1, 0.4), p(0.54, 0.4), p(0.54, 0.23), p(0.9, 0.5), p(0.54, 0.77), p(0.54, 0.6), p(0.1, 0.6)), blue, 0.08)
        },
        Piece("crown", "皇冠") { c ->
            blob(c, listOf(p(0.17, 0.27), p(0.83, 0.27), p(0.9, 0.68), p(0.69, 0.5), p(0.5, 0.8), p(0.31, 0.5), p(0.1, 0.68)), yellow, 0.07)
            circle(c, 0.5, 0.42, 0.055, red)
            circle(c, 0.3, 0.38, 0.04, blue)
            circle(c, 0.7, 0.38, 0.04, blue)
        },
        Piece("check", "对勾") { c ->
            circle(c, 0.5, 0.5, 0.44, green)
            val path = Path().apply { moveTo(0.29f, 0.5f); lineTo(0.44f, 0.35f); lineTo(0.72f, 0.65f) }
            c.drawPath(path, line(white, 0.1))
        },
        Piece("bow", "蝴蝶结") { c ->
            blob(c, listOf(p(0.5, 0.5), p(0.12, 0.76), p(0.12, 0.24)), pink, 0.1)
            blob(c, listOf(p(0.5, 0.5), p(0.88, 0.76), p(0.88, 0.24)), pink, 0.1)
            c.drawRoundRect(RectF(0.41f, 0.39f, 0.59f, 0.61f), 0.07f, 0.07f, fill(rgb(1.0, 0.45, 0.65)))
        },
        Piece("drop", "水滴") { c ->
            val path = Path().apply {
                moveTo(0.5f, 0.94f)
                cubicTo(0.42f, 0.78f, 0.2f, 0.58f, 0.2f, 0.38f)
                cubicTo(0.2f, 0.2f, 0.34f, 0.08f, 0.5f, 0.08f)
                cubicTo(0.66f, 0.08f, 0.8f, 0.2f, 0.8f, 0.38f)
                cubicTo(0.8f, 0.58f, 0.58f, 0.78f, 0.5f, 0.94f)
                close()
            }
            c.drawPath(path, fill(blue))
            c.drawOval(RectF(0.33f, 0.3f, 0.42f, 0.46f), fill(rgb(1.0, 1.0, 1.0, 0.5)))
        },
        Piece("gem", "钻石") { c ->
            blob(c, listOf(p(0.5, 0.1), p(0.1, 0.6), p(0.28, 0.84), p(0.72, 0.84), p(0.9, 0.6)), purple, 0.05)
            val pen = line(rgb(1.0, 1.0, 1.0, 0.65), 0.025)
            c.drawLine(0.1f, 0.6f, 0.9f, 0.6f, pen)
            val path = Path().apply { moveTo(0.5f, 0.1f); lineTo(0.36f, 0.6f); lineTo(0.5f, 0.84f); lineTo(0.64f, 0.6f); close() }
            c.drawPath(path, pen)
        },
    )

    private val cache = HashMap<String, Bitmap>()

    /** 画成一张透明背景的方图。 */
    fun image(piece: Piece, size: Int = 512): Bitmap? = synchronized(cache) {
        val key = "${piece.id}-$size"
        cache[key]?.let { return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // 翻成 y 向上的 0...1 方格
        c.translate(0f, size.toFloat())
        c.scale(size.toFloat(), -size.toFloat())
        piece.draw(c)
        cache[key] = b
        b
    }
}
