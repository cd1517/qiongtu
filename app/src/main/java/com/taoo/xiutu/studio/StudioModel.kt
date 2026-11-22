package com.taoo.xiutu.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.godcover.GodCoverEngine
import com.taoo.xiutu.godcover.SceneLook
import com.taoo.xiutu.godcover.SubjectMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 摄影棚：一张照片，按选好的场景、摄影师、器材拍出一组不同景别和姿势的套图。主线程使用。 */
class StudioModel(private val context: Context) {
    data class Option(val id: String, val title: String, val swatch: String? = null)
    data class SceneCategory(val title: String, val scenes: List<Option>)
    data class BrandCategory(val title: String, val items: List<Option>)
    class Catalog(
        val categories: List<SceneCategory> = emptyList(),
        val photographers: List<Option> = emptyList(),
        val equipment: List<Option> = emptyList(),
        val timeOfDay: List<Option> = emptyList(),
        val retouchParts: List<Option> = emptyList(),
        val hairColors: List<Option> = emptyList(),
        val brands: List<BrandCategory> = emptyList(),
        val persona: List<Option> = emptyList(),
        val age: List<Option> = emptyList(),
        val beauty: List<Option> = emptyList(),
        val gender: List<Option> = emptyList(),
        val counts: List<Int> = listOf(3, 6, 10, 15, 20),
    )

    enum class Status { PENDING, RUNNING, DONE, FAILED }

    data class Shot(
        val id: Int,
        val prompt: String,
        val framing: String,
        val photographer: String,
        val status: Status = Status.PENDING,
        val image: Bitmap? = null,
        val error: String? = null,
        /** 场景模式下这一张的拍法（原构图、更广全景……）。 */
        val sceneTitle: String? = null,
    ) {
        val framingTitle: String
            get() = sceneTitle ?: when (framing) {
                "closeup" -> "近景"
                "medium" -> "中景"
                "full_body" -> "全身"
                "wide" -> "远景"
                else -> ""
            }
    }

    val catalog: Catalog = loadCatalog(context)
    val engine = GodCoverEngine(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var isLoading by mutableStateOf(true)
    var errorText by mutableStateOf<String?>(null)
    var original by mutableStateOf<Bitmap?>(null)
    /** 摄影师、器材格子上的小图（key 见 StudioLooks），"original" 是原图。打开照片后在后台算一次。 */
    var thumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())

    /** 拍人物还是拍场景（照片里的场景就是拍摄对象，不加人）。打开照片时自动判断，可以手动换。 */
    var subject by mutableStateOf(SubjectMode.PERSON)
    // 拍场景：氛围（没选的就是保持原样）、拍法、张数
    val scenePicks = mutableStateMapOf<SceneLook.Group, String>()
    var sceneVariations by mutableStateOf(StudioScene.defaultVariations)
    var sceneCount by mutableStateOf(3)
    /** 这一组照片的宽高比，排缩略图用。 */
    var shotAspect by mutableStateOf(2f / 3f)

    // 场景
    var categoryIndex by mutableStateOf(0)
    /** "keep" = 不换地方，保持原照片的背景。 */
    var sceneID by mutableStateOf("keep")
    var sceneHint by mutableStateOf("")
    // 人物
    var gender by mutableStateOf("auto")
    var persona by mutableStateOf("random")
    var age by mutableStateOf("original")
    var beauty by mutableStateOf("none")
    var retouchParts by mutableStateOf<Set<String>>(emptySet())
    var hairColors by mutableStateOf<Set<String>>(emptySet())
    // 摄影
    /** "none" = 不指定摄影师。 */
    var photographer by mutableStateOf("none")
    var equipment by mutableStateOf("auto")
    var timeOfDay by mutableStateOf("auto")
    var brands by mutableStateOf<Set<String>>(emptySet())
    var brandCategoryIndex by mutableStateOf(0)
    // 套图
    var count by mutableStateOf(3)
    val shots = mutableStateListOf<Shot>()
    var running by mutableStateOf(false)
    var started by mutableStateOf(0L)
    var aiError by mutableStateOf<String?>(null)
    var spent by mutableStateOf(0.0)

    private var job: Job? = null
    private var lastExplore = false

    val scenes: List<Option> get() = catalog.categories.getOrNull(categoryIndex)?.scenes ?: emptyList()
    val doneCount: Int get() = shots.count { it.status == Status.DONE }
    val currentIndex: Int? get() = shots.indexOfFirst { it.status == Status.RUNNING }.takeIf { it >= 0 }

    fun selectCategory(index: Int) {
        categoryIndex = index
    }

    fun load(uri: Uri) {
        scope.launch {
            val photo = withContext(Dispatchers.IO) {
                Img.decode(context, uri, 2048)?.let { AIOneShot.prepared(it, 2048) }
            }
            if (photo == null) {
                errorText = "无法读取这张图片"
                isLoading = false
                return@launch
            }
            subject = SubjectMode.detect(photo)
            original = photo
            isLoading = false
            thumbs = withContext(Dispatchers.Default) { StudioLooks.render(photo) }
        }
    }

    fun toggleSceneVariation(id: String) {
        if (id !in sceneVariations) {
            sceneVariations = sceneVariations + id
        } else if (sceneVariations.size > 1) {
            // 至少留一种拍法
            sceneVariations = sceneVariations - id
        }
    }

    private fun update(index: Int, change: (Shot) -> Shot) {
        if (index in shots.indices) shots[index] = change(shots[index])
    }

    /** 开始拍摄。explore = true 是"试拍"：固定 6 张，每张换一位摄影师（拍人物时还换一种器材）。 */
    fun start(explore: Boolean) {
        val scene = subject == SubjectMode.SCENE
        val original = original ?: return
        if (running) return
        if (!scene && (catalog.categories.getOrNull(categoryIndex) == null || sceneID.isEmpty())) return
        if (AISettings.endpoint() == null) {
            aiError = "还没有设置 ${AISettings.active.shortTitle} 的 API Key。"
            return
        }
        lastExplore = explore
        aiError = null
        running = true
        started = SystemClock.elapsedRealtime()
        // 拍场景：画幅跟原图走，指令在本机拼
        val size = if (scene) AIOneShot.size(original) else "1024x1536"
        val scenePlan = if (!scene) emptyList() else StudioScene.plan(
            photographer = photographer, equipment = equipment, photographers = catalog.photographers,
            equipmentList = catalog.equipment, picks = scenePicks.toMap(), chosen = sceneVariations, count = sceneCount,
            explore = explore, orientation = SceneLook.orientation(original),
        )
        val params = if (scene) JSONObject() else JSONObject().apply {
            put("mainCategory", catalog.categories[categoryIndex].title)
            put("subScene", sceneID)
            put("sceneHint", sceneHint)
            put("persona", persona)
            put("gender", gender)
            put("age", age)
            put("beauty", beauty)
            put("retouchParts", JSONArray(retouchParts.toList()))
            put("hairColors", JSONArray(hairColors.toList()))
            put("photographer", photographer)
            put("equipment", equipment)
            put("timeOfDay", timeOfDay)
            put("brands", JSONArray(brands.toList()))
            put("count", count)
            put("explore", explore)
        }
        val wh = size.split("x").mapNotNull { it.toFloatOrNull() }
        val aspect = if (wh.size == 2 && wh[1] > 0) wh[0] / wh[1] else 2f / 3f
        if (scene) {
            if (scenePlan.isEmpty()) {
                running = false
                aiError = "这组设置没有排出拍摄计划。"
                return
            }
            shots.clear()
            shots.addAll(scenePlan.mapIndexed { index, item ->
                Shot(index, item.prompt, "", item.photographer, sceneTitle = item.variation)
            })
            shotAspect = aspect
        }
        job = scope.launch {
            try {
                if (scene) {
                    // 场景的指令是本机拼的：每条都过一遍网页里那套成年人净化再发送
                    val cleaned = shots.map { engine.sanitize(it.prompt) }
                    if (cleaned.any { it.isEmpty() }) throw AIError("这组设置没有排出拍摄计划。")
                    cleaned.forEachIndexed { i, p -> update(i) { it.copy(prompt = p) } }
                } else {
                    // 人物：gcStudio.plan 出来的每条指令在网页里已经净化过
                    val plan = engine.studioPlan(params)
                    if (plan.isEmpty()) throw AIError("这组设置没有排出拍摄计划。")
                    shots.clear()
                    shots.addAll(plan.mapIndexed { index, item ->
                        Shot(index, item.optString("prompt", ""), item.optString("framing", ""), item.optString("photographer", ""))
                    })
                    shotAspect = aspect
                }
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: Exception) {
                running = false
                aiError = (e as? AIError)?.text ?: e.message ?: "这组设置没有排出拍摄计划。"
                return@launch
            }
            var failuresInARow = 0
            try {
                for (index in shots.indices) {
                    if (!isActive) break
                    update(index) { it.copy(status = Status.RUNNING) }
                    started = SystemClock.elapsedRealtime()
                    try {
                        val (image, usage) = AIOneShot.run(shots[index].prompt, listOf(original), size)
                        if (!isActive) break
                        update(index) { it.copy(image = image, status = Status.DONE) }
                        spent += usage?.cost ?: 0.0
                        failuresInARow = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val text = (e as? AIError)?.text ?: e.message ?: "失败"
                        update(index) { it.copy(status = Status.FAILED, error = text) }
                        aiError = "第 ${index + 1} 张失败：$text"
                        failuresInARow += 1
                        // 连着两张都失败，多半是 Key、余额或审核的问题，继续拍只会白等
                        if (failuresInARow >= 2) break
                    }
                }
            } finally {
                for (i in shots.indices) {
                    val s = shots[i]
                    if (s.status == Status.RUNNING || s.status == Status.PENDING) {
                        shots[i] = s.copy(status = Status.FAILED, error = s.error ?: "没有拍")
                    }
                }
                running = false
            }
        }
    }

    fun retry() = start(lastExplore)

    fun cancel() {
        job?.cancel()
        running = false
    }

    suspend fun saveAll(): Int {
        var saved = 0
        for (shot in shots.toList()) {
            val image = shot.image ?: continue
            if (AIOneShot.saveToPhotos(context, image, "摄影棚")) saved++
        }
        return saved
    }

    fun dispose() {
        scope.cancel()
        engine.destroy()
    }

    companion object {
        private fun options(arr: JSONArray?): List<Option> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Option(it.optString("id"), it.optString("title"), if (it.has("swatch")) it.optString("swatch") else null)
                }
            }

        fun loadCatalog(context: Context): Catalog = runCatching {
            val o = JSONObject(context.assets.open("godcover/studio_catalog.json.gcweb").use { it.readBytes().decodeToString() })
            val cats = o.optJSONArray("categories")
            val categories = if (cats == null) emptyList() else (0 until cats.length()).mapNotNull { i ->
                cats.optJSONObject(i)?.let { SceneCategory(it.optString("title"), options(it.optJSONArray("scenes"))) }
            }
            val br = o.optJSONArray("brands")
            val brands = if (br == null) emptyList() else (0 until br.length()).mapNotNull { i ->
                br.optJSONObject(i)?.let { BrandCategory(it.optString("title"), options(it.optJSONArray("items"))) }
            }
            val counts = o.optJSONArray("counts")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } }
            Catalog(
                categories = categories,
                // 每一项都可以不选
                photographers = listOf(Option("none", "不指定")) + options(o.optJSONArray("photographers")),
                equipment = options(o.optJSONArray("equipment")),
                timeOfDay = options(o.optJSONArray("timeOfDay")),
                retouchParts = options(o.optJSONArray("retouchParts")),
                hairColors = options(o.optJSONArray("hairColors")),
                brands = brands,
                persona = options(o.optJSONArray("persona")),
                age = options(o.optJSONArray("age")),
                beauty = options(o.optJSONArray("beauty")),
                gender = options(o.optJSONArray("gender")),
                counts = counts?.takeIf { it.isNotEmpty() } ?: listOf(3, 6, 10, 15, 20),
            )
        }.getOrElse { Catalog(photographers = listOf(Option("none", "不指定"))) }
    }
}
