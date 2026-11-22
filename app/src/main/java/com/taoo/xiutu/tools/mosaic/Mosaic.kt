package com.taoo.xiutu.tools.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 马赛克样式。 */
enum class MosaicStyle(val title: String, val symbol: String) {
    PIXEL("方块", "square.grid.3x3.fill"),
    BLUR("模糊", "drop.fill"),
    HEX("蜂窝", "hexagon.fill"),
    CRYSTAL("晶格", "seal.fill");

    /** 在 GPU 上把整张 src 做成这种效果。 */
    fun effect(g: GpuScope, src: Tex, strength: Float): Tex {
        val dim = max(src.width, src.height).toFloat()
        return when (this) {
            PIXEL -> g.pass(MosaicShaders.PIXEL, mapOf("src" to src), mapOf("cell" to max(2f, dim * (0.008f + 0.04f * strength))), src.width, src.height)
            HEX -> g.pass(MosaicShaders.HEX, mapOf("src" to src), mapOf("cell" to max(2f, dim * (0.008f + 0.04f * strength))), src.width, src.height)
            CRYSTAL -> g.pass(MosaicShaders.CRYSTAL, mapOf("src" to src), mapOf("cell" to max(2f, dim * (0.006f + 0.03f * strength))), src.width, src.height)
            // Core Image 的模糊半径约等于标准差；这里的 blur 半径是核的范围（σ = 半径 / 2.5）
            BLUR -> g.blur(src, max(1f, dim * (0.006f + 0.03f * strength)) * 2.5f)
        }
    }
}

/**
 * 一笔。points 是归一化坐标（原点左上）；width 是笔刷直径，相对于图片长边。
 * 涂抹过程中 points 会一直追加，确认成步骤时拷贝一份。
 */
class MosaicStroke(
    val points: MutableList<PointF>,
    val width: Float,
    val erase: Boolean,
    val style: MosaicStyle,
    val strength: Float,
) {
    fun copy() = MosaicStroke(points.map { PointF(it.x, it.y) }.toMutableList(), width, erase, style, strength)

    fun sameAs(o: MosaicStroke) = width == o.width && erase == o.erase && style == o.style && strength == o.strength &&
        points.size == o.points.size && points.indices.all { points[it].x == o.points[it].x && points[it].y == o.points[it].y }
}

/** 一种"样式+强度"对应的一张蒙版（白 = 打码）。 */
class MosaicMask(val style: MosaicStyle, val strength: Float, val image: Bitmap)

/**
 * 把笔画画成蒙版。每种"样式+强度"一张；橡皮擦对所有蒙版生效。
 * 涂抹时在主线程增量绘制，渲染线程上传纹理时用 lock 同步。
 */
class MosaicMaskSet(width: Int, height: Int) {
    val width = max(1, width)
    val height = max(1, height)
    val lock = Any()

    private data class Key(val style: MosaicStyle, val strength: Int) {
        constructor(s: MosaicStroke) : this(s.style, (s.strength * 100).roundToInt())
    }

    private val order = ArrayList<Key>()
    private val masks = HashMap<Key, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun mask(key: Key): Bitmap = masks.getOrPut(key) {
        order += key
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
    }

    private fun draw(points: List<PointF>, lineWidth: Float, white: Boolean, target: Bitmap) {
        val first = points.firstOrNull() ?: return
        val w = max(1f, lineWidth * max(width, height))
        paint.color = if (white) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val c = Canvas(target)
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(first.x * width, first.y * height, w / 2, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        val path = Path()
        path.moveTo(first.x * width, first.y * height)
        for (i in 1 until points.size) path.lineTo(points[i].x * width, points[i].y * height)
        c.drawPath(path, paint)
    }

    /** 增量绘制：涂抹过程中每次只画新的一小段。 */
    fun add(points: List<PointF>, stroke: MosaicStroke) = synchronized(lock) {
        if (stroke.erase) {
            for (m in masks.values) draw(points, stroke.width, false, m)
        } else {
            draw(points, stroke.width, true, mask(Key(stroke)))
        }
    }

    fun rebuild(strokes: List<MosaicStroke>) = synchronized(lock) {
        order.clear()
        masks.clear()
        for (s in strokes) add(s.points, s)
    }

    /** 当前的蒙版。返回的是正在画的位图本身，读的时候要拿 lock。 */
    fun snapshot(): List<MosaicMask> = synchronized(lock) {
        order.mapNotNull { k -> masks[k]?.let { MosaicMask(k.style, k.strength / 100f, it) } }
    }
}

object MosaicParams {
    /** 按蒙版把各样式的效果叠到 base 上。lock 不为空时上传蒙版期间持有它。 */
    fun composite(base: Bitmap, masks: List<MosaicMask>, lock: Any? = null): Bitmap {
        if (masks.isEmpty()) return base
        return Gpu.run {
            val b = upload(base)
            val mt = if (lock != null) synchronized(lock) { masks.map { upload(it.image) } } else masks.map { upload(it.image) }
            var cur = b
            masks.forEachIndexed { i, m ->
                val eff = m.style.effect(this, b, m.strength)
                cur = pass(Shaders.BLEND_MASK, mapOf("src" to cur, "fg" to eff, "mask" to mt[i]), width = b.width, height = b.height)
            }
            read(cur)
        }
    }
}

/** 马赛克这一步。 */
class MosaicOp(strokes: List<MosaicStroke>) : EditOp {
    val strokes: List<MosaicStroke> = strokes.map { it.copy() }

    val hasPaint get() = strokes.any { !it.erase }

    override fun apply(src: Bitmap): Bitmap {
        if (!hasPaint) return src
        // 蒙版最多 2048，和分辨率无关
        val k = min(1f, 2048f / max(src.width, src.height))
        val set = MosaicMaskSet((src.width * k).roundToInt(), (src.height * k).roundToInt())
        set.rebuild(strokes)
        return MosaicParams.composite(src, set.snapshot())
    }

    override fun reopens(tool: Tool) = tool == Tool.MOSAIC

    override fun hitTest(point: PointF, ratio: Float): Tool? =
        if (strokes.any { !it.erase && strokeHits(it.points, it.width, point, ratio) }) Tool.MOSAIC else null
}

/**
 * 点中了一笔没有（照 iOS tapDecoration）。以画面宽度为单位量距离；
 * 只有两个点的（形状）点在它占的那一块里就算。
 */
fun strokeHits(points: List<PointF>, width: Float, point: PointF, ratio: Float): Boolean {
    val reach = max(width * max(1f, ratio) / 2, 0.035f)
    val tx = point.x
    val ty = point.y * ratio
    if (points.isEmpty()) return false
    if (points.size == 2) {
        val ax = points[0].x; val ay = points[0].y * ratio
        val bx = points[1].x; val by = points[1].y * ratio
        return tx >= min(ax, bx) - reach && tx <= max(ax, bx) + reach && ty >= min(ay, by) - reach && ty <= max(ay, by) + reach
    }
    var px = points[0].x
    var py = points[0].y * ratio
    for (p in points) {
        val x = p.x
        val y = p.y * ratio
        val vx = x - px; val vy = y - py
        val wx = tx - px; val wy = ty - py
        val len = vx * vx + vy * vy
        val t = if (len > 0) ((wx * vx + wy * vy) / len).coerceIn(0f, 1f) else 0f
        if (hypot(wx - vx * t, wy - vy * t) <= reach) return true
        px = x; py = y
    }
    return false
}

/** 四种样式的着色器。cell = 格子大小（像素）。 */
object MosaicShaders {
    const val PIXEL = Shaders.HEADER + """
uniform sampler2D src;
uniform float cell;
void main() {
    vec2 c0 = floor(uv * size / cell) * cell;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec2 p = c0 + (vec2(float(i), float(j)) + 0.5) * cell / 4.0;
            acc += texture(src, clamp(p / size, 0.0, 1.0));
        }
    }
    o = acc / 16.0;
}
"""

    const val HEX = Shaders.HEADER + """
uniform sampler2D src;
uniform float cell;
void main() {
    vec2 p = uv * size;
    vec2 r = vec2(1.0, 1.7320508) * cell;
    vec2 h = r * 0.5;
    vec2 a = mod(p, r) - h;
    vec2 b = mod(p - h, r) - h;
    vec2 c = dot(a, a) < dot(b, b) ? p - a : p - b;
    vec4 acc = texture(src, clamp(c / size, 0.0, 1.0));
    for (int i = 0; i < 6; i++) {
        float t = float(i) * 1.0471976;
        acc += texture(src, clamp((c + vec2(cos(t), sin(t)) * cell * 0.3) / size, 0.0, 1.0));
    }
    o = acc / 7.0;
}
"""

    const val CRYSTAL = Shaders.HEADER + """
uniform sampler2D src;
uniform float cell;
vec2 jitter(vec2 c) {
    uvec2 q = uvec2(ivec2(c) + ivec2(65536));
    uint n = (q.x * 1597334673u) ^ (q.y * 3812015801u);
    n = n * 1597334673u;
    uint m = (n ^ (n >> 16u)) * 3812015801u;
    return vec2(float(n >> 8u), float(m >> 8u)) / 16777216.0;
}
void main() {
    vec2 p = uv * size;
    vec2 g = floor(p / cell);
    float best = 1e20;
    vec2 site = p;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = g + vec2(float(x), float(y));
            vec2 s = (c + jitter(c)) * cell;
            vec2 d = s - p;
            float e = dot(d, d);
            if (e < best) { best = e; site = s; }
        }
    }
    o = texture(src, clamp(site / size, 0.0, 1.0));
}
"""
}
