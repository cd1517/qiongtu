package com.taoo.xiutu.tools.makeup

import android.graphics.PointF
import com.taoo.xiutu.ml.FaceInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 美妆内部用的二维向量。 */
data class MKVec(val x: Float, val y: Float) {
    constructor(p: PointF) : this(p.x, p.y)

    val length: Float get() = hypot(x, y)
    val unit: MKVec get() { val l = max(length, 1e-9f); return MKVec(x / l, y / l) }
    /** 顺时针转 90°（y 轴朝下的坐标里）。 */
    val perp: MKVec get() = MKVec(-y, x)

    fun dot(o: MKVec) = x * o.x + y * o.y
    fun dist(o: MKVec) = hypot(x - o.x, y - o.y)
    fun lerp(o: MKVec, t: Float) = MKVec(x + (o.x - x) * t, y + (o.y - y) * t)

    operator fun plus(b: MKVec) = MKVec(x + b.x, y + b.y)
    operator fun minus(b: MKVec) = MKVec(x - b.x, y - b.y)
    operator fun times(k: Float) = MKVec(x * k, y * k)
    operator fun unaryMinus() = MKVec(-x, -y)
}

fun mkCentroid(points: List<MKVec>): MKVec {
    if (points.isEmpty()) return MKVec(0f, 0f)
    var sx = 0f
    var sy = 0f
    for (p in points) { sx += p.x; sy += p.y }
    return MKVec(sx / points.size, sy / points.size)
}

fun mkSmoothstep(a: Float, b: Float, x: Float): Float {
    if (a == b) return if (x < a) 0f else 1f
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** 穿过若干点的平滑曲线，可以按 0...1（沿弧长）取点和切线方向。 */
class MKCurve(points: List<MKVec>, perSegment: Int = 10) {
    val samples: List<MKVec>
    private val lengths: FloatArray

    val length: Float get() = if (lengths.isEmpty()) 0f else lengths.last()
    val isValid: Boolean get() = samples.size >= 2 && length > 0

    init {
        if (points.size < 2) {
            samples = points
            lengths = FloatArray(points.size)
        } else {
            val out = ArrayList<MKVec>()
            val n = points.size
            for (i in 0 until n - 1) {
                // Catmull-Rom：两端用相邻点外推
                val p0 = if (i > 0) points[i - 1] else points[i] + (points[i] - points[i + 1])
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = if (i + 2 < n) points[i + 2] else points[i + 1] + (points[i + 1] - points[i])
                for (s in 0 until perSegment) {
                    val t = s.toFloat() / perSegment
                    out += catmull(p0, p1, p2, p3, t)
                }
            }
            out += points[n - 1]
            samples = out
            lengths = FloatArray(out.size)
            var acc = 0f
            for (i in 1 until out.size) {
                acc += out[i].dist(out[i - 1])
                lengths[i] = acc
            }
        }
    }

    private fun locate(t: Float): Pair<Int, Float> {
        val target = t.coerceIn(0f, 1f) * length
        var lo = 0
        var hi = lengths.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (lengths[mid] <= target) lo = mid else hi = mid
        }
        val span = lengths[hi] - lengths[lo]
        return lo to (if (span > 0) (target - lengths[lo]) / span else 0f)
    }

    fun at(t: Float): MKVec {
        if (samples.size < 2) return samples.firstOrNull() ?: MKVec(0f, 0f)
        val (i, f) = locate(t)
        return samples[i].lerp(samples[min(i + 1, samples.size - 1)], f)
    }

    /** 单位切线方向（沿 t 增大的方向）。 */
    fun tangent(t: Float): MKVec {
        if (samples.size < 2) return MKVec(1f, 0f)
        val (i, _) = locate(t)
        val a = samples[max(i - 1, 0)]
        val b = samples[min(i + 2, samples.size - 1)]
        return (b - a).unit
    }

    companion object {
        fun catmull(p0: MKVec, p1: MKVec, p2: MKVec, p3: MKVec, t: Float): MKVec {
            val t2 = t * t
            val t3 = t2 * t
            val x = 0.5f * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3)
            val y = 0.5f * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)
            return MKVec(x, y)
        }
    }
}

/** 一只眼睛。坐标都在这张脸的方框里（0...1，原点左上角，y 朝下）。 */
class MKEye(
    val inner: MKVec,
    val outer: MKVec,
    /** 上、下眼睑，都是从内眼角到外眼角。 */
    val upper: MKCurve,
    val lower: MKCurve,
    /** 瞳孔位置（没有就用眼睛中心）。 */
    val center: MKVec,
    val width: Float,
    /** 内眼角 → 外眼角的单位方向。 */
    val axis: MKVec,
    /** 垂直于 axis、指向眉毛的单位方向。 */
    val up: MKVec,
    /** 眼睛张开的范围（闭合多边形）。 */
    val outline: List<MKVec>,
) {
    /** 这只眼睛到同侧脸边缘的距离：这半边脸露出来多宽。 */
    var side = 0f
    /** 侧脸时远离镜头的那半边太窄，腮红、修容就不画这一边。 */
    var visible = true
    /** 上眼睑中点到眉毛下缘的距离。 */
    var browGap = 0f
}

/** 一条眉毛：中线（眉头 → 眉尾）和每一处的粗细。 */
class MKBrow(
    val center: MKCurve,
    /** 中线上均匀取 9 个点处，眉毛原本的粗细。 */
    val thickness: List<Float>,
    val length: Float,
    /** 眉头 → 眉尾的单位方向，以及垂直于它、朝额头的方向。 */
    val axis: MKVec,
    val up: MKVec,
) {
    fun thickness(at: Float): Float {
        if (thickness.size < 2) return thickness.firstOrNull() ?: 0f
        val f = at.coerceIn(0f, 1f) * (thickness.size - 1)
        val i = min(f.toInt(), thickness.size - 2)
        return thickness[i] + (thickness[i + 1] - thickness[i]) * (f - i)
    }
}

/** 脸方框在图片里的位置（像素，原点左上角）。 */
data class MKRect(val minX: Float, val minY: Float, val width: Float, val height: Float) {
    val maxX get() = minX + width
    val maxY get() = minY + height
}

/**
 * 一张脸的几何信息。所有坐标都换算到"这张脸的方框"里：0...1，原点左上角，y 朝下，
 * 方框按像素是正方形，所以这里面的长度和角度都是真实比例，和图片分辨率无关。
 */
class MKFace private constructor(val roi: MKRect) {
    var eyes: List<MKEye> = emptyList()           // [画面左边的眼睛, 画面右边的眼睛]
    val brows: Array<MKBrow?> = arrayOf(null, null)
    var right = MKVec(1f, 0f)                      // 左眼 → 右眼
    var down = MKVec(0f, 1f)                       // 眼睛 → 嘴
    var eyeMid = MKVec(0.5f, 0.4f)
    var eyeDist = 0.2f
    /** 两眼连线到嘴的距离。 */
    var mouthDist = 0.25f
    var faceWidth = 0.5f
    var contour = MKCurve(emptyList())
    var chin = MKVec(0.5f, 0.8f)

    var lipsOuter: List<MKVec> = emptyList()
    var lipsInner: List<MKVec> = emptyList()
    var mouthCenter = MKVec(0.5f, 0.65f)
    var mouthWidth = 0.15f
    var lipTop = MKVec(0.5f, 0.62f)      // 唇峰中间
    var lipBottom = MKVec(0.5f, 0.69f)   // 下唇最低处

    /** 鼻梁中线：眉心 → 鼻尖。 */
    var ridge = MKCurve(emptyList())
    var noseTip = MKVec(0.5f, 0.55f)
    var noseBase = MKVec(0.5f, 0.58f)
    var noseWidth = 0.1f

    val up: MKVec get() = -down

    companion object {
        /** 关键点不够或不可信时返回 null。w、h 是整张图的像素尺寸。 */
        fun create(face: FaceInfo, w: Float, h: Float): MKFace? {
            if (!face.hasReliableLandmarks || face.outerLips.size < 6 || w <= 0 || h <= 0) return null
            val bw = face.box.width() * w
            val bh = face.box.height() * h
            val side = max(bw, bh) * 2.1f
            val cx = face.box.centerX() * w
            val cy = face.box.centerY() * h - bh * 0.1f
            val g = MKFace(MKRect(cx - side / 2, cy - side / 2, side, side))
            val ox = g.roi.minX
            val oy = g.roi.minY
            fun unit(p: PointF) = MKVec((p.x * w - ox) / side, (p.y * h - oy) / side)

            val lp = face.leftEye.map(::unit)
            val rp = face.rightEye.map(::unit)
            var a = mkCentroid(lp)
            var b = mkCentroid(rp)
            var leftPts = lp
            var rightPts = rp
            var leftPupil = face.leftPupil?.let(::unit)
            var rightPupil = face.rightPupil?.let(::unit)
            var leftBrowPts = face.leftBrow.map(::unit)
            var rightBrowPts = face.rightBrow.map(::unit)
            if (a.x > b.x) {
                a = b.also { b = a }
                leftPts = rightPts.also { rightPts = leftPts }
                leftPupil = rightPupil.also { rightPupil = leftPupil }
                leftBrowPts = rightBrowPts.also { rightBrowPts = leftBrowPts }
            }
            val mouth = mkCentroid(face.outerLips.map(::unit))
            g.eyeMid = a.lerp(b, 0.5f)
            g.eyeDist = max(a.dist(b), 1e-4f)
            g.right = (b - a).unit
            var d = g.right.perp
            if (d.dot(mouth - g.eyeMid) < 0) d = -d
            g.down = d
            g.mouthDist = max((mouth - g.eyeMid).dot(g.down), g.eyeDist * 0.5f)

            val contourPts = face.contour.map(::unit)
            g.contour = MKCurve(contourPts, perSegment = 6)
            g.faceWidth = max(contourPts[0].dist(contourPts[contourPts.size - 1]), g.eyeDist * 1.2f)
            g.chin = contourPts.maxByOrNull { (it - g.eyeMid).dot(g.down) } ?: contourPts[contourPts.size / 2]

            // 眼睛
            val ends = listOf(contourPts[0], contourPts[contourPts.size - 1])
            val built = ArrayList<MKEye>()
            for ((points, pupil, other) in listOf(Triple(leftPts, leftPupil, b), Triple(rightPts, rightPupil, a))) {
                val eye = makeEye(points, pupil, other, -g.down) ?: return null
                eye.side = ends.minOfOrNull { it.dist(eye.center) } ?: 0f
                built += eye
            }
            val widest = maxOf(built[0].side, built[1].side, 1e-4f)
            for (i in 0..1) built[i].visible = built[i].side / widest > 0.5f

            // 眉毛
            val nosePoint = g.eyeMid
            for ((i, pts) in listOf(leftBrowPts, rightBrowPts).withIndex()) {
                val brow = makeBrow(pts, -g.down, nosePoint)
                g.brows[i] = brow
                if (brow != null) {
                    val mid = built[i].upper.at(0.5f)
                    val lowEdge = brow.center.at(0.5f) - brow.up * (brow.thickness(0.5f) / 2)
                    built[i].browGap = max((mid - lowEdge).dot(g.down), built[i].width * 0.25f)
                } else {
                    built[i].browGap = built[i].width * 0.55f
                }
            }
            g.eyes = built

            // 嘴
            g.lipsOuter = face.outerLips.map(::unit)
            g.lipsInner = face.innerLips.map(::unit)
            g.mouthCenter = if (g.lipsInner.size >= 3) mkCentroid(g.lipsInner) else mouth
            val proj = g.lipsOuter.map { (it - mouth).dot(g.right) }
            g.mouthWidth = max((proj.maxOrNull() ?: 0f) - (proj.minOrNull() ?: 0f), g.eyeDist * 0.3f)
            // 唇峰中间：中线附近最靠上的点；下唇最低处：最靠下的点
            val central = g.lipsOuter.filter { abs((it - mouth).dot(g.right)) < g.mouthWidth * 0.2f }
            val pool = central.ifEmpty { g.lipsOuter }
            g.lipTop = pool.minByOrNull { (it - mouth).dot(g.down) } ?: mouth
            g.lipBottom = pool.maxByOrNull { (it - mouth).dot(g.down) } ?: mouth

            // 鼻子
            val nosePts = face.nose.map(::unit)
            if (nosePts.size >= 3) {
                val low = nosePts.sortedByDescending { (it - g.eyeMid).dot(g.down) }
                g.noseBase = mkCentroid(low.take(max(2, nosePts.size / 2)))
                val lateral = nosePts.map { (it - g.eyeMid).dot(g.right) }
                g.noseWidth = max((lateral.maxOrNull() ?: 0f) - (lateral.minOrNull() ?: 0f), g.eyeDist * 0.4f)
            } else {
                g.noseBase = g.eyeMid + g.down * (g.mouthDist * 0.62f)
                g.noseWidth = g.eyeDist * 0.55f
            }
            val ridgePts = ArrayList<MKVec>()
            val crest = face.noseCrest?.map(::unit)
            if (crest != null && crest.size >= 4) {
                // iOS（Vision）的鼻梁点最后两个是鼻尖两侧，要去掉；ML Kit 的鼻梁点只沿鼻梁到鼻尖，全部用上
                var last = -Float.MAX_VALUE
                for (p in crest) {
                    val v = (p - g.eyeMid).dot(g.down)
                    if (v > last) { ridgePts += p; last = v }
                }
            }
            if (ridgePts.size < 2) {
                val tip = g.noseBase - g.down * (g.mouthDist * 0.08f)
                ridgePts.clear()
                ridgePts += listOf(g.eyeMid, g.eyeMid.lerp(tip, 0.5f), tip)
            }
            // 往上补到眉心
            val top = ridgePts[0] - g.down * (g.eyeDist * 0.16f)
            g.ridge = MKCurve(listOf(top) + ridgePts, perSegment = 8)
            g.noseTip = ridgePts[ridgePts.size - 1]
            return g
        }

        private fun farthestPair(points: List<MKVec>): Pair<Int, Int> {
            var best = 0 to 1
            var bestDistance = -1f
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val d = points[i].dist(points[j])
                    if (d > bestDistance) { bestDistance = d; best = i to j }
                }
            }
            return best
        }

        private fun makeEye(points: List<MKVec>, pupil: MKVec?, other: MKVec, faceUp: MKVec): MKEye? {
            if (points.size < 4) return null
            val (i, j) = farthestPair(points)
            // 离另一只眼睛近的那个角是内眼角
            val innerIndex = if (points[i].dist(other) < points[j].dist(other)) i else j
            val outerIndex = if (innerIndex == i) j else i
            val inner = points[innerIndex]
            val outer = points[outerIndex]
            val width = inner.dist(outer)
            if (width <= 1e-4f) return null
            val axis = (outer - inner).unit
            var up = axis.perp
            if (up.dot(faceUp) < 0) up = -up
            var upperPts = ArrayList<Pair<Float, MKVec>>()
            var lowerPts = ArrayList<Pair<Float, MKVec>>()
            for ((k, p) in points.withIndex()) {
                if (k == innerIndex || k == outerIndex) continue
                val t = (p - inner).dot(axis)
                if ((p - inner).dot(up) >= 0) upperPts += t to p else lowerPts += t to p
            }
            upperPts.sortBy { it.first }
            lowerPts.sortBy { it.first }
            // 关键点偶尔全落在一侧：给另一侧补一个对称的点，保证上下眼睑都有弧度
            if (upperPts.isEmpty()) upperPts = arrayListOf(width / 2 to inner.lerp(outer, 0.5f) + up * (width * 0.14f))
            if (lowerPts.isEmpty()) lowerPts = arrayListOf(width / 2 to inner.lerp(outer, 0.5f) - up * (width * 0.1f))
            val upper = MKCurve(listOf(inner) + upperPts.map { it.second } + listOf(outer))
            val lower = MKCurve(listOf(inner) + lowerPts.map { it.second } + listOf(outer))
            val outline = ArrayList<MKVec>()
            for (s in 0..12) outline += upper.at(s / 12f)
            for (s in 11 downTo 1) outline += lower.at(s / 12f)
            var center = pupil ?: mkCentroid(points)
            // 瞳孔点偶尔会飘到眼睛外面，这时退回眼睛中心
            if (center.dist(mkCentroid(points)) > width * 0.45f) center = mkCentroid(points)
            return MKEye(inner, outer, upper, lower, center, width, axis, up, outline)
        }

        private fun makeBrow(points: List<MKVec>, faceUp: MKVec, medial: MKVec): MKBrow? {
            if (points.size < 3) return null
            val (i, j) = farthestPair(points)
            // 离脸中线近的那头是眉头
            val headIndex = if (points[i].dist(medial) < points[j].dist(medial)) i else j
            val tailIndex = if (headIndex == i) j else i
            val head = points[headIndex]
            val tail = points[tailIndex]
            val length = head.dist(tail)
            if (length <= 1e-4f) return null
            val axis = (tail - head).unit
            var up = axis.perp
            if (up.dot(faceUp) < 0) up = -up
            // 把点按沿眉毛方向的位置排好，分成上缘和下缘两条折线
            val upperPts = arrayListOf(0f to 0f, length to 0f)
            val lowerPts = arrayListOf(0f to 0f, length to 0f)
            for ((k, p) in points.withIndex()) {
                if (k == headIndex || k == tailIndex) continue
                val t = (p - head).dot(axis)
                val v = (p - head).dot(up)
                if (v >= 0) upperPts += t to v else lowerPts += t to v
            }
            upperPts.sortBy { it.first }
            lowerPts.sortBy { it.first }
            fun value(list: List<Pair<Float, Float>>, t: Float): Float {
                var previous = list.firstOrNull() ?: return 0f
                for (item in list.drop(1)) {
                    if (t <= item.first) {
                        val span = item.first - previous.first
                        return if (span > 0) previous.second + (item.second - previous.second) * (t - previous.first) / span else item.second
                    }
                    previous = item
                }
                return previous.second
            }
            val centerPts = ArrayList<MKVec>()
            var thickness = ArrayList<Float>()
            val count = 9
            // 关键点只有一条线（没有上下缘）时，粗细按眉长估一个
            val fallback = length * 0.11f
            for (s in 0 until count) {
                val f = s.toFloat() / (count - 1)
                val t = f * length
                val hi = value(upperPts, t)
                val lo = value(lowerPts, t)
                centerPts += head + axis * t + up * ((hi + lo) / 2)
                thickness += hi - lo
            }
            val measured = thickness.maxOrNull() ?: 0f
            if (measured < length * 0.04f) {
                thickness = ArrayList((0 until count).map { s ->
                    val f = s.toFloat() / (count - 1)
                    fallback * (1 - 0.75f * mkSmoothstep(0.45f, 1f, f))
                })
            }
            return MKBrow(MKCurve(centerPts, perSegment = 4), thickness, length, axis, up)
        }
    }
}
