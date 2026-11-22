package com.taoo.xiutu.godcover

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIFailureView
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.BottomTabBar
import com.taoo.xiutu.ai.ChipRow
import com.taoo.xiutu.ai.NeedsKeyView
import com.taoo.xiutu.editor.TextInputOverlay
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.BusyOverlay
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.CornerBadge
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.Toast
import com.taoo.xiutu.ui.XSlider
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class CoverTab(val title: String) { STYLE("风格"), AI("AI 改造"), TEXT("文字"), ADJUST("调整") }

private enum class CoverField(val title: String) { MAIN("主标题"), SUB("副标题"), EXTRA1("附加文字 1"), EXTRA2("附加文字 2") }

/** "封神"：AI 杂志封面 / 海报。上面是画面，底部是一级菜单，菜单上方是二、三级选项。 */
@Composable
fun GodCoverScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(uri) { GodCoverModel(context).also { it.load(uri) } }
    DisposableEffect(model) { onDispose { model.dispose() } }
    var tab by remember { mutableStateOf(CoverTab.STYLE) }
    var editing by remember { mutableStateOf<CoverField?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastID by remember { mutableIntStateOf(0) }

    fun text(f: CoverField) = when (f) {
        CoverField.MAIN -> model.mainTitle
        CoverField.SUB -> model.subtitle
        CoverField.EXTRA1 -> model.extra1
        CoverField.EXTRA2 -> model.extra2
    }

    fun setText(f: CoverField, v: String) {
        when (f) {
            CoverField.MAIN -> model.mainTitle = v
            CoverField.SUB -> model.subtitle = v
            CoverField.EXTRA1 -> model.extra1 = v
            CoverField.EXTRA2 -> model.extra2 = v
        }
    }

    fun save() {
        saving = true
        scope.launch {
            val ok = model.save()
            saving = false
            toastID += 1
            val id = toastID
            toast = if (ok) "已保存到相册" else "保存失败，请检查相册权限"
            delay(2000)
            if (id == toastID) toast = null
        }
    }

    BackHandler { if (editing != null) editing = null else onClose() }

    Box(Modifier.fillMaxSize().background(Theme.bg).systemBarsPadding()) {
        // 引擎：必须留在界面层级里才能稳定工作，但不显示出来
        EngineHost(model.engine.webView)
        Column(Modifier.fillMaxSize()) {
            CoverTopBar(enabled = !model.isLoading && !saving, onBack = onClose, onSave = ::save)
            CoverCanvas(model, Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().background(Theme.panel).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.alpha(if (model.isLoading) 0f else 1f)) {
                    SubjectModeBar(model.subject, disabled = model.running) { model.subject = it }
                }
                when (tab) {
                    CoverTab.STYLE -> StylePanel(model)
                    CoverTab.AI -> AIPanel(model) { showSettings = true }
                    CoverTab.TEXT -> TextPanel(model) { editing = it }
                    CoverTab.ADJUST -> AdjustPanel(model)
                }
            }
            BottomTabBar(CoverTab.entries, { it.title }, tab) { tab = it }
        }
        if (model.isLoading || saving) BusyOverlay()
        model.errorText?.let { Text(it, color = Theme.dim, modifier = Modifier.align(Alignment.Center)) }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp)) }
        editing?.let { field ->
            TextInputOverlay(text(field), field.title) { value ->
                setText(field, value.trim())
                editing = null
                model.refresh()
            }
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

@Composable
private fun CoverTopBar(enabled: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).tap(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(sym("chevron.left"), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("封神", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(
            "保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black,
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f).clip(CircleShape).background(Color.White)
                .press(enabled, onSave).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CoverCanvas(model: GodCoverModel, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        model.preview?.let { image ->
            val ib = remember(image) { image.asImageBitmap() }
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Image(ib, null, contentScale = ContentScale.Fit, modifier = Modifier.clip(RoundedCornerShape(6.dp)))
            }
        }
        if (model.hasAI) {
            Row(Modifier.padding(bottom = 18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))) {
                Segment("原图", !model.showingAI) { model.showAI(false) }
                Segment("AI 图", model.showingAI) { model.showAI(true) }
            }
        }
    }
}

@Composable
private fun Segment(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(CircleShape).background(if (selected) Color.White else Color.Transparent)
            .tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** 分类名里"·"后面的补充说明在标签上省掉，保持一行。 */
private fun shortTitle(title: String) =
    title.replace(" · 14 种类型", "").replace("日本 · ", "").replace(" / ", "/")

@Composable
private fun StylePanel(model: GodCoverModel) {
    val categories = model.catalog.categories
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEachIndexed { index, c ->
                Chip("${c.icon} ${shortTitle(c.title)}", model.categoryIndex == index) { model.categoryIndex = index }
            }
        }
        val styles = categories.getOrNull(model.categoryIndex)?.styles ?: emptyList()
        // 换分类时滚动位置从头开始
        key(model.categoryIndex) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (style in styles) {
                    val selected = model.styleID == style.id
                    Column(
                        Modifier.width(124.dp).height(82.dp).clip(RoundedCornerShape(12.dp)).background(Theme.chip)
                            .border(2.dp, if (selected) Theme.accent else Color.Transparent, RoundedCornerShape(12.dp))
                            .press { model.selectStyle(style.id) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            style.name, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 15.sp,
                            overflow = TextOverflow.Ellipsis, color = if (selected) Theme.accent else Color.White,
                        )
                        Text(style.desc, fontSize = 10.sp, color = Theme.dim, maxLines = 2, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 主按钮（强调色胶囊）和次按钮（灰色胶囊），都会调用 AI，带角标。 */
@Composable
fun AIActionButtons(
    primary: String, primarySymbol: String, secondary: String, secondaryWidth: Int,
    onPrimary: () -> Unit, onSecondary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(Theme.accent).press(onClick = onPrimary),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(sym(primarySymbol), null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            CornerBadge("AI", Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-4).dp))
        }
        Box {
            Box(
                Modifier.width(secondaryWidth.dp).height(44.dp).clip(CircleShape).background(Theme.chip).press(onClick = onSecondary),
                contentAlignment = Alignment.Center,
            ) { Text(secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            CornerBadge("AI", Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-4).dp))
        }
    }
}

/** 生成中：转圈、文字（带秒数）、取消。 */
@Composable
fun RunningRow(start: Long, cancelTitle: String, verticalPadding: Int, onCancel: () -> Unit, text: (Long) -> String) {
    val seconds = elapsedSeconds(start)
    Row(Modifier.fillMaxWidth().padding(vertical = verticalPadding.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text(seconds), fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(cancelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Theme.accent, modifier = Modifier.tap(onClick = onCancel))
    }
}

@Composable
private fun AIPanel(model: GodCoverModel, openSettings: () -> Unit) {
    if (AISettings.endpoint() == null) {
        NeedsKeyView(openSettings = openSettings)
        return
    }
    if (model.running) {
        RunningRow(model.started, "取消", 26, model::cancelAI) { "${AISettings.active.shortTitle} 正在生成… $it 秒" }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (model.subject == SubjectMode.SCENE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(GodCoverScene.Group.entries, { it.title }, { model.sceneGroup == it }, Modifier.weight(1f)) { model.sceneGroup = it }
                ProviderMenu(openSettings)
            }
            ChipRow(model.sceneGroup.options, { it.title }, { model.sceneValue(model.sceneGroup) == it.id }) {
                model.sceneValues[model.sceneGroup] = it.id
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (group in GodCoverModel.OptionGroup.entries) {
                    Chip(group.title, model.optionGroup == group) { model.optionGroup = group }
                }
                Spacer(Modifier.weight(1f))
                ProviderMenu(openSettings)
            }
            ChipRow(model.options(model.optionGroup), { it.title }, { model.value(model.optionGroup) == it.id }) {
                model.setValue(it.id, model.optionGroup)
            }
        }
        AIActionButtons(
            primary = if (model.hasAI) "再生成一张" else "生成封面", primarySymbol = "sparkles",
            secondary = "无字版", secondaryWidth = 96,
            onPrimary = { model.generate(noText = false) }, onSecondary = { model.generate(noText = true) },
        )
        model.usageText?.let {
            Text(it, fontSize = 11.sp, color = Theme.accent, modifier = Modifier.fillMaxWidth())
        }
        AIFailureView(model.aiError) { model.retry() }
    }
}

@Composable
private fun TextPanel(model: GodCoverModel, edit: (CoverField) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextCell(CoverField.MAIN, model.mainTitle, Modifier.weight(1f), edit)
            TextCell(CoverField.SUB, model.subtitle, Modifier.weight(1f), edit)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextCell(CoverField.EXTRA1, model.extra1, Modifier.weight(1f), edit)
            TextCell(CoverField.EXTRA2, model.extra2, Modifier.weight(1f), edit)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("🎲 随机文案", false) { model.randomize() }
            Chip("叠加杂志模板", model.showTemplate) {
                model.showTemplate = !model.showTemplate
                model.refresh()
            }
            Chip("让 AI 用我的文字", model.forceText) { model.forceText = !model.forceText }
        }
    }
}

@Composable
private fun TextCell(field: CoverField, value: String, modifier: Modifier, edit: (CoverField) -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Theme.chip).tap { edit(field) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(field.title, fontSize = 10.sp, color = Theme.dim)
        // 空着时不放"点这里填写"之类的提示
        Text(value.ifEmpty { " " }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdjustPanel(model: GodCoverModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderRow("缩放", model.scale, 50f..200f, "%") { model.scale = it; model.refresh() }
        SliderRow("水平", model.offsetX, -100f..100f, "") { model.offsetX = it; model.refresh() }
        SliderRow("垂直", model.offsetY, -100f..100f, "") { model.offsetY = it; model.refresh() }
        ChipRow(model.catalog.filter, { it.title }, { model.filter == it.id }) {
            model.filter = it.id
            model.refresh()
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 13.sp, color = Theme.dim, modifier = Modifier.width(36.dp))
        XSlider(value, onChange, range, Modifier.weight(1f))
        Text(
            "${value.roundToInt()}$unit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            textAlign = TextAlign.End, modifier = Modifier.width(48.dp),
        )
    }
}
