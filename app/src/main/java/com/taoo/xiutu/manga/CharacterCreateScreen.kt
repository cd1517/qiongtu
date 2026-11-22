package com.taoo.xiutu.manga

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIFailureView
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.BottomTabBar
import com.taoo.xiutu.ai.NeedsKeyView
import com.taoo.xiutu.ai.ResultViewer
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.editor.TextInputOverlay
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 新建角色：一张照片 → 选一种或多种画风 → 生成角色立绘，生成一张就存进角色库一张。 */
class CharacterCreateModel {
    enum class Status { PENDING, RUNNING, DONE, FAILED }

    data class Result(
        val style: CharacterArtStyle,
        val status: Status = Status.PENDING,
        val image: Bitmap? = null,
        val error: String? = null,
        val note: String? = null,
    )

    var photo by mutableStateOf<Bitmap?>(null)
    var name by mutableStateOf("")
    var bio by mutableStateOf("")
    var ageGroup by mutableStateOf(CharacterAgeGroup.YOUNG_ADULT)
    var gender by mutableStateOf(CharacterGender.FEMALE)
    var styles by mutableStateOf(setOf(CharacterArtStyle.JP_ANIME))
    /** 立绘画好后接着画三视图（每个角色多调用一次）。 */
    var withSheet by mutableStateOf(false)

    val results = mutableStateListOf<Result>()
    var running by mutableStateOf(false)
    var started by mutableLongStateOf(System.currentTimeMillis())
    var aiError by mutableStateOf<String?>(null)
    var spent by mutableDoubleStateOf(0.0)
    var savedCount by mutableIntStateOf(0)

    private var job: Job? = null

    val currentIndex: Int? get() = results.indexOfFirst { it.status == Status.RUNNING }.takeIf { it >= 0 }

    fun toggle(style: CharacterArtStyle) {
        styles = if (style in styles) styles - style else styles + style
    }

    private fun update(index: Int, change: (Result) -> Result) {
        if (index in results.indices) results[index] = change(results[index])
    }

    fun start(scope: CoroutineScope) {
        if (running) return
        val photo = photo ?: run {
            aiError = "先选一张照片。"
            return
        }
        if (styles.isEmpty()) {
            aiError = "至少选一种画风。"
            return
        }
        if (AISettings.endpoint() == null) {
            aiError = "还没有设置 ${AISettings.active.shortTitle} 的 API Key。"
            return
        }
        val characterName = name.trim().ifEmpty { "角色 ${CharacterStore.characters.size + 1}" }
        val characterBio = bio.trim()
        val age = ageGroup
        val sex = gender
        val wantSheet = withSheet
        // 按画风列表的固定顺序排，方便对照
        results.clear()
        results.addAll(CharacterArtStyle.entries.filter { it in styles }.map { Result(it) })
        aiError = null
        running = true
        started = System.currentTimeMillis()
        job = scope.launch {
            var failuresInARow = 0
            loop@ for (index in results.indices) {
                val style = results[index].style
                update(index) { it.copy(status = Status.RUNNING) }
                started = System.currentTimeMillis()
                val prompt = CharacterPrompts.buildCharacterPortraitPrompt(
                    characterName, characterBio.ifEmpty { null }, age, sex, style,
                )
                try {
                    val (image, usage) = AIOneShot.run(prompt, listOf(photo))
                    update(index) { it.copy(image = image, status = Status.DONE) }
                    spent += usage?.cost ?: 0.0
                    // 多种画风时名字后面带上画风，角色库里好分辨
                    val saveName = if (results.size > 1) "$characterName · ${style.displayName}" else characterName
                    val saved = CharacterStore.add(saveName, characterBio, age, sex, style, image)
                    if (saved != null) savedCount += 1
                    failuresInARow = 0
                    if (wantSheet && saved != null) {
                        // 三视图失败不影响已经存好的立绘，以后可以在角色详情里再生成
                        update(index) { it.copy(status = Status.RUNNING, note = "正在画三视图…") }
                        try {
                            spent += CharacterStore.generateSheet(saved.id) ?: 0.0
                            update(index) { it.copy(note = "含三视图") }
                        } catch (e: CancellationException) {
                            update(index) { it.copy(status = Status.DONE) }
                            break@loop
                        } catch (e: Exception) {
                            update(index) { it.copy(note = "三视图没画成") }
                        }
                        update(index) { it.copy(status = Status.DONE) }
                    }
                } catch (e: CancellationException) {
                    break@loop
                } catch (e: Exception) {
                    val message = e.aiMessage()
                    update(index) { it.copy(status = Status.FAILED, error = message) }
                    aiError = "${style.displayName} 失败：$message"
                    failuresInARow += 1
                    if (failuresInARow >= 2) break@loop
                }
            }
            for (index in results.indices) {
                val r = results[index]
                if (r.status == Status.RUNNING || r.status == Status.PENDING) {
                    results[index] = r.copy(status = Status.FAILED, error = r.error ?: "没有生成")
                }
            }
            running = false
        }
    }

    fun cancel() {
        job?.cancel()
        running = false
    }
}

private enum class CreateTab(val title: String) { PROFILE("设定"), STYLE("画风"), MAKE("生成") }

private enum class Field { NAME, BIO }

@Composable
fun CharacterCreateScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { CharacterCreateModel() }
    var tab by remember { mutableStateOf(CreateTab.PROFILE) }
    var editing by remember { mutableStateOf<Field?>(null) }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { Img.decode(context, uri, 2048)?.let { AIOneShot.prepared(it, 2048) } }?.let { model.photo = it }
        }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    BackHandler(enabled = viewing == null) {
        if (editing != null) editing = null else onClose()
    }

    Box(Modifier.fillMaxSize().background(Theme.bg).tap { }) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(sym("xmark"), null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("新建角色", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                val canFinish = model.savedCount > 0 && !model.running
                Text(
                    "完成", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.alpha(if (canFinish) 1f else 0f).clip(CircleShape).background(Color.White)
                        .press(enabled = canFinish, onClick = onClose)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { CreateContent(model, pickPhoto) { viewing = it } }
            Column(
                Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    CreateTab.PROFILE -> ProfilePanel(model) { editing = it }
                    CreateTab.STYLE -> ArtStylePanel(model)
                    CreateTab.MAKE -> MakePanel(model, openSettings = { showSettings = true }) { model.start(scope) }
                }
            }
            BottomTabBar(CreateTab.entries, { it.title }, tab) { tab = it }
        }
        editing?.let { field ->
            TextInputOverlay(
                if (field == Field.NAME) model.name else model.bio,
                if (field == Field.NAME) "角色名字" else "角色设定，比如：爱笑、总是戴着耳机",
            ) { value ->
                val text = value.trim()
                if (field == Field.NAME) model.name = text else model.bio = text
                editing = null
            }
        }
        viewing?.let { ResultViewer(it, onClose = { viewing = null }) }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

// 上半部分：照片，或者生成结果

@Composable
private fun CreateContent(model: CharacterCreateModel, pickPhoto: () -> Unit, onView: (Bitmap) -> Unit) {
    val photo = model.photo
    when {
        model.results.isNotEmpty() -> LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(model.results) { ResultCard(it, onView) }
        }
        photo != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val bitmap = remember(photo) { photo.asImageBitmap() }
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.aspectRatio(photo.width.toFloat() / photo.height).clip(RoundedCornerShape(6.dp)),
                )
            }
            Text(
                "换一张照片", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.padding(bottom = 20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .press(onClick = pickPhoto).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        else -> Column(
            Modifier.fillMaxSize().tap(onClick = pickPhoto),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(sym("person.crop.rectangle.badge.plus"), null, tint = Theme.accent, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("选一张照片", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun ResultCard(result: CharacterCreateModel.Result, onView: (Bitmap) -> Unit) {
    Box(
        Modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Theme.panel)
            .tap { result.image?.let(onView) },
    ) {
        val image = result.image
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                when (result.status) {
                    CharacterCreateModel.Status.RUNNING -> {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("正在画…", fontSize = 12.sp, color = Theme.dim)
                    }
                    CharacterCreateModel.Status.FAILED -> {
                        Icon(sym("exclamationmark.triangle"), null, tint = Color(1f, 0.45f, 0.4f), modifier = Modifier.size(20.dp))
                        Text(
                            result.error ?: "失败", fontSize = 11.sp, color = Theme.dim, maxLines = 4,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    else -> {
                        Icon(sym("clock"), null, tint = Theme.dim, modifier = Modifier.size(20.dp))
                        Text("排队中", fontSize = 12.sp, color = Theme.dim)
                    }
                }
            }
        }
        val label = result.note?.let { "${result.style.displayName} · $it" }
            ?: if (result.status == CharacterCreateModel.Status.DONE) "${result.style.displayName} · 已存入角色库" else result.style.displayName
        Text(
            label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

// 面板

@Composable
private fun ProfilePanel(model: CharacterCreateModel, edit: (Field) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldCell("名字", model.name, "可不填", Modifier.weight(1f)) { edit(Field.NAME) }
        FieldCell("设定", model.bio, "可不填", Modifier.weight(1f)) { edit(Field.BIO) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in CharacterGender.entries) Chip(item.displayName, model.gender == item) { model.gender = item }
        Box(Modifier.width(1.dp).height(20.dp).background(Theme.chip))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in CharacterAgeGroup.entries) Chip(item.displayName, model.ageGroup == item) { model.ageGroup = item }
        }
    }
}

@Composable
private fun FieldCell(title: String, value: String, placeholder: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Theme.chip).tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, fontSize = 10.sp, color = Theme.dim)
        Text(
            value.ifEmpty { placeholder }, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (value.isEmpty()) Theme.dim else Color.White,
        )
    }
}

@Composable
private fun ArtStylePanel(model: CharacterCreateModel) {
    val all = CharacterArtStyle.entries.toSet()
    Row(Modifier.fillMaxWidth()) {
        Chip("全部画风", model.styles == all) {
            model.styles = if (model.styles == all) setOf(CharacterArtStyle.JP_ANIME) else all
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (style in CharacterArtStyle.entries) {
            val selected = style in model.styles
            StyleCard(style.displayName, style.subtitle, selected, check = selected) { model.toggle(style) }
        }
    }
}

@Composable
private fun MakePanel(model: CharacterCreateModel, openSettings: () -> Unit, start: () -> Unit) {
    if (!AISettings.isConfigured(AISettings.active)) {
        NeedsKeyView(openSettings = openSettings)
    } else if (model.running) {
        val seconds = elapsedSeconds(model.started)
        RunningRow("正在画第 ${(model.currentIndex ?: 0) + 1} / ${model.results.size} 个 · $seconds 秒", "停止") { model.cancel() }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("同时画三视图", model.withSheet) { model.withSheet = !model.withSheet }
            Text(
                "${model.gender.displayName} · ${model.ageGroup.displayName} · ${model.styles.size} 种画风",
                fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            ProviderMenu(openSettings = openSettings)
        }
        AccentButton(
            if (model.styles.size > 1) "生成 ${model.styles.size} 个角色" else "生成角色",
            "sparkles", Modifier.fillMaxWidth(), onClick = start,
        )
        if (model.savedCount > 0) {
            Text(
                "已有 ${model.savedCount} 个存进角色库" + if (model.spent > 0) "，花费约 \$${String.format(Locale.US, "%.2f", model.spent)}" else "",
                fontSize = 11.sp, color = Theme.dim, modifier = Modifier.fillMaxWidth(),
            )
        }
        AIFailureView(model.aiError, retry = start)
    }
}
