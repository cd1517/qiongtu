package com.taoo.xiutu.editor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 单指涂抹的回调。坐标是归一化的图片坐标（原点左上角）；displayScale = 图片长边当前在屏幕上占多少像素。 */
class DrawHandlers(
    val began: (PointF, Float) -> Unit,
    val moved: (PointF) -> Unit,
    val ended: () -> Unit,
    val cancelled: () -> Unit,
)

/** 图片在画布里的位置（缩放后），给叠加层（文字框、裁剪框）换算坐标用。 */
data class ImageFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun toImage(p: Offset) = PointF((p.x - left) / width, (p.y - top) / height)
    fun toView(p: PointF) = Offset(left + p.x * width, top + p.y * height)
}

/**
 * 可双指缩放、拖动的图片画布。传入 handlers 时单指变成画笔，双指仍然缩放/移动。
 * 双击在 1 倍和 2.5 倍之间切换。
 */
@Composable
fun ZoomableCanvas(
    image: Bitmap?,
    modifier: Modifier = Modifier,
    handlers: DrawHandlers? = null,
    focus: RectF? = null,
    onTap: ((PointF) -> Unit)? = null,
    onFrame: ((ImageFrame) -> Unit)? = null,
    overlay: @Composable (ImageFrame) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val imgW = image?.width ?: 1
        val imgH = image?.height ?: 1
        val aspect = imgW.toFloat() / imgH
        // 换了不同比例的图（裁剪之后）回到整张
        LaunchedEffect(aspect) { zoom = 1f; pan = Offset.Zero }
        val fitW: Float
        val fitH: Float
        if (vw / vh > aspect) { fitH = vh; fitW = vh * aspect } else { fitW = vw; fitH = vw / aspect }

        // 放大到指定区域
        LaunchedEffect(focus, vw, vh) {
            val f = focus
            if (f == null) { zoom = 1f; pan = Offset.Zero; return@LaunchedEffect }
            val z = min(vw / (f.width() * fitW), vh / (f.height() * fitH)).coerceIn(1f, 6f)
            val cx = (f.centerX() - 0.5f) * fitW * z
            val cy = (f.centerY() - 0.5f) * fitH * z
            zoom = z
            pan = Offset(-cx, -cy)
        }

        fun clampPan(p: Offset, z: Float): Offset {
            val mx = max(0f, (fitW * z - vw) / 2)
            val my = max(0f, (fitH * z - vh) / 2)
            return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
        }

        val frame = ImageFrame(
            left = (vw - fitW * zoom) / 2 + pan.x,
            top = (vh - fitH * zoom) / 2 + pan.y,
            width = fitW * zoom,
            height = fitH * zoom,
        )
        SideEffect { onFrame?.invoke(frame) }

        val currentHandlers by rememberUpdatedState(handlers)
        val currentTap by rememberUpdatedState(onTap)
        val currentFrame by rememberUpdatedState(frame)

        val bmp = remember(image) { image?.asImageBitmap() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(vw, vh, fitW, fitH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startTime = down.uptimeMillis
                    var drawing = false
                    var multi = false
                    var moved = false
                    val h = currentHandlers
                    if (h != null) {
                        drawing = true
                        val f = currentFrame
                        h.began(f.toImage(down.position), max(f.width, f.height))
                    }
                    var lastCentroid = down.position
                    var lastSpan = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (drawing) h?.ended()
                            val dt = event.changes.first().uptimeMillis - startTime
                            if (!moved && !multi && !drawing && dt < 300) {
                                currentTap?.invoke(currentFrame.toImage(down.position))
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            if (drawing) { drawing = false; h?.cancelled() }
                            val c = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                            val span = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                            if (!multi) { multi = true; lastCentroid = c; lastSpan = span }
                            else {
                                val k = if (lastSpan > 0) span / lastSpan else 1f
                                val nz = (zoom * k).coerceIn(1f, 8f)
                                val real = nz / zoom
                                // 以手指中心为锚点缩放
                                val anchor = c - Offset(vw / 2, vh / 2)
                                val np = (pan - anchor) * real + anchor + (c - lastCentroid)
                                zoom = nz
                                pan = clampPan(np, nz)
                                lastCentroid = c; lastSpan = span
                            }
                            moved = true
                            event.changes.forEach { it.consume() }
                        } else {
                            val ch = pressed[0]
                            if (ch.positionChanged()) {
                                val d = ch.position - ch.previousPosition
                                if (abs(ch.position.x - down.position.x) + abs(ch.position.y - down.position.y) > 12f) moved = true
                                if (drawing) h?.moved(currentFrame.toImage(ch.position))
                                else if (!multi && zoom > 1.01f) pan = clampPan(pan + d, zoom)
                                ch.consume()
                            }
                        }
                    }
                }
            }.pointerInput(Unit) {
                // 双击切换缩放（只在不涂抹时）
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (currentHandlers != null) return@awaitEachGesture
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val second = withTimeoutOrNullCompat(280) { awaitFirstDown(requireUnconsumed = false) } ?: return@awaitEachGesture
                    if ((second.position - first.position).getDistance() > 80f || up.uptimeMillis - first.uptimeMillis > 300) return@awaitEachGesture
                    if (zoom > 1.05f) { zoom = 1f; pan = Offset.Zero } else {
                        val z = 2.5f
                        val anchor = second.position - Offset(vw / 2, vh / 2)
                        zoom = z
                        pan = clampPan(-anchor * (z - 1f), z)
                    }
                }
            },
        ) {
            if (bmp != null) {
                drawImage(
                    bmp,
                    dstOffset = IntOffset(frame.left.toInt(), frame.top.toInt()),
                    dstSize = IntSize(frame.width.toInt(), frame.height.toInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        Box(Modifier.fillMaxSize()) { overlay(frame) }
    }
}

private suspend fun <T> androidx.compose.ui.input.pointer.AwaitPointerEventScope.withTimeoutOrNullCompat(
    ms: Long, block: suspend androidx.compose.ui.input.pointer.AwaitPointerEventScope.() -> T,
): T? = try { withTimeout(ms, block) } catch (_: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) { null }
