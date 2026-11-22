package com.taoo.xiutu.tools.shape

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIPresetGroup
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession
import com.taoo.xiutu.ml.PersonSegmenter
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.IconTile
import com.taoo.xiutu.ui.LabeledSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手动美体（推拉 / 液化）：用手指推拉画面，全部在本机完成。 */
class LiquifySession(model: EditorModel) : ToolSession(model, Tool.LIQUIFY) {
    var segments by mutableStateOf<List<LiquifySegment>>(emptyList())
        private set
    var brush by mutableFloatStateOf(70f)
    var strength by mutableFloatStateOf(0.6f)
    var restore by mutableStateOf(false)
    /** 固定背景：推的时候只有人变形。 */
    var lock by mutableStateOf(false)
        private set
    var lockBusy by mutableStateOf(false)
        private set
    var note by mutableStateOf<String?>(null)
        private set

    private var lockMask: Bitmap? = null
    private var personMask: Bitmap? = null
    /** 提前补好的"没有人的背景"，实时预览时直接用。 */
    private var personPlate: Warp.Plate? = null
    private var grid: LiquifyGrid? = null
    private var last: PointF? = null
    private var radius = 0f
    private var stroke = 0
    private val density = model.context.resources.displayMetrics.density

    override val drawing: Boolean get() = true

    override fun start() {
        val b = model.base ?: return
        grid = LiquifyGrid(b.width, b.height)
    }

    override fun commitOps(): List<EditOp> {
        val p = LiquifyParams(segments.toList(), lockMask)
        return if (p.isIdentity) emptyList() else listOf(LiquifyOp(p))
    }

    override fun strokeBegan(p: PointF, displayScale: Float) {
        if (displayScale <= 0) return
        stroke += 1
        last = p
        // 笔刷大小按屏幕上的 dp 算，存成相对图片长边的比例
        radius = brush * density / 2 / displayScale
    }

    override fun strokeMoved(p: PointF) {
        val from = last ?: return
        val g = grid ?: return
        val segment = LiquifySegment(V2(from.x, from.y), V2(p.x, p.y), radius, strength, restore, stroke)
        segments = segments + segment
        g.add(segment)
        last = p
        render()
    }

    override fun strokeCancelled() = undoStroke()

    fun undoStroke() {
        val lastStroke = segments.lastOrNull()?.stroke ?: return
        val g = grid ?: return
        segments = segments.filter { it.stroke != lastStroke }
        g.replay(segments)
        last = null
        render()
    }

    fun reset() {
        val g = grid ?: return
        segments = emptyList()
        g.reset()
        render()
    }

    private fun render() {
        val field = grid?.snapshot() ?: return
        val mask = lockMask
        val plate = personPlate
        val identity = segments.none { !it.restore }
        model.liveOnBase { b -> if (identity) b else Warp.render(b, field, mask, plate) }
    }

    /** 开关"固定背景"。第一次打开时在本机识别一次人像，并补一张"没有人的背景"。 */
    fun changeLock(on: Boolean) {
        if (lockBusy) return
        note = null
        fun apply(mask: Bitmap?) {
            lock = mask != null
            lockMask = mask
            render()
        }
        if (!on) return apply(null)
        personMask?.let { return apply(it) }
        val base = model.base ?: return
        lockBusy = true
        model.scope.launch {
            val found = withContext(Dispatchers.Default) {
                runCatching {
                    val mask = PersonSegmenter.mask(base) ?: return@runCatching null
                    mask to Warp.plate(base, mask)
                }.getOrNull()
            }
            if (model.session !== this@LiquifySession) return@launch
            lockBusy = false
            personMask = found?.first
            personPlate = found?.second
            if (found == null) note = "这张图里没有识别到人物，没法固定背景。"
            apply(found?.first)
        }
    }

    /** 美体里换到"AI 美体"：已经做出来的修改先保留成一步，再换过去。 */
    private fun switchToAI() {
        if (model.isBusy) return
        model.commitTool { model.beginAI(AIPresetGroup.BODY) }
    }

    @Composable
    override fun Panel() {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("AI 美体", selected = false, symbol = "sparkles") { switchToAI() }
                Chip("手动美体", selected = true, symbol = "hand.draw") {}
            }
            LabeledSlider("大小", brush, { brush = it }, 24f..200f)
            LabeledSlider("力度", strength, { strength = it }, 0.15f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconTile("hand.draw", "推", selected = !restore) { restore = false }
                IconTile("bandage", "恢复", selected = restore) { restore = true }
                IconTile(if (lockBusy) "hourglass" else "lock.rectangle", "固定背景", selected = lock) { changeLock(!lock) }
                Spacer(Modifier.weight(1f))
                IconTile("arrow.uturn.backward", "撤销一笔", enabled = segments.isNotEmpty()) { undoStroke() }
                IconTile("arrow.counterclockwise", "全部还原", enabled = segments.isNotEmpty()) { reset() }
            }
            note?.let { Text(it, fontSize = 12.sp, color = Color(1f, 0.45f, 0.4f), modifier = Modifier.fillMaxWidth()) }
        }
    }
}
