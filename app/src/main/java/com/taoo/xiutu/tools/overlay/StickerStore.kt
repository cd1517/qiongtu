package com.taoo.xiutu.tools.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taoo.xiutu.ai.AIClient
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ai.AIProvider
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.AIUsage
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.ml.Cutout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** "我的贴纸"里的一张。id = 文件名。 */
class StickerEntry(val id: String, val thumb: Bitmap)

/** 自己的贴纸：存成 PNG 放在 App 私有目录的 Stickers 里，卸载 App 之前一直在。 */
object StickerStore {
    /** 新加的排在前面。 */
    var items by mutableStateOf<List<StickerEntry>>(emptyList())
        private set

    private var folder: File? = null
    private val loaded = HashMap<String, Bitmap>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        if (folder != null) return
        val dir = File(context.applicationContext.filesDir, "Stickers").apply { mkdirs() }
        folder = dir
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                (dir.listFiles() ?: emptyArray())
                    .filter { it.extension.lowercase() == "png" }
                    .sortedByDescending { it.lastModified() }
                    .mapNotNull { f -> thumbnail(f)?.let { StickerEntry(f.name, it) } }
            }
            // 加载期间新加的放在前面
            items = items + list.filter { e -> items.none { it.id == e.id } }
        }
    }

    private fun thumbnail(f: File): Bitmap? = Img.decode(f, 200)

    /** 存一张新贴纸（在主线程调，写文件放到后台）。 */
    suspend fun add(image: Bitmap): StickerEntry? {
        val dir = folder ?: return null
        val name = "sticker-${System.currentTimeMillis() / 1000}-${UUID.randomUUID().toString().take(6)}.png"
        val thumb = withContext(Dispatchers.IO) {
            val ok = runCatching { File(dir, name).writeBytes(Img.png(image)) }.isSuccess
            if (ok) Img.downscale(image, 200) else null
        } ?: return null
        val entry = StickerEntry(name, thumb)
        synchronized(loaded) { loaded[name] = image }
        items = listOf(entry) + items
        return entry
    }

    fun delete(entry: StickerEntry) {
        folder?.let { File(it, entry.id).delete() }
        synchronized(loaded) { loaded.remove(entry.id) }
        items = items.filter { it.id != entry.id }
    }

    /** 原大小的图（贴到画面上用）。可以在后台线程调。 */
    fun image(entry: StickerEntry): Bitmap? {
        synchronized(loaded) { loaded[entry.id]?.let { return it } }
        val dir = folder ?: return null
        val raw = Img.decode(File(dir, entry.id)) ?: return null
        val image = StickerCut.normalized(raw, 2048) ?: return null
        synchronized(loaded) {
            if (loaded.size > 12) loaded.clear()
            loaded[entry.id] = image
        }
        return image
    }
}

/** 从相册选来的照片做成贴纸。 */
object StickerImport {
    class Made(val image: Bitmap, val cutFailed: Boolean)

    /** 摆正方向、限制大小；autoCut = true 时在本机把主体抠出来。在后台线程调用。 */
    fun make(source: Bitmap?, autoCut: Boolean): Made? {
        source ?: return null
        // 本来就带透明背景的图（PNG 贴图）：原样用，只裁掉四周空白
        if (StickerCut.hasTransparency(source)) StickerCut.trimmed(source)?.let { return Made(it, false) }
        val upright = AIOneShot.prepared(Img.downscale(source, 1536), 1536)
        if (!autoCut) return StickerCut.normalized(upright)?.let { Made(it, false) }
        val mask = Cutout.mask(upright)
        if (mask != null) Cutout.cut(upright, mask, 0.0015f, trim = true)?.let { return Made(it, false) }
        return StickerCut.normalized(upright)?.let { Made(it, true) }
    }
}

/** 用 AI 生成贴纸。 */
object StickerAI {
    val styles: List<Pair<String, String>> = listOf(
        "卡通" to "cute flat cartoon illustration, bold clean outlines, bright colors",
        "手绘" to "hand-drawn marker doodle, slightly wobbly lines, playful",
        "3D" to "glossy 3D clay render, soft studio lighting, rounded shapes",
        "水彩" to "soft watercolor painting, gentle pastel colors",
        "像素" to "retro pixel art, crisp pixels",
        "复古" to "retro 1970s badge style, muted warm colors",
        "写实" to "realistic photo of a real object, sharp detail",
    )

    private const val GREEN = " Put it on a solid, perfectly flat pure green (#00FF00) background with nothing else in the picture."

    /** 用设置里当前选中的服务商生成一张贴纸，并在本机把背景去掉。 */
    suspend fun generate(what: String, style: String): Pair<Bitmap, AIUsage?> {
        val endpoint = AISettings.endpoint() ?: throw AIError("还没有设置 ${AISettings.activeShortTitle} 的 API Key。")
        val subject = "A single die-cut sticker of: $what. Style: $style. Exactly one subject, centered, fully inside the frame with empty margin around it. " +
            "A thick clean white border runs around the subject like a vinyl sticker. No drop shadow, no extra text or watermark, no scenery."
        var raw: Bitmap? = null
        var usage: AIUsage? = null
        val isOpenAI = endpoint.provider == AIProvider.OPENAI
        // 兼容 OpenAI 接口的自定义服务（AI 模块里叫 CUSTOM）
        val isCustom = endpoint.provider.name == "CUSTOM"

        if (isOpenAI || isCustom) {
            val transparent = isOpenAI
            AIClient.takeLastUsage()
            try {
                val data = try {
                    AIClient.generate(endpoint, subject + if (transparent) " Transparent background." else GREEN, "1024x1024", transparent)
                } catch (e: AIError) {
                    // 这个型号不认"透明背景"：改成画在纯色背景上，回头在本机抠
                    if (transparent && e.status == 400 && rejectsTransparency(e)) {
                        AIClient.generate(endpoint, subject + GREEN, "1024x1024", false)
                    } else throw e
                }
                usage = AIClient.takeLastUsage()
                usage?.let { AISettings.addUsage(it) }
                raw = withContext(Dispatchers.Default) { Img.decode(data) }
            } catch (e: AIError) {
                // 这个兼容服务没有文生图接口，改走下面的"空白画布"
                if (isCustom && (e.status == 404 || e.status == 405)) raw = null else throw e
            }
        }
        if (raw == null) {
            // 没有文生图接口的服务商：给一张空白画布，让它在上面画
            val blank = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }
            val result = AIOneShot.run(
                subject + GREEN + " The input image is only an empty canvas; replace it completely.",
                listOf(blank), "1024x1024",
            )
            raw = result.first
            usage = result.second
        }
        val cg = raw ?: throw AIError("服务商返回的不是有效图片。")
        val finished = withContext(Dispatchers.Default) {
            StickerCut.finish(cg)
                // 背景不是纯色：退回本机的人像识别；再不行就整张用
                ?: Cutout.mask(cg)?.let { Cutout.cut(cg, it, 0.0015f, trim = true) }
                ?: StickerCut.normalized(cg, 1024)
        } ?: throw AIError("生成的图片无法处理。")
        return finished to usage
    }

    private fun rejectsTransparency(e: AIError): Boolean {
        val raw = e.raw.lowercase()
        return "background" in raw || "transparent" in raw || "output_format" in raw
    }
}
