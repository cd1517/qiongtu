package com.taoo.xiutu.studio

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.taoo.xiutu.ai.NeedsKeyView
import com.taoo.xiutu.ai.ResultViewer
import com.taoo.xiutu.godcover.AIActionButtons
import com.taoo.xiutu.godcover.EngineHost
import com.taoo.xiutu.godcover.ProviderMenu
import com.taoo.xiutu.godcover.RunningRow
import com.taoo.xiutu.godcover.SceneLook
import com.taoo.xiutu.godcover.SubjectMode
import com.taoo.xiutu.godcover.SubjectModeBar
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.BusyOverlay
import com.taoo.xiutu.ui.Chip
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.Toast
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class StudioTab(val title: String) {
    SCENE("场景"), PERSON("人物"), CAMERA("摄影"), MOOD("氛围"), SHOOT("拍摄");

    companion object {
        /** 拍人物：选场景、人物、摄影；拍场景：照片里的场景就是拍摄对象，只剩摄影、氛围。 */
        fun list(subject: SubjectMode) =
            if (subject == SubjectMode.SCENE) listOf(CAMERA, MOOD, SHOOT) else listOf(SCENE, PERSON, CAMERA, SHOOT)
    }
}

private enum class PersonGroup(val raw: String, val title: String) {
    GENDER("gender", "性别"), PERSONA("persona", "人设"), AGE("age", "年龄"),
    BEAUTY("beauty", "美颜"), RETOUCH("retouch", "精修部位"), HAIR("hair", "发色"),
}

private enum class CameraGroup(val raw: String, val title: String) {
    PHOTOGRAPHER("photographer", "摄影师"), EQUIPMENT("equipment", "器材"), TIME("time", "时段"), BRANDS("brands", "品牌单品");

    companion object {
        /** 拍场景时没有时段（在"氛围"里）和品牌单品。 */
        fun list(subject: SubjectMode) = if (subject == SubjectMode.SCENE) listOf(PHOTOGRAPHER, EQUIPMENT) else entries
    }
}

/** 摄影棚。上面是照片或套图，底部是步骤条、二三级选项和上一步 / 下一步。 */
@Composable
fun StudioScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(uri) { StudioModel(context).also { it.load(uri) } }
    DisposableEffect(model) { onDispose { model.dispose() } }
    var tab by remember { mutableStateOf(StudioTab.SCENE) }
    // 走到过的最远一步
    var reached by remember { mutableStateOf(StudioTab.SCENE) }
    var personGroup by remember { mutableStateOf(PersonGroup.GENDER) }
    var cameraGroup by remember { mutableStateOf(CameraGroup.PHOTOGRAPHER) }
    var moodGroup by remember { mutableStateOf(SceneLook.Group.TIME) }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastID by remember { mutableIntStateOf(0) }

    fun go(t: StudioTab) {
        val list = StudioTab.list(model.subject)
        tab = t
        if (list.indexOf(t) > list.indexOf(reached)) reached = t
    }

    fun step(delta: Int) {
        val list = StudioTab.list(model.subject)
        val i = list.indexOf(tab)
        if (i < 0 || i + delta !in list.indices) return
        go(list[i + delta])
    }

    /** 换了主体以后，步骤换了一套，从第一步重新走。 */
    fun fitMenus() {
        tab = StudioTab.list(model.subject)[0]
        reached = tab
        if (cameraGroup !in CameraGroup.list(model.subject)) cameraGroup = CameraGroup.PHOTOGRAPHER
    }

    LaunchedEffect(model.isLoading) { if (!model.isLoading) fitMenus() }
    BackHandler { if (viewing != null) viewing = null else onClose() }

    Box(Modifier.fillMaxSize().background(Theme.bg).systemBarsPadding()) {
        EngineHost(model.engine.webView)
        Column(Modifier.fillMaxSize()) {
            StudioTopBar(model.doneCount > 0, onBack = onClose) {
                scope.launch {
                    val n = model.saveAll()
                    toastID += 1
                    val id = toastID
                    toast = if (n > 0) "已保存 $n 张到相册" else "保存失败，请检查相册权限"
                    delay(2000)
                    if (id == toastID) toast = null
                }
            }
            Content(model, Modifier.weight(1f).fillMaxWidth()) { viewing = it }
            // 还没判断出拍人物还是拍场景时，先不把菜单亮出来，免得闪一下又换掉
            Column(Modifier.alpha(if (model.isLoading) 0f else 1f)) {
                val steps = StudioTab.list(model.subject)
                StepBar(steps, tab, reached, { it.title }, disabled = model.running) { go(it) }
                Column(
                    Modifier.fillMaxWidth().background(Theme.panel).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubjectModeBar(model.subject, prefix = "拍", disabled = model.running) { mode ->
                        model.subject = mode
                        fitMenus()
                    }
                    when (tab) {
                        StudioTab.SCENE -> ScenePanel(model)
                        StudioTab.PERSON -> PersonPanel(model, personGroup) { personGroup = it }
                        StudioTab.CAMERA -> CameraPanel(model, cameraGroup) { cameraGroup = it }
                        StudioTab.MOOD -> MoodPanel(model, moodGroup) { moodGroup = it }
                        StudioTab.SHOOT -> ShootPanel(model) { showSettings = true }
                    }
                }
                StepFooter(
                    isFirst = tab == steps.first(), isLast = tab == steps.last(), disabled = model.running,
                    back = { step(-1) }, next = { step(1) },
                )
            }
        }
        if (model.isLoading) BusyOverlay()
        model.errorText?.let { Text(it, color = Theme.dim, modifier = Modifier.align(Alignment.Center)) }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp)) }
        viewing?.let { image -> ResultViewer(image, onClose = { viewing = null }, source = "摄影棚") }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

@Composable
private fun StudioTopBar(canSave: Boolean, onBack: () -> Unit, onSaveAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).tap(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(sym("chevron.left"), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("摄影棚", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(
            "全部保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black,
            modifier = Modifier.alpha(if (canSave) 1f else 0f).clip(CircleShape).background(Color.White)
                .press(canSave, onSaveAll).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// 上半部分：原图或套图

@Composable
private fun Content(model: StudioModel, modifier: Modifier, view: (Bitmap) -> Unit) {
    Box(modifier) {
        if (model.shots.isEmpty()) {
            model.original?.let { image ->
                val ib = remember(image) { image.asImageBitmap() }
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Image(ib, null, contentScale = ContentScale.Fit, modifier = Modifier.clip(RoundedCornerShape(6.dp)))
                }
            }
        } else {
            // 横幅的照片一行放一张，其余一行两张
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (model.shotAspect > 1.2f) 1 else 2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(model.shots, key = { it.id }) { shot -> ShotCard(shot, model.shotAspect, view) }
            }
        }
    }
}

@Composable
private fun ShotCard(shot: StudioModel.Shot, aspect: Float, view: (Bitmap) -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Theme.panel)
            .tap { shot.image?.let(view) },
    ) {
        val image = shot.image
        if (image != null) {
            val ib = remember(image) { image.asImageBitmap() }
            Image(ib, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                when (shot.status) {
                    StudioModel.Status.RUNNING -> {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        Text("正在拍…", fontSize = 12.sp, color = Theme.dim)
                    }
                    StudioModel.Status.FAILED -> {
                        Icon(sym("exclamationmark.triangle"), null, tint = Color(1f, 0.45f, 0.4f), modifier = Modifier.size(20.dp))
                        Text(
                            shot.error ?: "失败", fontSize = 11.sp, color = Theme.dim, textAlign = TextAlign.Center, maxLines = 4,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    else -> {
                        Icon(sym("clock"), null, tint = Theme.dim, modifier = Modifier.size(20.dp))
                        Text("排队中", fontSize = 12.sp, color = Theme.dim)
                    }
                }
            }
        }
        Text(
            listOf((shot.id + 1).toString(), shot.framingTitle, shot.photographer).filter { it.isNotEmpty() }.joinToString(" · "),
            fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

// 二、三级选项

@Composable
private fun ScenePanel(model: StudioModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.catalog.categories.forEachIndexed { index, c ->
                Chip(StudioIcons.plain(c.title), model.categoryIndex == index, symbol = StudioIcons.categorySymbol(c.title)) {
                    model.selectCategory(index)
                }
            }
        }
        // 原背景在最前面，用自己这张照片做缩略图；后面是这一类的场景
        key(model.categoryIndex) {
            TileRow {
                PickTile("原背景", model.sceneID == "keep", image = model.thumbs["original"], symbol = "photo") { model.sceneID = "keep" }
                Box(Modifier.padding(top = 8.dp).width(1.dp).height((StudioLooks.TILE_HEIGHT - 16).dp).background(Theme.chip))
                val category = model.catalog.categories.getOrNull(model.categoryIndex)?.title ?: ""
                for (scene in model.scenes) {
                    val (symbol, tint) = StudioIcons.sceneSymbol(scene.title, category)
                    PickTile(StudioIcons.sceneName(scene.title), model.sceneID == scene.id, symbol = symbol, tint = tint) {
                        model.sceneID = scene.id
                    }
                }
            }
        }
    }
}

private fun toggle(set: Set<String>, id: String) = if (id in set) set - id else set + id

@Composable
private fun PersonPanel(model: StudioModel, group: PersonGroup, select: (PersonGroup) -> Unit) {
    fun groupTitle(g: PersonGroup) = when (g) {
        PersonGroup.RETOUCH -> if (model.retouchParts.isEmpty()) g.title else "${g.title} ${model.retouchParts.size}"
        PersonGroup.HAIR -> if (model.hairColors.isEmpty()) g.title else "${g.title} ${model.hairColors.size}"
        else -> g.title
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SymbolChipRow(PersonGroup.entries, ::groupTitle, { StudioIcons.personGroupSymbol(it.raw) }, { it == group }, select)
        val c = model.catalog
        when (group) {
            PersonGroup.GENDER -> SymbolChipRow(c.gender, { it.title }, { StudioIcons.genderSymbols[it.id] }, { model.gender == it.id }) { model.gender = it.id }
            PersonGroup.PERSONA -> SymbolChipRow(c.persona, { it.title }, { StudioIcons.personaSymbols[it.id] }, { model.persona == it.id }) { model.persona = it.id }
            PersonGroup.AGE -> SymbolChipRow(c.age, { it.title }, { StudioIcons.ageSymbols[it.id] }, { model.age == it.id }) { model.age = it.id }
            PersonGroup.BEAUTY -> SymbolChipRow(c.beauty, { it.title }, { StudioIcons.beautySymbols[it.id] }, { model.beauty == it.id }) { model.beauty = it.id }
            PersonGroup.RETOUCH -> SymbolChipRow(c.retouchParts, { it.title }, { StudioIcons.retouchSymbols[it.id] }, { it.id in model.retouchParts }) {
                model.retouchParts = toggle(model.retouchParts, it.id)
            }
            PersonGroup.HAIR -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (hair in c.hairColors) {
                    SwatchChip(hair.title, StudioIcons.swatch(hair.swatch), hair.id in model.hairColors) {
                        model.hairColors = toggle(model.hairColors, hair.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPanel(model: StudioModel, group: CameraGroup, select: (CameraGroup) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SymbolChipRow(
            CameraGroup.list(model.subject),
            { if (it == CameraGroup.BRANDS && model.brands.isNotEmpty()) "${it.title} ${model.brands.size}" else it.title },
            { StudioIcons.cameraGroupSymbol(it.raw) }, { it == group }, select,
        )
        when (group) {
            CameraGroup.PHOTOGRAPHER -> key(group) {
                TileRow {
                    // "不指定"是原图，"随机大师"是骰子
                    for (p in model.catalog.photographers) {
                        val image = if (p.id == "random") null else model.thumbs[if (p.id == "none") "original" else "p.${p.id}"]
                        PickTile(
                            StudioIcons.photographerName(p), model.photographer == p.id, image = image,
                            symbol = if (p.id == "random") "dice.fill" else "camera.aperture", tint = StudioIcons.PURPLE,
                        ) { model.photographer = p.id }
                    }
                }
            }
            CameraGroup.EQUIPMENT -> key(group) {
                TileRow {
                    // 胶片和相机是套了色调的小图；"自动""随机"是图标
                    for (e in model.catalog.equipment) {
                        val symbol = when (e.id) { "random" -> "dice.fill"; "auto" -> "wand.and.stars"; else -> "film.fill" }
                        PickTile(
                            StudioIcons.equipmentName(e), model.equipment == e.id, image = model.thumbs["e.${e.id}"],
                            symbol = symbol, tint = StudioIcons.PURPLE,
                        ) { model.equipment = e.id }
                    }
                }
            }
            CameraGroup.TIME -> key(group) {
                TileRow {
                    for (t in model.catalog.timeOfDay) {
                        val (symbol, tint) = StudioIcons.lightSymbol(t.id)
                        PickTile(StudioIcons.plain(t.title), model.timeOfDay == t.id, symbol = symbol, tint = tint) { model.timeOfDay = t.id }
                    }
                }
            }
            CameraGroup.BRANDS -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.catalog.brands.forEachIndexed { index, b ->
                        Chip(StudioIcons.plain(b.title), model.brandCategoryIndex == index, symbol = StudioIcons.brandCategorySymbol(b.title)) {
                            model.brandCategoryIndex = index
                        }
                    }
                }
                model.catalog.brands.getOrNull(model.brandCategoryIndex)?.let { cat ->
                    key(model.brandCategoryIndex) {
                        SymbolChipRow(cat.items, { it.title }, { StudioIcons.brandItemSymbol(it, cat.title) }, { it.id in model.brands }) {
                            model.brands = toggle(model.brands, it.id)
                        }
                    }
                }
            }
        }
    }
}

/** 拍场景：时间、天气、季节、光线，每组第一项都是"保持原样"（用原图做缩略图）。 */
@Composable
private fun MoodPanel(model: StudioModel, group: SceneLook.Group, select: (SceneLook.Group) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SymbolChipRow(SceneLook.Group.entries, { g ->
            val picked = g.option(model.scenePicks[g])
            if (picked.id == SceneLook.KEEP) g.title else "${g.title} · ${picked.title}"
        }, { StudioIcons.moodGroupSymbol(it) }, { it == group }, select)
        key(group) {
            TileRow {
                for (option in group.options) {
                    val (symbol, tint) = StudioIcons.lightSymbol(option.id)
                    PickTile(
                        option.title, group.option(model.scenePicks[group]).id == option.id,
                        image = if (option.id == SceneLook.KEEP) model.thumbs["original"] else null, symbol = symbol, tint = tint,
                    ) { model.scenePicks[group] = option.id }
                }
            }
        }
    }
}

@Composable
private fun ShootPanel(model: StudioModel, openSettings: () -> Unit) {
    if (AISettings.endpoint() == null) {
        NeedsKeyView(openSettings = openSettings)
        return
    }
    if (model.running) {
        RunningRow(model.started, "停止", 22, model::cancel) { s ->
            "正在拍第 ${(model.currentIndex ?: 0) + 1} / ${model.shots.size} 张 · $s 秒"
        }
        return
    }
    val scene = model.subject == SubjectMode.SCENE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("张数", fontSize = 13.sp, color = Theme.dim)
            val counts = if (scene) StudioScene.counts else model.catalog.counts
            val current = if (scene) model.sceneCount else model.count
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in counts) {
                    Chip("$n", current == n) { if (scene) model.sceneCount = n else model.count = n }
                }
            }
            ProviderMenu(openSettings)
        }
        if (scene) {
            SymbolChipRow(StudioScene.variations, { it.title }, { StudioIcons.variationSymbols[it.id] }, { it.id in model.sceneVariations }) {
                model.toggleSceneVariation(it.id)
            }
        }
        AIActionButtons(
            primary = "开始拍 ${if (scene) model.sceneCount else model.count} 张", primarySymbol = "camera.fill",
            secondary = "试拍 6 张", secondaryWidth = 104,
            onPrimary = { model.start(explore = false) }, onSecondary = { model.start(explore = true) },
        )
        if (!scene && model.spent > 0) {
            Text(
                "已花费约 $" + String.format(Locale.US, "%.2f", model.spent),
                fontSize = 11.sp, color = Theme.accent, modifier = Modifier.fillMaxWidth(),
            )
        }
        AIFailureView(model.aiError) { model.retry() }
    }
}
