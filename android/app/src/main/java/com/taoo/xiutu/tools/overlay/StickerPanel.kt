package com.taoo.xiutu.tools.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.CornerBadge
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface StickerCategory {
    data object Mine : StickerCategory
    data object Shapes : StickerCategory
    data object Labels : StickerCategory
    data class Emoji(val index: Int) : StickerCategory
}

private val emojiSets: List<Pair<String, List<String>>> = listOf(
    "表情" to listOf("😀", "😂", "🥹", "😍", "😎", "🥳", "😭", "😡", "🤔", "😴", "🤯", "🥺", "😇", "🤪", "😱", "🫠"),
    "爱心" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💖", "💘", "💝", "💕", "💔", "❣️", "💋", "🫶"),
    "手势" to listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👏", "🙌", "🙏", "💪", "👋", "🤙", "👉", "👆", "✊"),
    "自然" to listOf("🌸", "🌹", "🌻", "🌈", "⭐️", "🌙", "☀️", "☁️", "❄️", "🔥", "💧", "🍀", "🌊", "⚡️", "🍁", "🌴"),
    "美食" to listOf("🍰", "🍓", "🍑", "🍒", "🍉", "🍔", "🍕", "🍜", "🍣", "🍩", "🍦", "🧋", "☕️", "🍺", "🥂", "🍷"),
    "符号" to listOf("✨", "💯", "💢", "💤", "💬", "❗️", "❓", "✅", "❌", "⭕️", "🎀", "🎉", "🎈", "👑", "💎", "🎵"),
)

/** 内置的文字标签：加到画面上以后还是文字，双击可以改内容。字体用安卓自带的相近字体。 */
private val labels: List<OverlayItem> = run {
    fun make(text: String, font: String = OverlayItem.DEFAULT_FONT_ID, configure: (OverlayItem) -> OverlayItem) =
        configure(OverlayItem(text = text, size = 0.085f, fontID = font))
    val red = OverlayColor.of(0.9, 0.16, 0.2)
    val yellow = OverlayColor.of(1.0, 0.84, 0.04)
    val pink = OverlayColor.of(1.0, 0.45, 0.7)
    val navy = OverlayColor.of(0.12, 0.2, 0.5)
    val blue = OverlayColor.of(0.04, 0.52, 1.0)
    val mint = OverlayColor.of(0.55, 0.9, 0.78)
    val w = OverlayColor.white
    val b = OverlayColor.black
    listOf(
        make("OOTD", "fam:sans-serif:900") { it.copy(color = w, boxOn = true, boxColor = b, boxShape = OverlayShape.CAPSULE) },
        make("打卡", "sys.black") { it.copy(color = w, boxOn = true, boxColor = red, boxShape = OverlayShape.TAG) },
        make("今日份快乐", "sys.rounded") { it.copy(color = b, boxOn = true, boxColor = yellow, boxShape = OverlayShape.MARKER) },
        make("Yummy!", "fam:casual:700") { it.copy(color = b, boxOn = true, boxColor = w, boxShape = OverlayShape.BUBBLE) },
        make("周末愉快", "sys.rounded") {
            it.copy(
                color = pink, strokeOn = true, strokeColor = w, strokeWidth = 0.7f,
                shadowOn = true, shadowColor = pink, shadowOpacity = 0.5f, shadowBlur = 0.5f, shadowDistance = 0.1f, shadowAngle = 90f,
            )
        },
        make("NEW", "fam:sans-serif-condensed:800") { it.copy(color = w, italic = true, boxOn = true, boxColor = red, boxShape = OverlayShape.SLANT) },
        make("Vlog", "fam:cursive:700") { it.copy(color = w, glowOn = true, glowColor = OverlayColor.of(1.0, 0.3, 0.65), glowStrength = 0.8f) },
        make("好吃", "sys.black") {
            it.copy(
                color = yellow, strokeOn = true, strokeColor = b, strokeWidth = 0.6f,
                shadowOn = true, shadowBlur = 0f, shadowDistance = 0.22f, shadowAngle = 45f, shadowOpacity = 1f,
            )
        },
        make("Hello", "fam:serif:700") { it.copy(color = w, boxOn = true, boxColor = navy, boxShape = OverlayShape.RIBBON) },
        make("LOVE", "fam:sans-serif:700") { it.copy(color = red, letterSpacing = 0.2f, boxOn = true, boxColor = w, boxShape = OverlayShape.ROUNDED) },
        make("出发", "sys.black") { it.copy(color = w, italic = true, boxOn = true, boxColor = blue, boxShape = OverlayShape.SLANT) },
        make("谢谢", "sys.rounded") { it.copy(color = b, boxOn = true, boxColor = mint, boxShape = OverlayShape.BUBBLE) },
        make("Mood", "fam:monospace:700") { it.copy(color = w, boxOn = true, boxColor = b, boxOpacity = 0.6f, boxShape = OverlayShape.ROUNDED) },
        make("生日快乐", "sys.rounded") { it.copy(color = w, boxOn = true, boxColor = pink, boxShape = OverlayShape.ELLIPSE) },
        make("SALE", "fam:sans-serif-condensed:900") { it.copy(color = b, boxOn = true, boxColor = yellow, boxShape = OverlayShape.RECT) },
        make("晚安", "sys.serif") { it.copy(color = w, glowOn = true, glowColor = OverlayColor.of(0.2, 0.7, 1.0), glowStrength = 0.7f) },
    )
}

private const val AUTO_CUT_KEY = "xiutu.sticker.autoCut"

private fun prefs(context: Context) = context.getSharedPreferences("xiutu", Context.MODE_PRIVATE)

/** 贴纸面板：我的贴纸（相册导入、AI 生成）、内置的图形和标签，以及表情。 */
@Composable
fun StickerPanel(s: OverlaySession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf<StickerCategory>(StickerCategory.Shapes) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showAI by remember { mutableStateOf(false) }
    var autoCut by remember { mutableStateOf(prefs(context).getBoolean(AUTO_CUT_KEY, true)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        problem = null
        val cut = autoCut
        scope.launch {
            val made = withContext(Dispatchers.Default) { StickerImport.make(Img.decode(context, uri, 2048), cut) }
            busy = false
            if (made == null) {
                problem = "这张图读不出来"
                return@launch
            }
            val saved = StickerStore.add(made.image)
            if (saved == null) {
                problem = "保存失败"
                return@launch
            }
            s.addOverlay(made.image)
            if (made.cutFailed) problem = "没识别出主体，整张加进来了"
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EdgeRow {
            Chip("我的", category == StickerCategory.Mine) { category = StickerCategory.Mine }
            Chip("图形", category == StickerCategory.Shapes) { category = StickerCategory.Shapes }
            Chip("标签", category == StickerCategory.Labels) { category = StickerCategory.Labels }
            emojiSets.forEachIndexed { i, set -> Chip(set.first, category == StickerCategory.Emoji(i)) { category = StickerCategory.Emoji(i) } }
        }
        LazyHorizontalGrid(
            GridCells.Fixed(2), Modifier.fillMaxWidth().height(98.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (val c = category) {
                StickerCategory.Mine -> {
                    item { ActionTile("photo.badge.plus", "相册") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
                    item { ActionTile("sparkles", "AI 生成", badge = "AI") { showAI = true } }
                    items(StickerStore.items, key = { it.id }) { entry -> MineTile(s, entry) }
                }
                StickerCategory.Shapes -> items(StickerArt.all, key = { it.id }) { piece ->
                    val thumb = remember(piece.id) { StickerArt.image(piece, 128) }
                    Box(tileModifier().press {
                        scope.launch {
                            val img = withContext(Dispatchers.Default) { StickerArt.image(piece, 1024) }
                            if (img != null) s.addOverlay(img, dieCut = true)
                        }
                    }, contentAlignment = Alignment.Center) {
                        if (thumb != null) BitmapImage(thumb, Modifier.fillMaxSize().padding(5.dp))
                    }
                }
                StickerCategory.Labels -> items(labels.size) { i -> LabelTile(labels[i], "label-$i") { s.addOverlay(labels[i]) } }
                is StickerCategory.Emoji -> items(emojiSets[c.index].second) { emoji ->
                    Box(tileModifier().tap { s.addOverlay(emoji, sticker = true) }, contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Footer(s, category, autoCut, { autoCut = it; prefs(context).edit().putBoolean(AUTO_CUT_KEY, it).apply() }, busy, problem)
        }
    }

    if (showAI) {
        AIStickerSheet(
            onDismiss = { showAI = false },
            onSettings = { showAI = false; s.model.showSettings = true },
        ) { image ->
            category = StickerCategory.Mine
            s.addOverlay(image)
        }
    }
}

private fun tileModifier() = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Theme.chip)

@Composable
private fun ActionTile(symbol: String, title: String, badge: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(46.dp)) {
        Column(tileModifier().tap(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(sym(symbol), null, tint = Theme.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(3.dp))
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Theme.accent, maxLines = 1)
        }
        if (badge != null) CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MineTile(s: OverlaySession, entry: StickerEntry) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            tileModifier().combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onLongClick = { menu = true },
                onClick = {
                    scope.launch {
                        val img = withContext(Dispatchers.IO) { StickerStore.image(entry) }
                        if (img != null) s.addOverlay(img)
                    }
                },
            ),
            contentAlignment = Alignment.Center,
        ) { BitmapImage(entry.thumb, Modifier.fillMaxSize().padding(5.dp)) }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
            DropdownMenuItem(text = { Text("删除", color = Theme.red) }, onClick = { menu = false; StickerStore.delete(entry) })
        }
    }
}

@Composable
private fun LabelTile(label: OverlayItem, key: String, onClick: () -> Unit) {
    val density = LocalDensity.current.density
    val image = OverlayThumbs.image("$key-$density", 15f * density) { label }
    val w = (image?.width ?: 0) / density
    val h = (image?.height ?: 0) / density
    val fit = minOf(1f, 38f / maxOf(h, 1f))
    Box(
        Modifier.width(maxOf(46f, w * fit + 8f).dp).height(46.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0.3f, 0.3f, 0.3f)).press(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, null, Modifier.size((w * fit).dp, (h * fit).dp))
    }
}

/** 最下面一行：选中图片贴纸时是它的样式，否则在"我的"里是导入选项和状态。 */
@Composable
private fun Footer(s: OverlaySession, category: StickerCategory, autoCut: Boolean, onAutoCut: (Boolean) -> Unit, busy: Boolean, problem: String?) {
    val item = s.selected
    if (item != null && item.isImage) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("白边", item.strokeOn) { s.update(item.id) { it.copy(strokeOn = !it.strokeOn, strokeColor = OverlayColor.white) } }
            Chip("阴影", item.shadowOn) {
                s.update(item.id) {
                    it.copy(
                        shadowOn = !it.shadowOn, shadowColor = OverlayColor.black, shadowOpacity = 0.4f, shadowBlur = 0.6f,
                        shadowDistance = 0.25f, shadowAngle = 90f,
                    )
                }
            }
            MiniSlider("透明度", item.opacity, 0.1f..1f, Modifier.weight(1f), labelWidth = 40.dp) { v -> s.update(item.id) { it.copy(opacity = v) } }
        }
    } else if (category == StickerCategory.Mine) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("自动抠图", autoCut, symbol = "scissors") { onAutoCut(!autoCut) }
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("正在处理…", fontSize = 13.sp, color = Color.White)
            } else if (problem != null) {
                Text(problem, fontSize = 12.sp, color = problemColor, maxLines = 2)
            }
        }
    }
}

// MARK: AI 生成贴纸

/** 写一句想要什么，用设置里选的服务商生成一张贴纸，存进"我的贴纸"。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIStickerSheet(onDismiss: () -> Unit, onSettings: () -> Unit, onUse: (Bitmap) -> Unit) {
    val scope = rememberCoroutineScope()
    var what by remember { mutableStateOf("") }
    var style by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableLongStateOf(0L) }
    var seconds by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    val trimmed = what.trim()
    val configured = remember { AISettings.isActiveConfigured() }

    LaunchedEffect(running) {
        while (running) {
            seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun generate() {
        if (trimmed.isEmpty() || running) return
        error = null
        running = true
        started = System.currentTimeMillis()
        seconds = 0
        val hint = StickerAI.styles[style].second
        val text = trimmed
        job = scope.launch {
            try {
                val made = StickerAI.generate(text, hint)
                result = made.first
                StickerStore.add(made.first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AIError) {
                error = e.text
            } catch (e: Exception) {
                error = e.message ?: "生成失败"
            } finally {
                running = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Theme.panel, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("AI 生成贴纸", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (!configured) {
                // 没填 Key
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("还没有设置 ${AISettings.activeShortTitle} 的 API Key", fontSize = 14.sp)
                    Text(
                        "去设置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Black,
                        modifier = Modifier.clip(CircleShape).background(Theme.accent).press(onClick = onSettings).padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }
            } else {
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                TextField(
                    value = what, onValueChange = { what = it },
                    placeholder = { Text("想要什么贴纸", color = Theme.dim) },
                    maxLines = 3, textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = Color.White),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Theme.chip, unfocusedContainerColor = Theme.chip,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Theme.accent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StickerAI.styles.forEachIndexed { i, st -> Chip(st.first, style == i) { style = i } }
                }
                result?.let { img ->
                    Box(
                        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).background(Color(0.28f, 0.28f, 0.28f)).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) { BitmapImage(img, Modifier.fillMaxSize()) }
                }
                if (running) {
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("${AISettings.activeShortTitle} 正在生成… $seconds 秒", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Theme.accent, modifier = Modifier.tap {
                            job?.cancel()
                            job = null
                            running = false
                        })
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        result?.let { img ->
                            BigButton("加到画面", "plus", primary = true, modifier = Modifier.weight(1f)) {
                                onUse(img)
                                onDismiss()
                            }
                        }
                        BigButton(
                            if (result == null) "生成" else "再生成", if (result == null) "sparkles" else "arrow.clockwise",
                            primary = result == null, enabled = trimmed.isNotEmpty(), badge = "AI", modifier = Modifier.weight(1f),
                        ) { generate() }
                    }
                }
                error?.let { Text(it, fontSize = 12.sp, color = problemColor) }
            }
        }
    }
}

@Composable
private fun BigButton(title: String, symbol: String, primary: Boolean, modifier: Modifier, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(if (primary) Theme.accent else Theme.chip)
                .alpha(if (enabled) 1f else 0.4f).press(enabled, onClick),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (primary) Color.Black else Color.White
            Icon(sym(symbol), null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
        if (badge != null) CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-4).dp))
    }
}
