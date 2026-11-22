package com.taoo.xiutu.tools.beauty

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.ml.FaceDetector
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 满意脸型：用户自己修满意的一张脸，存下来当参考。下次修图时让 AI 按这张参考把脸一键修成同样的感觉。date 是毫秒时间戳。 */
@Serializable
data class FavoriteFace(val id: String, val date: Long)

object FavoriteFaceStore {
    /** 新的在前。 */
    var faces by mutableStateOf<List<FavoriteFace>>(emptyList())
        private set
    /** 修图时用哪一张；null = 用最新的一张。 */
    var currentID by mutableStateOf<String?>(null)
        private set

    private var folder: File? = null
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, Bitmap>()
    private val json = Json { ignoreUnknownKeys = true }
    private const val CURRENT_KEY = "favoriteFace.current"

    fun init(context: Context) {
        if (folder != null) return
        val dir = File(context.applicationContext.filesDir, "FavoriteFaces").apply { mkdirs() }
        folder = dir
        val p = context.applicationContext.getSharedPreferences("xiutu", Context.MODE_PRIVATE)
        prefs = p
        val list = runCatching { json.decodeFromString<List<FavoriteFace>>(File(dir, "index.json").readText()) }.getOrDefault(emptyList())
        faces = list.filter { file(it.id).exists() }
        val raw = p.getString(CURRENT_KEY, null)
        if (raw != null && faces.any { it.id == raw }) currentID = raw
    }

    val current: FavoriteFace? get() = faces.firstOrNull { it.id == currentID } ?: faces.firstOrNull()

    private fun file(id: String) = File(folder, "$id.jpg")

    private fun save() {
        val dir = folder ?: return
        runCatching {
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(faces))
            tmp.renameTo(File(dir, "index.json"))
        }
        prefs?.edit()?.putString(CURRENT_KEY, currentID)?.apply()
    }

    /** 读出这张参考图（会解码文件，后台线程调用更好）。 */
    fun image(face: FavoriteFace): Bitmap? {
        synchronized(cache) { cache[face.id]?.let { return it } }
        val image = Img.decode(file(face.id)) ?: return null
        synchronized(cache) { cache[face.id] = image }
        return image
    }

    /** 从一张图里找到最大的脸，把头部这一块存成满意脸型，并设为当前。找不到脸返回 false。 */
    suspend fun add(context: Context, data: ByteArray): Boolean {
        init(context)
        val id = UUID.randomUUID().toString().uppercase()
        val ok = withContext(Dispatchers.Default) {
            try {
                val image = Img.decode(data, 3000) ?: return@withContext false
                val face = FaceDetector.detect(image).firstOrNull() ?: return@withContext false
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                val side = min(max(face.box.width() * w, face.box.height() * h) * 2.2f, min(w, h))
                val cx = face.box.centerX() * w
                val cy = face.box.centerY() * h - face.box.height() * h * 0.1f
                val x = (cx - side / 2).coerceIn(0f, w - side)
                val y = (cy - side / 2).coerceIn(0f, h - side)
                val r = Rect(x.toInt(), y.toInt(), min(image.width, (x + side).roundToInt()), min(image.height, (y + side).roundToInt()))
                if (r.width() <= 0 || r.height() <= 0) return@withContext false
                val piece = Bitmap.createBitmap(image, r.left, r.top, r.width(), r.height())
                val out = AIOneShot.prepared(piece, 1024)
                val f = file(id)
                val tmp = File(f.parentFile, "$id.tmp")
                tmp.writeBytes(Img.jpeg(out, 92))
                tmp.renameTo(f)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }
        if (!ok) return false
        faces = listOf(FavoriteFace(id, System.currentTimeMillis())) + faces
        currentID = id
        save()
        return true
    }

    fun setCurrent(face: FavoriteFace) {
        currentID = face.id
        save()
    }

    fun remove(face: FavoriteFace) {
        runCatching { file(face.id).delete() }
        synchronized(cache) { cache.remove(face.id) }
        faces = faces.filter { it.id != face.id }
        if (currentID == face.id) currentID = null
        save()
    }
}

private val warnRed = Color(1f, 0.45f, 0.4f)

/** 主页进来的"满意脸型"：看、换当前、删除，也可以直接从相册添加。 */
@Composable
fun FavoriteFaceScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = FavoriteFaceStore
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<FavoriteFace?>(null) }
    LaunchedEffect(Unit) { store.init(context) }
    BackHandler(onBack = onClose)

    fun picked(uri: Uri?) {
        if (uri == null) return
        busy = true
        note = null
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            val ok = data != null && store.add(context, data)
            busy = false
            if (!ok) note = "这张图里没有找到人脸"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it) }

    Box(Modifier.fillMaxSize().background(Theme.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(sym("chevron.left"), "返回", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("满意脸型", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                Box(
                    Modifier.size(44.dp).tap(!busy) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Icon(sym("plus"), "添加", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            note?.let { Text(it, fontSize = 13.sp, color = warnRed, modifier = Modifier.align(Alignment.CenterHorizontally)) }
            if (store.faces.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(sym("face.smiling"), null, tint = Theme.dim, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("还没有满意脸型", color = Theme.dim)
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(store.faces, key = { it.id }) { face ->
                        FaceCell(face, store.current?.id == face.id, onDelete = { deleting = face })
                    }
                }
            }
        }
    }
    deleting?.let { face ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text("删除这个满意脸型？", fontSize = 15.sp, color = Color.White) },
            confirmButton = { TextButton({ store.remove(face); deleting = null }) { Text("删除", color = Theme.red) } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消", color = Color.White) } },
            containerColor = Theme.panel,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaceCell(face: FavoriteFace, isCurrent: Boolean, onDelete: () -> Unit) {
    var image by remember(face.id) { mutableStateOf<Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(face.id) { image = withContext(Dispatchers.IO) { FavoriteFaceStore.image(face) } }
    val shape = RoundedCornerShape(12.dp)
    Box {
        Box(
            Modifier.aspectRatio(1f).clip(shape).background(Color(0.12f, 0.12f, 0.12f))
                .border(2.5.dp, if (isCurrent) Theme.accent else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onLongClick = { menu = true },
                    onClick = { FavoriteFaceStore.setCurrent(face) },
                ),
        ) {
            image?.let {
                val ib = remember(it) { it.asImageBitmap() }
                Image(ib, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            }
            if (isCurrent) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).background(Theme.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(sym("star.fill"), null, tint = Color.Black, modifier = Modifier.size(12.dp)) }
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = Theme.panel) {
            DropdownMenuItem({ Text("设为当前", color = Color.White) }, onClick = { menu = false; FavoriteFaceStore.setCurrent(face) })
            DropdownMenuItem({ Text("删除", color = Theme.red) }, onClick = { menu = false; onDelete() })
        }
    }
}
