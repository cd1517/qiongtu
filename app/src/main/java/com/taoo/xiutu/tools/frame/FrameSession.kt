package com.taoo.xiutu.tools.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession
import com.taoo.xiutu.tools.doodle.DecoColorWell
import com.taoo.xiutu.tools.doodle.RGB
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.IconTile
import com.taoo.xiutu.ui.ImageTile
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 边框 / 背景：tool = FRAME 或 BACKDROP。 */
class FrameSession(model: EditorModel, tool: Tool) : ToolSession(model, tool) {
    // 边框
    var frame by mutableStateOf(FrameParams())
        private set
    /** 当前选中的样式（FramePreset.id）。 */
    var presetID by mutableStateOf("none")
        private set
    /** 每个样式的缩略图：用当前这张照片套上边框画出来的。 */
    var thumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    private var frameTab by mutableStateOf(FrameTab.COLOR)

    // 背景
    var backdrop by mutableStateOf(BackdropParams())
        private set
    private var backdropKey by mutableStateOf(BackdropKey.SCALE)

    private var reopenedFrame: FrameParams? = null
    private var reopenedBackdrop: BackdropParams? = null
    private var thumbJob: Job? = null

    override fun start() {
        if (tool == Tool.FRAME) makeFrameThumbs()
    }

    override fun close() {
        thumbJob?.cancel()
    }

    override fun commitOps(): List<EditOp> = when (tool) {
        Tool.FRAME -> if (frame.isIdentity) emptyList() else listOf(FrameOp(frame))
        Tool.BACKDROP -> if (backdrop.isIdentity) emptyList() else listOf(BackdropOp(backdrop))
        else -> emptyList()
    }

    override fun reopen(op: EditOp) {
        when (op) {
            is FrameOp -> {
                frame = op.params
                reopenedFrame = op.params
                FramePreset.all.firstOrNull { it.params.style == op.params.style }?.let { presetID = it.id }
                renderFrame()
            }
            is BackdropOp -> {
                backdrop = op.params
                reopenedBackdrop = op.params
                renderBackdrop()
            }
        }
    }

    override fun unchangedSinceReopen(): Boolean = when (tool) {
        Tool.FRAME -> reopenedFrame != null && reopenedFrame == frame
        Tool.BACKDROP -> reopenedBackdrop != null && reopenedBackdrop == backdrop
        else -> false
    }

    // MARK: 边框

    /** 换一个边框样式：用它的默认参数，已经写好的字留着。 */
    fun selectFramePreset(preset: FramePreset) {
        presetID = preset.id
        frame = preset.params.copy(caption = frame.caption)
        renderFrame()
    }

    fun updateFrame(change: (FrameParams) -> FrameParams) {
        frame = change(frame)
        renderFrame()
    }

    private fun renderFrame() {
        if (tool != Tool.FRAME) return
        val p = frame
        model.liveOnBase { p.apply(it) }
    }

    /** 样式缩略图：把照片居中裁一小块，套上每种边框，再整个放进按钮那么大的深色底里（边框不能被裁掉）。 */
    private fun makeFrameThumbs() {
        val base = model.base ?: return
        thumbJob = model.scope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    val small = Img.downscale(Img.centerCrop(base, 0.82f), 170)
                    val tw = 204
                    val th = 252
                    val room = RectF(14f, 14f, tw - 14f, th - 14f)
                    val map = LinkedHashMap<String, Bitmap>()
                    for (preset in FramePreset.all) {
                        val framed = preset.params.apply(small)
                        val k = min(room.width() / framed.width, room.height() / framed.height)
                        val fw = framed.width * k
                        val fh = framed.height * k
                        val tile = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                        val c = Canvas(tile)
                        c.drawColor(android.graphics.Color.rgb(36, 36, 36))
                        val x = (tw / 2f - fw / 2).roundToInt().toFloat()
                        val y = (th / 2f - fh / 2).roundToInt().toFloat()
                        c.drawBitmap(framed, null, RectF(x, y, x + fw, y + fh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                        map[preset.id] = tile
                    }
                    map
                }.onFailure { it.printStackTrace() }.getOrNull()
            }
            if (out != null && model.session === this@FrameSession) thumbs = out
        }
    }

    // MARK: 背景

    fun updateBackdrop(change: (BackdropParams) -> BackdropParams) {
        backdrop = change(backdrop)
        renderBackdrop()
    }

    /** 换背景的填充方式或颜色。画布和照片一样大时背景露不出来，这时先把照片缩小一点，改了才看得见。 */
    fun updateBackdropFill(change: (BackdropParams) -> BackdropParams) {
        var next = change(backdrop)
        if (next.ratio == BackdropRatio.ORIGINAL && next.scale >= 0.999f) next = next.copy(scale = 0.86f)
        backdrop = next
        renderBackdrop()
    }

    private fun renderBackdrop() {
        if (tool != Tool.BACKDROP) return
        val params = backdrop
        model.liveOnBase { base ->
            // 画布是往外放大的（照片不缩），竖长比例加上小照片时预览图会变得很大。
            // 参数全是相对值，所以预览时先把照片缩小一点再算，样子不变，拖滑块才跟手。
            var input = base
            val (cw, ch) = params.canvasSize(base.width.toFloat(), base.height.toFloat())
            val long = max(cw, ch)
            if (!params.isIdentity && long > 2000) {
                val k = 2000 / long
                input = Img.scaled(Img.downscale(base, max(8, floor(max(base.width, base.height) * k).toInt())),
                    max(8, floor(base.width * k).toInt()), max(8, floor(base.height * k).toInt()))
            }
            params.apply(input)
        }
    }

    // MARK: 面板

    @Composable
    override fun Panel() {
        if (tool == Tool.BACKDROP) BackdropPanel() else FramePanel()
    }

    /** 边框面板：上面一排样式（用当前照片画的缩略图），下面调颜色、粗细、圆角。 */
    @Composable
    private fun FramePanel() {
        val params = frame
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EdgeScroll(spacing = 10.dp, alignment = Alignment.Top) {
                for (preset in FramePreset.all) {
                    ImageTile(preset.title, thumbs[preset.id], selected = presetID == preset.id, symbol = "photo.artframe") {
                        selectFramePreset(preset)
                    }
                }
            }
            if (params.style != FrameStyle.NONE) {
                FrameOptions(params)
                Box(Modifier.padding(horizontal = 16.dp)) { FrameControl(params) }
            }
        }
    }

    /** 当前样式下实际用的那个标签（比如从邮票换到白边后，"底色"就没有了）。 */
    private fun currentTab(p: FrameParams) = if (frameTab == FrameTab.BACKING && !p.style.hasBacking) FrameTab.COLOR else frameTab

    @Composable
    private fun FrameOptions(p: FrameParams) {
        val now = currentTab(p)
        EdgeScroll(spacing = 8.dp) {
            Chip(if (p.style.hasBacking) "纸色" else "颜色", selected = now == FrameTab.COLOR) { frameTab = FrameTab.COLOR }
            if (p.style.hasBacking) Chip("底色", selected = now == FrameTab.BACKING) { frameTab = FrameTab.BACKING }
            Chip(if (p.style == FrameStyle.LINE) "位置" else "粗细", selected = now == FrameTab.WIDTH) { frameTab = FrameTab.WIDTH }
            Chip("圆角", selected = now == FrameTab.RADIUS) { frameTab = FrameTab.RADIUS }
            if (p.style.hasCaption || p.style.canGoInside) Box(Modifier.size(1.dp, 20.dp).background(Theme.chip))
            if (p.style.hasCaption) {
                Chip(if (p.caption.isEmpty()) "写字" else "改字", selected = false, symbol = "textformat") {
                    model.requestText(p.caption, "比如：2026.11 东京") { text -> updateFrame { it.copy(caption = text.trim()) } }
                }
            }
            if (p.style.canGoInside) {
                Chip("向内", selected = p.inside, symbol = "arrow.down.right.and.arrow.up.left") { updateFrame { it.copy(inside = !it.inside) } }
            }
        }
    }

    @Composable
    private fun FrameControl(p: FrameParams) {
        when (currentTab(p)) {
            FrameTab.COLOR -> FrameColorRow(p.color) { c -> updateFrame { it.copy(color = c) } }
            FrameTab.BACKING -> FrameColorRow(p.backing) { c -> updateFrame { it.copy(backing = c) } }
            FrameTab.WIDTH -> FrameSliderRow(p.width) { v -> updateFrame { it.copy(width = v) } }
            FrameTab.RADIUS -> FrameSliderRow(p.radius) { v -> updateFrame { it.copy(radius = if (v < 0.02f) 0f else v) } }
        }
    }

    /** 背景面板：选画布比例和背景的填充方式，再调照片在画布里的大小、位置、圆角和投影。 */
    @Composable
    private fun BackdropPanel() {
        val p = backdrop
        val now = currentKey(p)
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EdgeScroll(spacing = 8.dp) {
                for (r in BackdropRatio.entries) Chip(r.title, selected = p.ratio == r) { updateBackdrop { it.copy(ratio = r) } }
            }
            EdgeScroll(spacing = 8.dp) {
                for (f in BackdropFill.entries) Chip(f.title, selected = p.fill == f, symbol = f.symbol) { updateBackdropFill { it.copy(fill = f) } }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { FillRow(p) }
            Box(Modifier.padding(horizontal = 16.dp)) { BackdropSlider(p, now) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in keys(p)) IconTile(k.symbol, k.title, selected = k == now, modified = changed(k, p)) { backdropKey = k }
            }
        }
    }

    private fun keys(p: BackdropParams): List<BackdropKey> {
        val list = mutableListOf(BackdropKey.SCALE, BackdropKey.RADIUS, BackdropKey.SHADOW, BackdropKey.MOVE_X, BackdropKey.MOVE_Y)
        when (p.fill) {
            BackdropFill.BLUR -> list += listOf(BackdropKey.BLUR, BackdropKey.DIM)
            BackdropFill.GRADIENT -> list += BackdropKey.ANGLE
            BackdropFill.CHECKS, BackdropFill.DOTS, BackdropFill.STRIPES -> list += BackdropKey.PATTERN
            BackdropFill.SOLID -> {}
        }
        return list
    }

    /** 换了填充方式后，原来选的那一项可能没有了，退回"大小"。 */
    private fun currentKey(p: BackdropParams) = if (backdropKey in keys(p)) backdropKey else BackdropKey.SCALE

    private fun changed(k: BackdropKey, p: BackdropParams): Boolean {
        val base = BackdropParams()
        return when (k) {
            BackdropKey.SCALE -> p.scale < 0.999f
            BackdropKey.RADIUS -> p.radius > 0
            BackdropKey.SHADOW -> p.shadow > 0
            BackdropKey.MOVE_X -> p.offsetX != 0f
            BackdropKey.MOVE_Y -> p.offsetY != 0f
            BackdropKey.BLUR -> p.blur != base.blur
            BackdropKey.DIM -> p.dim != base.dim
            BackdropKey.PATTERN -> p.pattern != base.pattern
            BackdropKey.ANGLE -> p.angle != base.angle
        }
    }

    @Composable
    private fun FillRow(p: BackdropParams) {
        when (p.fill) {
            BackdropFill.BLUR -> Spacer(Modifier.height(36.dp))
            BackdropFill.SOLID -> FrameColorRow(p.colorA) { c -> updateBackdropFill { it.copy(colorA = c) } }
            BackdropFill.GRADIENT -> PairRow(p, GRADIENTS, gradient = true)
            BackdropFill.CHECKS, BackdropFill.DOTS, BackdropFill.STRIPES -> PairRow(p, PATTERNS, gradient = false)
        }
    }

    /** 两个自选颜色 + 一排现成配色。 */
    @Composable
    private fun PairRow(p: BackdropParams, pairs: List<Pair<FrameColor, FrameColor>>, gradient: Boolean) {
        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameColorWell(p.colorA) { c -> updateBackdropFill { it.copy(colorA = c) } }
            FrameColorWell(p.colorB) { c -> updateBackdropFill { it.copy(colorB = c) } }
            Box(Modifier.size(1.dp, 20.dp).background(Theme.chip))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((a, b) in pairs) {
                    val on = p.colorA == a && p.colorB == b
                    val brush = if (gradient) Brush.verticalGradient(listOf(a.compose(), b.compose()))
                    else Brush.horizontalGradient(0.5f to a.compose(), 0.5f to b.compose())
                    Box(
                        Modifier.border(2.dp, if (on) Theme.accent else Color.Transparent, RoundedCornerShape(10.dp)).padding(3.dp)
                            .size(40.dp, 28.dp).clip(RoundedCornerShape(7.dp)).background(brush)
                            .border(1.dp, Color(0.4f, 0.4f, 0.4f), RoundedCornerShape(7.dp))
                            .tap { updateBackdropFill { it.copy(colorA = a, colorB = b) } },
                    )
                }
            }
        }
    }

    @Composable
    private fun BackdropSlider(p: BackdropParams, k: BackdropKey) {
        when (k) {
            BackdropKey.SCALE -> FrameSliderRow(p.scale, 0.4f..1f) { v -> updateBackdrop { it.copy(scale = if (v > 0.99f) 1f else v) } }
            BackdropKey.RADIUS -> FrameSliderRow(p.radius) { v -> updateBackdrop { it.copy(radius = if (v < 0.02f) 0f else v) } }
            BackdropKey.SHADOW -> FrameSliderRow(p.shadow) { v -> updateBackdrop { it.copy(shadow = if (v < 0.02f) 0f else v) } }
            BackdropKey.MOVE_X -> FrameSliderRow(p.offsetX, -1f..1f) { v -> updateBackdrop { it.copy(offsetX = if (abs(v) < 0.03f) 0f else v) } }
            BackdropKey.MOVE_Y -> FrameSliderRow(p.offsetY, -1f..1f) { v -> updateBackdrop { it.copy(offsetY = if (abs(v) < 0.03f) 0f else v) } }
            BackdropKey.BLUR -> FrameSliderRow(p.blur) { v -> updateBackdrop { it.copy(blur = v) } }
            BackdropKey.DIM -> FrameSliderRow(p.dim) { v -> updateBackdrop { it.copy(dim = v) } }
            BackdropKey.PATTERN -> FrameSliderRow(p.pattern) { v -> updateBackdrop { it.copy(pattern = v) } }
            BackdropKey.ANGLE -> FrameSliderRow(p.angle, 0f..360f, "${p.angle.roundToInt()}°") { v ->
                // 每 45° 吸附一下，方便对齐横平竖直
                val snapped = (v / 45).roundToInt() * 45f
                updateBackdrop { it.copy(angle = if (abs(v - snapped) < 4) snapped else v) }
            }
        }
    }

    private enum class FrameTab { COLOR, BACKING, WIDTH, RADIUS }

    private enum class BackdropKey(val title: String, val symbol: String) {
        SCALE("大小", "arrow.up.left.and.arrow.down.right"),
        RADIUS("圆角", "app"),
        SHADOW("投影", "shadow"),
        MOVE_X("左右", "arrow.left.and.right"),
        MOVE_Y("上下", "arrow.up.and.down"),
        BLUR("模糊", "drop"),
        DIM("压暗", "moon"),
        PATTERN("图案大小", "circle.grid.3x3"),
        ANGLE("方向", "rotate.right"),
    }

    companion object {
        /** 渐变的现成配色。 */
        private val GRADIENTS = listOf(
            0xFDFBFB to 0xEBEDEE, 0xF5F7FA to 0xC3CFE2, 0xFFECD2 to 0xFCB69F, 0xFBC2EB to 0xA6C1EE,
            0xA1C4FD to 0xC2E9FB, 0xD4FC79 to 0x96E6A1, 0xFA709A to 0xFEE140, 0x30CFD0 to 0x330867,
            0x232526 to 0x414345,
        ).map { FrameColor.hex(it.first) to FrameColor.hex(it.second) }

        /** 图案的现成配色：底色 + 图案色。 */
        private val PATTERNS = listOf(
            0xFFF4E0 to 0xF2B5A0, 0xBFE3F2 to 0xFFFFFF, 0xF7F7F7 to 0xDADADA, 0xFDE2E4 to 0xFFFFFF,
            0xE2ECE9 to 0xBEE1E6, 0x1F2A44 to 0xE8C547, 0x222222 to 0x3A3A3A,
        ).map { FrameColor.hex(it.first) to FrameColor.hex(it.second) }
    }
}

private fun FrameColor.compose() = Color(r, g, b)

/** 横向滚动的一排，两边留 16 的内边距（内容可以滚到屏幕边缘）。 */
@Composable
private fun EdgeScroll(spacing: androidx.compose.ui.unit.Dp, alignment: Alignment.Vertical = Alignment.CenterVertically, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = alignment, content = content,
    )
}

/** 常用色。 */
private val COMMON = listOf(
    0xFFFFFF, 0x0A0A0A, 0xF6F2EA, 0xECEDEF, 0x8E8E93, 0x2B2B2E, 0xF4C7C3, 0xF9E0B0,
    0xCDE7C9, 0xBFE3F2, 0xC9C6F0, 0xE85D5D, 0xF2A33A, 0x3A7BD5, 0x2E5E4E, 0x1F2A44, 0x6B4A3A,
).map { FrameColor.hex(it) }

/** 自选颜色的小圆钮。 */
@Composable
private fun FrameColorWell(color: FrameColor, pick: (FrameColor) -> Unit) {
    DecoColorWell(RGB(color.r, color.g, color.b), 32.dp) { pick(FrameColor.of(it.r, it.g, it.b)) }
}

/** 一排颜色：最前面是自选颜色，后面是常用色。 */
@Composable
private fun FrameColorRow(selected: FrameColor, pick: (FrameColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        FrameColorWell(selected, pick)
        for (c in COMMON) {
            Box(
                Modifier.size(32.dp).border(2.dp, if (selected == c) Theme.accent else Color.Transparent, CircleShape)
                    .padding(3.dp).clip(CircleShape).background(c.compose())
                    .border(1.dp, Color(0.4f, 0.4f, 0.4f), CircleShape).tap { pick(c) },
            )
        }
    }
}

/** 数值 + 滑块的一行。 */
@Composable
private fun FrameSliderRow(value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, text: String? = null, set: (Float) -> Unit) {
    Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text ?: "${(value * 100).roundToInt()}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.width(48.dp))
        XSlider(value, set, range, Modifier.weight(1f))
    }
}
