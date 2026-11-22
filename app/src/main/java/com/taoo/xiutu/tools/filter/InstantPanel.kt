package com.taoo.xiutu.tools.filter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.ImageTile
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.tap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class InstantTab(val title: String) { STOCK("色调"), STRENGTH("强度"), PAPER("相纸"), FRAMING("取景") }

/** 滤镜面板选到"拍立得"这一类时的内容：上面一排相纸，下面是色调、相纸颜色、取景这些调节。 */
@Composable
fun InstantFilmRow(s: FilterSession) {
    val params = s.instant
    val on = !params.isIdentity
    LaunchedEffect(Unit) { s.prepareInstantThumbs() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            ImageTile("无", s.lookThumbOriginal, selected = !on) { s.selectInstant(null) }
            for (preset in InstantPreset.all) {
                ImageTile(preset.title, s.instantThumbs[preset.id], selected = on && params.format == preset.format && params.rotated == preset.rotated, symbol = "photo") {
                    s.selectInstant(preset)
                }
            }
        }
        Column(Modifier.alpha(if (on) 1f else 0.3f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fun act(block: () -> Unit) { if (on) block() }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (item in InstantTab.entries) Chip(item.title, s.instantTab == item) { act { s.instantTab = item } }
                Box(Modifier.size(1.dp, 20.dp).background(Theme.chip))
                Chip("写字", params.caption.isNotEmpty(), symbol = "pencil") {
                    act {
                        val draft = params.caption.ifEmpty { SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()) }
                        s.model.requestText(draft, "相纸上的字") { text -> s.updateInstant { it.copy(caption = text.trim()) } }
                    }
                }
                Chip("旋转", false, symbol = "rotate.right") { act { s.updateInstant { it.copy(rotated = !it.rotated, shift = 0f) } } }
                Chip("实物", params.real, symbol = "scanner") { act { s.updateInstant { it.copy(real = !it.real) } } }
                if (params.real) {
                    // 扫描台的颜色。
                    for (bed in InstantBed.entries) {
                        Swatch(Color(bed.color.argb), params.bed == bed, 24) { act { s.updateInstant { it.copy(bed = bed) } } }
                    }
                } else {
                    Chip("投影", params.shadow, symbol = "shadow") { act { s.updateInstant { it.copy(shadow = !it.shadow) } } }
                }
            }
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) {
                when (s.instantTab) {
                    InstantTab.STOCK -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (stock in InstantStock.entries) Chip(stock.title, params.stock == stock) { act { s.updateInstant { it.copy(stock = stock) } } }
                    }
                    InstantTab.STRENGTH -> SliderRow(params.intensity, 0f..1f, "${(params.intensity * 100).roundToInt()}", on) { v ->
                        s.updateInstant { it.copy(intensity = v) }
                    }
                    InstantTab.PAPER -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorWell(params.paper) { c -> act { s.updateInstant { it.copy(paper = c) } } }
                        for (item in InstantPaper.all) Swatch(Color(item.argb), params.paper == item, 26) { act { s.updateInstant { it.copy(paper = item) } } }
                    }
                    InstantTab.FRAMING -> SliderRow(params.shift, -1f..1f, "${(params.shift * 100).roundToInt()}", on) { v ->
                        // 靠近正中时吸过去。
                        s.updateInstant { it.copy(shift = if (abs(v) < 0.04f) 0f else v) }
                    }
                }
            }
        }
    }
}

/** 数值 + 滑块的一行。 */
@Composable
private fun SliderRow(value: Float, range: ClosedFloatingPointRange<Float>, text: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.width(48.dp))
        XSlider(value, onChange, range, Modifier.weight(1f), enabled = enabled)
    }
}

/** 一个颜色圆点，选中时外面一圈强调色。 */
@Composable
private fun Swatch(color: Color, selected: Boolean, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size((size + 6).dp).clip(CircleShape).border(2.dp, if (selected) Theme.accent else Color.Transparent, CircleShape).tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(color).border(1.dp, Color(0.4f, 0.4f, 0.4f), CircleShape))
    }
}

/** 自选颜色：彩虹圈，点开调色相、饱和度、亮度。 */
@Composable
private fun ColorWell(color: PaperColor, onPick: (PaperColor) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(Brush.sweepGradient((0..6).map { Color.hsv((it * 60f) % 360f, 0.9f, 1f) }))
            .tap { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(color.argb)).border(1.5.dp, Color.White, CircleShape))
    }
    if (open) {
        val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color.argb, it) } }
        var h by remember { mutableFloatStateOf(hsv[0]) }
        var sat by remember { mutableFloatStateOf(hsv[1]) }
        var v by remember { mutableFloatStateOf(hsv[2]) }
        val current = Color.hsv(h.coerceIn(0f, 359.9f), sat, v)
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Theme.panel,
            confirmButton = {
                TextButton({
                    open = false
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
                    onPick(PaperColor(argb and 0xFFFFFF))
                }) { Text("确定", color = Theme.accent) }
            },
            dismissButton = { TextButton({ open = false }) { Text("取消", color = Color.White) } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(current))
                    XSlider(h, { h = it }, 0f..359.9f)
                    XSlider(sat, { sat = it }, 0f..1f)
                    XSlider(v, { v = it }, 0f..1f)
                }
            },
        )
    }
}
