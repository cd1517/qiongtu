package com.taoo.xiutu.raw

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.BottomTabBar
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.core.Saver
import com.taoo.xiutu.editor.SaveOptions
import com.taoo.xiutu.editor.ZoomableCanvas
import com.taoo.xiutu.history.HistoryStore
import com.taoo.xiutu.picker.PhotoLibrary
import com.taoo.xiutu.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 正在冲洗的文件。 */
private class RawSession(val name: String, val file: File)

/** 选 RAW 文件：相册里的 RAW，或者从"文件"导入。冲洗好按原分辨率写成文件交给 onEdit。 */
@Composable
fun RawScreen(onEdit: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RawLibrary.Item>>(emptyList()) }
    var access by remember { mutableStateOf<Boolean?>(if (PhotoLibrary.granted(context)) true else null) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<RawSession?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = PhotoLibrary.granted(context)
    }
    LaunchedEffect(Unit) {
        if (access != true) {
            val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(PhotoLibrary.permission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else arrayOf(PhotoLibrary.permission)
            permission.launch(perms)
        }
    }
    LaunchedEffect(access) {
        if (access == true) items = withContext(Dispatchers.IO) { runCatching { RawLibrary.items(context) }.getOrDefault(emptyList()) }
    }

    fun open(uri: Uri, name: String) {
        note = null
        loading = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { RawLibrary.copy(context, uri, name) }
            loading = false
            if (f == null) note = "读不了这个文件。" else session = RawSession(name, f)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = RawLibrary.displayName(context, uri) ?: uri.lastPathSegment ?: "RAW"
        if (!RawLibrary.isRaw(name)) {
            note = "这不是 RAW 文件。"
            return@rememberLauncherForActivityResult
        }
        open(uri, name)
    }

    BackHandler(session == null) { onClose() }

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
                Text("RAW 冲洗", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.size(44.dp).align(Alignment.CenterStart).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(sym("xmark"), "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Row(
                    Modifier.align(Alignment.CenterEnd).clip(CircleShape).background(Theme.accent)
                        .press { importer.launch(arrayOf("*/*")) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(sym("folder"), null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text("从文件导入", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                }
            }
            note?.let {
                Text(it, fontSize = 12.sp, color = Color(1f, 0.45f, 0.4f), modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
            }
            if (items.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (access == null) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Icon(sym("camera.aperture"), null, tint = Theme.dim, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (access == false) "没有相册权限" else "相册里没有 RAW 照片", fontSize = 14.sp, color = Theme.dim, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(items.size, key = { items[it].uri.toString() }) { i ->
                        val item = items[i]
                        Box(Modifier.aspectRatio(1f).background(Theme.tile).tap { open(item.uri, item.name) }) {
                            var bmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(item.uri) { bmp = withContext(Dispatchers.IO) { RawLibrary.thumb(context, item.uri) } }
                            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Text(
                                "RAW", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black, lineHeight = 11.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        if (loading) BusyOverlay()
    }

    session?.let { s ->
        RawView(s.name, s.file, onClose = { session = null }) { f ->
            session = null
            onEdit(f)
        }
    }
}

/** RAW 冲洗：像修图软件的"基本"面板那样拖滑块，调好后送进图片编辑。 */
@Composable
private fun RawView(name: String, file: File, onClose: () -> Unit, onEdit: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(file) { RawModel(name).also { it.load(file) } }
    DisposableEffect(model) { onDispose { model.close() } }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    BackHandler { if (!model.busy) onClose() }

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val enabled = !model.busy && model.ready
                Box(Modifier.size(44.dp).tap(!model.busy, onClose), contentAlignment = Alignment.Center) {
                    Icon(sym("xmark"), "关闭", tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.sizeText, fontSize = 10.sp, color = Theme.dim, maxLines = 1)
                }
                val canReset = model.isModified && enabled
                Box(
                    Modifier.size(36.dp, 44.dp).alpha(if (canReset) 1f else 0.35f).tap(canReset) { model.resetAll() },
                    contentAlignment = Alignment.Center,
                ) { Icon(sym("arrow.uturn.backward"), "全部复位", tint = Color.White, modifier = Modifier.size(18.dp)) }
                Box(
                    Modifier.size(40.dp, 44.dp).alpha(if (enabled) 1f else 0.35f).tap(enabled) {
                        scope.launch {
                            val img = model.develop()
                            if (img == null) {
                                toast = "冲洗失败"
                                return@launch
                            }
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val data = Img.jpeg(img, 98)
                                    img.recycle()
                                    Saver.saveToGallery(context, data, SaveOptions.Format.JPEG)
                                    if (HistoryStore.enabled) HistoryStore.add(context, data, "jpg", "RAW")
                                }.isSuccess
                            }
                            toast = if (ok) "已保存到相册" else "保存失败，请检查相册权限"
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(sym("square.and.arrow.down"), "存相册", tint = Color.White, modifier = Modifier.size(19.dp)) }
                Box(
                    Modifier.press(enabled) {
                        scope.launch {
                            val f = model.developToFile(File(context.cacheDir, "raw-output"))
                            if (f != null) onEdit(f) else toast = "冲洗失败"
                        }
                    }.clip(CircleShape).background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("进入编辑", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
            }

            // 画布
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ZoomableCanvas(
                    image = model.shown,
                    onTap = if (model.pickingWhiteBalance) { p -> model.pickWhiteBalance(p) } else null,
                )
                model.histogram?.let { Histogram(it, Modifier.align(Alignment.TopStart).padding(10.dp)) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                model.comparing = true
                                tryAwaitRelease()
                                model.comparing = false
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(sym("square.split.2x1"), "对比", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }

            Panel(model)
            BottomTabBar(RawGroup.entries, { it.title }, model.group) { model.select(it) }
        }

        if (model.busy) {
            Box(Modifier.fillMaxSize().tap { }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Text("正在按原始分辨率冲洗…", fontSize = 13.sp, color = Color.White)
                }
            }
        }
        model.errorText?.let { error ->
            Box(Modifier.fillMaxSize().background(Theme.bg).tap { }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(error, fontSize = 14.sp, color = Theme.dim, textAlign = TextAlign.Center)
                    Text("关闭", fontSize = 16.sp, color = Theme.accent, modifier = Modifier.tap(onClick = onClose).padding(8.dp))
                }
            }
        }
        if (!model.ready && model.errorText == null) BusyOverlay()
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp)) }
    }
}

/** 底部面板：滑块 + 当前一级菜单的各项。 */
@Composable
private fun Panel(model: RawModel) {
    Column(
        Modifier.fillMaxWidth().background(Theme.panel).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (model.group == RawGroup.OPTICS) {
            Row {
                val supported = model.lensCorrectionSupported
                Box(Modifier.alpha(if (supported) 1f else 0.35f)) {
                    Chip("镜头校正", model.lensCorrection && supported) { if (supported) model.toggleLensCorrection(!model.lensCorrection) }
                }
            }
        } else {
            ThirdRow(model)
            val key = model.key
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(key.format(model.value(key)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.width(62.dp), maxLines = 1)
                XSlider(model.value(key), { model.set(key, it) }, key.range, Modifier.weight(1f))
                Box(Modifier.size(36.dp).tap { model.reset(key) }, contentAlignment = Alignment.Center) {
                    Icon(sym("arrow.counterclockwise"), "还原", tint = if (model.isChanged(key)) Color.White else Theme.dim, modifier = Modifier.size(18.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in model.keys(model.group)) {
                    IconTile(item.symbol, item.title, selected = item == key, modified = model.isChanged(item)) {
                        model.key = item
                        model.pickingWhiteBalance = false
                    }
                }
            }
        }
    }
}

/** 三级菜单：白平衡预设、混色器的八个颜色、颜色分级的四个区。 */
@Composable
private fun ThirdRow(model: RawModel) {
    when (model.group) {
        RawGroup.COLOR -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("吸管", model.pickingWhiteBalance) { model.pickingWhiteBalance = !model.pickingWhiteBalance }
            Chip("原照设置", !model.isChanged(RawKey.temperature) && !model.isChanged(RawKey.tint)) { model.applyWhiteBalance(null) }
            for (preset in WhiteBalancePreset.all) {
                val on = model.isChanged(RawKey.temperature) && model.value(RawKey.temperature) == preset.temperature &&
                    model.value(RawKey.tint) == preset.tint
                Chip(preset.title, on) { model.applyWhiteBalance(preset) }
            }
        }
        RawGroup.MIXER -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (color in MixColor.entries) {
                val on = model.mixColor == color
                Row(
                    Modifier.clip(CircleShape).background(if (on) Color.White else Theme.chip).tap { model.select(color) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color.swatch))
                    Text(
                        if (model.isChanged(color)) "${color.title} •" else color.title, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Color.Black else Color.White, maxLines = 1,
                    )
                }
            }
        }
        RawGroup.GRADING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (zone in GradeZone.entries) {
                Chip(if (model.isChanged(zone)) "${zone.title} •" else zone.title, model.gradeZone == zone) { model.select(zone) }
            }
        }
        else -> {}
    }
}

/** 直方图：红绿蓝三条叠在一起（相加混合）。 */
@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
    Canvas(modifier.size(128.dp, 56.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))) {
        val colors = listOf(Color(1f, 0.25f, 0.25f, 0.85f), Color(0.25f, 1f, 0.25f, 0.85f), Color(0.3f, 0.45f, 1f, 0.85f))
        val w = size.width
        val h = size.height
        for (c in 0..2) {
            val path = Path()
            path.moveTo(0f, h)
            for (i in 0 until 256) path.lineTo(i / 255f * w, h - bins[c * 256 + i] * h)
            path.lineTo(w, h)
            path.close()
            drawPath(path, colors[c], blendMode = BlendMode.Plus)
        }
    }
}
