package com.taoo.xiutu.tools.reshape

import android.graphics.PointF
import com.taoo.xiutu.ml.FaceInfo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 二维向量。 */
internal data class V2(val x: Float, val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(k: Float) = V2(x * k, y * k)
    operator fun div(k: Float) = V2(x / k, y / k)
    operator fun unaryMinus() = V2(-x, -y)
    fun dot(o: V2) = x * o.x + y * o.y
    val length: Float get() = hypot(x, y)

    companion object {
        val ZERO = V2(0f, 0f)
    }
}

// MARK: - 脸的几何

internal class Eye(
    val c: V2, val inner: V2, val outer: V2,
    /** 从内眼角指向外眼角的单位向量，以及和它垂直、朝下的单位向量。 */
    val a: V2, val n: V2, val w: Float,
    val pupil: V2, val polygon: List<V2>,
)

internal class Brow(val c: V2, val inner: V2, val outer: V2, val a: V2, val n: V2, val w: Float)

internal class Mouth(
    val c: V2, val left: V2, val right: V2, val a: V2, val n: V2, val w: Float,
    /** 上唇最高处、下唇最低处在脸坐标系里的 y。 */
    val top: Float, val bottom: Float,
    val inner: List<V2>,
)

/**
 * 一张脸自己的坐标系和各个部位的位置。
 * 全部以"图片长边 = 1"为单位（y 朝下），所以同一组数值在预览图和原图上效果一致。
 */
internal class FaceGeometry private constructor(
    /** 两眼中点。 */
    val origin: V2,
    /** 脸的横轴（沿两眼连线）和纵轴（朝下巴）。 */
    val ex: V2,
    val ey: V2,
    /** 脸宽、两眼间距、眼睛到下巴的距离。 */
    val fw: Float,
    val ed: Float,
    val hc: Float,
) {
    /** 画面左边的那只眼在前。 */
    var eyes: List<Eye> = emptyList()
    var brows: MutableList<Brow?> = mutableListOf(null, null)
    var mouth: Mouth? = null
    var noseBase: V2 = V2.ZERO
    var noseTip: V2 = V2.ZERO
    var noseRoot: V2 = V2.ZERO
    var noseWidth: Float = 0f
    /** 鼻子的关键点是不是落在该在的位置；不是的话鼻子的项目不做，上面几个值只是按比例估的。 */
    var noseValid = true
    /** 下颌轮廓，从画面左到右。 */
    var jaw: List<V2> = emptyList()
    var chin: V2 = V2.ZERO
    /** 下半张脸中线的横坐标（脸坐标系）。侧着脸时它不在两眼正中。 */
    var axisX: Float = 0f

    fun world(x: Float, y: Float): V2 = origin + ex * x + ey * y

    fun local(p: V2): V2 {
        val d = p - origin
        return V2(d.dot(ex), d.dot(ey))
    }

    /** 下颌线上的点：t = 0 是画面左侧与眼睛齐平的那一端，0.5 在下巴，1 是右侧那一端。 */
    fun jawPoint(t: Float): V2 {
        if (jaw.size < 2) return chin
        val f = t.coerceIn(0f, 1f) * (jaw.size - 1)
        val i = min(f.toInt(), jaw.size - 2)
        return jaw[i] + (jaw[i + 1] - jaw[i]) * (f - i)
    }

    companion object {
        fun make(face: FaceInfo, aspect: V2): FaceGeometry? {
            if (!face.hasReliableLandmarks) return null
            fun P(p: PointF) = V2(p.x * aspect.x, p.y * aspect.y)
            fun centroid(points: List<V2>): V2 {
                var s = V2.ZERO
                for (p in points) s += p
                return s / max(points.size, 1).toFloat()
            }
            var a = face.leftEye.map(::P)
            var b = face.rightEye.map(::P)
            var browA = face.leftBrow.map(::P)
            var browB = face.rightBrow.map(::P)
            var pupilA = face.leftPupil?.let(::P)
            var pupilB = face.rightPupil?.let(::P)
            val contour = face.contour.map(::P)
            var ca = centroid(a)
            var cb = centroid(b)
            fun swapAll() {
                a = b.also { b = a }
                ca = cb.also { cb = ca }
                browA = browB.also { browB = browA }
                pupilA = pupilB.also { pupilB = pupilA }
            }
            if (ca.x > cb.x) swapAll()
            val distance = (cb - ca).length
            if (distance <= 1e-5f) return null
            var x = (cb - ca) / distance
            var y = V2(-x.y, x.x)
            val o = (ca + cb) / 2f
            // 下巴应该在纵轴的正方向；倒过来的脸把两根轴都翻一下。
            val below = contour.fold(0f) { s, p -> s + (p - o).dot(y) }
            if (below < 0) {
                x = -x; y = -y
                swapAll()
            }
            fun L(p: V2) = V2((p - o).dot(x), (p - o).dot(y))

            // 下颌线：按脸坐标系从左到右排。
            var line = contour
            if (line.isNotEmpty() && L(line.first()).x > L(line.last()).x) line = line.reversed()
            val xs = line.map { L(it).x }
            val fw = max((xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f), distance * 1.6f)
            val lowest = line.maxByOrNull { L(it).y } ?: (o + y * (distance * 2))
            val hc = max(L(lowest).y, distance * 1.2f)
            // 整体比例先过一遍：眼距、脸宽、眼睛到下巴的距离对不上，说明这组关键点是乱的，整张脸都不做。
            val chinDepth = L(lowest).y
            if (!(chinDepth > distance * 0.9f && chinDepth < distance * 3.2f && fw > distance * 1.25f && fw < distance * 4.5f)) return null

            val g = FaceGeometry(o, x, y, fw, distance, hc)
            g.jaw = line
            g.chin = lowest

            fun eye(points: List<V2>, center: V2, pupil: V2?): Eye {
                val sorted = points.sortedBy { L(it).x }
                val p0 = sorted.firstOrNull() ?: center
                val p1 = sorted.lastOrNull() ?: center
                // 离脸中线近的是内眼角。
                val innerFirst = abs(L(p0).x) < abs(L(p1).x)
                val inner = if (innerFirst) p0 else p1
                val outer = if (innerFirst) p1 else p0
                val w = max((outer - inner).length, distance * 0.2f)
                val axis = (outer - inner) / max((outer - inner).length, 1e-6f)
                var normal = V2(-axis.y, axis.x)
                if (normal.dot(y) < 0) normal = -normal
                return Eye(center, inner, outer, axis, normal, w, pupil ?: center, points)
            }
            g.eyes = listOf(eye(a, ca, pupilA), eye(b, cb, pupilB))
            if (!g.eyes.all { it.w > distance * 0.12f && it.w < distance * 0.95f }) return null

            fun brow(points: List<V2>): Brow? {
                if (points.size < 3) return null
                val sorted = points.sortedBy { L(it).x }
                val p0 = sorted[0]
                val p1 = sorted[sorted.size - 1]
                val innerFirst = abs(L(p0).x) < abs(L(p1).x)
                val inner = if (innerFirst) p0 else p1
                val outer = if (innerFirst) p1 else p0
                val length = (outer - inner).length
                if (length <= distance * 0.15f) return null
                val axis = (outer - inner) / length
                var normal = V2(-axis.y, axis.x)
                if (normal.dot(y) < 0) normal = -normal
                return Brow(centroid(points), inner, outer, axis, normal, length)
            }
            g.brows = mutableListOf(brow(browA), brow(browB))
            // 眉毛应该在同一侧眼睛的正上方不远处，否则这条眉毛不做。
            for (i in 0 until 2) {
                val item = g.brows[i] ?: continue
                val lb = L(item.c)
                val le = L(g.eyes[i].c)
                val above = le.y - lb.y
                if (lb.x * le.x <= 0 || above < distance * 0.06f || above > distance * 0.9f ||
                    item.w < g.eyes[i].w * 0.4f || item.w > g.eyes[i].w * 3
                ) g.brows[i] = null
            }

            val lips = face.outerLips.map(::P)
            if (lips.size >= 6) {
                val sorted = lips.sortedBy { L(it).x }
                val left = sorted[0]
                val right = sorted[sorted.size - 1]
                val w = (right - left).length
                if (w > distance * 0.2f) {
                    val axis = (right - left) / w
                    var normal = V2(-axis.y, axis.x)
                    if (normal.dot(y) < 0) normal = -normal
                    val ys = lips.map { L(it).y }
                    val top = ys.minOrNull() ?: 0f
                    val bottom = ys.maxOrNull() ?: 0f
                    val mid = (left + right) / 2f
                    val c = o + x * L(mid).x + y * ((top + bottom) / 2)
                    // 嘴应该在鼻子下面、下巴上面、靠近脸的中线，否则嘴唇的项目不做。
                    val lc = L(c)
                    if (lc.y > hc * 0.4f && lc.y < hc * 0.97f && abs(lc.x) < fw * 0.4f && w > fw * 0.12f && w < fw * 0.8f) {
                        g.mouth = Mouth(c, left, right, axis, normal, w, top, bottom, face.innerLips.map(::P))
                    }
                }
            }

            val nose = face.nose.map(::P)
            var valid = false
            if (nose.size >= 5) {
                val ys = nose.map { L(it).y }
                val top = ys.minOrNull() ?: 0f
                val bottom = ys.maxOrNull() ?: 0f
                val low = nose.filter { L(it).y >= bottom - (bottom - top) * 0.3f }
                val base = centroid(low)
                val nx = nose.map { L(it).x }
                val width = (nx.maxOrNull() ?: 0f) - (nx.minOrNull() ?: 0f)
                val lb = L(base)
                if (lb.y > hc * 0.18f && lb.y < hc * 0.75f && abs(lb.x) < fw * 0.35f && width > fw * 0.06f && width < fw * 0.6f) {
                    g.noseBase = base
                    g.noseWidth = max(width, distance * 0.3f)
                    valid = true
                }
            }
            if (!valid) {
                g.noseBase = o + y * (hc * 0.42f)
                g.noseWidth = distance * 0.55f
            }
            g.noseValid = valid
            g.noseRoot = o + y * (hc * 0.03f)
            var tip = g.noseBase - y * (hc * 0.06f)
            val crest = face.noseCrest?.map(::P)
            if (valid && crest != null && crest.size >= 4) {
                // iOS 的鼻梁线最后两个点在鼻尖两侧要去掉；ML Kit 的鼻梁线是从眉心插值到鼻尖，最后一点就是鼻尖，所以整条都用。
                crest.maxByOrNull { L(it).y }?.let { tip = it }
                g.noseRoot = crest.first()
            }
            g.noseTip = tip
            g.axisX = (L(g.noseBase).x + L(g.chin).x) / 2
            return g
        }
    }
}

// MARK: - 变形的基本动作

/** 局部变形：在一个椭圆范围里平移、缩放、单向拉伸或旋转，往外平滑衰减到 0。 */
internal class LocalWarp(
    /** 权重椭圆：中心、长轴方向（单位向量）、两个半径。 */
    val wc: V2, val ax: V2, val rx: Float, val ry: Float,
    /** 缩放和旋转的中心。 */
    val c: V2,
    val kind: Kind,
    /** 椭圆里这么大比例的中间部分整体一起动（权重 = 1），之外才开始衰减。 */
    val plateau: Float = 0f,
) {
    sealed class Kind {
        class Translate(val shift: V2) : Kind()
        /** 围绕 c 等比缩放。 */
        class Scale(val factor: Float) : Kind()
        /** 只沿这根轴（单位向量）缩放。 */
        class Stretch(val axis: V2, val factor: Float) : Kind()
        /** 围绕 c 旋转（弧度，画面上顺时针为正）。 */
        class Rotate(val angle: Float) : Kind()
    }

    val radius: Float get() = max(rx, ry)

    /** 这个动作让位于 (px, py) 的像素从哪里取样（相对偏移，写进 out[0..1]）。 */
    fun offset(px: Float, py: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        val dx = px - wc.x
        val dy = py - wc.y
        val u = (dx * ax.x + dy * ax.y) / rx
        val v = (dy * ax.x - dx * ax.y) / ry
        val t2 = u * u + v * v
        if (t2 >= 1) return
        val t = sqrt(t2)
        var w = 1f
        if (t > plateau) {
            val s = (t - plateau) / (1 - plateau)
            w = 1 - s * s * (3 - 2 * s)
        }
        when (val k = kind) {
            is Kind.Translate -> { out[0] = -k.shift.x * w; out[1] = -k.shift.y * w }
            is Kind.Scale -> {
                val f = 1 + (k.factor - 1) * w
                val m = 1 / f - 1
                out[0] = (px - c.x) * m; out[1] = (py - c.y) * m
            }
            is Kind.Stretch -> {
                val f = 1 + (k.factor - 1) * w
                val along = ((px - c.x) * k.axis.x + (py - c.y) * k.axis.y) * (1 / f - 1)
                out[0] = k.axis.x * along; out[1] = k.axis.y * along
            }
            is Kind.Rotate -> {
                val rxp = px - c.x
                val ryp = py - c.y
                val sn = sin(-k.angle * w)
                val cs = cos(-k.angle * w)
                out[0] = rxp * cs - ryp * sn - rxp
                out[1] = rxp * sn + ryp * cs - ryp
            }
        }
    }
}

/**
 * 一段一段挪：纵向在 y0→y1 之间从不动过渡到整体挪动，一直保持到 y2，再在 y2→y3 之间还原；
 * 横向离中线 x0 以内完全生效，到 x1 衰减为 0。用来改中庭、人中、下庭、下巴这些"长度"。
 */
internal class BandShift(
    val origin: V2, val ex: V2, val ey: V2,
    val y0: Float, val y1: Float, val y2: Float, val y3: Float,
    val xc: Float, val x0: Float, val x1: Float,
    val shift: V2,
) {
    val center: V2 get() = origin + ex * xc + ey * ((y0 + y3) / 2)
    val radius: Float get() = hypot((y3 - y0) / 2, x1)

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val s = ((v - a) / max(b - a, 1e-6f)).coerceIn(0f, 1f)
        return s * s * (3 - 2 * s)
    }

    fun offset(px: Float, py: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        val dx = px - origin.x
        val dy = py - origin.y
        val y = dx * ey.x + dy * ey.y
        if (y <= y0 || y >= y3) return
        val x = abs(dx * ex.x + dy * ex.y - xc)
        if (x >= x1) return
        val g = smooth(y0, y1, y) * (1 - smooth(y2, y3, y)) * (1 - smooth(x0, x1, x))
        out[0] = -shift.x * g; out[1] = -shift.y * g
    }
}

internal sealed class Warp {
    class Local(val w: LocalWarp) : Warp()
    class Band(val b: BandShift) : Warp()

    val center: V2
        get() = when (this) {
            is Local -> w.wc
            is Band -> b.center
        }

    val radius: Float
        get() = when (this) {
            is Local -> w.radius
            is Band -> b.radius
        }

    fun offset(px: Float, py: Float, out: FloatArray) = when (this) {
        is Local -> w.offset(px, py, out)
        is Band -> b.offset(px, py, out)
    }
}

// MARK: - 把滑块数值变成变形动作

internal object ReshapePlan {
    /** 一张脸上所有起作用的变形动作。 */
    fun warps(face: ReshapeFace, g: FaceGeometry): List<Warp> {
        val out = ArrayList<Warp>()
        val fw = g.fw
        val hc = g.hc
        val ed = g.ed
        fun v(key: ReshapeKey): Float = face.values[key]?.left ?: 0f
        fun side(key: ReshapeKey, index: Int): Float {
            val value = face.values[key] ?: return 0f
            return if (index == 0) value.left else value.right
        }
        fun local(wc: V2, kind: LocalWarp.Kind, rx: Float, ry: Float, axis: V2? = null, c: V2? = null, plateau: Float = 0f) {
            out += Warp.Local(LocalWarp(wc, axis ?: g.ex, max(rx, 1e-5f), max(ry, 1e-5f), c ?: wc, kind, plateau))
        }
        fun band(y0: Float, y1: Float, y2: Float, y3: Float, x0: Float, x1: Float, shift: Float, xc: Float? = null) {
            if (!(y1 > y0 && y3 > y2)) return
            out += Warp.Band(BandShift(g.origin, g.ex, g.ey, y0, y1, max(y2, y1), y3, xc ?: g.axisX, x0, x1, g.ey * shift))
        }
        fun translate(s: V2) = LocalWarp.Kind.Translate(s)
        fun scale(f: Float) = LocalWarp.Kind.Scale(f)
        fun stretch(axis: V2, f: Float) = LocalWarp.Kind.Stretch(axis, f)
        fun rotate(a: Float) = LocalWarp.Kind.Rotate(a)

        val noseY = g.local(g.noseBase).y
        val chinY = g.local(g.chin).y
        val mouthTop = g.mouth?.top ?: (noseY + (chinY - noseY) * 0.3f)
        val mouthBottom = g.mouth?.bottom ?: (noseY + (chinY - noseY) * 0.55f)
        val neck0 = chinY + hc * 0.12f
        val neck1 = chinY + hc * 0.75f
        /** 把下颌线上的一点朝脸里面推。 */
        fun pushJaw(t: Float, amount: Float, radius: Float, toward: V2? = null) {
            if (amount == 0f) return
            val p = g.jawPoint(t)
            val target = toward ?: g.world(g.axisX, g.local(p).y)
            val d = target - p
            val length = d.length
            if (length <= 1e-6f) return
            local(p, translate(d / length * amount), radius, radius, plateau = 0.15f)
        }

        // ---- 3D 塑颜
        val tilt = v(ReshapeKey.HEAD_TILT)
        if (tilt != 0f) {
            // 以脖子为轴转头；权重盖住整个头（连头发），到肩膀衰减为 0。
            local(g.world(g.axisX * 0.5f, hc * 0.05f), rotate(tilt * 0.09f), fw * 1.5f, hc * 2.0f,
                c = g.world(g.axisX, chinY + hc * 0.25f), plateau = 0.4f)
        }

        // ---- 比例
        // 这一组和下面的脸型，范围都盖到头发、耳朵外面，再很宽地过渡到 0：
        // 脸动的时候头的轮廓跟着一起动，而不是脸在一个不动的头里面滑。
        if (v(ReshapeKey.SMALL_HEAD) != 0f) {
            // 整个头朝脖子收，肩膀不动。
            local(g.world(g.axisX * 0.5f, hc * 0.15f), scale(1 - 0.08f * v(ReshapeKey.SMALL_HEAD)), fw * 1.6f, hc * 2.3f,
                c = g.world(g.axisX, chinY + hc * 0.3f), plateau = 0.4f)
        }
        if (v(ReshapeKey.FACE_IN_HEAD) != 0f) {
            // 只收五官和脸颊这一圈，头发的外轮廓基本不动。
            local(g.world(g.axisX * 0.5f, hc * 0.35f), scale(1 - 0.07f * v(ReshapeKey.FACE_IN_HEAD)), fw * 0.74f, hc * 1.2f, plateau = 0.2f)
        }
        if (v(ReshapeKey.CROWN) != 0f) {
            local(g.world(0f, -hc * 1.0f), translate(g.ey * (-0.08f * hc * v(ReshapeKey.CROWN))), fw * 1.15f, hc * 0.8f, plateau = 0.15f)
        }
        if (v(ReshapeKey.FOREHEAD) != 0f) {
            // 发际线以上整体挪，眉毛到发际线之间均匀拉长或压短。
            band(-hc * 1.95f, -hc * 1.3f, -hc * 0.7f, -hc * 0.2f, fw * 0.75f, fw * 1.5f,
                -0.07f * hc * v(ReshapeKey.FOREHEAD), xc = 0f)
        }
        if (v(ReshapeKey.MID_FACE) != 0f) {
            band(hc * 0.06f, noseY, neck0, neck1, fw * 0.7f, fw * 1.5f, -0.06f * hc * v(ReshapeKey.MID_FACE))
        }
        if (v(ReshapeKey.PHILTRUM) != 0f) {
            band(noseY, mouthTop, neck0, neck1, fw * 0.7f, fw * 1.5f, -0.3f * (mouthTop - noseY) * v(ReshapeKey.PHILTRUM))
        }
        if (v(ReshapeKey.LOWER_FACE) != 0f) {
            band(noseY, chinY, neck0, neck1, fw * 0.7f, fw * 1.5f, -0.07f * hc * v(ReshapeKey.LOWER_FACE))
        }
        if (v(ReshapeKey.SHORT_FACE) != 0f) {
            band(-hc * 0.5f, chinY, neck0, neck1, fw * 0.75f, fw * 1.5f, -0.09f * hc * v(ReshapeKey.SHORT_FACE))
        }

        // ---- 脸型
        val smart = v(ReshapeKey.SMART_SHAPE)
        val flow = v(ReshapeKey.SMOOTH_FACE)
        val width = v(ReshapeKey.FACE_WIDTH) - 0.45f * smart
        if (width != 0f) {
            // 整个头一起变窄 / 变宽：脸颊到下颌这一段最明显，额头、太阳穴、耳朵和头发跟着走，
            // 往头顶和脖子逐渐减弱。拉满是头宽的 8% 左右。
            val c = g.world(g.axisX * 0.5f, hc * 0.4f)
            local(c, stretch(g.ex, 1 + 0.08f * width), fw * 1.6f, hc * 2.0f, c = c, plateau = 0.35f)
        }
        val small = v(ReshapeKey.SMALL_FACE) + 0.25f * smart
        if (small != 0f) {
            // 整张脸往中间收一点，头发的外轮廓跟着收一半左右。
            local(g.world(g.axisX * 0.5f, hc * 0.35f), scale(1 - 0.07f * small), fw * 1.2f, hc * 1.6f,
                c = g.world(g.axisX * 0.6f, hc * 0.25f), plateau = 0.3f)
        }
        val cheek = v(ReshapeKey.CHEEKBONE) + 0.25f * smart + 0.6f * flow
        if (cheek != 0f) {
            pushJaw(0.07f, 0.035f * fw * cheek, fw * 0.42f)
            pushJaw(0.93f, 0.035f * fw * cheek, fw * 0.42f)
        }
        if (flow != 0f) {
            // 太阳穴稍微往外补一点，轮廓从额头到脸颊就顺了。
            for (sign in floatArrayOf(-1f, 1f)) {
                val p = g.world(sign * fw * 0.5f, -hc * 0.3f)
                local(p, translate(g.ex * (sign * 0.018f * fw * flow)), fw * 0.32f, fw * 0.32f, plateau = 0.15f)
            }
        }
        val vface = v(ReshapeKey.V_FACE) + 0.45f * smart
        if (vface != 0f) {
            val target = g.world(g.axisX, hc * 0.3f)
            pushJaw(0.3f, 0.045f * fw * vface, fw * 0.38f, target)
            pushJaw(0.7f, 0.045f * fw * vface, fw * 0.38f, target)
            pushJaw(0.4f, 0.02f * fw * vface, fw * 0.3f, target)
            pushJaw(0.6f, 0.02f * fw * vface, fw * 0.3f, target)
        }
        val angle = v(ReshapeKey.JAW_ANGLE) + 0.3f * smart + 0.5f * flow
        if (angle != 0f) {
            pushJaw(0.2f, 0.045f * fw * angle, fw * 0.34f)
            pushJaw(0.8f, 0.045f * fw * angle, fw * 0.34f)
        }
        if (v(ReshapeKey.JAW_LINE) != 0f) {
            val target = g.world(g.axisX, hc * 0.45f)
            for (t in floatArrayOf(0.3f, 0.38f, 0.62f, 0.7f)) {
                pushJaw(t, 0.022f * fw * v(ReshapeKey.JAW_LINE), fw * 0.24f, target)
            }
        }
        if (v(ReshapeKey.CHIN_LENGTH) != 0f) {
            band(mouthBottom, chinY, neck0, neck1, fw * 0.2f, fw * 0.8f, 0.06f * hc * v(ReshapeKey.CHIN_LENGTH))
        }
        if (v(ReshapeKey.POINTED_CHIN) != 0f) {
            val c = g.chin - g.ey * (hc * 0.08f)
            local(c, stretch(g.ex, 1 - 0.22f * v(ReshapeKey.POINTED_CHIN)), fw * 0.42f, hc * 0.36f)
            local(g.chin, translate(g.ey * (0.015f * hc * v(ReshapeKey.POINTED_CHIN))), fw * 0.24f, fw * 0.24f, plateau = 0.15f)
        }
        if (v(ReshapeKey.HAIRLINE) != 0f) {
            local(g.world(0f, -hc * 0.72f), translate(g.ey * (0.05f * hc * v(ReshapeKey.HAIRLINE))), fw * 0.75f, hc * 0.42f, plateau = 0.2f)
        }

        // ---- 眼睛（画面左、右各一只）
        for ((i, e) in g.eyes.withIndex()) {
            val w = e.w
            val outward = if (g.local(e.c).x < 0) -g.ex else g.ex
            var s = side(ReshapeKey.EYE_SIZE, i)
            if (s != 0f) local(e.c, scale(1 + 0.16f * s), w * 0.95f, w * 0.72f, axis = e.a)
            s = side(ReshapeKey.EYE_VERTICAL, i)
            if (s != 0f) local(e.c, translate(g.ey * (-0.10f * w * s)), w, w * 0.62f, axis = e.a, plateau = 0.45f)
            s = side(ReshapeKey.EYE_HEIGHT, i)
            if (s != 0f) local(e.c, stretch(e.n, 1 + 0.25f * s), w * 0.8f, w * 0.5f, axis = e.a)
            s = side(ReshapeKey.EYE_LENGTH, i)
            if (s != 0f) local(e.c, stretch(e.a, 1 + 0.14f * s), w * 1.05f, w * 0.5f, axis = e.a)
            s = side(ReshapeKey.EYE_DISTANCE, i)
            if (s != 0f) local(e.c, translate(outward * (0.08f * w * s)), w, w * 0.62f, axis = e.a, plateau = 0.45f)
            s = side(ReshapeKey.EYE_LIFT, i)
            if (s != 0f) {
                local(e.c - e.n * (w * 0.17f), translate(e.n * (-0.08f * w * s)), w * 0.58f, w * 0.3f, axis = e.a, plateau = 0.1f)
            }
            s = side(ReshapeKey.IRIS_SIZE, i)
            if (s != 0f) local(e.pupil, scale(1 + 0.22f * s), w * 0.3f, w * 0.3f)
            s = side(ReshapeKey.LOWER_LID, i)
            if (s != 0f) {
                local(e.c + e.a * (w * 0.2f) + e.n * (w * 0.14f), translate(e.n * (0.08f * w * s)), w * 0.45f, w * 0.3f,
                    axis = e.a, plateau = 0.1f)
            }
            s = side(ReshapeKey.EYE_TAIL, i)
            if (s != 0f) local(e.outer, translate(e.n * (-0.10f * w * s)), w * 0.36f, w * 0.36f, plateau = 0.15f)
            s = side(ReshapeKey.EYE_TILT, i)
            if (s != 0f) {
                // 让外眼角往上走：按内→外的轴和"朝下"的关系决定转的方向。
                val perp = V2(-e.a.y, e.a.x)
                val direction = if (perp.dot(e.n) > 0) -1f else 1f
                local(e.c, rotate(direction * 0.12f * s), w * 0.85f, w * 0.6f, axis = e.a)
            }
            s = side(ReshapeKey.INNER_CORNER, i)
            if (s != 0f) local(e.inner, translate(e.a * (-0.09f * w * s)), w * 0.3f, w * 0.3f, plateau = 0.1f)
            s = side(ReshapeKey.OUTER_CORNER, i)
            if (s != 0f) local(e.outer, translate(e.a * (0.10f * w * s)), w * 0.32f, w * 0.32f, plateau = 0.1f)
        }

        // ---- 嘴唇
        g.mouth?.let { m ->
            val w = m.w
            if (v(ReshapeKey.LIP_SIZE) != 0f) local(m.c, scale(1 + 0.16f * v(ReshapeKey.LIP_SIZE)), w * 0.78f, w * 0.55f, axis = m.a)
            if (v(ReshapeKey.LIP_WIDTH) != 0f) local(m.c, stretch(m.a, 1 + 0.15f * v(ReshapeKey.LIP_WIDTH)), w * 0.85f, w * 0.45f, axis = m.a)
            if (v(ReshapeKey.LIP_VERTICAL) != 0f) {
                local(m.c, translate(m.n * (-0.07f * w * v(ReshapeKey.LIP_VERTICAL))), w * 0.8f, w * 0.5f, axis = m.a, plateau = 0.4f)
            }
            if (v(ReshapeKey.LIP_TILT) != 0f) local(m.c, rotate(-0.09f * v(ReshapeKey.LIP_TILT)), w * 0.8f, w * 0.55f, axis = m.a)
            if (v(ReshapeKey.LIP_HORIZONTAL) != 0f) {
                local(m.c, translate(m.a * (0.06f * w * v(ReshapeKey.LIP_HORIZONTAL))), w * 0.8f, w * 0.5f, axis = m.a, plateau = 0.4f)
            }
            if (v(ReshapeKey.M_LIP) != 0f) {
                val top = m.c - m.n * ((m.bottom - m.top) / 2)
                for (sign in floatArrayOf(-1f, 1f)) {
                    local(top + m.a * (sign * w * 0.17f), translate(m.n * (-0.05f * w * v(ReshapeKey.M_LIP))), w * 0.15f, w * 0.15f, plateau = 0.1f)
                }
                local(top, translate(m.n * (0.02f * w * v(ReshapeKey.M_LIP))), w * 0.09f, w * 0.09f)
            }
            if (v(ReshapeKey.FULL_LIP) != 0f) local(m.c, stretch(m.n, 1 + 0.28f * v(ReshapeKey.FULL_LIP)), w * 0.62f, w * 0.36f, axis = m.a)
            if (v(ReshapeKey.SMILE) != 0f) {
                for ((corner, sign) in listOf(m.left to -1f, m.right to 1f)) {
                    local(corner, translate((m.n * (-0.085f * w) + m.a * (sign * 0.025f * w)) * v(ReshapeKey.SMILE)),
                        w * 0.3f, w * 0.3f, plateau = 0.15f)
                }
            }
        }

        // ---- 鼻子
        val nw = g.noseWidth
        if (g.noseValid && v(ReshapeKey.NOSE_SIZE) != 0f) {
            local(g.noseBase - g.ey * (hc * 0.1f), scale(1 + 0.13f * v(ReshapeKey.NOSE_SIZE)), nw * 0.8f, hc * 0.28f)
        }
        if (g.noseValid && v(ReshapeKey.NOSE_LIFT) != 0f) {
            local(g.noseBase - g.ey * (hc * 0.03f), translate(g.ey * (-0.04f * hc * v(ReshapeKey.NOSE_LIFT))), nw * 0.72f, hc * 0.16f, plateau = 0.3f)
        }
        if (g.noseValid && v(ReshapeKey.NOSE_WING) != 0f) {
            local(g.noseBase - g.ey * (hc * 0.03f), stretch(g.ex, 1 - 0.17f * v(ReshapeKey.NOSE_WING)), nw * 0.85f, hc * 0.15f, c = g.noseBase)
        }
        if (g.noseValid && v(ReshapeKey.NOSE_ROOT) != 0f) {
            local(g.noseRoot, stretch(g.ex, 1 - 0.25f * v(ReshapeKey.NOSE_ROOT)), ed * 0.3f, hc * 0.17f)
        }
        if (g.noseValid && v(ReshapeKey.NOSE_BRIDGE) != 0f) {
            local((g.noseRoot + g.noseTip) / 2f, stretch(g.ex, 1 - 0.25f * v(ReshapeKey.NOSE_BRIDGE)), ed * 0.32f, hc * 0.22f)
        }
        if (g.noseValid && v(ReshapeKey.NOSE_TIP) != 0f) {
            local(g.noseTip, translate(g.ey * (-0.03f * hc * v(ReshapeKey.NOSE_TIP))), nw * 0.36f, nw * 0.36f, plateau = 0.15f)
        }

        // ---- 眉毛
        for ((i, item) in g.brows.withIndex()) {
            val b = item ?: continue
            val w = b.w
            val outward = if (g.local(b.c).x < 0) -g.ex else g.ex
            var s = side(ReshapeKey.BROW_VERTICAL, i)
            if (s != 0f) {
                // 范围往上偏一点、收窄一点，免得把上眼皮也带着动。
                local(b.c - b.n * (w * 0.06f), translate(g.ey * (-0.06f * w * s)), w * 0.75f, w * 0.34f, axis = b.a, plateau = 0.35f)
            }
            s = side(ReshapeKey.BROW_THICKNESS, i)
            if (s != 0f) local(b.c, stretch(b.n, 1 + 0.3f * s), w * 0.62f, w * 0.2f, axis = b.a)
            s = side(ReshapeKey.BROW_LENGTH, i)
            if (s != 0f) {
                local(b.c + b.a * (w * 0.1f), stretch(b.a, 1 + 0.12f * s), w * 0.8f, w * 0.24f, axis = b.a, c = b.inner)
            }
            s = side(ReshapeKey.BROW_DISTANCE, i)
            if (s != 0f) local(b.c, translate(outward * (0.08f * w * s)), w * 0.72f, w * 0.3f, axis = b.a, plateau = 0.4f)
            s = side(ReshapeKey.BROW_HEAD_DISTANCE, i)
            if (s != 0f) local(b.inner, translate(b.a * (0.09f * w * s)), w * 0.3f, w * 0.3f, plateau = 0.1f)
            s = side(ReshapeKey.BROW_TILT, i)
            if (s != 0f) {
                val perp = V2(-b.a.y, b.a.x)
                val direction = if (perp.dot(b.n) > 0) -1f else 1f
                local(b.c, rotate(direction * 0.11f * s), w * 0.75f, w * 0.32f, axis = b.a)
            }
            s = side(ReshapeKey.BROW_PEAK, i)
            if (s != 0f) {
                local(b.c + b.a * (w * 0.2f) - b.n * (w * 0.06f), translate(b.n * (-0.06f * w * s)), w * 0.42f, w * 0.3f, axis = b.a)
            }
        }
        return out
    }
}

// MARK: - 位移场

/**
 * 一张脸附近的位移场：一小块网格，每格记录"这里的画面要从哪边取"。
 * 存的是"位移 ÷ 这张网格里最大的位移"（-1...1），渲染时按 maxOffset 还原；格子之间由 GPU 双线性插值。
 */
internal class ReshapeField(
    /** 网格盖住的范围（长边为 1 的坐标，y 朝下）。 */
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val gw: Int,
    val gh: Int,
    /** 每格两个数（x, y），已经除以 maxOffset。 */
    val data: FloatArray,
    /** 最大位移（长边为 1）。 */
    val maxOffset: Float,
) {
    companion object {
        fun make(warps: List<Warp>, limit: V2, restore: List<ReshapeRestoreStroke> = emptyList()): ReshapeField? {
            if (warps.isEmpty()) return null
            var loX = Float.MAX_VALUE
            var loY = Float.MAX_VALUE
            var hiX = -Float.MAX_VALUE
            var hiY = -Float.MAX_VALUE
            for (w in warps) {
                val r = w.radius * 1.06f
                val c = w.center
                loX = min(loX, c.x - r); loY = min(loY, c.y - r)
                hiX = max(hiX, c.x + r); hiY = max(hiY, c.y + r)
            }
            loX = max(loX, 0f); loY = max(loY, 0f)
            hiX = min(hiX, limit.x); hiY = min(hiY, limit.y)
            val sx = hiX - loX
            val sy = hiY - loY
            if (sx <= 1e-4f || sy <= 1e-4f) return null
            val long = max(sx, sy)
            val cells = 128f
            val gw = max(4, ceil(sx / long * cells).toInt())
            val gh = max(4, ceil(sy / long * cells).toInt())
            val buf = FloatArray(gw * gh * 2)
            val tmp = FloatArray(2)
            // 每个动作只算它的范围盖到的那些格子。最外面一圈保持不动，这样网格之外和原图自然接上。
            for (warp in warps) {
                val c = warp.center
                val r = warp.radius
                val i0 = max(1, floor((c.x - r - loX) / sx * gw).toInt())
                val i1 = min(gw - 2, ceil((c.x + r - loX) / sx * gw).toInt())
                val j0 = max(1, floor((c.y - r - loY) / sy * gh).toInt())
                val j1 = min(gh - 2, ceil((c.y + r - loY) / sy * gh).toInt())
                if (i0 > i1 || j0 > j1) continue
                for (j in j0..j1) {
                    val y = loY + (j + 0.5f) / gh * sy
                    for (i in i0..i1) {
                        warp.offset(loX + (i + 0.5f) / gw * sx, y, tmp)
                        val n = (j * gw + i) * 2
                        buf[n] += tmp[0]; buf[n + 1] += tmp[1]
                    }
                }
            }
            // 变形还原：笔刷涂过的格子把位移按比例抹掉。
            for (stroke in restore) {
                val r = stroke.radius
                val first = stroke.points.firstOrNull() ?: continue
                if (r <= 1e-5f) continue
                fun P(p: PointF) = V2(p.x * limit.x, p.y * limit.y)
                val stamps = arrayListOf(P(first))
                for (point in stroke.points.drop(1)) {
                    val a = stamps[stamps.size - 1]
                    val b = P(point)
                    val steps = max(1, ceil((b - a).length / (r * 0.35f)).toInt())
                    for (n in 1..steps) stamps += a + (b - a) * (n.toFloat() / steps)
                }
                for (c in stamps) {
                    val i0 = max(0, floor((c.x - r - loX) / sx * gw).toInt())
                    val i1 = min(gw - 1, ceil((c.x + r - loX) / sx * gw).toInt())
                    val j0 = max(0, floor((c.y - r - loY) / sy * gh).toInt())
                    val j1 = min(gh - 1, ceil((c.y + r - loY) / sy * gh).toInt())
                    if (i0 > i1 || j0 > j1) continue
                    for (j in j0..j1) {
                        val y = loY + (j + 0.5f) / gh * sy - c.y
                        for (i in i0..i1) {
                            val x = loX + (i + 0.5f) / gw * sx - c.x
                            val t2 = (x * x + y * y) / (r * r)
                            if (t2 >= 1) continue
                            // 中间完全还原，往边缘平滑过渡；多次盖印只会越抹越干净。
                            val w = min(1f, (1 - t2) * (1 - t2) * 1.6f)
                            val n = (j * gw + i) * 2
                            buf[n] *= 1 - w; buf[n + 1] *= 1 - w
                        }
                    }
                }
            }
            // 很多项一起拉满时，叠加出来的位移可能把画面挤得太狠甚至折过去。
            // 找出挤得过头的格子，把那一小片位移抹平一点，直到不再折叠。
            val hx = sx / gw
            val hy = sy / gh
            val bad = BooleanArray(gw * gh)
            for (round in 0 until 12) {
                var found = false
                bad.fill(false)
                for (j in 0 until gh - 1) {
                    for (i in 0 until gw - 1) {
                        val n0 = (j * gw + i) * 2
                        val nx = (j * gw + i + 1) * 2
                        val ny = ((j + 1) * gw + i) * 2
                        val a = 1 + (buf[nx] - buf[n0]) / hx
                        val b = (buf[ny] - buf[n0]) / hy
                        val c = (buf[nx + 1] - buf[n0 + 1]) / hx
                        val d = 1 + (buf[ny + 1] - buf[n0 + 1]) / hy
                        if (a * d - b * c < 0.3f) {
                            found = true
                            for (jj in max(0, j - 1)..min(gh - 1, j + 2)) {
                                for (ii in max(0, i - 1)..min(gw - 1, i + 2)) bad[jj * gw + ii] = true
                            }
                        }
                    }
                }
                if (!found) break
                val before = buf.copyOf()
                for (j in 1 until gh - 1) {
                    for (i in 1 until gw - 1) {
                        if (!bad[j * gw + i]) continue
                        var ax = 0f
                        var ay = 0f
                        for (jj in j - 1..j + 1) for (ii in i - 1..i + 1) {
                            val n = (jj * gw + ii) * 2
                            ax += before[n]; ay += before[n + 1]
                        }
                        val n = (j * gw + i) * 2
                        buf[n] = ax / 9; buf[n + 1] = ay / 9
                    }
                }
            }
            // 网格比图片粗得多，再抹平 0.8 格左右（iOS 是放大后做 0.8 格的高斯模糊），否则直线边缘会被推成折线。
            smooth(buf, gw, gh, 0.8f)
            var maxOffset = 0f
            for (v in buf) maxOffset = max(maxOffset, abs(v))
            if (maxOffset <= 1e-6f) return null
            val k = 1f / maxOffset
            for (n in buf.indices) buf[n] *= k
            return ReshapeField(loX, loY, sx, sy, gw, gh, buf, maxOffset)
        }

        /** 可分离高斯（边缘按最外一格延伸）。 */
        private fun smooth(buf: FloatArray, gw: Int, gh: Int, sigma: Float) {
            val r = ceil(sigma * 3).toInt()
            val kernel = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
            val sum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= sum
            val tmp = FloatArray(buf.size)
            for (j in 0 until gh) for (i in 0 until gw) {
                var ax = 0f
                var ay = 0f
                for (t in -r..r) {
                    val ii = (i + t).coerceIn(0, gw - 1)
                    val n = (j * gw + ii) * 2
                    ax += buf[n] * kernel[t + r]; ay += buf[n + 1] * kernel[t + r]
                }
                val n = (j * gw + i) * 2
                tmp[n] = ax; tmp[n + 1] = ay
            }
            for (j in 0 until gh) for (i in 0 until gw) {
                var ax = 0f
                var ay = 0f
                for (t in -r..r) {
                    val jj = (j + t).coerceIn(0, gh - 1)
                    val n = (jj * gw + i) * 2
                    ax += tmp[n] * kernel[t + r]; ay += tmp[n + 1] * kernel[t + r]
                }
                val n = (j * gw + i) * 2
                buf[n] = ax; buf[n + 1] = ay
            }
        }
    }
}
