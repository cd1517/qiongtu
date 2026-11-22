package com.taoo.xiutu.tools.makeup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI_F = PI.toFloat()

/** 负数开小数次方会得 NaN（Float 的 sin(π) 是个很小的负数），先截到 0。 */
private fun spow(x: Float, e: Float) = max(x, 0f).pow(e)

/**
 * 美妆要画的每一种形状。每种形状画成一张灰度蒙版（白 = 上妆的地方），
 * 颜色和浓淡之后交给 GPU 处理，所以拖滑块时蒙版不用重画。
 */
sealed class MKShape {
    data object LipFull : MKShape()
    data object LipGradient : MKShape()
    data object LipLine : MKShape()
    data class LipGloss(val strong: Boolean) : MKShape()
    data class Blush(val placement: MKBlushPlacement) : MKShape()
    data class BlushGlow(val placement: MKBlushPlacement) : MKShape()
    data class BrowFill(val shape: MKBrowShape) : MKShape()
    data class BrowHair(val shape: MKBrowShape) : MKShape()
    data object ShadowLid : MKShape()
    data object ShadowCrease : MKShape()
    data object ShadowOuter : MKShape()
    data object ShadowLower : MKShape()
    data object ShadowSmoke : MKShape()
    data object Shimmer : MKShape()
    data object Sparkle : MKShape()
    data class Liner(val style: MKLinerStyle) : MKShape()
    data class Lashes(val style: MKLashStyle) : MKShape()
    data class Fold(val style: MKFoldStyle) : MKShape()
    data object AegyoLight : MKShape()
    data object AegyoShade : MKShape()
    data object Lens : MKShape()
    data object LensRing : MKShape()
    data object LensRays : MKShape()
    data class Catchlight(val style: MKCatchlight) : MKShape()
    data object NoseShade : MKShape()
    data object NoseLight : MKShape()
    data object HlForehead : MKShape()
    data object HlCheek : MKShape()
    data object HlChin : MKShape()
    data object HlCupid : MKShape()
    data object HlUnderEye : MKShape()
    data object ShJaw : MKShape()
    data object ShCheek : MKShape()
    data object ShTemple : MKShape()
    data class Freckles(val style: MKFreckleStyle) : MKShape()
    data class Moles(val spots: List<MKMoleSpot>) : MKShape()

    /** 蒙版位图的边长：越柔的形状用越小的图。fine 是精细形状（睫毛、眼线）用的边长。 */
    fun raster(fine: Int): Int = when (this) {
        is Blush, is BlushGlow, HlForehead, HlCheek, HlChin, HlUnderEye, ShJaw, ShCheek, ShTemple,
        NoseShade, NoseLight, ShadowCrease, AegyoLight, AegyoShade -> 256
        LipFull, LipGradient, is LipGloss, ShadowLid, ShadowOuter, ShadowLower, ShadowSmoke, Shimmer,
        is BrowFill, Lens, HlCupid -> 512
        else -> fine
    }
}

/** 固定种子的随机数：睫毛、雀斑这类"随机"的形状，预览和导出必须长得一样（和 iOS 版同一套算法）。 */
class MKRandom(seed: ULong) {
    private var state: ULong = seed + GOLDEN

    fun next(): Float {
        state += GOLDEN
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        z = z xor (z shr 31)
        return ((z shr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    }

    fun range(a: Float, b: Float) = a + (b - a) * next()

    /** 近似正态分布（-1...1 之间居多）。 */
    fun gauss() = (next() + next() + next() + next() - 2) / 1.15f

    private companion object {
        const val GOLDEN = 0x9E3779B97F4A7C15uL
    }
}

/** 在一张灰度位图（ALPHA_8，alpha = 灰度）上画蒙版。坐标用这张脸方框里的 0...1（原点左上角，y 朝下）。 */
class MKPainter(val size: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).apply { eraseColor(0) }
    private val canvas = Canvas(bitmap).apply { scale(size.toFloat(), size.toFloat()) }
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private fun paint(gray: Float, alpha: Float, style: Paint.Style, width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = width
        color = Color.argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), 255, 255, 255)
        // 灰度 0 = 涂黑：在只有 alpha 的图上就是把已有的擦掉
        if (gray < 0.5f) xfermode = erase
    }

    fun fill(points: List<MKVec>, gray: Float = 1f, alpha: Float = 1f) {
        if (points.size < 3) return
        canvas.drawPath(polygon(points), paint(gray, alpha, Paint.Style.FILL))
    }

    fun stroke(points: List<MKVec>, width: Float, gray: Float = 1f, alpha: Float = 1f, closed: Boolean = false) {
        if (points.size < 2 || width <= 0) return
        canvas.drawPath(polygon(points, closed), paint(gray, alpha, Paint.Style.STROKE, width))
    }

    fun dot(center: MKVec, radius: Float, gray: Float = 1f, alpha: Float = 1f) {
        canvas.drawCircle(center.x, center.y, radius, paint(gray, alpha, Paint.Style.FILL))
    }

    /** 一条两头尖的线：沿 points 走，每处半宽 half(0...1)。 */
    fun tapered(points: List<MKVec>, alpha: Float = 1f, half: (Float) -> Float) {
        if (points.size < 2) return
        val left = ArrayList<MKVec>()
        val right = ArrayList<MKVec>()
        val n = points.size
        for (i in 0 until n) {
            val a = points[max(i - 1, 0)]
            val b = points[min(i + 1, n - 1)]
            val normal = (b - a).unit.perp
            val h = half(i.toFloat() / (n - 1))
            left += points[i] + normal * h
            right += points[i] - normal * h
        }
        fill(left + right.reversed(), alpha = alpha)
    }

    /** 高斯状的柔和椭圆：中心最亮，往外渐隐。axis 是长轴方向。 */
    fun blob(center: MKVec, rx: Float, ry: Float, axis: MKVec = MKVec(1f, 0f), alpha: Float = 1f) {
        if (rx <= 0 || ry <= 0) return
        val stops = floatArrayOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1f)
        val values = floatArrayOf(1f, 0.93f, 0.76f, 0.54f, 0.33f, 0.16f, 0.045f, 0f)
        val colors = IntArray(values.size) { Color.argb((values[it] * alpha * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP) }
        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.rotate(Math.toDegrees(atan2(axis.y, axis.x).toDouble()).toFloat())
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, p)
        canvas.restore()
    }

    /** 环形渐变：stops 是 (半径比例, 亮度)。 */
    fun ring(center: MKVec, radius: Float, stops: List<Pair<Float, Float>>) {
        if (radius <= 0) return
        val colors = IntArray(stops.size) { Color.argb((stops[it].second * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255) }
        val positions = FloatArray(stops.size) { stops[it].first }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(center.x, center.y, radius, colors, positions, Shader.TileMode.CLAMP) }
        canvas.drawCircle(center.x, center.y, radius, p)
    }

    fun clipped(points: List<MKVec>, body: () -> Unit) {
        if (points.size < 3) return
        canvas.save()
        canvas.clipPath(polygon(points))
        body()
        canvas.restore()
    }

    companion object {
        /** 穿过各点的平滑闭合曲线。 */
        fun smoothClosed(points: List<MKVec>, perSegment: Int = 6): List<MKVec> {
            val n = points.size
            if (n < 3) return points
            val out = ArrayList<MKVec>()
            for (i in 0 until n) {
                val p0 = points[(i + n - 1) % n]
                val p1 = points[i]
                val p2 = points[(i + 1) % n]
                val p3 = points[(i + 2) % n]
                for (s in 0 until perSegment) out += MKCurve.catmull(p0, p1, p2, p3, s.toFloat() / perSegment)
            }
            return out
        }

        /** 二次贝塞尔曲线上均匀取几个点。 */
        fun bezier(a: MKVec, control: MKVec, b: MKVec, samples: Int = 8): List<MKVec> = (0..samples).map { i ->
            val t = i.toFloat() / samples
            val u = 1 - t
            a * (u * u) + control * (2 * u * t) + b * (t * t)
        }

        fun polygon(points: List<MKVec>, closed: Boolean = true): Path = Path().apply {
            val first = points.firstOrNull() ?: return@apply
            moveTo(first.x, first.y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
            if (closed) close()
        }
    }
}

// MARK: 各种形状的画法

/** 这张脸对应某个形状的蒙版（ALPHA_8）。imageW / imageH 是整张图的像素尺寸。 */
fun MKFace.drawMask(shape: MKShape, size: Int, imageW: Float, imageH: Float): Bitmap? {
    val p = MKPainter(size)
    when (shape) {
        MKShape.LipFull -> drawLips(p, gradient = false)
        MKShape.LipGradient -> drawLips(p, gradient = true)
        MKShape.LipLine -> p.stroke(lipPath, mouthWidth * 0.016f, closed = true)
        is MKShape.LipGloss -> drawLipGloss(p, shape.strong)
        is MKShape.Blush -> drawBlush(p, shape.placement, glow = false)
        is MKShape.BlushGlow -> drawBlush(p, shape.placement, glow = true)
        is MKShape.BrowFill -> drawBrows(p, shape.shape, hair = false)
        is MKShape.BrowHair -> drawBrows(p, shape.shape, hair = true)
        MKShape.ShadowLid -> for (e in eyes) drawLidZone(p, e, min(0.40f * e.width, 0.5f * e.browGap), 0.10f)
        MKShape.ShadowCrease -> for (e in eyes) drawLidZone(p, e, min(0.78f * e.width, 0.84f * e.browGap), 0.20f)
        MKShape.ShadowOuter -> for (e in eyes) {
            p.blob(e.outer + e.axis * (e.width * 0.07f) + e.up * (e.width * 0.11f), e.width * 0.32f, e.width * 0.17f,
                (e.axis * 0.8f + e.up * 0.55f).unit)
        }
        MKShape.ShadowLower -> for (e in eyes) drawLowerBand(p, e)
        MKShape.ShadowSmoke -> for (e in eyes) p.stroke(e.outline, e.width * 0.17f, closed = true)
        MKShape.Shimmer -> for (e in eyes) {
            p.blob(e.upper.at(0.5f) + e.up * (e.width * 0.075f), e.width * 0.25f, e.width * 0.075f, e.axis)
        }
        MKShape.Sparkle -> drawSparkle(p)
        is MKShape.Liner -> eyes.forEachIndexed { i, e -> drawLiner(p, e, shape.style, i) }
        is MKShape.Lashes -> eyes.forEachIndexed { i, e -> drawLashes(p, e, shape.style, i) }
        is MKShape.Fold -> for (e in eyes) drawFold(p, e, shape.style)
        MKShape.AegyoLight -> for (e in eyes) {
            val top = ArrayList<MKVec>()
            val bottom = ArrayList<MKVec>()
            for (s in 0..12) {
                val t = 0.08f + 0.84f * s / 12f
                val base = e.lower.at(t) - e.up * (e.width * 0.015f)
                top += base
                bottom += base - e.up * (e.width * 0.085f * spow(sin(PI_F * s / 12f), 0.7f))
            }
            p.fill(top + bottom.reversed())
        }
        MKShape.AegyoShade -> for (e in eyes) {
            val line = ArrayList<MKVec>()
            for (s in 0..12) {
                val f = s / 12f
                line += e.lower.at(0.12f + 0.76f * f) - e.up * (e.width * (0.012f + 0.125f * spow(sin(PI_F * f), 0.6f)))
            }
            p.tapered(line) { e.width * 0.014f * max(0f, sin(PI_F * it)) }
        }
        MKShape.Lens -> for (e in eyes) {
            p.clipped(e.outline) {
                p.ring(e.center, irisRadius(e), listOf(0f to 0f, 0.30f to 0f, 0.50f to 1f, 0.90f to 1f, 1f to 0f))
            }
        }
        MKShape.LensRing -> for (e in eyes) {
            p.clipped(e.outline) {
                p.ring(e.center, irisRadius(e) * 1.04f, listOf(0f to 0f, 0.76f to 0f, 0.90f to 1f, 0.97f to 0.7f, 1f to 0f))
            }
        }
        MKShape.LensRays -> eyes.forEachIndexed { i, e ->
            val random = MKRandom(0x1E45uL + i.toULong())
            val r = irisRadius(e)
            p.clipped(e.outline) {
                for (k in 0 until 46) {
                    val angle = (k + random.range(-0.3f, 0.3f)) / 46f * 2 * PI_F
                    val dir = MKVec(cos(angle), sin(angle))
                    val a = e.center + dir * (r * random.range(0.40f, 0.5f))
                    val b = e.center + dir * (r * random.range(0.72f, 0.9f))
                    p.stroke(listOf(a, b), r * 0.035f, alpha = random.range(0.35f, 1f))
                }
            }
        }
        is MKShape.Catchlight -> for (e in eyes) drawCatchlight(p, e, shape.style)
        MKShape.NoseShade -> drawNose(p, light = false)
        MKShape.NoseLight -> drawNose(p, light = true)
        MKShape.HlForehead -> p.blob(eyeMid + up * (eyeDist * 0.80f), eyeDist * 0.42f, eyeDist * 0.25f, right)
        MKShape.HlCheek -> eyes.forEachIndexed { i, e ->
            if (!e.visible) return@forEachIndexed
            val outward = if (i == 0) -right else right
            p.blob(e.outer.lerp(e.center, 0.4f) + down * (mouthDist * 0.38f), eyeDist * 0.32f, eyeDist * 0.18f,
                (outward * 0.9f + up * 0.38f).unit)
        }
        MKShape.HlChin -> p.blob(lipBottom.lerp(chin, 0.5f), eyeDist * 0.19f, eyeDist * 0.13f, right)
        MKShape.HlCupid -> p.blob(lipTop + up * (eyeDist * 0.045f), eyeDist * 0.075f, eyeDist * 0.036f, right)
        MKShape.HlUnderEye -> eyes.forEachIndexed { i, e ->
            val inward = if (i == 0) right else -right
            p.blob(e.center + down * (eyeDist * 0.33f) + inward * (eyeDist * 0.05f), eyeDist * 0.24f, eyeDist * 0.14f,
                (inward * 0.9f - down * 0.35f).unit, alpha = if (e.visible) 1f else 0.6f)
        }
        MKShape.ShJaw -> {
            val center = noseBase
            val line = ArrayList<MKVec>()
            for (s in 0..28) {
                val point = contour.at(0.10f + 0.80f * s / 28f)
                line += point + (center - point).unit * (faceWidth * 0.03f)
            }
            p.stroke(line, faceWidth * 0.08f)
        }
        MKShape.ShCheek -> {
            // 轮廓线的哪一头靠着哪只眼睛
            val startIsLeft = contour.at(0f).dist(eyes[0].center) < contour.at(0f).dist(eyes[1].center)
            eyes.forEachIndexed { i, e ->
                if (!e.visible) return@forEachIndexed
                val t = if ((i == 0) == startIsLeft) 0.13f else 0.87f
                val start = contour.at(t)
                val corner = mouthCenter + (if (i == 0) -right else right) * (mouthWidth * 0.5f) + up * (eyeDist * 0.06f)
                val inset = start + (corner - start).unit * (faceWidth * 0.035f)
                p.stroke(listOf(inset, inset.lerp(corner, 0.52f)), faceWidth * 0.065f)
            }
        }
        MKShape.ShTemple -> eyes.forEachIndexed { i, e ->
            if (!e.visible) return@forEachIndexed
            val outward = if (i == 0) -right else right
            val anchor = brows[i]?.center?.at(1f) ?: (e.outer + up * (eyeDist * 0.3f))
            p.blob(anchor + up * (eyeDist * 0.24f) + outward * (eyeDist * 0.12f), eyeDist * 0.26f, eyeDist * 0.14f, up)
        }
        is MKShape.Freckles -> drawFreckles(p, shape.style)
        is MKShape.Moles -> drawMoles(p, shape.spots, imageW, imageH)
    }
    return p.bitmap
}

val MKFace.lipPath: List<MKVec> get() = MKPainter.smoothClosed(lipsOuter, perSegment = 5)

/** 张着嘴时，嘴里面（牙齿）不上色。闭着嘴时内唇线几乎是一条线，就不用挖了。 */
private val MKFace.mouthOpening: List<MKVec>?
    get() {
        if (lipsInner.size < 3) return null
        var area = 0f
        for (i in lipsInner.indices) {
            val a = lipsInner[i]
            val b = lipsInner[(i + 1) % lipsInner.size]
            area += a.x * b.y - b.x * a.y
        }
        return if (abs(area) / 2 > mouthWidth * mouthWidth * 0.012f) lipsInner else null
    }

fun MKFace.irisRadius(eye: MKEye) = eye.width * 0.235f

private fun MKFace.drawLips(p: MKPainter, gradient: Boolean) {
    val path = lipPath
    if (gradient) {
        // 咬唇：中间浓、往唇边淡下去
        p.fill(path, alpha = 0.22f)
        val height = max(lipTop.dist(lipBottom), mouthWidth * 0.2f)
        p.clipped(path) { p.blob(mouthCenter, mouthWidth * 0.46f, height * 0.62f, right) }
    } else {
        p.fill(path)
    }
    mouthOpening?.let { p.fill(it, gray = 0f) }
}

private fun MKFace.drawLipGloss(p: MKPainter, strong: Boolean) {
    val lower = max(mouthCenter.dist(lipBottom), mouthWidth * 0.08f)
    val upper = max(mouthCenter.dist(lipTop), mouthWidth * 0.06f)
    p.clipped(lipPath) {
        p.blob(mouthCenter.lerp(lipBottom, 0.52f), mouthWidth * 0.20f, lower * 0.20f, right, alpha = 0.85f)
        for (sign in listOf(-1f, 1f)) {
            p.blob(lipTop.lerp(mouthCenter, 0.38f) + right * (mouthWidth * 0.13f * sign), mouthWidth * 0.07f,
                upper * 0.16f, right, alpha = 0.55f)
        }
        if (strong) {
            p.blob(mouthCenter.lerp(lipBottom, 0.44f), mouthWidth * 0.12f, lower * 0.075f, right)
            p.blob(mouthCenter.lerp(lipBottom, 0.50f) + right * (mouthWidth * 0.2f), mouthWidth * 0.04f,
                lower * 0.06f, right, alpha = 0.7f)
        }
    }
    mouthOpening?.let { p.fill(it, gray = 0f) }
}

private fun MKFace.drawBlush(p: MKPainter, placement: MKBlushPlacement, glow: Boolean) {
    eyes.forEachIndexed { i, e ->
        if (!e.visible) return@forEachIndexed
        val outward = if (i == 0) -right else right
        val m = mouthDist
        val s = e.side
        if (glow) {
            // 光泽感：颧骨最高处一小块亮
            p.blob(e.center + down * (m * 0.34f) + outward * (s * 0.22f), s * 0.26f, s * 0.11f,
                (outward * 0.9f + up * 0.4f).unit, alpha = 0.9f)
            return@forEachIndexed
        }
        when (placement) {
            MKBlushPlacement.APPLE ->
                p.blob(e.center + down * (m * 0.54f) + outward * (s * 0.16f), s * 0.66f, s * 0.52f, right)
            MKBlushPlacement.SWEEP ->
                p.blob(e.center + down * (m * 0.47f) + outward * (s * 0.36f), s * 0.74f, s * 0.34f, (outward * 0.86f + up * 0.5f).unit)
            MKBlushPlacement.UNDER_EYE ->
                p.blob(e.center + down * (m * 0.35f) + outward * (s * 0.08f), s * 0.68f, s * 0.27f, right)
            MKBlushPlacement.SUNBURN ->
                p.blob(e.center + down * (m * 0.36f) + outward * (s * 0.05f), s * 0.68f, s * 0.28f, right)
            MKBlushPlacement.HIGH ->
                p.blob(e.center + down * (m * 0.30f) + outward * (s * 0.36f), s * 0.56f, s * 0.30f, (outward * 0.75f + up * 0.66f).unit)
            MKBlushPlacement.DOLL ->
                p.blob(e.center + down * (m * 0.56f) + outward * (s * 0.08f), s * 0.42f, s * 0.38f, right)
            MKBlushPlacement.DRUNK ->
                p.blob(e.center + down * (m * 0.44f) + outward * (s * 0.02f), s * 0.72f, s * 0.40f, right)
        }
    }
    if (glow) return
    if (placement == MKBlushPlacement.SUNBURN) p.blob(ridge.at(0.62f), eyeDist * 0.46f, mouthDist * 0.15f, right, alpha = 0.9f)
    if (placement == MKBlushPlacement.DRUNK) p.blob(noseTip, eyeDist * 0.16f, eyeDist * 0.13f, right, alpha = 0.7f)
}

// MARK: 眉毛

/** 按眉形算出要画的中线和半宽（各 17 个点，从眉头到眉尾）。 */
private fun browTarget(brow: MKBrow, shape: MKBrowShape): Pair<List<MKVec>, List<Float>> {
    val l = brow.length
    val extend = when (shape) {
        MKBrowShape.NATURAL, MKBrowShape.SOFT -> 0.04f
        MKBrowShape.STRAIGHT -> 0.03f
        MKBrowShape.WILD -> 0.02f
        MKBrowShape.DROOP -> 0.10f
        MKBrowShape.WILLOW -> 0.12f
        MKBrowShape.SWORD -> 0f
        MKBrowShape.ARCH -> 0.05f
    }
    fun original(t: Float): MKVec = if (t <= 1) brow.center.at(t) else brow.center.at(1f) + brow.center.tangent(1f) * ((t - 1) * l)
    fun envelope(t: Float, head: Float, tail: Float, hold: Float): Float {
        val rise = head + (1 - head) * mkSmoothstep(0f, 0.25f, t)
        return rise * (1 - (1 - tail) * mkSmoothstep(hold, 1f, t))
    }
    val center = ArrayList<MKVec>()
    val half = ArrayList<Float>()
    val count = 16
    for (s in 0..count) {
        val f = s.toFloat() / count          // 0...1 画出来的眉毛
        val t = f * (1 + extend)              // 对应原眉毛的位置
        val natural = max(brow.thickness(min(t, 1f)), l * 0.11f)
        var c = original(t)
        val width: Float
        when (shape) {
            MKBrowShape.NATURAL -> width = natural * envelope(f, 0.78f, 0.2f, 0.5f)
            MKBrowShape.SOFT -> width = natural * 1.1f * envelope(f, 0.8f, 0.3f, 0.5f)
            MKBrowShape.WILD -> width = natural * 1.28f * envelope(f, 0.95f, 0.22f, 0.55f)
            MKBrowShape.STRAIGHT -> {
                val line = original(0f).lerp(original(1 + extend) + brow.up * (l * 0.04f), f)
                c = c.lerp(line, 0.7f)
                width = l * 0.105f * envelope(f, 0.9f, 0.35f, 0.68f)
            }
            MKBrowShape.DROOP -> {
                c = c - brow.up * (l * 0.085f * mkSmoothstep(0.5f, 1.05f, f))
                width = natural * 0.95f * envelope(f, 0.78f, 0.16f, 0.45f)
            }
            MKBrowShape.WILLOW -> {
                c = c + brow.up * (l * 0.03f * sin(PI_F * f))
                width = l * 0.064f * envelope(f, 0.7f, 0.14f, 0.4f)
            }
            MKBrowShape.SWORD -> {
                val head = original(0f) - brow.up * (l * 0.02f)
                val peak = original(0.72f) + brow.up * (l * 0.05f)
                val end = original(1f) + brow.up * (l * 0.005f)
                c = if (f <= 0.72f) head.lerp(peak, f / 0.72f) else peak.lerp(end, (f - 0.72f) / 0.28f)
                width = l * 0.118f * envelope(f, 1f, 0.1f, 0.7f)
            }
            MKBrowShape.ARCH -> {
                val bump = exp(-((f - 0.66f) / 0.2f).pow(2))
                c = c + brow.up * (l * 0.06f * bump)
                width = l * 0.092f * envelope(f, 0.85f, 0.12f, 0.55f)
            }
        }
        center += c
        half += width / 2
    }
    return center to half
}

private fun MKFace.drawBrows(p: MKPainter, shape: MKBrowShape, hair: Boolean) {
    for ((index, brow) in brows.withIndex()) {
        brow ?: continue
        val (center, half) = browTarget(brow, shape)
        val n = center.size
        fun normal(i: Int): MKVec {
            val tangent = (center[min(i + 1, n - 1)] - center[max(i - 1, 0)]).unit
            var v = tangent.perp
            if (v.dot(brow.up) < 0) v = -v
            return v
        }
        if (!hair) {
            val top = ArrayList<MKVec>()
            val bottom = ArrayList<MKVec>()
            for (i in 0 until n) {
                top += center[i] + normal(i) * half[i]
                bottom += center[i] - normal(i) * half[i]
            }
            p.fill(top + bottom.reversed())
            continue
        }
        // 一根根的眉毛：眉头的往上长，越往眉尾越顺着眉毛的方向倒
        val random = MKRandom(0xB20uL + index.toULong() * 31uL + shape.ordinal.toULong())
        val count = if (shape == MKBrowShape.WILD) 64 else if (shape == MKBrowShape.WILLOW) 34 else 48
        for (k in 0 until count) {
            val f = ((k + random.range(-0.4f, 0.4f)) / count).coerceIn(0f, 1f) * 0.9f
            val position = f * (n - 1)
            val i = min(position.toInt(), n - 2)
            val c = center[i].lerp(center[i + 1], position - i)
            val h = half[i] + (half[i + 1] - half[i]) * (position - i)
            val nrm = normal(i)
            val along = (center[i + 1] - center[i]).unit
            val lean = mkSmoothstep(0f, 0.75f, f)
            var direction = (nrm * (1 - 0.9f * lean) + along * (0.3f + 0.75f * lean)).unit
            if (shape == MKBrowShape.WILD) direction = (direction + nrm * (0.5f * (1 - lean))).unit
            val base = c - nrm * (h * random.range(0.15f, 0.95f))
            val length = h * random.range(1.2f, if (shape == MKBrowShape.WILD) 2.5f else 1.8f)
            if (length <= brow.length * 0.012f) continue
            val tip = base + direction * length
            val alpha = random.range(0.5f, 1f)
            p.tapered(MKPainter.bezier(base, base.lerp(tip, 0.5f) + along * (length * 0.14f), tip, samples = 5), alpha = alpha) {
                brow.length * 0.0045f * (1 - 0.85f * it)
            }
        }
    }
}

// MARK: 眼妆

/** 上眼睑往上的一片区域：height 是最高处离眼睑多远，spread 是往眼尾外面延伸多少（按眼宽）。 */
private fun drawLidZone(p: MKPainter, e: MKEye, height: Float, spread: Float) {
    val bottom = ArrayList<MKVec>()
    val top = ArrayList<MKVec>()
    for (s in 0..14) {
        val t = s / 14f
        val lid = e.upper.at(t)
        bottom += lid
        val profile = 0.4f + 0.6f * spow(sin(PI_F * (0.12f + 0.76f * t)), 0.8f)
        top += lid + e.up * (height * profile) + e.axis * (e.width * spread * t * t)
    }
    val wing = e.outer + e.axis * (e.width * (spread + 0.03f)) + e.up * (e.width * 0.04f)
    p.fill(bottom + listOf(wing) + top.reversed())
}

private fun drawLowerBand(p: MKPainter, e: MKEye) {
    val top = ArrayList<MKVec>()
    val bottom = ArrayList<MKVec>()
    for (s in 0..10) {
        val t = 0.3f + 0.7f * s / 10f
        val lid = e.lower.at(t)
        top += lid
        bottom += lid - e.up * (e.width * 0.085f * mkSmoothstep(0.25f, 0.9f, t))
    }
    p.fill(top + listOf(e.outer + e.axis * (e.width * 0.06f)) + bottom.reversed())
}

private fun MKFace.drawSparkle(p: MKPainter) {
    eyes.forEachIndexed { i, e ->
        val random = MKRandom(0x5A2uL + i.toULong())
        val center = e.upper.at(0.55f) + e.up * (e.width * 0.05f)
        for (k in 0 until 26) {
            val angle = random.range(0f, 2 * PI_F)
            val r = sqrt(random.next())
            val point = center + e.axis * (cos(angle) * r * e.width * 0.34f) + e.up * (sin(angle) * r * e.width * 0.035f)
            val size = e.width * random.range(0.005f, 0.011f)
            p.dot(point, size, alpha = random.range(0.5f, 1f))
            if (k % 7 == 0) {
                // 每隔几颗画一个十字星芒
                p.stroke(listOf(point - e.axis * (size * 3.2f), point + e.axis * (size * 3.2f)), size * 0.5f, alpha = 0.9f)
                p.stroke(listOf(point - e.up * (size * 3.2f), point + e.up * (size * 3.2f)), size * 0.5f, alpha = 0.9f)
            }
        }
    }
}

private fun drawLiner(p: MKPainter, e: MKEye, style: MKLinerStyle, @Suppress("UNUSED_PARAMETER") index: Int) {
    val w = e.width
    var thickStart = 0.006f
    var thickEnd = 0.028f
    // 翘尾的尖在眼尾外面多远（沿眼睛方向）、比眼尾高多少，都按眼宽算。null = 不翘
    var wing: Pair<Float, Float>? = null
    when (style) {
        MKLinerStyle.THIN -> { thickStart = 0.004f; thickEnd = 0.016f }
        MKLinerStyle.NATURAL -> wing = 0.09f to 0.06f
        MKLinerStyle.WING -> wing = 0.17f to 0.13f
        MKLinerStyle.PUPPY -> wing = 0.14f to -0.035f
        MKLinerStyle.CAT -> { thickEnd = 0.040f; wing = 0.26f to 0.20f }
        MKLinerStyle.FULL -> { thickEnd = 0.034f; wing = 0.15f to 0.10f }
    }
    // 沿上眼睑的一条由细到粗的线，贴着睫毛根、往上长
    val bottom = ArrayList<MKVec>()
    val top = ArrayList<MKVec>()
    for (s in 0..16) {
        val t = s / 16f
        val lid = e.upper.at(t)
        val thick = w * (thickStart + (thickEnd - thickStart) * mkSmoothstep(0f, 1f, t)) * (if (t < 0.06f) t / 0.06f else 1f)
        bottom += lid - e.up * (w * 0.004f)
        top += lid + e.up * thick
    }
    p.fill(bottom + top.reversed())
    if (wing != null) {
        // 翘尾：从眼尾接着往外上方收成一个细尖；再把眼尾上方那个小凹口填一下，线条才顺
        val tip = e.outer + e.axis * (w * wing.first) + e.up * (w * wing.second)
        val topPt = e.outer + e.up * (w * thickEnd * 1.15f)
        p.fill(listOf(e.outer - e.up * (w * 0.006f), tip, topPt))
        p.fill(listOf(e.upper.at(0.9f) + e.up * (w * thickEnd * 0.9f), topPt.lerp(tip, 0.45f), topPt))
    }
    if (style == MKLinerStyle.FULL) {
        val line = ArrayList<MKVec>()
        for (s in 0..10) line += e.lower.at(0.35f + 0.65f * s / 10f) - e.up * (w * 0.004f)
        p.tapered(line) { w * 0.011f * mkSmoothstep(0f, 0.6f, it) }
    }
}

private fun drawLashes(p: MKPainter, e: MKEye, style: MKLashStyle, index: Int) {
    val w = e.width
    val random = MKRandom(0x1A5uL + index.toULong() * 7uL + style.ordinal.toULong())
    var count = 22
    var scale = 1f
    var thickness = 0.0085f
    when (style) {
        MKLashStyle.NATURAL -> { count = 24; scale = 0.85f }
        MKLashStyle.DENSE -> { count = 40; scale = 1.05f; thickness = 0.0095f }
        MKLashStyle.SUNFLOWER -> { count = 15; scale = 1.25f; thickness = 0.012f }
        MKLashStyle.DOLL -> { count = 30; scale = 1.18f; thickness = 0.010f }
    }
    for (k in 0 until count) {
        var f = k.toFloat() / (count - 1)
        if (style != MKLashStyle.SUNFLOWER) f = (f + random.range(-0.5f, 0.5f) / count).coerceIn(0f, 1f)
        val t = 0.10f + 0.90f * f
        val base = e.upper.at(t) + e.up * (w * 0.004f)
        // 内眼角的睫毛朝里上方，越往外越朝外上方，像一把打开的扇子
        val fan = -0.30f + 1.05f * f + (if (style == MKLashStyle.SUNFLOWER) 0f else random.range(-0.07f, 0.07f))
        val direction = (e.up + e.axis * fan).unit
        var length = w * (0.09f + 0.10f * sin(PI_F * spow(f, 0.8f))) * scale
        if (f > 0.7f) length *= 1 + 0.25f * (f - 0.7f) / 0.3f
        length *= if (style == MKLashStyle.SUNFLOWER) (if (k % 2 == 0) 1.0f else 0.72f) else random.range(0.78f, 1.08f)
        val tip = base + direction * length
        // 先顺着眼睑往外长，再往上翘
        val control = base + (e.up * 0.35f + e.axis * (fan * 1.9f)).unit * (length * 0.5f)
        p.tapered(MKPainter.bezier(base, control, tip), alpha = if (style == MKLashStyle.NATURAL) 0.85f else 1f) {
            w * thickness * (1 - 0.92f * it)
        }
    }
    if (style != MKLashStyle.DOLL && style != MKLashStyle.DENSE) return
    // 下睫毛：短、稀，朝外下方
    val lower = if (style == MKLashStyle.DOLL) 13 else 9
    for (k in 0 until lower) {
        val f = k.toFloat() / (lower - 1)
        val t = 0.30f + 0.66f * f
        val base = e.lower.at(t)
        val direction = (-e.up + e.axis * (0.15f + 0.6f * f)).unit
        val length = w * (if (style == MKLashStyle.DOLL) 0.075f else 0.05f) * (0.7f + 0.5f * f) * random.range(0.8f, 1.1f)
        p.tapered(listOf(base, base + direction * (length * 0.5f), base + direction * length), alpha = 0.9f) {
            w * 0.0065f * (1 - 0.9f * it)
        }
    }
}

private fun drawFold(p: MKPainter, e: MKEye, style: MKFoldStyle) {
    val w = e.width
    val line = ArrayList<MKVec>()
    for (s in 0..16) {
        val t = s / 16f
        val offset = when (style) {
            MKFoldStyle.FAN -> 0.105f * mkSmoothstep(0f, 0.5f, t) * (1 - 0.2f * mkSmoothstep(0.8f, 1f, t))
            MKFoldStyle.PARALLEL -> 0.06f + 0.035f * sin(PI_F * (0.1f + 0.75f * t))
        }
        line += e.upper.at(0.03f + 0.97f * t) + e.up * (w * offset) + e.axis * (w * 0.05f * t * t)
    }
    p.tapered(line) { w * 0.011f * spow(sin(PI_F * it.coerceIn(0.02f, 0.98f)), 0.5f) }
}

private fun MKFace.drawCatchlight(p: MKPainter, e: MKEye, style: MKCatchlight) {
    val r = irisRadius(e)
    val main = e.center - right * (r * 0.32f) + up * (r * 0.36f)
    p.clipped(e.outline) {
        when (style) {
            MKCatchlight.DOT -> p.blob(main, r * 0.26f, r * 0.26f)
            MKCatchlight.TWIN -> {
                p.blob(main, r * 0.26f, r * 0.26f)
                p.blob(e.center + right * (r * 0.34f) - up * (r * 0.30f), r * 0.13f, r * 0.13f, alpha = 0.85f)
            }
            MKCatchlight.WINDOW -> {
                val a = right * (r * 0.19f)
                val b = up * (r * 0.15f)
                p.fill(listOf(main - a - b, main + a - b, main + a + b, main - a + b), alpha = 0.95f)
                p.stroke(listOf(main - b, main + b), r * 0.035f, gray = 0f)
                p.stroke(listOf(main - a, main + a), r * 0.035f, gray = 0f)
            }
            MKCatchlight.STAR -> {
                val star = ArrayList<MKVec>()
                for (k in 0 until 8) {
                    val angle = k / 8f * 2 * PI_F
                    val radius = r * (if (k % 2 == 0) 0.36f else 0.09f)
                    star += main + right * (cos(angle) * radius) + up * (sin(angle) * radius)
                }
                p.fill(star)
                p.blob(e.center + right * (r * 0.34f) - up * (r * 0.30f), r * 0.1f, r * 0.1f, alpha = 0.8f)
            }
            MKCatchlight.RING -> {
                val arc = ArrayList<MKVec>()
                for (k in 0..40) {
                    val angle = (-0.35f + 1.7f * k / 40f) * PI_F
                    arc += e.center + right * (cos(angle) * r * 0.5f) - up * (sin(angle) * r * 0.5f)
                }
                p.stroke(arc, r * 0.075f, alpha = 0.9f)
            }
        }
    }
}

// MARK: 修容

private fun MKFace.drawNose(p: MKPainter, light: Boolean) {
    if (light) {
        val line = ArrayList<MKVec>()
        for (s in 0..12) line += ridge.at(0.14f + 0.66f * s / 12f)
        p.stroke(line, eyeDist * 0.045f)
        p.blob(ridge.at(0.95f), eyeDist * 0.065f, eyeDist * 0.055f, right)
        return
    }
    val average = max((eyes[0].side + eyes[1].side) / 2, 1e-4f)
    eyes.forEachIndexed { i, e ->
        // 侧脸时远离镜头那一侧的鼻影要收窄，不然会画到脸颊上
        val ratio = 0.45f + 0.55f * min(e.side / average, 1.3f)
        val sideways = if (i == 0) -right else right
        val line = ArrayList<MKVec>()
        for (s in 0..12) {
            val t = 0.04f + 0.74f * s / 12f
            line += ridge.at(t) + sideways * (eyeDist * (0.085f + 0.055f * t) * ratio)
        }
        p.stroke(line, eyeDist * 0.06f)
        // 眉头下面那一小段，把鼻影和眉毛接起来
        brows[i]?.let { brow ->
            p.stroke(listOf(brow.center.at(0.04f) - brow.up * (brow.length * 0.1f), line[0]), eyeDist * 0.05f, alpha = 0.7f)
        }
    }
}

private fun MKFace.drawFreckles(p: MKPainter, style: MKFreckleStyle) {
    val random = MKRandom(0xF2ECuL + style.ordinal.toULong())
    val count = when (style) {
        MKFreckleStyle.FEW -> 36
        MKFreckleStyle.NATURAL -> 84
        MKFreckleStyle.SUN -> 150
    }
    val visibleSides = eyes.withIndex().filter { it.value.visible }
    for (n in 0 until count) {
        val pick = random.next()
        val point: MKVec
        if (pick < 0.42f || visibleSides.isEmpty()) {
            point = ridge.at(0.6f) + right * (random.gauss() * eyeDist * 0.2f) + down * (random.gauss() * mouthDist * 0.1f)
        } else {
            val (i, e) = visibleSides[(random.next() * visibleSides.size).toInt() % visibleSides.size]
            val outward = if (i == 0) -right else right
            val center = e.center + down * (mouthDist * 0.42f) + outward * (e.side * 0.12f)
            point = center + right * (random.gauss() * e.side * 0.36f) + down * (random.gauss() * mouthDist * 0.15f)
        }
        // 不画到眼睛里和嘴上
        if (eyes.any { point.dist(it.center) < it.width * 0.62f }) continue
        if ((point - mouthCenter).dot(down) > -mouthDist * 0.12f) continue
        val radius = eyeDist * random.range(0.004f, 0.010f)
        p.dot(point, radius, alpha = random.range(0.35f, 1f))
    }
}

/** 一颗痣在这张脸方框里的位置。 */
fun MKFace.position(spot: MKMoleSpot, imageW: Float, imageH: Float): MKVec? = when (spot) {
    MKMoleSpot.TearLeft -> eyes[0].lower.at(0.72f) + down * (eyes[0].width * 0.20f)
    MKMoleSpot.TearRight -> eyes[1].lower.at(0.72f) + down * (eyes[1].width * 0.20f)
    MKMoleSpot.Lip -> {
        val i = if (eyes[1].visible) 1 else 0
        val outward = if (i == 0) -right else right
        lipTop + outward * (mouthWidth * 0.36f) + up * (mouthDist * 0.09f)
    }
    MKMoleSpot.Nose -> ridge.at(0.5f) + (if (eyes[0].side >= eyes[1].side) -right else right) * (eyeDist * 0.06f)
    MKMoleSpot.Cheek -> {
        val i = if (eyes[0].side >= eyes[1].side) 0 else 1
        val outward = if (i == 0) -right else right
        eyes[i].center + down * (mouthDist * 0.62f) + outward * (eyes[i].side * 0.30f)
    }
    MKMoleSpot.Chin -> {
        val i = if (eyes[0].side >= eyes[1].side) 0 else 1
        lipBottom + (if (i == 0) -right else right) * (mouthWidth * 0.30f) + down * (mouthDist * 0.14f)
    }
    is MKMoleSpot.Custom -> if (imageW <= 0 || roi.width <= 0) null else
        MKVec((spot.x / 10000f * imageW - roi.minX) / roi.width, (spot.y / 10000f * imageH - roi.minY) / roi.height)
}

private fun MKFace.drawMoles(p: MKPainter, spots: List<MKMoleSpot>, imageW: Float, imageH: Float) {
    for (spot in spots) {
        val point = position(spot, imageW, imageH) ?: continue
        val r = eyeDist * 0.013f
        p.dot(point, r)
        p.blob(point, r * 1.7f, r * 1.7f, alpha = 0.5f)
    }
}

// MARK: 蒙版缓存

/**
 * 画好的蒙版按"哪张脸 + 什么形状 + 多大 + 羽化多少"记住。拖动浓淡、换颜色时只是重新调色，不用再画。
 * 总量按占的内存限制，超了就把最早画的丢掉。
 */
object MKMaskCache {
    data class Key(val face: Int, val shape: MKShape, val size: Int, val blur: Int = 0)

    private val store = LinkedHashMap<Key, Bitmap>()
    private var bytes = 0L
    private const val LIMIT = 48L * 1024 * 1024

    fun image(key: Key, make: () -> Bitmap?): Bitmap? {
        synchronized(this) { store[key]?.let { return it } }
        val made = make() ?: return null
        synchronized(this) {
            if (store[key] == null) {
                store[key] = made
                bytes += made.allocationByteCount
                while (bytes > LIMIT && store.size > 1) {
                    val oldest = store.keys.first()
                    store.remove(oldest)?.let { bytes -= it.allocationByteCount }
                }
            }
        }
        return made
    }

    fun removeAll() = synchronized(this) {
        store.clear()
        bytes = 0
    }
}
