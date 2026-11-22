package com.taoo.xiutu.tools.shape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.editor.CompareButton
import com.taoo.xiutu.ui.sym
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 图片在画布上的位置（像素）和一个单位坐标（原图长边）在屏幕上是多少像素。 */
private class Geo(val left: Float, val top: Float, val width: Float, val height: Float, val ppu: Float, val density: Float) {
    fun screen(p: V2) = Offset(left + p.x * ppu, top + p.y * ppu)
    /** 屏幕坐标 → 图片框里的局部坐标（像素）。 */
    fun local(o: Offset) = Offset(o.x - left, o.y - top)
    fun dp(v: Float) = v * density
}

/** 美体塑形时的画布：显示当前效果，并在上面画可以拖动的辅助线 / 框 / 圆（iOS ShapeCanvas）。 */
@Composable
fun ShapeCanvas(s: ShapeSession, modifier: Modifier) {
    val model = s.model
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val image = model.shownImage
        val iw = (image?.width ?: 1).toFloat()
        val ih = (image?.height ?: 1).toFloat()
        val k = min(max(vw - 24 * density, 1f) / iw, max(vh - 24 * density, 1f) / ih)
        val fw = iw * k
        val fh = ih * k
        val out = s.outputSize
        val ppu = if (out.x > 0) fw / out.x else 1f
        val geo = Geo((vw - fw) / 2, (vh - fh) / 2, fw, fh, ppu, density)
        val bmp = remember(image) { image?.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) {
            if (bmp != null) {
                drawImage(
                    bmp, dstOffset = IntOffset(geo.left.roundToInt(), geo.top.roundToInt()),
                    dstSize = IntSize(fw.roundToInt(), fh.roundToInt()), filterQuality = FilterQuality.High,
                )
            }
        }
        if (!model.comparing && !model.isBusy && !model.isLoading && ppu > 0) {
            val g = rememberUpdatedState(geo)
            if (s.restoring) {
                RestoreLayer(s, g)
            } else {
                when (s.mode) {
                    ShapeMode.TALL -> TallGuide(s, g)
                    ShapeMode.SLIM -> BoxGuide(s, g)
                    ShapeMode.ZOOM -> SpotGuide(s, g)
                    else -> {}
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd)) { CompareButton(model) }
    }
}

/** 把一个控件的中心放在 (x, y)（像素）。 */
private fun Modifier.centerAt(x: Float, y: Float) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place((x - p.width / 2f).roundToInt(), (y - p.height / 2f).roundToInt()) }
}

/** 辅助线上的圆形手柄。 */
@Composable
private fun Knob(symbol: String, at: Offset, rotation: Float = 0f) {
    Box(
        Modifier.centerAt(at.x, at.y).graphicsLayer { rotationZ = Math.toDegrees(rotation.toDouble()).toFloat() }
            .size(30.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(sym(symbol), null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
}

private fun DrawScope.guideLine(a: Offset, b: Offset, width: Float) {
    drawLine(Color.Black.copy(alpha = 0.35f), a, b, width + 2f)
    drawLine(Color.White, a, b, width)
}

/** 拖动手势：按下时 onStart 判断有没有点中（返回 null = 没点中，不拦截），之后每次移动给 (位移, 局部坐标)。 */
private suspend fun PointerInputScope.dragGesture(
    onStart: AwaitPointerEventScope.(Offset) -> ((Offset, Offset) -> Unit)?,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val update = onStart(down.position) ?: return@awaitEachGesture
        down.consume()
        try {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                update(c.position - down.position, c.position)
                c.consume()
            }
        } finally {
            onEnd()
        }
    }
}

/** 变形还原：手指涂过的地方回到原样。 */
@Composable
private fun RestoreLayer(s: ShapeSession, geo: State<Geo>) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        dragGesture(onStart = { pos ->
            val g = geo.value
            val l = g.local(pos)
            if (l.x < 0 || l.y < 0 || l.x > g.width || l.y > g.height) return@dragGesture null
            s.restoreBegan(V2(l.x / g.ppu, l.y / g.ppu))
            val update: (Offset, Offset) -> Unit = { _, p ->
                val gg = geo.value
                val q = gg.local(p)
                s.restoreMoved(V2(q.x / gg.ppu, q.y / gg.ppu))
            }
            update
        }, onEnd = {})
    })
}

/** 增高：两条平行线框出拉伸区域。拖线上的 ↕ 调两条线的位置，拖带子中间整体移动，拖旋转手柄转方向。 */
@Composable
private fun TallGuide(s: ShapeSession, geoState: State<Geo>) {
    val geo = geoState.value
    val guide = s.tallGuide ?: return
    val c = geo.screen(guide.center)
    val along = Offset(cos(guide.angle), sin(guide.angle))
    val normal = Offset(-sin(guide.angle), cos(guide.angle))
    val gap = guide.halfGap * geo.ppu
    val reach = 3000 * geo.density
    val a = c - normal * gap
    val b = c + normal * gap
    val arm = (geo.width * 0.27f).coerceIn(geo.dp(60f), geo.dp(110f))
    val dragging = s.guideDragging
    Canvas(Modifier.fillMaxSize()) {
        if (dragging) {
            // 拖的时候把拉伸区域以外压暗，一眼看清楚选的是哪一段。
            val p = Path()
            for ((edge, side) in listOf(a to -1f, b to 1f)) {
                val e0 = edge - along * reach
                val e1 = edge + along * reach
                p.moveTo(e0.x, e0.y)
                p.lineTo(e1.x, e1.y)
                p.lineTo(e1.x + normal.x * reach * side, e1.y + normal.y * reach * side)
                p.lineTo(e0.x + normal.x * reach * side, e0.y + normal.y * reach * side)
                p.close()
            }
            drawPath(p, Color.Black.copy(alpha = 0.45f))
        }
        val w = 1.5f * geo.density
        guideLine(a - along * reach, a + along * reach, w)
        guideLine(b - along * reach, b + along * reach, w)
    }
    if (dragging) {
        Text(
            "拉伸区域",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f)),
            modifier = Modifier.centerAt(c.x, c.y).graphicsLayer { rotationZ = Math.toDegrees(guide.angle.toDouble()).toFloat() },
        )
    }
    Knob("arrow.up.and.down", a, guide.angle)
    Knob("arrow.up.and.down", b, guide.angle)
    Knob("arrow.triangle.2.circlepath", b + along * arm)

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        dragGesture(onStart = start@{ pos ->
            val g = geoState.value
            val cur = s.tallGuide ?: return@start null
            val cc = g.screen(cur.center)
            val al = Offset(cos(cur.angle), sin(cur.angle))
            val nm = Offset(-sin(cur.angle), cos(cur.angle))
            val gp = cur.halfGap * g.ppu
            val ka = cc - nm * gp
            val kb = cc + nm * gp
            val armPx = (g.width * 0.27f).coerceIn(g.dp(60f), g.dp(110f))
            val hit = g.dp(25f)
            val target = when {
                (pos - (kb + al * armPx)).getDistance() <= hit -> 3
                (pos - kb).getDistance() <= hit -> 2
                (pos - ka).getDistance() <= hit -> 1
                abs((pos - cc).dot(nm)) <= max(gp * 2 - g.dp(28f), g.dp(12f)) / 2 -> 0
                else -> return@start null
            }
            s.guideBegan()
            val st = s.tallGuide ?: return@start null
            val update: (Offset, Offset) -> Unit = { t, p ->
                val gg = geoState.value
                val ppu = gg.ppu
                when (target) {
                    0 -> s.setTall(V2(st.center.x + t.x / ppu, st.center.y + t.y / ppu), st.angle, st.halfGap)
                    1, 2 -> {
                        // 拖其中一条线：另一条不动。
                        val n = V2(-sin(st.angle), cos(st.angle))
                        val d = (t.x * n.x + t.y * n.y) / ppu
                        val half = st.halfGap + (if (target == 1) -d else d) / 2
                        if (half >= 0.02f) s.setTall(st.center + n * (d / 2), st.angle, half)
                    }
                    else -> {
                        // 手柄在第二条线上、离中心 arm 远的地方：按手指相对中心的方向反推线的角度。
                        val l = gg.local(p)
                        val vx = l.x - st.center.x * ppu
                        val vy = l.y - st.center.y * ppu
                        val armNow = (gg.width * 0.27f).coerceIn(gg.dp(60f), gg.dp(110f))
                        val turned = atan2(vy, vx) - atan2(st.halfGap * ppu, armNow)
                        s.setTall(st.center, turned, st.halfGap)
                    }
                }
            }
            update
        }, onEnd = { s.guideEnded() })
    })
}

private fun Offset.dot(o: Offset) = x * o.x + y * o.y

/** 瘦身：带两条弧线的框。拖框里面移动；右边、下边的小条改宽和高；右下角的手柄旋转并整体缩放。 */
@Composable
private fun BoxGuide(s: ShapeSession, geoState: State<Geo>) {
    val geo = geoState.value
    val box = s.boxes.lastOrNull() ?: return
    val c = geo.screen(box.center)
    val w = box.width * geo.ppu
    val h = box.height * geo.ppu
    val ax = Offset(cos(box.angle), sin(box.angle))
    val ay = Offset(-sin(box.angle), cos(box.angle))
    fun at(lx: Float, ly: Float) = c + ax * lx + ay * ly
    Canvas(Modifier.fillMaxSize()) {
        rotateRad(box.angle, pivot = c) {
            val tl = Offset(c.x - w / 2, c.y - h / 2)
            drawRect(Color.Black.copy(alpha = 0.35f), tl, androidx.compose.ui.geometry.Size(w, h), style = Stroke(3.5f * geo.density))
            drawRect(Color.White, tl, androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.5f * geo.density))
            // 两条往里弯的弧线：中段离中线大约三分之一个框宽，瘦身就是沿这个形状往里收。
            val arcs = Path()
            for (side in listOf(-1f, 1f)) {
                arcs.moveTo(c.x + side * w * 0.42f, c.y - h / 2 + h * 0.05f)
                arcs.quadraticTo(c.x + side * w * 0.24f, c.y, c.x + side * w * 0.42f, c.y + h / 2 - h * 0.05f)
            }
            drawPath(arcs, Color.Black.copy(alpha = 0.35f), style = Stroke(4f * geo.density, cap = StrokeCap.Round))
            drawPath(arcs, Color.White, style = Stroke(2f * geo.density, cap = StrokeCap.Round))
            // 右边、下边的小条
            val pw = 6f * geo.density
            val pl = 30f * geo.density
            drawRoundRect(Color.White, Offset(c.x + w / 2 - pw / 2, c.y - pl / 2), androidx.compose.ui.geometry.Size(pw, pl),
                androidx.compose.ui.geometry.CornerRadius(pw / 2))
            drawRoundRect(Color.White, Offset(c.x - pl / 2, c.y + h / 2 - pw / 2), androidx.compose.ui.geometry.Size(pl, pw),
                androidx.compose.ui.geometry.CornerRadius(pw / 2))
        }
    }
    Knob("arrow.triangle.2.circlepath", at(w / 2, h / 2), box.angle)

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        dragGesture(onStart = start@{ pos ->
            val g = geoState.value
            val cur = s.boxes.lastOrNull() ?: return@start null
            val cc = g.screen(cur.center)
            val d = pos - cc
            val lx = d.x * cos(cur.angle) + d.y * sin(cur.angle)
            val ly = -d.x * sin(cur.angle) + d.y * cos(cur.angle)
            val bw = cur.width * g.ppu
            val bh = cur.height * g.ppu
            val target = when {
                hypot(lx - bw / 2, ly - bh / 2) <= g.dp(25f) -> 3
                abs(lx - bw / 2) <= g.dp(15f) && abs(ly) <= g.dp(27f) -> 1
                abs(lx) <= g.dp(27f) && abs(ly - bh / 2) <= g.dp(15f) -> 2
                abs(lx) <= bw / 2 && abs(ly) <= bh / 2 -> 0
                else -> return@start null
            }
            s.guideBegan()
            val st = s.boxes.lastOrNull() ?: return@start null
            val startLocal = g.local(pos)
            val update: (Offset, Offset) -> Unit = { t, p ->
                val gg = geoState.value
                val ppu = gg.ppu
                val l = gg.local(p)
                val vx = l.x - st.center.x * ppu
                val vy = l.y - st.center.y * ppu
                when (target) {
                    0 -> s.setBox(V2(st.center.x + t.x / ppu, st.center.y + t.y / ppu), st.width, st.height, st.angle)
                    1 -> {
                        val half = abs(vx * cos(st.angle) + vy * sin(st.angle))
                        s.setBox(st.center, half * 2 / ppu, st.height, st.angle)
                    }
                    2 -> {
                        val half = abs(-vx * sin(st.angle) + vy * cos(st.angle))
                        s.setBox(st.center, st.width, half * 2 / ppu, st.angle)
                    }
                    else -> {
                        val fx = startLocal.x - st.center.x * ppu
                        val fy = startLocal.y - st.center.y * ppu
                        val k = max(hypot(vx, vy), 1f) / max(hypot(fx, fy), 1f)
                        s.setBox(st.center, st.width * k, st.height * k, st.angle + atan2(vy, vx) - atan2(fy, fx))
                    }
                }
            }
            update
        }, onEnd = { s.guideEnded() })
    })
}

/** 缩放：一个圆圈。拖圈里面移动，拖圈上的手柄改大小。 */
@Composable
private fun SpotGuide(s: ShapeSession, geoState: State<Geo>) {
    val geo = geoState.value
    val spot = s.spots.lastOrNull() ?: return
    val c = geo.screen(spot.center)
    val r = spot.radius * geo.ppu
    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * geo.density, 5f * geo.density))
        drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(3.5f * geo.density, pathEffect = dash))
        drawCircle(Color.White, r, c, style = Stroke(1.5f * geo.density, pathEffect = dash))
    }
    Knob("arrow.up.left.and.arrow.down.right", Offset(c.x + r * 0.7071f, c.y + r * 0.7071f))

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        dragGesture(onStart = start@{ pos ->
            val g = geoState.value
            val cur = s.spots.lastOrNull() ?: return@start null
            val cc = g.screen(cur.center)
            val rr = cur.radius * g.ppu
            val target = when {
                (pos - Offset(cc.x + rr * 0.7071f, cc.y + rr * 0.7071f)).getDistance() <= g.dp(25f) -> 1
                (pos - cc).getDistance() <= rr -> 0
                else -> return@start null
            }
            s.guideBegan()
            val st = s.spots.lastOrNull() ?: return@start null
            val update: (Offset, Offset) -> Unit = { t, p ->
                val gg = geoState.value
                val ppu = gg.ppu
                if (target == 0) {
                    s.setSpot(V2(st.center.x + t.x / ppu, st.center.y + t.y / ppu), st.radius)
                } else {
                    val l = gg.local(p)
                    s.setSpot(st.center, hypot(l.x - st.center.x * ppu, l.y - st.center.y * ppu) / ppu)
                }
            }
            update
        }, onEnd = { s.guideEnded() })
    })
}
