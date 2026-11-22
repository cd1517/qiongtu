package com.taoo.xiutu.tools.adjust

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIPresetGroup
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession
import com.taoo.xiutu.ui.IconTile
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.UnderlineTabs
import com.taoo.xiutu.ui.ValueSliderRow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** 调色。面板上面是当前这一项的滑块，下面一排是这一组的全部项目；分组在确认栏中间切换。 */
class AdjustSession(model: EditorModel) : ToolSession(model, Tool.ADJUST) {
    var params by mutableStateOf(AdjustParams())
        private set
    var group by mutableStateOf(AdjustGroup.LIGHT)
    var key by mutableStateOf(AdjustKey.BRIGHTNESS)

    // 界面状态：开着哪个子面板、选中哪个颜色……不算进"改了什么"。
    var sub by mutableStateOf<AdjustSub?>(null)
        private set
    var hslColor by mutableStateOf(HSLColor.RED)
    var curveChannel by mutableIntStateOf(0)
    var localID by mutableStateOf<Long?>(null)

    override fun commitOps(): List<EditOp> = if (params.isIdentity) emptyList() else listOf(AdjustOp(params))

    fun setAdjust(k: AdjustKey, value: Float) {
        params = params.with(k, if (abs(value) < 0.015f) 0f else value)
        render()
    }

    /** 改滑块以外的参数（HSL、曲线、色调分离、局部调色……），改完刷新预览。 */
    fun change(body: (AdjustParams) -> AdjustParams) {
        params = body(params)
        render()
    }

    fun openSub(s: AdjustSub?) {
        sub = s
        if (s == AdjustSub.LOCAL && params.locals.none { it.id == localID }) localID = params.locals.lastOrNull()?.id
        render()
    }

    fun selectLocal(id: Long) {
        localID = id
        render()
    }

    fun render() {
        val p = params
        val marks = if (sub == AdjustSub.LOCAL && p.locals.isNotEmpty()) p.locals else null
        val sel = localID
        model.liveOnBase { base -> AdjustRender.apply(base, p, marks, sel) }
    }

    // MARK: 局部调色

    override val wantsTap: Boolean get() = sub == AdjustSub.LOCAL

    /** 点在已有的点附近就选中它，否则在这里新放一个点。 */
    override fun onTap(p: PointF) {
        if (sub != AdjustSub.LOCAL) return
        val base = model.base ?: return
        val w = base.width.toFloat()
        val h = base.height.toFloat()
        val dim = max(w, h)
        if (dim <= 0) return
        fun distance(l: LocalAdjust) = hypot((l.cx - p.x) * w, (l.cy - p.y) * h) / dim
        val near = params.locals.minByOrNull { distance(it) }
        if (near != null && distance(near) < 0.045f) {
            localID = near.id
        } else if (params.locals.size < LOCAL_LIMIT) {
            val added = LocalAdjust(cx = p.x.coerceIn(0f, 1f), cy = p.y.coerceIn(0f, 1f))
            params = params.copy(locals = params.locals + added)
            localID = added.id
        } else {
            model.showToast("已经放满 $LOCAL_LIMIT 个点了")
        }
        render()
    }

    val selectedLocal: LocalAdjust? get() = localID?.let { id -> params.locals.firstOrNull { it.id == id } }

    fun changeSelectedLocal(body: (LocalAdjust) -> LocalAdjust) {
        val id = localID ?: return
        change { p -> p.copy(locals = p.locals.map { if (it.id == id) body(it) else it }) }
    }

    fun removeSelectedLocal() {
        val id = localID ?: return
        params = params.copy(locals = params.locals.filter { it.id != id })
        localID = params.locals.lastOrNull()?.id
        render()
    }

    // MARK: 界面

    @Composable
    override fun Title() {
        val s = sub
        if (s != null) {
            Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            UnderlineTabs(AdjustGroup.entries, group, { it.title }) { g ->
                if (g != group) {
                    group = g
                    g.keys.firstOrNull()?.let { key = it }
                }
            }
        }
    }

    @Composable
    override fun Panel() {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
            val s = sub
            if (s != null) {
                Box(Modifier.padding(horizontal = 16.dp)) { AdjustSubPanel(this@AdjustSession, s) }
            } else {
                Main()
            }
        }
    }

    @Composable
    private fun Main() {
        val k = key
        val v = params[k]
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                ValueSliderRow(
                    value = v, onChange = { setAdjust(k, it) }, range = if (k.bipolar) -1f..1f else 0f..1f,
                    label = "${(v * 100).roundToInt()}", onReset = { setAdjust(k, 0f) }, resetEnabled = v != 0f,
                )
            }
            // 换组时回到最左边。
            composeKey(group) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 16.dp)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (item in group.items) Tile(item, k)
                }
            }
        }
    }

    @Composable
    private fun Tile(item: AdjustItem, selected: AdjustKey) {
        when (item) {
            is AdjustItem.Key -> IconTile(item.key.symbol, item.key.title, selected = item.key == selected, modified = params[item.key] != 0f) {
                key = item.key
            }
            is AdjustItem.Sub -> {
                IconTile(item.sub.symbol, item.sub.title, modified = params.isChanged(item.sub)) { openSub(item.sub) }
                // 局部调色和后面整张图的调节之间隔一条线。
                if (item.sub == AdjustSub.LOCAL) Box(Modifier.size(1.dp, 34.dp).background(Theme.chip))
            }
            is AdjustItem.AI -> IconTile(item.ai.symbol, item.ai.title, badge = "AI") {
                model.startAIPreset(item.ai.preset, AIPresetGroup.FIX)
            }
        }
    }

    companion object {
        /** 局部调色最多放几个点。 */
        const val LOCAL_LIMIT = 8
    }
}
