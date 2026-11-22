package com.taoo.xiutu.tools.adjust

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.tools.color.CurveShape
import com.taoo.xiutu.tools.color.V3
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 调色里的子面板：局部调色、HSL、肤色调整、色调分离、曲线。左上角返回，右上角重置这一项。 */
@Composable
fun AdjustSubPanel(s: AdjustSession, sub: AdjustSub) {
    val p = s.params
    val changed = when (sub) {
        AdjustSub.CURVE -> p.curves.isChanged(s.curveChannel)
        AdjustSub.LOCAL -> p.locals.isNotEmpty()
        else -> p.isChanged(sub)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton("chevron.left", "返回", true) { s.openSub(null) }
            Spacer(Modifier.weight(1f))
            CapsuleButton("arrow.counterclockwise", if (sub == AdjustSub.CURVE) "重置这条" else "重置", changed) {
                if (changed) reset(s, sub)
            }
        }
        when (sub) {
            AdjustSub.LOCAL -> LocalSection(s)
            AdjustSub.HSL -> HSLSection(s)
            AdjustSub.SKIN -> SkinSection(s)
            AdjustSub.SPLIT -> SplitSection(s)
            AdjustSub.CURVE -> CurveSection(s)
        }
    }
}

private fun reset(s: AdjustSession, sub: AdjustSub) {
    when (sub) {
        AdjustSub.LOCAL -> { s.localID = null; s.change { it.copy(locals = emptyList()) } }
        AdjustSub.HSL -> s.change { it.copy(hsl = List(HSLColor.entries.size) { V3.ZERO }) }
        AdjustSub.SKIN -> s.change { it.with(AdjustKey.SKIN_TONE, 0f).with(AdjustKey.SKIN_WHITEN, 0f) }
        AdjustSub.SPLIT -> s.change { it.copy(split = SplitTone()) }
        AdjustSub.CURVE -> { val ch = s.curveChannel; s.change { it.copy(curves = it.curves.with(ch, CurveSet.IDENTITY)) } }
    }
}

@Composable
private fun CapsuleButton(symbol: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) Color.White else Theme.dim
    Row(
        Modifier.clip(CircleShape).background(Theme.chip).tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(sym(symbol), null, tint = fg, modifier = Modifier.size(13.dp))
        Text(title, fontSize = 14.sp, color = fg)
    }
}

// MARK: 通用的小部件

/** 靠近零的时候吸到零，方便回到"没改"。 */
private fun snapped(v: Float) = if (abs(v) < 0.015f) 0f else v

private fun hueColor(hue: Float, saturation: Float = 1f, brightness: Float = 1f): Color {
    val h = hue - floor(hue)
    return Color.hsv((h * 360f).coerceIn(0f, 359.999f), saturation, brightness)
}

/** 一行：名字 + 滑块 + 数值。 */
@Composable
private fun ValueRow(title: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, shown: ((Float) -> Int)? = null, onChange: (Float) -> Unit) {
    Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 13.sp, color = Theme.dim, modifier = Modifier.width(52.dp))
        XSlider(value, onChange, range, Modifier.weight(1f))
        Text("${shown?.invoke(value) ?: (value * 100).roundToInt()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** 轨道是一条颜色渐变的滑块。 */
@Composable
private fun TintSlider(value: Float, range: ClosedFloatingPointRange<Float>, colors: List<Color>, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val knob = with(density) { 24.dp.toPx() }
    val latest by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    Canvas(
        modifier.height(30.dp).pointerInput(range) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun emit(x: Float) {
                    val w = (size.width - knob).coerceAtLeast(1f)
                    val t = ((x - knob / 2) / w).coerceIn(0f, 1f)
                    latest(range.start + t * span)
                }
                emit(down.position.x)
                down.consume()
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    emit(c.position.x)
                    c.consume()
                }
            }
        },
    ) {
        val w = (size.width - knob).coerceAtLeast(1f)
        val t = ((value - range.start) / span).coerceIn(0f, 1f)
        val inset = knob / 2 - with(density) { 2.dp.toPx() }
        val th = with(density) { 5.dp.toPx() }
        drawRoundRect(
            Brush.horizontalGradient(if (colors.size >= 2) colors else listOf(colors.first(), colors.first()), startX = inset, endX = size.width - inset),
            topLeft = Offset(inset, size.height / 2 - th / 2), size = Size(size.width - inset * 2, th), cornerRadius = CornerRadius(th / 2),
        )
        val cx = knob / 2 + t * w
        drawCircle(Color.Black.copy(alpha = 0.4f), knob / 2, Offset(cx, size.height / 2 + with(density) { 1.dp.toPx() }))
        drawCircle(Color.White, knob / 2, Offset(cx, size.height / 2))
    }
}

/** 名字 + 渐变滑块 + 数值。 */
@Composable
private fun TintRow(title: String, value: Float, colors: List<Color>, range: ClosedFloatingPointRange<Float> = -1f..1f, shown: ((Float) -> Int)? = null, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 13.sp, color = Theme.dim, modifier = Modifier.width(52.dp))
        TintSlider(value, range, colors, Modifier.weight(1f), onChange)
        Text("${shown?.invoke(value) ?: (value * 100).roundToInt()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

// MARK: HSL

@Composable
private fun HSLSection(s: AdjustSession) {
    val selected = s.hslColor
    val value = s.params.hsl[selected.ordinal]
    val hue = selected.hue
    fun update(body: (V3) -> V3) = s.change { p -> p.copy(hsl = p.hsl.toMutableList().also { it[selected.ordinal] = body(it[selected.ordinal]) }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            for (item in HSLColor.entries) {
                val on = item == selected
                Column(Modifier.weight(1f).tap { s.hslColor = item }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(hueColor(item.hue, 0.85f, 0.95f))
                            .border(if (on) 2.5.dp else 0.dp, if (on) Color.White else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(sym("checkmark"), item.title, tint = Color.White, modifier = Modifier.size(13.dp)) }
                    // 改过的颜色下面有个小点。
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (!s.params.hsl[item.ordinal].isZero) Theme.accent else Color.Transparent))
                }
            }
        }
        TintRow("色相", value.x, listOf(hueColor(hue - 30f / 360), hueColor(hue), hueColor(hue + 30f / 360))) { v -> update { it.copy(x = snapped(v)) } }
        TintRow("饱和度", value.y, listOf(Color(0.5f, 0.5f, 0.5f), hueColor(hue))) { v -> update { it.copy(y = snapped(v)) } }
        TintRow("明度", value.z, listOf(hueColor(hue, brightness = 0.25f), hueColor(hue), hueColor(hue, saturation = 0.25f))) { v -> update { it.copy(z = snapped(v)) } }
    }
}

// MARK: 肤色调整

@Composable
private fun SkinSection(s: AdjustSession) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TintRow("肤色", s.params[AdjustKey.SKIN_TONE],
            listOf(Color(1f, 0.74f, 0.76f), Color(0.98f, 0.8f, 0.68f), Color(0.98f, 0.84f, 0.56f))) { s.setAdjust(AdjustKey.SKIN_TONE, it) }
        ValueRow("白皙", s.params[AdjustKey.SKIN_WHITEN], 0f..1f) { s.setAdjust(AdjustKey.SKIN_WHITEN, it) }
    }
}

// MARK: 色调分离

private val rainbow = (0..6).map { hueColor(it / 6f, 0.9f) }

@Composable
private fun SplitSection(s: AdjustSession) {
    val split = s.params.split
    fun update(body: (SplitTone) -> SplitTone) = s.change { it.copy(split = body(it.split)) }
    val deg: (Float) -> Int = { (it * 360).roundToInt() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TintRow("高光色", split.highlightHue, rainbow, 0f..1f, deg) { v -> update { it.copy(highlightHue = v) } }
        ValueRow("高光量", split.highlightAmount, 0f..1f) { v -> update { it.copy(highlightAmount = snapped(v)) } }
        TintRow("阴影色", split.shadowHue, rainbow, 0f..1f, deg) { v -> update { it.copy(shadowHue = v) } }
        ValueRow("阴影量", split.shadowAmount, 0f..1f) { v -> update { it.copy(shadowAmount = snapped(v)) } }
        ValueRow("平衡", split.balance) { v -> update { it.copy(balance = snapped(v)) } }
    }
}

// MARK: 曲线

private val curveNames = listOf("全部", "红", "绿", "蓝")
private val curveTints = listOf(Color.White, Color(1f, 0.35f, 0.35f), Color(0.35f, 0.9f, 0.45f), Color(0.4f, 0.62f, 1f))

@Composable
private fun CurveSection(s: AdjustSession) {
    val channel = s.curveChannel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 4) {
                Chip(if (s.params.curves.isChanged(i)) curveNames[i] + " •" else curveNames[i], channel == i) { s.curveChannel = i }
            }
        }
        CurveEditor(s.params.curves.channels[channel], curveTints[channel]) { pts ->
            s.change { it.copy(curves = it.curves.with(channel, pts)) }
        }
    }
}

/** 可以拖的曲线：头尾两个点只能上下动，中间的点可以随便加、拖、删。 */
@Composable
private fun CurveEditor(points: List<CurvePoint>, tint: Color, change: (List<CurvePoint>) -> Unit) {
    val density = LocalDensity.current
    val latestPoints by rememberUpdatedState(points)
    val latestChange by rememberUpdatedState(change)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val near = with(density) { 28.dp.toPx() }
    val away = with(density) { 36.dp.toPx() }
    val limit = 8

    Canvas(
        Modifier.fillMaxWidth().height(150.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var index: Int? = null
                var finished = false
                fun moved(lx: Float, ly: Float) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (finished || w <= 0 || h <= 0) return
                    val list = latestPoints.toMutableList()
                    val x = (lx / w).coerceIn(0f, 1f)
                    val y = (1 - ly / h).coerceIn(0f, 1f)
                    if (index == null) {
                        // 手指落下：离哪个点近就拖哪个，都不近就在这里加一个。
                        fun dist(p: CurvePoint) = hypot(p.x * w - lx, (1 - p.y) * h - ly)
                        val n = list.indices.minByOrNull { dist(list[it]) }
                        val after = list.indexOfFirst { it.x > x }
                        if (n != null && dist(list[n]) < near) {
                            index = n
                        } else if (list.size < limit && after > 0 && x - list[after - 1].x > 0.04f && list[after].x - x > 0.04f) {
                            list.add(after, CurvePoint(x, y))
                            index = after
                        } else {
                            finished = true
                            return
                        }
                        dragging = index
                    }
                    val i = index ?: return
                    if (i !in list.indices) return
                    if (i == 0 || i == list.size - 1) {
                        list[i] = list[i].copy(y = y)
                    } else if (ly < -away || ly > h + away) {
                        list.removeAt(i)
                        dragging = null
                        finished = true
                    } else {
                        list[i] = CurvePoint(x.coerceIn(list[i - 1].x + 0.03f, list[i + 1].x - 0.03f), y)
                    }
                    latestChange(list)
                }
                moved(down.position.x, down.position.y)
                down.consume()
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    moved(c.position.x, c.position.y)
                    c.consume()
                }
                dragging = null
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0.11f, 0.11f, 0.11f), cornerRadius = CornerRadius(8.dp.toPx()))
        for (i in 1 until 4) {
            val x = w * i / 4
            val y = h * i / 4
            drawLine(Color(0.2f, 0.2f, 0.2f), Offset(x, 0f), Offset(x, h), 1f)
            drawLine(Color(0.2f, 0.2f, 0.2f), Offset(0f, y), Offset(w, y), 1f)
        }
        drawLine(Color(0.3f, 0.3f, 0.3f), Offset(0f, h), Offset(w, 0f), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        val shape = CurveShape(points.map { it.pointF() })
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val x = i.toDouble() / steps
            val px = (w * x).toFloat()
            val py = (h * (1 - shape.value(x))).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEachIndexed { i, p ->
            val c = Offset(p.x * w, (1 - p.y) * h)
            drawCircle(if (dragging == i) Theme.accent else Color.White, 7.dp.toPx(), c)
            drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
    }
}

// MARK: 局部调色

@Composable
private fun LocalSection(s: AdjustSession) {
    val point = s.selectedLocal
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (point != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.params.locals.forEachIndexed { i, item ->
                        Chip(if (item.isNeutral) "点 ${i + 1}" else "点 ${i + 1} •", item.id == point.id) { s.selectLocal(item.id) }
                    }
                }
                Box(Modifier.size(34.dp).clip(CircleShape).background(Theme.chip).tap { s.removeSelectedLocal() }, contentAlignment = Alignment.Center) {
                    Icon(sym("trash"), "删除这个点", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            ValueRow("范围", point.radius, 0.05f..0.6f) { v -> s.changeSelectedLocal { it.copy(radius = v) } }
            ValueRow("亮度", point.brightness) { v -> s.changeSelectedLocal { it.copy(brightness = snapped(v)) } }
            ValueRow("对比度", point.contrast) { v -> s.changeSelectedLocal { it.copy(contrast = snapped(v)) } }
            ValueRow("饱和度", point.saturation) { v -> s.changeSelectedLocal { it.copy(saturation = snapped(v)) } }
            ValueRow("色温", point.temperature) { v -> s.changeSelectedLocal { it.copy(temperature = snapped(v)) } }
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                Icon(sym("hand.tap"), null, tint = Theme.accent, modifier = Modifier.size(30.dp).alpha(1f))
            }
        }
    }
}
