package com.taoo.xiutu.godcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taoo.xiutu.ai.AIClient
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ai.AIPayload
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.core.Img
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** "封神"封面生成的全部状态。主线程使用。 */
class GodCoverModel(private val context: Context) {
    data class Option(val id: String, val title: String)
    data class Style(val id: String, val name: String, val desc: String)
    data class Category(val icon: String, val title: String, val styles: List<Style>)
    class Catalog(
        val categories: List<Category> = emptyList(),
        val beauty: List<Option> = emptyList(),
        val age: List<Option> = emptyList(),
        val persona: List<Option> = emptyList(),
        val gender: List<Option> = emptyList(),
        val filter: List<Option> = emptyList(),
    )

    enum class OptionGroup(val title: String) { BEAUTY("美颜"), AGE("年龄"), PERSONA("人设"), GENDER("性别") }

    val catalog: Catalog = loadCatalog(context)
    val engine = GodCoverEngine(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var isLoading by mutableStateOf(true)
    var errorText by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Bitmap?>(null)

    // 风格
    var categoryIndex by mutableStateOf(0)
    var styleID by mutableStateOf("vogue")

    // AI 改造
    var optionGroup by mutableStateOf(OptionGroup.BEAUTY)
    var beauty by mutableStateOf("medium")
    var age by mutableStateOf("original")
    var persona by mutableStateOf("random")
    var gender by mutableStateOf("auto")
    var running by mutableStateOf(false)
    /** 开始生成的时刻（elapsedRealtime）。 */
    var started by mutableStateOf(0L)
    var aiError by mutableStateOf<String?>(null)
    var usageText by mutableStateOf<String?>(null)
    var hasAI by mutableStateOf(false)
    var showingAI by mutableStateOf(false)

    /** 封面的主角是人还是场景本身（AI 不加人）。打开照片时自动判断，可以手动换。 */
    var subject by mutableStateOf(SubjectMode.PERSON)
    var sceneGroup by mutableStateOf(GodCoverScene.Group.LOOK)
    val sceneValues = mutableStateMapOf<GodCoverScene.Group, String>()

    // 文字
    var mainTitle by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var extra1 by mutableStateOf("")
    var extra2 by mutableStateOf("")
    var showTemplate by mutableStateOf(true)
    var forceText by mutableStateOf(false)

    // 调整
    var scale by mutableStateOf(100f)
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)
    var filter by mutableStateOf("none")

    private var original: Bitmap? = null
    private var aiImage: Bitmap? = null
    private var renderJob: Job? = null
    private var renderDirty = false
    private var aiJob: Job? = null
    private var lastNoText = false

    val currentStyle: Style? get() = catalog.categories.flatMap { it.styles }.firstOrNull { it.id == styleID }

    fun value(group: OptionGroup): String = when (group) {
        OptionGroup.BEAUTY -> beauty
        OptionGroup.AGE -> age
        OptionGroup.PERSONA -> persona
        OptionGroup.GENDER -> gender
    }

    fun options(group: OptionGroup): List<Option> = when (group) {
        OptionGroup.BEAUTY -> catalog.beauty
        OptionGroup.AGE -> catalog.age
        OptionGroup.PERSONA -> catalog.persona
        OptionGroup.GENDER -> catalog.gender
    }

    fun setValue(id: String, group: OptionGroup) {
        when (group) {
            OptionGroup.BEAUTY -> beauty = id
            OptionGroup.AGE -> age = id
            OptionGroup.PERSONA -> persona = id
            OptionGroup.GENDER -> gender = id
        }
    }

    fun sceneValue(group: GodCoverScene.Group): String = sceneValues[group] ?: group.fallback

    // 加载

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
            original = photo
            val detected = async { SubjectMode.detect(photo) }
            try {
                val jpeg = withContext(Dispatchers.Default) { Img.jpeg(photo, 92) }
                engine.setPhoto(false, jpeg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errorText = "封面引擎没有启动成功"
                isLoading = false
                return@launch
            }
            subject = detected.await()
            randomizeText(render = false)
            isLoading = false
            refresh()
        }
    }

    // 预览

    private fun params(): JSONObject = JSONObject().apply {
        put("style", styleID)
        put("mainTitle", mainTitle)
        put("subtitle", subtitle)
        put("issueDate", extra1)
        put("price", extra2)
        put("scale", scale / 100.0)
        put("offsetX", Math.round(offsetX))
        put("offsetY", Math.round(offsetY))
        put("filter", filter)
        put("showTemplate", showTemplate)
        put("forceCustomText", forceText)
        put("photoMode", if (showingAI && hasAI) "ai" else "original")
    }

    /** 重画预览。正在画时只记一笔，画完再按最新的参数画一次（拖滑块时不堆积）。 */
    fun refresh() {
        if (renderJob?.isActive == true) {
            renderDirty = true
            return
        }
        renderJob = scope.launch {
            do {
                renderDirty = false
                val image = runCatching { engine.render(params()) }.getOrNull()
                if (!isActive) return@launch
                if (image != null) preview = image
            } while (renderDirty)
        }
    }

    fun selectStyle(id: String) {
        styleID = id
        scope.launch { randomizeText(render = true) }
    }

    /** 换一组这个风格的标题文案（和网站里选风格时的行为一致）。 */
    suspend fun randomizeText(render: Boolean) {
        engine.randomText(styleID)?.let { (t, s) ->
            mainTitle = t
            subtitle = s
        }
        if (render) refresh()
    }

    fun randomize() {
        scope.launch { randomizeText(render = true) }
    }

    fun showAI(on: Boolean) {
        showingAI = on && hasAI
        refresh()
    }

    // AI 生成

    fun generate(noText: Boolean) {
        val original = original ?: return
        if (running) return
        val endpoint = AISettings.endpoint()
        if (endpoint == null) {
            aiError = "还没有设置 ${AISettings.active.shortTitle} 的 API Key。"
            return
        }
        lastNoText = noText
        aiError = null
        usageText = null
        running = true
        started = SystemClock.elapsedRealtime()
        val task = JSONObject().apply {
            put("style", styleID)
            put("styleName", currentStyle?.name ?: styleID)
            put("beauty", beauty)
            put("age", age)
            put("persona", persona)
            put("gender", gender)
            put("photoSchool", "random")
            put("forceCustomText", forceText)
            put("customMainTitle", mainTitle)
            put("customSubtitle", subtitle)
            put("customIssueDate", extra1)
            put("customPrice", extra2)
        }
        // 场景模式：指令在本机拼；无字版的画幅跟原图走，带字的封面仍然是竖幅
        val scene = subject == SubjectMode.SCENE
        val scenePrompt = if (!scene) "" else GodCoverScene.prompt(
            styleName = currentStyle?.name ?: styleID, styleDesc = currentStyle?.desc ?: "",
            category = catalog.categories.firstOrNull { c -> c.styles.any { it.id == styleID } }?.title ?: "",
            values = sceneValues.toMap(), noText = noText,
            texts = if (forceText) listOf(mainTitle, subtitle, extra1, extra2) else emptyList(),
            orientation = SceneLook.orientation(original),
        )
        val size = if (scene && noText) AIOneShot.size(original) else "1024x1536"
        aiJob = scope.launch {
            try {
                // 两条路出来的指令都过同一套清理：人物在 gc.prompt 里，场景单独调一次
                val prompt = if (scene) engine.sanitize(scenePrompt) else engine.prompt(task, noText)
                if (prompt.isEmpty()) throw AIError("这个风格的指令没有生成出来。")
                val usePNG = endpoint.provider.supportsMask
                // 发给 AI 的图：长边 1536 足够，输出本身就是 1024×1536
                val bytes = withContext(Dispatchers.Default) {
                    val upload = AIOneShot.prepared(original, 1536)
                    (if (usePNG) Img.png(upload) else Img.jpeg(upload, 92)).takeIf { it.isNotEmpty() }
                } ?: throw AIError("图片编码失败。")
                val payload = AIPayload(prompt, bytes, if (usePNG) "image/png" else "image/jpeg", maskPNG = null, markedJPEG = null, size = size)
                AIClient.takeLastUsage()
                val data = AIClient.edit(endpoint, payload)
                val usage = AIClient.takeLastUsage()
                if (!isActive) return@launch
                val result = withContext(Dispatchers.Default) {
                    val raw = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw AIError("服务商返回的不是有效图片。")
                    AIOneShot.prepared(raw, 4096)
                }
                val jpeg = withContext(Dispatchers.Default) { Img.jpeg(result, 95) }
                if (jpeg.isEmpty()) throw AIError("结果图片无法处理。")
                engine.setPhoto(true, jpeg)
                aiImage = result
                hasAI = true
                showingAI = true
                // AI 出的图通常已经带了刊名和排版，再叠一层模板会重影，所以默认关掉模板；
                // 场景的无字版是一张干净的照片，模板照旧叠在上面，就是成品封面
                if (!(scene && noText)) showTemplate = false
                usageText = usage?.summary
                running = false
                refresh()
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: Exception) {
                running = false
                aiError = (e as? AIError)?.text ?: e.message ?: "生成失败"
            }
        }
    }

    fun retry() = generate(lastNoText)

    fun cancelAI() {
        aiJob?.cancel()
        aiJob = null
        running = false
    }

    // 保存

    suspend fun save(): Boolean {
        val image: Bitmap? = if (showTemplate) {
            val data = runCatching { engine.exportCover(params(), 3) }.getOrNull() ?: return false
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size) }
        } else {
            if (showingAI && hasAI) aiImage else original
        }
        image ?: return false
        return AIOneShot.saveToPhotos(context, image, "封神")
    }

    fun dispose() {
        scope.cancel()
        engine.destroy()
    }

    companion object {
        private fun options(arr: JSONArray?): List<Option> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Option(it.optString("id"), it.optString("title")) }
            }

        fun loadCatalog(context: Context): Catalog = runCatching {
            val o = JSONObject(context.assets.open("godcover/catalog.json.gcweb").use { it.readBytes().decodeToString() })
            val cats = o.optJSONArray("categories")
            val categories = if (cats == null) emptyList() else (0 until cats.length()).mapNotNull { i ->
                val c = cats.optJSONObject(i) ?: return@mapNotNull null
                val styles = c.optJSONArray("styles")
                Category(
                    c.optString("icon"), c.optString("title"),
                    if (styles == null) emptyList() else (0 until styles.length()).mapNotNull { j ->
                        styles.optJSONObject(j)?.let { Style(it.optString("id"), it.optString("name"), it.optString("desc")) }
                    },
                )
            }
            Catalog(
                categories, options(o.optJSONArray("beauty")), options(o.optJSONArray("age")),
                options(o.optJSONArray("persona")), options(o.optJSONArray("gender")), options(o.optJSONArray("filter")),
            )
        }.getOrElse { Catalog() }
    }
}
