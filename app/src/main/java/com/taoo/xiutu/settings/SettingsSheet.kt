package com.taoo.xiutu.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIClient
import com.taoo.xiutu.ai.AICurrency
import com.taoo.xiutu.ai.AIPricing
import com.taoo.xiutu.ai.AIProvider
import com.taoo.xiutu.ai.AIProviderStats
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.ChipRow
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.launch
import java.util.Locale

private val cell = Color(0.12f, 0.12f, 0.12f)
private val sheetBg = Color(0.04f, 0.04f, 0.04f)
private val danger = Color(1f, 0.27f, 0.23f)
private const val TNUM = "tnum"

/** AI 设置：选服务商、填 API Key、选型号，看各家的用量。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AIProvider?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = sheetBg,
        dragHandle = null, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.96f).imePadding()) {
            // 顶栏
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
                val d = detail
                if (d != null) {
                    Box(Modifier.align(Alignment.CenterStart).size(44.dp).tap { detail = null }, contentAlignment = Alignment.Center) {
                        Icon(sym("chevron.left"), null, tint = Theme.accent, modifier = Modifier.size(22.dp))
                    }
                }
                Text(
                    d?.title ?: "AI 设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                )
                Text(
                    "完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Theme.accent,
                    modifier = Modifier.align(Alignment.CenterEnd).tap(onClick = close).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            val d = detail
            if (d == null) {
                MainPage(onOpen = { detail = it }, onReset = { confirmReset = true })
            } else {
                BackHandler { detail = null }
                ProviderPage(d)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("把所有服务商的用量统计清零？", fontSize = 16.sp) },
            confirmButton = {
                TextButton({ AISettings.resetUsage(); confirmReset = false }) { Text("清零", color = danger) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text("取消", color = Color.White) } },
            containerColor = cell,
        )
    }
}

@Composable
private fun MainPage(onOpen: (AIProvider) -> Unit, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        Section("AI 修图使用哪一家") {
            AIProvider.entries.forEachIndexed { i, p ->
                if (i > 0) Line()
                val on = AISettings.active == p
                Cell(onClick = { AISettings.setActive(p) }) {
                    Icon(
                        if (on) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (on) Theme.accent else Theme.dim, modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 16.sp, color = Color.White)
                        Text(
                            if (AISettings.isConfigured(p)) AISettings.config(p).model else "未设置",
                            fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Section("API Key 和型号") {
            AIProvider.entries.forEachIndexed { i, p ->
                if (i > 0) Line()
                Cell(onClick = { onOpen(p) }) {
                    Text(p.title, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val empty = AISettings.key(p).isEmpty()
                    val ok = AISettings.isVerified(p)
                    Text(
                        if (empty) "未填 Key" else if (ok) "已通过测试" else "未通过测试", fontSize = 12.sp,
                        color = if (empty) Theme.dim else if (ok) Theme.green else Color(1f, 0.6f, 0.1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(sym("chevron.right"), null, tint = Theme.dim, modifier = Modifier.size(13.dp))
                }
            }
        }

        Section("输出画质") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("画质（OpenAI）", fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(4.dp))
                ChipRow(AISettings.qualities, { it.second }, { it.first == AISettings.quality }) { AISettings.setQuality(it.first) }
            }
            Line()
            Cell {
                Text("高清输出", fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                Switch(
                    AISettings.hires, { AISettings.setHires(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Theme.accent, checkedThumbColor = Color.White),
                )
            }
        }

        UsageSection(onReset)

        RateSection()

        Spacer(Modifier.height(24.dp))
    }
}

/** 各家的累计用量：次数、token、张数和估算费用；按币种各有一行合计。 */
@Composable
private fun UsageSection(onReset: () -> Unit) {
    val used = AIProvider.entries.filter { AISettings.stats(it).requests > 0 }
    Section("用量统计") {
        if (used.isEmpty()) {
            Cell { Text("还没有用量", fontSize = 16.sp, color = Theme.dim) }
        } else {
            used.forEachIndexed { i, p ->
                if (i > 0) Line()
                UsageRow(p.title, AISettings.stats(p), p.currency)
            }
            for (c in AICurrency.entries) {
                val total = AISettings.usageTotal(c)
                if (total.requests > 0) {
                    Line()
                    UsageRow(if (c == AICurrency.USD) "合计（美元）" else "合计（人民币）", total, c, bold = true)
                }
            }
            Line()
            Cell(onClick = onReset) { Text("清零", fontSize = 16.sp, color = danger) }
        }
    }
}

/** 各家当前型号的费率（估算费用就是按这个算的）。 */
@Composable
private fun RateSection() {
    Section("费率") {
        AIProvider.entries.filter { it != AIProvider.CUSTOM }.forEachIndexed { i, p ->
            if (i > 0) Line()
            val config = AISettings.config(p)
            val model = config.model.ifEmpty { p.builtinModels.firstOrNull()?.id ?: "" }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, fontSize = 16.sp, color = Color.White, maxLines = 1)
                    Spacer(Modifier.weight(1f).widthIn(min = 10.dp))
                    Text(model, fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
                }
                Text(
                    AIPricing.rateText(p, model, AIClient.isOverseas(config.baseURL)) ?: "—",
                    fontSize = 12.sp, color = Theme.accent, style = TextStyle(fontFeatureSettings = TNUM),
                )
            }
        }
    }
}

/** 用量的一行：左边名字和明细，右边估算费用。 */
@Composable
private fun UsageRow(title: String, stats: AIProviderStats, currency: AICurrency, bold: Boolean = false) {
    Cell {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, color = Color.White, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            val parts = mutableListOf("${stats.requests} 次")
            if (stats.inputTokens + stats.outputTokens > 0) {
                parts += "输入 ${compact(stats.inputTokens)}"
                parts += "输出 ${compact(stats.outputTokens)} token"
            }
            parts += "${stats.images} 张"
            if (stats.unpriced > 0 && stats.unpriced < stats.requests) parts += "${stats.unpriced} 次无价目"
            Text(parts.joinToString(" · "), fontSize = 12.sp, color = Theme.dim, style = TextStyle(fontFeatureSettings = TNUM))
        }
        // 一次都算不出价格时显示"—"，不编数字
        val cost = if (stats.unpriced >= stats.requests) "—"
        else "约 ${currency.symbol}${String.format(Locale.US, "%.2f", stats.cost)}"
        Text(
            cost, fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (bold) Theme.accent else Color.White, style = TextStyle(fontFeatureSettings = TNUM),
        )
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> "$n"
}

/** 一家服务商的 Key、型号、接口地址。 */
@Composable
private fun ProviderPage(provider: AIProvider) {
    val scope = rememberCoroutineScope()
    var key by remember(provider) { mutableStateOf(AISettings.key(provider)) }
    var showKey by remember(provider) { mutableStateOf(false) }
    var baseURL by remember(provider) { mutableStateOf(AISettings.config(provider).baseURL) }
    var manualModel by remember(provider) { mutableStateOf("") }
    var busy by remember(provider) { mutableStateOf(false) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var statusOK by remember(provider) { mutableStateOf(false) }

    // 填了 Key 还没测就离开：自动测一次，通过了才算可用
    DisposableEffect(provider) {
        onDispose {
            if (AISettings.key(provider).isNotEmpty() && !AISettings.isVerified(provider)) AISettings.verifyInBackground(provider)
        }
    }

    val config = AISettings.config(provider)
    val stats = AISettings.stats(provider)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        Section("API Key") {
            Cell {
                Field(
                    key, {
                        key = it
                        AISettings.setKey(it, provider)
                    },
                    placeholder = "粘贴 API Key", modifier = Modifier.weight(1f), mono = true, secret = !showKey,
                    keyboard = KeyboardType.Password,
                )
                Box(Modifier.size(36.dp).tap { showKey = !showKey }, contentAlignment = Alignment.Center) {
                    Icon(sym(if (showKey) "eye.slash" else "eye"), null, tint = Theme.dim, modifier = Modifier.size(20.dp))
                }
            }
        }

        Section("型号") {
            val choices = AISettings.modelChoices(provider)
            choices.forEachIndexed { i, id ->
                if (i > 0) Line()
                Cell(onClick = { AISettings.update(provider) { it.copy(model = id) } }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(id, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                        provider.builtinModels.firstOrNull { it.id == id }?.let { Text(it.note, fontSize = 12.sp, color = Theme.dim) }
                    }
                    if (config.model == id) Icon(sym("checkmark"), null, tint = Theme.accent, modifier = Modifier.size(20.dp))
                }
            }
            if (choices.isNotEmpty()) Line()
            Cell {
                Field(manualModel, { manualModel = it }, placeholder = "手动填写型号 ID", modifier = Modifier.weight(1f), mono = true, size = 15.sp)
                val ok = manualModel.isNotBlank()
                Text(
                    "使用", fontSize = 16.sp, color = Theme.accent,
                    modifier = Modifier.alpha(if (ok) 1f else 0.4f).tap(enabled = ok) {
                        val id = manualModel.trim()
                        if (id.isNotEmpty()) {
                            AISettings.update(provider) { it.copy(model = id) }
                            manualModel = ""
                        }
                    }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }

        Section("接口地址") {
            fun setBase(value: String) {
                baseURL = value
                val new = value.trim()
                // 地址（地域）换了也要重新测试
                AISettings.update(provider) { c -> c.copy(baseURL = new, verified = if (c.baseURL != new) false else c.verified) }
            }
            Cell {
                Field(baseURL, { setBase(it) }, placeholder = "https://…", modifier = Modifier.weight(1f), mono = true, size = 13.sp, keyboard = KeyboardType.Uri)
            }
            for ((title, url) in provider.baseURLChoices) {
                Line()
                Cell(onClick = { setBase(url) }) {
                    Text(title, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                    if (baseURL == url) Icon(sym("checkmark"), null, tint = Theme.accent, modifier = Modifier.size(20.dp))
                }
            }
            if (provider.baseURLChoices.isEmpty() && provider.defaultBaseURL.isNotEmpty() && baseURL != provider.defaultBaseURL) {
                Line()
                Cell(onClick = { setBase(provider.defaultBaseURL) }) { Text("恢复官方地址", fontSize = 16.sp, color = Theme.accent) }
            }
        }

        Section(null) {
            val enabled = !busy && key.isNotBlank()
            Cell(onClick = if (enabled) ({
                scope.launch {
                    busy = true
                    status = null
                    val failure = AISettings.verify(provider)
                    statusOK = failure == null
                    status = failure ?: if (provider.canListModels) {
                        val count = AISettings.config(provider).fetchedModels.size
                        if (count == 0) "连接成功，但没有找到图像型号。" else "连接成功，找到 $count 个型号。"
                    } else "连接成功。"
                    busy = false
                }
            }) else null) {
                Text(
                    if (provider.canListModels) "测试连接并刷新型号列表" else "测试连接", fontSize = 16.sp,
                    color = Theme.accent, modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f),
                )
                if (busy) CircularProgressIndicator(color = Theme.dim, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            status?.let {
                Line()
                Text(it, fontSize = 13.sp, color = if (statusOK) Theme.green else danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }

        if (stats.requests > 0) {
            Section("用量统计") {
                UsageRow("累计", stats, provider.currency)
                Line()
                Cell(onClick = { AISettings.resetUsage(provider) }) { Text("清零", fontSize = 16.sp, color = danger) }
            }
        }

        Section(null) {
            val current = AISettings.active == provider
            Cell(onClick = if (current) null else ({ AISettings.setActive(provider) })) {
                Text(
                    if (current) "当前正在使用" else "设为当前使用", fontSize = 16.sp,
                    color = if (current) Theme.dim else Theme.accent,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// 分组列表的小部件

@Composable
private fun Section(header: String?, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (header != null) {
            Text(header, fontSize = 13.sp, color = Theme.dim, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cell), content = content)
    }
}

@Composable
private fun Cell(onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Line() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Color(0.22f, 0.22f, 0.22f))
}

/** 单行输入框：不自动纠错、不自动大写。secret = true 时遮挡内容并关掉输入法联想。 */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    secret: Boolean = false,
    size: TextUnit = 16.sp,
    keyboard: KeyboardType = KeyboardType.Ascii,
) {
    BasicTextField(
        value, onChange, modifier,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = size, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(Theme.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else keyboard,
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = size, color = Theme.dim.copy(alpha = 0.6f), maxLines = 1)
                inner()
            }
        },
    )
}
