package com.taoo.xiutu.tools.doodle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.tap

/** 一个 sRGB 颜色（0...1），涂鸦和边框的取色共用。 */
data class RGB(val r: Float, val g: Float, val b: Float) {
    val compose: Color get() = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

/** 自选颜色的小圆钮：彩虹圈，中间是当前颜色；点开是取色框（相当于 iOS 的系统取色器）。 */
@Composable
fun DecoColorWell(color: RGB, size: Dp = 30.dp, onPick: (RGB) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .tap { picking = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.56f).clip(CircleShape).background(color.compose))
    }
    if (picking) DecoColorDialog(color, onDismiss = { picking = false }, onPick = onPick)
}

/** 色相、饱和度、亮度三条滑块，边调边生效。 */
@Composable
private fun DecoColorDialog(initial: RGB, onDismiss: () -> Unit, onPick: (RGB) -> Unit) {
    val hsv = remember {
        FloatArray(3).also {
            android.graphics.Color.RGBToHSV(
                (initial.r.coerceIn(0f, 1f) * 255).toInt(), (initial.g.coerceIn(0f, 1f) * 255).toInt(), (initial.b.coerceIn(0f, 1f) * 255).toInt(), it,
            )
        }
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    fun current(): RGB {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        return RGB(android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f, android.graphics.Color.blue(c) / 255f)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Theme.panel,
        confirmButton = { TextButton(onDismiss) { Text("完成", color = Theme.accent) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(current().compose))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })))
                XSlider(h, { h = it; onPick(current()) }, 0f..360f)
                XSlider(s, { s = it; onPick(current()) }, 0f..1f)
                XSlider(v, { v = it; onPick(current()) }, 0f..1f)
            }
        },
    )
}
