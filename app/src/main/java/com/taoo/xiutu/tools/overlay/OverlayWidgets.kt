package com.taoo.xiutu.tools.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap

fun OverlayColor.compose(): Color = Color(r, g, b)

/** 横向滚动的一排，两边留 16 的内边距（内容可以滚到屏幕边缘）。 */
@Composable
fun EdgeRow(spacing: Dp = 8.dp, verticalAlignment: Alignment.Vertical = Alignment.CenterVertically, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = verticalAlignment, content = content,
    )
}

/** 一排颜色：最前面是"自定义颜色"，allowsNone 时再前面多一个"关掉"。 */
@Composable
fun OverlayColorRow(selected: OverlayColor?, allowsNone: Boolean = false, onPick: (OverlayColor?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    EdgeRow(spacing = 10.dp) {
        if (allowsNone) {
            Box(Modifier.size(34.dp).tap { onPick(null) }, contentAlignment = Alignment.Center) {
                Icon(sym("nosign"), "关掉", tint = if (selected == null) Theme.accent else Theme.dim, modifier = Modifier.size(24.dp))
            }
        }
        // 自定义颜色：彩虹圈
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                .tap { picking = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background((selected ?: OverlayColor.white).compose()))
        }
        for (c in OverlayColor.palette) {
            Box(
                Modifier.size(34.dp).border(2.dp, if (selected == c) Theme.accent else Color.Transparent, CircleShape)
                    .padding(3.dp).clip(CircleShape).background(c.compose())
                    .border(1.dp, Color(0.4f, 0.4f, 0.4f), CircleShape).tap { onPick(c) },
            )
        }
    }
    if (picking) ColorPickerDialog(selected ?: OverlayColor.white, onDismiss = { picking = false }) { onPick(it) }
}

/** 自定义颜色：色相、饱和度、亮度三条滑块，边调边生效。 */
@Composable
private fun ColorPickerDialog(initial: OverlayColor, onDismiss: () -> Unit, onPick: (OverlayColor) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.argb(), it) }
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    fun current(): OverlayColor {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
        return OverlayColor.of(android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f, android.graphics.Color.blue(c) / 255f)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Theme.panel,
        confirmButton = { TextButton(onDismiss) { Text("完成", color = Theme.accent) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(current().compose()))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(
                    (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) },
                )))
                XSlider(h, { h = it; onPick(current()) }, 0f..360f)
                XSlider(sat, { sat = it; onPick(current()) }, 0f..1f)
                XSlider(v, { v = it; onPick(current()) }, 0f..1f)
            }
        },
    )
}

/** 带名字的小滑块。两个并排放正好一行。 */
@Composable
fun MiniSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, labelWidth: Dp = 30.dp, onChange: (Float) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 12.sp, color = Theme.dim, maxLines = 1, modifier = Modifier.width(labelWidth))
        XSlider(value, onChange, range, Modifier.weight(1f))
    }
}

/** 面板上各种小样的缓存。 */
object OverlayThumbs {
    private val cache = HashMap<String, ImageBitmap?>()

    fun image(key: String, fontSize: Float, make: () -> OverlayItem): ImageBitmap? {
        if (cache.containsKey(key)) return cache[key]
        val b: Bitmap? = OverlayRenderer.thumbnail(make(), fontSize)
        if (cache.size > 400) cache.clear()
        val img = b?.asImageBitmap()
        cache[key] = img
        return img
    }
}

/** 用真正的绘制代码画的小样。 */
@Composable
fun OverlayThumb(key: String, width: Dp, height: Dp, selected: Boolean = false, fontSize: Float = 30f, make: () -> OverlayItem) {
    val density = LocalDensity.current.density
    val image = OverlayThumbs.image("$key-$fontSize-$density", fontSize * density, make)
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(10.dp)).background(Color(0.3f, 0.3f, 0.3f))
            .border(2.dp, if (selected) Theme.accent else Color.Transparent, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            val iw = (image.width / density).dp
            val ih = (image.height / density).dp
            val k = minOf(1f, (width - 6.dp) / iw, (height - 6.dp) / ih)
            Image(image, null, Modifier.size(iw * k, ih * k))
        }
    }
}

/** 一张位图铺在方块里（贴纸缩略图）。 */
@Composable
fun BitmapImage(bitmap: Bitmap, modifier: Modifier) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        val k = minOf(size.width / img.width, size.height / img.height)
        val w = img.width * k
        val h = img.height * k
        drawImage(
            img,
            dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
    }
}
