package com.taoo.xiutu.tools.mosaic

import android.graphics.PointF
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession
import com.taoo.xiutu.ui.IconTile
import com.taoo.xiutu.ui.LabeledSlider

/** 马赛克：用手指涂抹打码。 */
class MosaicSession(model: EditorModel) : ToolSession(model, Tool.MOSAIC) {
    var style by mutableStateOf(lastStyle)
    var strength by mutableFloatStateOf(lastStrength)
    var brushSize by mutableFloatStateOf(lastBrush)
    var erasing by mutableStateOf(false)

    private val strokes = ArrayList<MosaicStroke>()
    private var strokeCount by mutableIntStateOf(0)
    private var maskSet: MosaicMaskSet? = null
    private var reopenedFrom: List<MosaicStroke>? = null
    private val density = model.context.resources.displayMetrics.density

    override val drawing: Boolean get() = true

    override fun start() {
        val b = model.base ?: return
        maskSet = MosaicMaskSet(b.width, b.height)
    }

    override fun close() {
        lastStyle = style
        lastStrength = strength
        lastBrush = brushSize
    }

    override fun reopen(op: EditOp) {
        val m = op as? MosaicOp ?: return
        strokes.clear()
        strokes.addAll(m.strokes.map { it.copy() })
        reopenedFrom = m.strokes
        strokeCount = strokes.size
        maskSet?.rebuild(strokes)
        render()
    }

    override fun unchangedSinceReopen(): Boolean {
        val r = reopenedFrom ?: return false
        return r.size == strokes.size && r.indices.all { r[it].sameAs(strokes[it]) }
    }

    override fun commitOps(): List<EditOp> {
        val op = MosaicOp(strokes)
        return if (op.hasPaint) listOf(op) else emptyList()
    }

    override fun strokeBegan(p: PointF, displayScale: Float) {
        if (displayScale <= 0) return
        // 笔刷大小按屏幕上的 dp 算，存成相对图片长边的比例
        val s = MosaicStroke(mutableListOf(p), brushSize * density / displayScale, erasing, style, strength)
        strokes += s
        strokeCount = strokes.size
        maskSet?.add(listOf(p), s)
        render()
    }

    override fun strokeMoved(p: PointF) {
        val s = strokes.lastOrNull() ?: return
        val prev = s.points.lastOrNull() ?: return
        s.points += p
        maskSet?.add(listOf(prev, p), s)
        render()
    }

    override fun strokeCancelled() = undoStroke()

    fun undoStroke() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        strokeCount = strokes.size
        maskSet?.rebuild(strokes)
        render()
    }

    private fun render() {
        val set = maskSet ?: return
        val masks = set.snapshot()
        model.liveOnBase { MosaicParams.composite(it, masks, set.lock) }
    }

    @Composable
    override fun Panel() {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledSlider("大小", brushSize, { brushSize = it }, 12f..90f)
            LabeledSlider("强度", strength, { strength = it }, 0.05f..1f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 六个按钮放不下时整行可以左右滑
                val gap = (maxWidth - 58.dp * 6 - 4.dp * 6).coerceAtLeast(4.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in MosaicStyle.entries) {
                        IconTile(s.symbol, s.title, selected = !erasing && style == s) {
                            style = s
                            erasing = false
                        }
                    }
                    Spacer(Modifier.width(gap))
                    IconTile("eraser", "橡皮", selected = erasing) { erasing = !erasing }
                    IconTile("arrow.uturn.backward", "撤销一笔", enabled = strokeCount > 0) { undoStroke() }
                }
            }
        }
    }

    companion object {
        // 这次打开 App 里上次用的样式、强度、大小
        private var lastStyle = MosaicStyle.PIXEL
        private var lastStrength = 0.45f
        private var lastBrush = 36f
    }
}
