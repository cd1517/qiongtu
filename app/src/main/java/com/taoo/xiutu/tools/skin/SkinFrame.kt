package com.taoo.xiutu.tools.skin

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.taoo.xiutu.ml.FaceInfo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 二维向量（像素坐标，y 朝下）。 */
internal data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(k: Float) = V(x * k, y * k)
    operator fun unaryMinus() = V(-x, -y)
    fun dot(o: V) = x * o.x + y * o.y
    fun dist(o: V) = hypot(x - o.x, y - o.y)

    companion object {
        val ZERO = V(0f, 0f)
        fun centroid(p: List<V>): V {
            if (p.isEmpty()) return ZERO
            var sx = 0f
            var sy = 0f
            for (q in p) { sx += q.x; sy += q.y }
            return V(sx / p.size, sy / p.size)
        }
    }
}

/**
 * 一张脸的几何：关键点换成像素坐标（y 朝下），并建立"脸自己的坐标系"：以两眼中点为原点、两眼间距为单位。
 */
internal class SkinFrame(face: FaceInfo, val w: Float, val h: Float) {
    val reliable: Boolean
    /** 按画面里的左右排：[左眼, 右眼] 的中心。 */
    val eyes: List<V>
    val eyePolygons: List<List<V>>
    val browPolygons: List<List<V>>
    val eyeMid: V
    /** 两眼间距。 */
    val d: Float
    /** 脸的"右"和"下"两个单位向量。 */
    val ex: V
    val ey: V
    val mouth: V
    /** [左嘴角, 右嘴角]。 */
    val mouthCorners: List<V>
    /** [左鼻翼, 右鼻翼]。 */
    val alar: List<V>
    val chin: V
    /** 下颌轮廓线，从画面左边到右边。 */
    val contour: List<V>
    val outerLips: List<V>
    val innerLips: List<V>
    val fw: Float
    /** 左右半边脸各露出来多少（正脸约 1；侧脸时远离镜头的那边很小）。 */
    val side: List<Float>
    /** 要处理的范围：像素坐标、已取整并限制在图片内。 */
    val roi: Rect

    /** 脸坐标系里的一点：u 向右、v 向下，单位是两眼间距。 */
    fun at(u: Float, v: Float): V = eyeMid + ex * (u * d) + ey * (v * d)

    /** 从 p 出发，沿脸的方向走 (u, v)。 */
    fun from(p: V, u: Float, v: Float): V = p + ex * (u * d) + ey * (v * d)

    init {
        fun px(p: PointF) = V(p.x * w, p.y * h)
        val box = RectF(face.box.left * w, face.box.top * h, face.box.right * w, face.box.bottom * h)
        var rel = face.hasReliableLandmarks
        var eyesL: List<V> = emptyList()
        var eyeP: List<List<V>> = emptyList()
        var browP: List<List<V>> = emptyList()
        var mid = V.ZERO
        var dd = 1f
        var vx = V(1f, 0f)
        var vy = V(0f, 1f)
        var mo = V.ZERO
        var ch = V.ZERO
        var con: List<V> = emptyList()
        var outer: List<V> = emptyList()
        var inner: List<V> = emptyList()
        var nose: List<V> = emptyList()
        if (rel) {
            var a = face.leftEye.map(::px)
            var b = face.rightEye.map(::px)
            var ba = face.leftBrow.map(::px)
            var bb = face.rightBrow.map(::px)
            if (V.centroid(a).x > V.centroid(b).x) {
                a = b.also { b = a }
                ba = bb.also { bb = ba }
            }
            // 眉毛不一定和眼睛同名对应，按左右来配。
            if (ba.isNotEmpty() && bb.isNotEmpty() && V.centroid(ba).x > V.centroid(bb).x) ba = bb.also { bb = ba }
            eyeP = listOf(a, b)
            browP = listOf(ba, bb)
            eyesL = listOf(V.centroid(a), V.centroid(b))
            dd = eyesL[0].dist(eyesL[1])
            if (dd < 2f) {
                rel = false
            } else {
                vx = (eyesL[1] - eyesL[0]) * (1f / dd)
                vy = V(-vx.y, vx.x)
                mid = (eyesL[0] + eyesL[1]) * 0.5f
                outer = face.outerLips.map(::px)
                inner = face.innerLips.map(::px)
                nose = face.nose.map(::px)
                con = face.contour.map(::px)
                ch = con[con.size / 2]
                mo = if (outer.size >= 3) V.centroid(outer) else mid + (ch - mid) * 0.6f
                if ((ch - mid).dot(vy) < 0f) vy = -vy
                if ((con.first() - mid).dot(vx) > (con.last() - mid).dot(vx)) con = con.reversed()
            }
        }
        if (!rel) {
            // 只有人脸框可用：按一般的比例估一个正脸。
            dd = max(box.width() * 0.42f, 2f)
            mid = V(box.centerX(), box.top + box.height() * 0.4f)
            vx = V(1f, 0f)
            vy = V(0f, 1f)
            eyesL = listOf(V(mid.x - dd / 2, mid.y), V(mid.x + dd / 2, mid.y))
            eyeP = listOf(emptyList(), emptyList())
            browP = listOf(emptyList(), emptyList())
            mo = V(mid.x, mid.y + dd * 1.1f)
            ch = V(mid.x, box.bottom + box.height() * 0.05f)
            con = listOf(V(box.left, mid.y + dd * 0.1f), ch, V(box.right, mid.y + dd * 0.1f))
            outer = emptyList()
            inner = emptyList()
            nose = emptyList()
        }
        reliable = rel
        eyes = eyesL
        eyePolygons = eyeP
        browPolygons = browP
        eyeMid = mid
        d = dd
        ex = vx
        ey = vy
        mouth = mo
        chin = ch
        contour = con
        outerLips = outer
        innerLips = inner
        val a = con.first()
        val b = con.last()
        fw = max(a.dist(b), dd * 1.6f)

        fun along(p: V) = (p - mid).dot(vx)
        // 嘴角、鼻翼：有关键点就取最左最右的点，没有就按比例估。
        fun extremes(points: List<V>, u: Float, v: Float): List<V> {
            if (points.size < 3) {
                return listOf(mid - vx * (u * dd) + vy * (v * dd), mid + vx * (u * dd) + vy * (v * dd))
            }
            return listOf(points.minBy { along(it) }, points.maxBy { along(it) })
        }
        mouthCorners = extremes(outer, 0.4f, 1.1f)
        alar = extremes(nose, 0.3f, 0.72f)
        side = listOf(0, 1).map { i ->
            val end = if (i == 0) a else b
            val wv = abs(along(end) - along(eyesL[i]))
            (wv / (0.62f * dd)).coerceIn(0.35f, 1.15f)
        }

        // 处理范围：上到发际线上面一点，下到脖子，左右各多留半张脸。
        val top = mid - vy * (dd * 2.1f)
        val bottom = ch + vy * (dd * 1.05f)
        val corners = ArrayList<V>()
        for (p in listOf(top, bottom)) {
            corners += p - vx * (fw * 1.05f)
            corners += p + vx * (fw * 1.05f)
        }
        val x0 = floor(max(0f, corners.minOf { it.x })).toInt()
        val y0 = floor(max(0f, corners.minOf { it.y })).toInt()
        val x1 = ceil(min(w, corners.maxOf { it.x })).toInt()
        val y1 = ceil(min(h, corners.maxOf { it.y })).toInt()
        roi = if (x1 > x0 && y1 > y0) Rect(x0, y0, x1, y1) else Rect(0, 0, 0, 0)
    }

    /** 嘴是不是张开到能看见牙。 */
    val mouthOpen: Boolean
        get() {
            if (!reliable || innerLips.size < 4 || outerLips.size < 4) return false
            fun extent(points: List<V>, axis: V): Float {
                val values = points.map { it.dot(axis) }
                return values.max() - values.min()
            }
            val gap = extent(innerLips, ey)
            val width = extent(outerLips, ex)
            return width > 0 && gap / width > 0.07f
        }
}

/** 一张脸要用到的所有区域，每张位图的 R/G/B 各放一个区域。位图只按"处理范围"的比例画，和图片分辨率无关。 */
internal class SkinMaskSet(
    /** 皮肤（不含眼、眉、唇）/ 额头 / 眼周。 */
    val a: Bitmap,
    /** 法令纹 / 脖子 / 口周。 */
    val b: Bitmap,
    /** 眼下 / 泪沟 / 苹果肌。 */
    val c: Bitmap,
    /** 面颊 / 太阳穴 / 额头中央。 */
    val d: Bitmap,
    /** 眼睛里面 / 牙齿 / 鼻基底。 */
    val fine: Bitmap,
)

/** 在灰度位图上按"图片像素坐标"画形状。 */
private class Painter(val w: Int, val h: Int, frame: SkinFrame) {
    val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    private val canvas = Canvas(bitmap)
    private val roi = frame.roi
    private val sx = w / max(roi.width().toFloat(), 1f)
    private val sy = h / max(roi.height().toFloat(), 1f)
    private val faceMatrix = Matrix().apply {
        setValues(floatArrayOf(frame.ex.x, frame.ey.x, 0f, frame.ex.y, frame.ey.y, 0f, 0f, 0f, 1f))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    fun point(p: V) = V((p.x - roi.left) * sx, (p.y - roi.top) * sy)

    private fun inFace(center: V, ru: Float, rv: Float, body: () -> Unit) {
        val c = point(center)
        canvas.save()
        canvas.translate(c.x, c.y)
        canvas.concat(faceMatrix)
        canvas.scale(max(ru * sx, 0.01f), max(rv * sx, 0.01f))
        body()
        canvas.restore()
    }

    private fun reset(white: Boolean, style: Paint.Style = Paint.Style.FILL) {
        paint.shader = null
        paint.blendMode = null
        paint.style = style
        paint.color = if (white) Color.WHITE else Color.BLACK
    }

    /** 实心椭圆，长短轴跟着脸的方向。半径是图片像素。 */
    fun oval(center: V, ru: Float, rv: Float, white: Boolean = true) {
        reset(white)
        inFace(center, ru, rv) { canvas.drawOval(-1f, -1f, 1f, 1f, paint) }
    }

    /** 中间亮、边缘渐隐的一团。几团重叠时取"滤色"，不会互相盖掉。 */
    fun blob(center: V, ru: Float, rv: Float) {
        reset(true)
        paint.shader = RadialGradient(0f, 0f, 1f, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        paint.blendMode = BlendMode.SCREEN
        inFace(center, ru, rv) { canvas.drawCircle(0f, 0f, 1f, paint) }
        reset(true)
    }

    fun polygon(points: List<V>, white: Boolean = true, grow: Float = 0f) {
        if (points.size < 3) return
        val path = Path()
        val first = point(points[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until points.size) point(points[i]).let { path.lineTo(it.x, it.y) }
        path.close()
        if (grow > 0f) {
            reset(white, Paint.Style.FILL_AND_STROKE)
            paint.strokeWidth = grow * sx * 2
        } else {
            reset(white)
        }
        canvas.drawPath(path, paint)
    }

    fun line(from: V, to: V, width: Float) {
        reset(true, Paint.Style.STROKE)
        paint.strokeWidth = max(width * sx, 1f)
        val a = point(from)
        val b = point(to)
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }
}

internal object SkinMasks {
    private fun span(points: List<V>): Float {
        if (points.isEmpty()) return 0f
        return hypot(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    }

    /** 三张灰度图合成一张：R / G / B 各一张。 */
    private fun pack(r: Bitmap?, g: Bitmap?, b: Bitmap?, w: Int, h: Int): Bitmap {
        val n = w * h
        fun channel(bm: Bitmap?): IntArray? = bm?.let { IntArray(n).also { px -> it.getPixels(px, 0, w, 0, 0, w, h) } }
        val pr = channel(r)
        val pg = channel(g)
        val pb = channel(b)
        val out = IntArray(n) { i ->
            val rv = pr?.let { (it[i] shr 16) and 0xFF } ?: 0
            val gv = pg?.let { (it[i] shr 16) and 0xFF } ?: 0
            val bv = pb?.let { (it[i] shr 16) and 0xFF } ?: 0
            (0xFF shl 24) or (rv shl 16) or (gv shl 8) or bv
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private val cache = LinkedHashMap<String, SkinMaskSet>()

    /** 这张脸的区域蒙版。拖滑块时每帧都要用，所以按"脸 + 图片比例"缓存。 */
    fun masks(frame: SkinFrame, face: FaceInfo): SkinMaskSet? {
        val r = frame.roi
        var key = "${r.left},${r.top},${r.width()},${r.height()}|${frame.w.toInt()}x${frame.h.toInt()}|${frame.reliable}"
        key += "|${face.box.left},${face.box.top},${face.box.width()}"
        val p = face.contour.firstOrNull()
        val q = face.outerLips.firstOrNull()
        val e = face.leftEye.firstOrNull()
        if (p != null && q != null && e != null) key += "|${p.x},${p.y},${q.x},${q.y},${e.x},${e.y},${face.innerLips.size}"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(frame) ?: return null
        synchronized(cache) {
            cache[key] = built
            while (cache.size > 12) cache.remove(cache.keys.first())
        }
        return built
    }

    private fun build(f: SkinFrame): SkinMaskSet? {
        val roi = f.roi
        if (roi.width() < 8 || roi.height() < 8) return null
        val scale = 384f / max(roi.width(), roi.height())
        val w = max(8, (roi.width() * scale).roundToInt())
        val h = max(8, (roi.height() * scale).roundToInt())
        val d = f.d
        fun layer(draw: (Painter) -> Unit): Bitmap {
            val p = Painter(w, h, f)
            draw(p)
            return p.bitmap
        }
        fun cutFeatures(p: Painter, eyes: Float, brows: Float, lips: Float) {
            for (eye in f.eyePolygons) p.polygon(eye, white = false, grow = span(eye) * eyes)
            for (brow in f.browPolygons) p.polygon(brow, white = false, grow = span(brow) * brows)
            p.polygon(f.outerLips, white = false, grow = span(f.outerLips) * lips)
        }
        /** 下颌轮廓加上估出来的额头，就是整张脸的范围。 */
        fun facePolygon(forehead: Float): List<V> {
            val a = f.contour.first()
            val b = f.contour.last()
            val mid = (a + b) * 0.5f
            val half = a.dist(mid)
            val polygon = ArrayList(f.contour)
            for (step in 11 downTo 1) {
                val t = step / 12f * Math.PI.toFloat()
                // 从轮廓的右端绕过头顶回到左端。
                polygon += V(
                    mid.x + cos(t) * (a.x - mid.x) - sin(t) * f.ey.x * half * forehead,
                    mid.y + cos(t) * (a.y - mid.y) - sin(t) * f.ey.y * half * forehead,
                )
            }
            return polygon
        }

        // 皮肤
        val skin = layer { p ->
            if (f.reliable) {
                p.polygon(facePolygon(1.22f))
                cutFeatures(p, 0.16f, 0.07f, 0.04f)
            } else {
                p.oval(f.at(0f, 0.35f), f.fw * 0.55f, f.fw * 0.72f)
            }
        }
        if (!f.reliable) {
            val a = pack(skin, null, null, w, h)
            val empty = pack(null, null, null, w, h)
            return SkinMaskSet(a, empty, empty, empty, empty)
        }
        data class Side(val index: Int, val sign: Float, val q: Float)
        val sides = listOf(Side(0, -1f, f.side[0]), Side(1, 1f, f.side[1]))
        // 远离镜头、几乎看不到的那半边脸不画只属于那一侧的区域。
        val shown = sides.filter { it.q > 0.5f }

        val forehead = layer { p ->
            p.oval(f.at(0f, -0.92f), d * 1.02f, d * 0.46f)
            for (brow in f.browPolygons) p.polygon(brow, white = false, grow = span(brow) * 0.1f)
        }
        val eyeAround = layer { p ->
            for (s in sides) {
                val eye = f.eyes[s.index]
                p.oval(f.from(eye, 0f, 0.07f), d * 0.6f, d * 0.4f)
                if (s.q > 0.5f) p.oval(f.from(eye, s.sign * 0.5f * s.q, 0.02f), d * 0.3f * s.q, d * 0.32f)
            }
            cutFeatures(p, 0.14f, 0.08f, 0f)
        }
        val naso = layer { p ->
            for (s in shown) {
                val from = f.from(f.alar[s.index], s.sign * 0.1f, -0.06f)
                val to = f.from(f.mouthCorners[s.index], s.sign * 0.24f * s.q, 0.16f)
                p.line(from, to, d * 0.2f)
            }
        }
        val neck = layer { p ->
            p.oval(f.from(f.chin, 0f, 0.72f), d * 0.85f, d * 0.62f)
            // 下巴以上不算脖子。
            val face = ArrayList(f.contour)
            face += f.from(f.contour.last(), 0f, -1.5f)
            face += f.from(f.contour.first(), 0f, -1.5f)
            p.polygon(face, white = false, grow = d * 0.04f)
        }
        val mouthAround = layer { p ->
            p.oval(f.from(f.mouth, 0f, 0.06f), d * 0.78f, d * 0.5f)
            p.polygon(f.outerLips, white = false, grow = span(f.outerLips) * 0.06f)
        }
        val underEye = layer { p ->
            for (s in sides) p.oval(f.from(f.eyes[s.index], 0f, 0.3f), d * 0.42f, d * 0.2f)
            cutFeatures(p, 0.14f, 0f, 0f)
        }
        val tear = layer { p ->
            for (s in sides) p.blob(f.from(f.eyes[s.index], -s.sign * 0.12f, 0.36f), d * 0.32f, d * 0.15f)
        }
        val apple = layer { p ->
            for (s in shown) p.blob(f.from(f.eyes[s.index], s.sign * 0.14f * s.q, 0.68f), d * 0.42f * s.q, d * 0.34f)
        }
        val cheek = layer { p ->
            for (s in shown) p.blob(f.from(f.eyes[s.index], s.sign * 0.3f * s.q, 1.0f), d * 0.3f * s.q, d * 0.4f)
        }
        val temple = layer { p ->
            for (s in shown) p.blob(f.from(f.eyes[s.index], s.sign * 0.52f * s.q, -0.42f), d * 0.22f * s.q, d * 0.34f)
        }
        val foreheadCenter = layer { p -> p.blob(f.at(0f, -0.9f), d * 0.78f, d * 0.42f) }
        val eyeInside = layer { p -> for (eye in f.eyePolygons) p.polygon(eye) }
        val teeth = layer { p -> if (f.mouthOpen) p.polygon(f.innerLips) }
        val noseBase = layer { p ->
            for (s in shown) p.blob(f.from(f.alar[s.index], s.sign * 0.12f, 0.03f), d * 0.2f, d * 0.2f)
        }
        val out = SkinMaskSet(
            a = pack(skin, forehead, eyeAround, w, h),
            b = pack(naso, neck, mouthAround, w, h),
            c = pack(underEye, tear, apple, w, h),
            d = pack(cheek, temple, foreheadCenter, w, h),
            fine = pack(eyeInside, teeth, noseBase, w, h),
        )
        for (bm in listOf(skin, forehead, eyeAround, naso, neck, mouthAround, underEye, tear, apple, cheek, temple, foreheadCenter, eyeInside, teeth, noseBase)) bm.recycle()
        return out
    }
}
