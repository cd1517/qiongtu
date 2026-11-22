package com.taoo.xiutu.manga

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIFailureView
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.ChipRow
import com.taoo.xiutu.ai.NeedsKeyView
import com.taoo.xiutu.ai.ResultViewer
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.Toast
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 漫画人生：把生活照片画成一页漫画。 */
class MangaModel {
    data class BubbleMode(val id: String, val title: String)

    companion object {
        val bubbleModes = listOf(
            BubbleMode("chinese", "中文台词"), BubbleMode("japanese", "日文台词"),
            BubbleMode("english", "英文台词"), BubbleMode("empty", "气泡留空"),
            BubbleMode("none", "不画气泡"),
        )
        private const val SHEET_NOTE =
            "Some characters also have a three-view TURNAROUND SHEET attached after the character portraits (front, side and back of the same character). Use it only to keep the character's side and back views consistent; never copy the sheet layout itself into the manga page."
    }

    val photos = mutableStateListOf<Bitmap>()
    var style by mutableStateOf(MangaStyle.SHONEN_JUMP)
    var isColor by mutableStateOf(false)
    var bubbleMode by mutableStateOf("chinese")
    var note by mutableStateOf("")
    /** 续接：把上一页一起发给 AI，画风、人物和剧情接着往下画。 */
    var continueFromLast by mutableStateOf(false)
    /** 这一页要出场的角色（来自角色库），最多 4 个。 */
    val cast = mutableStateListOf<MangaCharacter>()

    val pages = mutableStateListOf<Bitmap>()
    var pageIndex by mutableStateOf(0)
    var running by mutableStateOf(false)
    var started by mutableLongStateOf(System.currentTimeMillis())
    var aiError by mutableStateOf<String?>(null)
    var usageText by mutableStateOf<String?>(null)

    private var job: Job? = null

    val currentPage: Bitmap? get() = pages.getOrNull(pageIndex)

    fun add(images: List<Bitmap>) {
        val room = max(0, 8 - photos.size)
        photos.addAll(images.take(room))
    }

    fun generate(scope: CoroutineScope) {
        if (running) return
        if (photos.isEmpty()) {
            aiError = "先在“素材”里添加至少一张照片。"
            return
        }
        if (AISettings.endpoint() == null) {
            aiError = "还没有设置 ${AISettings.active.shortTitle} 的 API Key。"
            return
        }
        val previous = if (continueFromLast) pages.lastOrNull() else null
        // 指令拼法和 LifeManga 一致：风格 → 气泡 → 续接 → 角色 → 补充说明
        val parts = mutableListOf(style.effectivePrompt(isColor), BubbleDirective.simpleMode(bubbleMode))
        if (previous != null) parts += BubbleDirective.CONTINUITY_DIRECTIVE
        // 角色库里已经删掉的角色不再带上
        cast.retainAll { c -> CharacterStore.characters.any { it.id == c.id } }
        val castList = cast.toList()
        val photoList = photos.toList()
        val extra = note.trim()

        aiError = null
        usageText = null
        running = true
        started = System.currentTimeMillis()
        job = scope.launch {
            try {
                val castImages = withContext(Dispatchers.IO) { castList.mapNotNull { CharacterStore.image(it) } }.toMutableList()
                val castReady = castImages.size == castList.size
                if (castReady) {
                    CharacterPrompts.directive(castList)?.let { directive ->
                        parts += directive
                        // 有三视图的角色把三视图也带上；接口一次最多 16 张图，超了就不带
                        val sheets = withContext(Dispatchers.IO) { castList.mapNotNull { CharacterStore.sheet(it) } }
                        val used = (if (previous == null) 0 else 1) + photoList.size + castImages.size
                        if (sheets.isNotEmpty() && used + sheets.size <= 16) {
                            castImages += sheets
                            parts += SHEET_NOTE
                        }
                    }
                }
                if (extra.isNotEmpty()) parts += "Additional user note: $extra"
                val prompt = parts.joinToString("\n\n")
                // 图片顺序：上一页 → 照片 → 角色设定图
                val images = listOfNotNull(previous) + photoList + (if (castReady) castImages else emptyList())
                val (image, usage) = AIOneShot.run(prompt, images)
                pages.add(image)
                pageIndex = pages.size - 1
                usageText = usage?.summary
                running = false
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: Exception) {
                running = false
                aiError = e.aiMessage()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        running = false
    }

    fun toggleCast(character: MangaCharacter) {
        val index = cast.indexOfFirst { it.id == character.id }
        if (index >= 0) cast.removeAt(index) else if (cast.size < 4) cast.add(character)
    }
}

private enum class MangaTab(val title: String) {
    MATERIAL("素材"), CAST("角色"), STYLE("风格"), BUBBLE("台词"), DRAW("生成")
}

/** 读选中的照片：转正方向、最长边 2048。 */
private suspend fun loadPhotos(context: Context, uris: List<Uri>): List<Bitmap> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri -> Img.decode(context, uri, 2048)?.let { AIOneShot.prepared(it, 2048) } }
}

@Composable
fun MangaScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { CharacterStore.init(context) }
    val model = remember { MangaModel() }
    var tab by remember { mutableStateOf(MangaTab.MATERIAL) }
    // 走到过的最远一步：之前的步骤随时能点回去改，后面的要一步步走过去
    var reached by remember { mutableStateOf(MangaTab.MATERIAL) }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var creatingCharacter by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 系统选图器：一次最多选剩下的张数（iOS 的 PhotosPicker 同款）
    val room = max(1, 8 - model.photos.size)
    val onPicked: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) scope.launch { model.add(loadPhotos(context, uris)) }
    }
    val multiContract = remember(room) { ActivityResultContracts.PickMultipleVisualMedia(max(2, room)) }
    val pickMany = rememberLauncherForActivityResult(multiContract) { onPicked(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(listOfNotNull(uri)) }
    val pickPhotos = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (room > 1) pickMany.launch(request) else pickOne.launch(request)
    }

    fun go(t: MangaTab) {
        tab = t
        if (t.ordinal > reached.ordinal) reached = t
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    BackHandler(enabled = viewing == null && !creatingCharacter && detail == null, onBack = onClose)

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 顶栏
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(sym("chevron.left"), null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("漫画人生", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                val page = model.currentPage
                Text(
                    "保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.alpha(if (page == null) 0f else 1f).clip(CircleShape).background(Color.White)
                        .press(enabled = page != null) {
                            val p = model.currentPage ?: return@press
                            scope.launch {
                                val ok = AIOneShot.saveToPhotos(context, p, "漫画人生")
                                toast = if (ok) "已保存到相册" else "保存失败，请检查相册权限"
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { Content(model) { viewing = it } }
            StepBar(tab, reached, model.running) { go(it) }
            Column(
                Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    MangaTab.MATERIAL -> MaterialPanel(model, pickPhotos)
                    MangaTab.CAST -> CastPanel(model, onCreate = { creatingCharacter = true }, onDetail = { detail = it.id })
                    MangaTab.STYLE -> StylePanel(model)
                    MangaTab.BUBBLE -> ChipRow(MangaModel.bubbleModes, { it.title }, { model.bubbleMode == it.id }) { model.bubbleMode = it.id }
                    MangaTab.DRAW -> DrawPanel(model, openSettings = { showSettings = true }) { model.generate(scope) }
                }
            }
            StepFooter(tab, model) { go(it) }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp)) }
        viewing?.let { ResultViewer(it, onClose = { viewing = null }) }
        if (creatingCharacter) CharacterCreateScreen(onClose = { creatingCharacter = false })
        detail?.let { id ->
            CharacterDetailScreen(
                characterID = id,
                isInCast = { cid -> model.cast.any { it.id == cid } },
                toggleCast = { model.toggleCast(it) },
                onClose = { detail = null },
            )
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

// 上半部分：生成的页面，或者已选的照片

@Composable
private fun Content(model: MangaModel, onView: (Bitmap) -> Unit) {
    val page = model.currentPage
    when {
        page != null -> Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bitmap = remember(page) { page.asImageBitmap() }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.aspectRatio(page.width.toFloat() / page.height).clip(RoundedCornerShape(6.dp)).tap { onView(page) },
                )
            }
            if (model.pages.size > 1) {
                ChipRow(model.pages.indices.toList(), { "第 ${it + 1} 页" }, { model.pageIndex == it }, Modifier.padding(horizontal = 16.dp)) {
                    model.pageIndex = it
                }
            }
        }
        model.photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(sym("book.pages"), null, tint = Theme.dim, modifier = Modifier.size(44.dp))
        }
        else -> LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(model.photos) { _, photo ->
                val bitmap = remember(photo) { photo.asImageBitmap() }
                Image(
                    bitmap, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0.12f, 0.12f, 0.12f)),
                )
            }
        }
    }
}

// 步骤条：走过的步骤可以点回去，没走到的点不了

@Composable
private fun StepBar(tab: MangaTab, reached: MangaTab, running: Boolean, onGo: (MangaTab) -> Unit) {
    val steps = MangaTab.entries
    Row(
        Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in steps) {
            val i = step.ordinal
            val current = step == tab
            val done = i < reached.ordinal || (i <= reached.ordinal && !current)
            val open = i <= reached.ordinal
            Row(
                Modifier.weight(1f).tap(enabled = open && !running) { onGo(step) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (current) Theme.accent else if (open) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (open) Color.Transparent else Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done && !current) {
                        Icon(sym("checkmark"), null, tint = Theme.accent, modifier = Modifier.size(12.dp))
                    } else {
                        Text(
                            "${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp,
                            color = if (current) Color.Black else if (open) Color.White else Theme.dim,
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    step.title, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = if (current) Color.White else if (open) Color.White.copy(alpha = 0.8f) else Theme.dim,
                )
            }
            if (step != steps.last()) {
                Box(
                    Modifier.width(10.dp).height(1.5.dp)
                        .background(if (i < reached.ordinal) Theme.accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f)),
                )
            }
        }
    }
}

/** 底部：上一步 / 下一步。最后一步的生成按钮在面板里。 */
@Composable
private fun StepFooter(tab: MangaTab, model: MangaModel, onGo: (MangaTab) -> Unit) {
    val steps = MangaTab.entries
    val canAdvance = tab != MangaTab.MATERIAL || model.photos.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tab != steps.first()) {
            Row(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(CircleShape).background(Theme.chip)
                    .press(enabled = !model.running) { onGo(steps[tab.ordinal - 1]) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(sym("chevron.left"), null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("上一步", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        if (tab != steps.last()) {
            Row(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(CircleShape)
                    .background(if (canAdvance) Theme.accent else Theme.accent.copy(alpha = 0.35f))
                    .press(enabled = canAdvance) { onGo(steps[tab.ordinal + 1]) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(if (tab == MangaTab.CAST && model.cast.isEmpty()) "跳过" else "下一步", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.width(6.dp))
                Icon(sym("chevron.right"), null, tint = Color.Black, modifier = Modifier.size(13.dp))
            }
        }
    }
}

// 各步骤的面板

@Composable
private fun MaterialPanel(model: MangaModel, pick: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.size(62.dp).clip(RoundedCornerShape(10.dp)).background(Theme.accent).tap(enabled = model.photos.size < 8, onClick = pick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(sym("plus"), null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text("添加", fontSize = 11.sp, color = Color.Black)
        }
        model.photos.forEachIndexed { index, photo ->
            Box(Modifier.size(62.dp)) {
                val bitmap = remember(photo) { photo.asImageBitmap() }
                Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f))
                        .tap { if (index < model.photos.size) model.photos.removeAt(index) },
                    contentAlignment = Alignment.Center,
                ) { Icon(sym("xmark"), null, tint = Color.White, modifier = Modifier.size(10.dp)) }
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.alpha(if (model.pages.isEmpty()) 0.35f else 1f)) {
            Chip("续接上一页", model.continueFromLast) {
                if (model.pages.isNotEmpty()) model.continueFromLast = !model.continueFromLast
            }
        }
    }
}

/** 角色库：点一下让角色出场（再点取消），长按看详情或删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CastPanel(model: MangaModel, onCreate: () -> Unit, onDetail: (MangaCharacter) -> Unit) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.size(66.dp, 88.dp).clip(RoundedCornerShape(10.dp)).background(Theme.accent).press(onClick = onCreate),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(sym("plus"), null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text("新建", fontSize = 11.sp, color = Color.Black)
        }
        for (character in CharacterStore.characters) {
            val selected = model.cast.any { it.id == character.id }
            Column(
                Modifier.width(70.dp).combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { model.toggleCast(character) },
                    onLongClick = { menuFor = character.id },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(66.dp, 88.dp).clip(RoundedCornerShape(10.dp)).background(Color(0.14f, 0.14f, 0.14f))
                        .border(2.5.dp, if (selected) Theme.accent else Color.Transparent, RoundedCornerShape(10.dp)),
                ) {
                    val image by produceState(CharacterStore.cached(character.imageFile), character.imageFile) {
                        value = CharacterStore.imageAsync(character.imageFile)
                    }
                    image?.let { bmp ->
                        val bitmap = remember(bmp) { bmp.asImageBitmap() }
                        Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (character.sheetFile != null) {
                        Text(
                            "三视图", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Black, lineHeight = 10.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    if (selected) {
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp).clip(CircleShape).background(Color.Black)) {
                            Icon(sym("checkmark.circle.fill"), null, tint = Theme.accent, modifier = Modifier.fillMaxSize())
                        }
                    }
                    DropdownMenu(menuFor == character.id, onDismissRequest = { menuFor = null }, containerColor = Theme.chip) {
                        DropdownMenuItem(text = { Text("角色详情 / 三视图", color = Color.White, fontSize = 14.sp) }, onClick = {
                            menuFor = null
                            onDetail(character)
                        })
                        DropdownMenuItem(text = { Text("删除角色", color = Theme.red, fontSize = 14.sp) }, onClick = {
                            menuFor = null
                            model.cast.removeAll { it.id == character.id }
                            CharacterStore.remove(character)
                        })
                    }
                }
                Text(
                    character.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Theme.accent else Color.White,
                )
            }
        }
    }
    if (model.cast.isNotEmpty()) {
        Text(
            "出场：${model.cast.joinToString("、") { it.name }}", fontSize = 12.sp, color = Theme.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StylePanel(model: MangaModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("黑白", !model.isColor) { model.isColor = false }
        Chip("彩色", model.isColor) { model.isColor = true }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (style in MangaStyle.entries) {
            StyleCard(style.displayName, style.subtitle, model.style == style) { model.style = style }
        }
    }
}

@Composable
private fun DrawPanel(model: MangaModel, openSettings: () -> Unit, generate: () -> Unit) {
    if (!AISettings.isConfigured(AISettings.active)) {
        NeedsKeyView(openSettings = openSettings)
    } else if (model.running) {
        val seconds = elapsedSeconds(model.started)
        RunningRow("${AISettings.active.shortTitle} 正在画… $seconds 秒", "取消") { model.cancel() }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${model.style.displayName} · ${if (model.isColor) "彩色" else "黑白"} · ${model.photos.size} 张照片" +
                    if (model.cast.isEmpty()) "" else " · ${model.cast.size} 个角色",
                fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            ProviderMenu(openSettings = openSettings)
        }
        AccentButton(
            if (model.pages.isEmpty()) "画一页漫画" else if (model.continueFromLast) "接着画下一页" else "再画一页",
            "pencil.and.outline", Modifier.fillMaxWidth(), onClick = generate,
        )
        model.usageText?.let { Text(it, fontSize = 11.sp, color = Theme.accent, modifier = Modifier.fillMaxWidth()) }
        AIFailureView(model.aiError, retry = generate)
    }
}
