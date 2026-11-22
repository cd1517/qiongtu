package com.taoo.xiutu.tools.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 字体列表里的一项。 */
data class FontChoice(
    /** 稳定标识，见 OverlayFonts.resolve。 */
    val id: String,
    val title: String,
    val kind: Kind,
    /** 自己导入的字体：对应 Fonts 文件夹里的文件名。 */
    val fileName: String? = null,
) {
    enum class Kind { BUILTIN, DOWNLOAD, CUSTOM }
}

enum class FontCategory(val title: String) {
    COMMON("常用"), CHINESE("中文"), LATIN("英文"), MINE("我的字体"),
}

/** 一款可下载的字体文件（Google Fonts / 霞鹜文楷，开源授权）。几个字重可以共用一个可变字体文件。 */
class FontDownload(val file: String, val url: String, val variable: Boolean)

/**
 * 按标识找字体。哪个线程都可以调；找不到就退回系统字体。
 *
 * 标识：
 * - `sys.xxx`：常用的几款（系统无衬线的不同字重、衬线、等宽）
 * - `fam:<系统字体族>:<字重>`：安卓系统自带的字体族
 * - `dl:<文件键>:<字重>`：下载的字体
 * - `file:<文件名>#<序号>`：自己导入的字体
 */
object OverlayFonts {
    class Resolved(val typeface: Typeface, val weight: Int)

    @Volatile var dir: File? = null
    private val cache = ConcurrentHashMap<String, Resolved>()

    private const val BASE = "https://raw.githubusercontent.com/google/fonts/main/"

    /** 可下载的字体文件。键 → 文件。 */
    val downloads: Map<String, FontDownload> = mapOf(
        "notoserifsc" to FontDownload("NotoSerifSC-VF.ttf", BASE + "ofl/notoserifsc/NotoSerifSC%5Bwght%5D.ttf", true),
        "notosanssc" to FontDownload("NotoSansSC-VF.ttf", BASE + "ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf", true),
        "wenkai" to FontDownload("LXGWWenKai-Regular.ttf", "https://github.com/lxgw/LxgwWenKai/releases/latest/download/LXGWWenKai-Regular.ttf", false),
        "wenkaimd" to FontDownload("LXGWWenKai-Medium.ttf", "https://github.com/lxgw/LxgwWenKai/releases/latest/download/LXGWWenKai-Medium.ttf", false),
        "zhimangxing" to FontDownload("ZhiMangXing-Regular.ttf", BASE + "ofl/zhimangxing/ZhiMangXing-Regular.ttf", false),
        "mashanzheng" to FontDownload("MaShanZheng-Regular.ttf", BASE + "ofl/mashanzheng/MaShanZheng-Regular.ttf", false),
        "liujianmaocao" to FontDownload("LiuJianMaoCao-Regular.ttf", BASE + "ofl/liujianmaocao/LiuJianMaoCao-Regular.ttf", false),
        "longcang" to FontDownload("LongCang-Regular.ttf", BASE + "ofl/longcang/LongCang-Regular.ttf", false),
        "kuaile" to FontDownload("ZCOOLKuaiLe-Regular.ttf", BASE + "ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf", false),
        "huangyou" to FontDownload("ZCOOLQingKeHuangYou-Regular.ttf", BASE + "ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf", false),
        "xiaowei" to FontDownload("ZCOOLXiaoWei-Regular.ttf", BASE + "ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf", false),
        "kleeone" to FontDownload("KleeOne-SemiBold.ttf", BASE + "ofl/kleeone/KleeOne-SemiBold.ttf", false),
        "zenmaru" to FontDownload("ZenMaruGothic-Medium.ttf", BASE + "ofl/zenmarugothic/ZenMaruGothic-Medium.ttf", false),
        "mplusrounded" to FontDownload("MPLUSRounded1c-Bold.ttf", BASE + "ofl/mplusrounded1c/MPLUSRounded1c-Bold.ttf", false),
        // 英文
        "montserrat" to FontDownload("Montserrat-VF.ttf", BASE + "ofl/montserrat/Montserrat%5Bwght%5D.ttf", true),
        "jost" to FontDownload("Jost-VF.ttf", BASE + "ofl/jost/Jost%5Bwght%5D.ttf", true),
        "oswald" to FontDownload("Oswald-VF.ttf", BASE + "ofl/oswald/Oswald%5Bwght%5D.ttf", true),
        "archivonarrow" to FontDownload("ArchivoNarrow-VF.ttf", BASE + "ofl/archivonarrow/ArchivoNarrow%5Bwght%5D.ttf", true),
        "barlowcond" to FontDownload("BarlowCondensed-Bold.ttf", BASE + "ofl/barlowcondensed/BarlowCondensed-Bold.ttf", false),
        "latoblack" to FontDownload("Lato-Black.ttf", BASE + "ofl/lato/Lato-Black.ttf", false),
        "playfair" to FontDownload("PlayfairDisplay-VF.ttf", BASE + "ofl/playfairdisplay/PlayfairDisplay%5Bwght%5D.ttf", true),
        "bodonimoda" to FontDownload("BodoniModa-VF.ttf", BASE + "ofl/bodonimoda/BodoniModa%5Bopsz,wght%5D.ttf", true),
        "librebaskerville" to FontDownload("LibreBaskerville-VF.ttf", BASE + "ofl/librebaskerville/LibreBaskerville%5Bwght%5D.ttf", true),
        "gelasio" to FontDownload("Gelasio-VF.ttf", BASE + "ofl/gelasio/Gelasio%5Bwght%5D.ttf", true),
        "ebgaramond" to FontDownload("EBGaramond-VF.ttf", BASE + "ofl/ebgaramond/EBGaramond%5Bwght%5D.ttf", true),
        "marcellus" to FontDownload("Marcellus-Regular.ttf", BASE + "ofl/marcellus/Marcellus-Regular.ttf", false),
        "robotoslab" to FontDownload("RobotoSlab-VF.ttf", BASE + "apache/robotoslab/RobotoSlab%5Bwght%5D.ttf", true),
        "cinzel" to FontDownload("Cinzel-VF.ttf", BASE + "ofl/cinzel/Cinzel%5Bwght%5D.ttf", true),
        "specialelite" to FontDownload("SpecialElite-Regular.ttf", BASE + "apache/specialelite/SpecialElite-Regular.ttf", false),
        "courierprime" to FontDownload("CourierPrime-Bold.ttf", BASE + "ofl/courierprime/CourierPrime-Bold.ttf", false),
        "jetbrainsmono" to FontDownload("JetBrainsMono-VF.ttf", BASE + "ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf", true),
        "permanentmarker" to FontDownload("PermanentMarker-Regular.ttf", BASE + "apache/permanentmarker/PermanentMarker-Regular.ttf", false),
        "patrickhand" to FontDownload("PatrickHand-Regular.ttf", BASE + "ofl/patrickhand/PatrickHand-Regular.ttf", false),
        "rocksalt" to FontDownload("RockSalt-Regular.ttf", BASE + "apache/rocksalt/RockSalt-Regular.ttf", false),
        "gochihand" to FontDownload("GochiHand-Regular.ttf", BASE + "ofl/gochihand/GochiHand-Regular.ttf", false),
        "caveat" to FontDownload("Caveat-VF.ttf", BASE + "ofl/caveat/Caveat%5Bwght%5D.ttf", true),
        "greatvibes" to FontDownload("GreatVibes-Regular.ttf", BASE + "ofl/greatvibes/GreatVibes-Regular.ttf", false),
        "pinyon" to FontDownload("PinyonScript-Regular.ttf", BASE + "ofl/pinyonscript/PinyonScript-Regular.ttf", false),
        "parisienne" to FontDownload("Parisienne-Regular.ttf", BASE + "ofl/parisienne/Parisienne-Regular.ttf", false),
        "uncial" to FontDownload("UncialAntiqua-Regular.ttf", BASE + "ofl/uncialantiqua/UncialAntiqua-Regular.ttf", false),
        "lobster" to FontDownload("Lobster-Regular.ttf", BASE + "ofl/lobster/Lobster-Regular.ttf", false),
        "cinzeldeco" to FontDownload("CinzelDecorative-Bold.ttf", BASE + "ofl/cinzeldecorative/CinzelDecorative-Bold.ttf", false),
    )

    /** 下载的字体放在哪。 */
    fun downloadFile(key: String): File? {
        val d = downloads[key] ?: return null
        return dir?.let { File(File(it, "dl"), d.file) }
    }

    fun customFolder(): File? = dir?.let { File(it, "custom") }

    /** 下载类标识对应的文件键。 */
    fun downloadKey(id: String): String? = if (id.startsWith("dl:")) id.removePrefix("dl:").substringBefore(':') else null

    fun resolve(id: String): Resolved {
        cache[id]?.let { return it }
        val r = load(id)
        if (r != null) {
            cache[id] = r
            return r
        }
        return cache.getOrPut(DEFAULT) { load(DEFAULT)!! }
    }

    /** 文件被删掉时清掉缓存。 */
    fun forget(prefix: String) {
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    private const val DEFAULT = OverlayItem.DEFAULT_FONT_ID

    private fun family(name: String, weight: Int) =
        Resolved(Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false), weight)

    private fun load(id: String): Resolved? = runCatching {
        when {
            id == "sys.semibold" -> family("sans-serif", 600)
            id == "sys.black" -> family("sans-serif", 900)
            id == "sys.light" -> family("sans-serif", 300)
            // 安卓没有系统圆体，用粗一点的无衬线代替
            id == "sys.rounded" -> family("sans-serif", 700)
            id == "sys.serif" -> family("serif", 600)
            id == "sys.mono" -> family("monospace", 600)
            id.startsWith("fam:") -> {
                val parts = id.split(':')
                family(parts[1], parts.getOrNull(2)?.toIntOrNull() ?: 400)
            }
            id.startsWith("dl:") -> {
                val parts = id.split(':')
                val key = parts[1]
                val weight = parts.getOrNull(2)?.toIntOrNull() ?: 400
                val file = downloadFile(key)?.takeIf { it.exists() } ?: return null
                val variable = downloads[key]?.variable == true
                val b = Font.Builder(file)
                if (variable) b.setFontVariationSettings("'wght' $weight")
                val font = b.setWeight(weight).build()
                Resolved(typeface(font), weight)
            }
            id.startsWith("file:") -> {
                val rest = id.removePrefix("file:")
                val name = rest.substringBeforeLast('#')
                val index = rest.substringAfterLast('#', "0").toIntOrNull() ?: 0
                val file = customFolder()?.let { File(it, name) }?.takeIf { it.exists() } ?: return null
                val font = Font.Builder(file).setTtcIndex(index).build()
                Resolved(typeface(font), font.style.weight)
            }
            else -> null
        }
    }.getOrNull()

    /** 单个字体做成 Typeface，缺的字交给系统字体。 */
    fun typeface(font: Font): Typeface =
        Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).setSystemFallback("sans-serif").build()

    // MARK: 列表

    val system: List<FontChoice> = listOf(
        FontChoice("sys.semibold", "常规", FontChoice.Kind.BUILTIN),
        FontChoice("sys.black", "粗黑", FontChoice.Kind.BUILTIN),
        FontChoice("sys.light", "细体", FontChoice.Kind.BUILTIN),
        FontChoice("sys.rounded", "圆体", FontChoice.Kind.BUILTIN),
        FontChoice("sys.serif", "衬线", FontChoice.Kind.BUILTIN),
        FontChoice("sys.mono", "等宽", FontChoice.Kind.BUILTIN),
    )

    /** 系统自带的中文字体（思源黑体 / 思源宋体，各厂商系统里都有）。 */
    val chineseBuiltin: List<FontChoice> = listOf(
        FontChoice("fam:sans-serif:100", "黑体 极细", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif:400", "黑体 常规", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif:600", "黑体 中粗", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif:500", "黑体", FontChoice.Kind.BUILTIN),
        FontChoice("fam:serif:700", "明朝 粗", FontChoice.Kind.BUILTIN),
        FontChoice("fam:serif:400", "明朝 细", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif:800", "黑体 粗", FontChoice.Kind.BUILTIN),
    )

    /** 可下载的中文字体。第一次用要联网下载一次。 */
    val chineseDownload: List<FontChoice> = listOf(
        FontChoice("dl:notoserifsc:700", "宋体 粗", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:notoserifsc:400", "宋体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:notoserifsc:900", "宋体 特粗", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:wenkai:400", "楷体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:wenkaimd:500", "楷体 粗", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:huangyou:400", "黄油体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:zenmaru:500", "丸黑", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:zhimangxing:400", "行楷", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:mashanzheng:400", "毛笔楷", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:liujianmaocao:400", "草书", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:longcang:400", "手札体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:kuaile:400", "快乐体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:xiaowei:400", "小薇体", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:notosanssc:500", "兰亭黑", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:notosanssc:900", "兰亭特黑", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:kleeone:600", "Klee 手写", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:mplusrounded:700", "M+ 丸黑", FontChoice.Kind.DOWNLOAD),
    )

    /** 英文：系统自带的几个字体族，加上可下载的替代字体。 */
    val latin: List<FontChoice> = listOf(
        FontChoice("fam:sans-serif:900", "Roboto Black", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif-condensed:700", "Condensed", FontChoice.Kind.BUILTIN),
        FontChoice("fam:serif:700", "Serif", FontChoice.Kind.BUILTIN),
        FontChoice("fam:monospace:400", "Mono", FontChoice.Kind.BUILTIN),
        FontChoice("fam:serif-monospace:400", "Typewriter", FontChoice.Kind.BUILTIN),
        FontChoice("fam:casual:400", "Casual", FontChoice.Kind.BUILTIN),
        FontChoice("fam:cursive:700", "Cursive", FontChoice.Kind.BUILTIN),
        FontChoice("fam:sans-serif-smallcaps:400", "Small Caps", FontChoice.Kind.BUILTIN),
        FontChoice("dl:montserrat:900", "Montserrat", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:jost:700", "Jost", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:oswald:700", "Oswald", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:archivonarrow:700", "Archivo Narrow", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:barlowcond:700", "Barlow Cond", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:latoblack:900", "Lato Black", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:playfair:700", "Playfair", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:bodonimoda:700", "Bodoni Moda", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:librebaskerville:700", "Baskerville", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:gelasio:700", "Gelasio", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:ebgaramond:700", "Garamond", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:marcellus:400", "Marcellus", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:robotoslab:800", "Roboto Slab", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:cinzel:700", "Cinzel", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:specialelite:400", "Special Elite", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:courierprime:700", "Courier Prime", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:jetbrainsmono:700", "JetBrains Mono", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:permanentmarker:400", "Marker", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:patrickhand:400", "Patrick Hand", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:rocksalt:400", "Rock Salt", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:gochihand:400", "Gochi Hand", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:caveat:700", "Caveat", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:greatvibes:400", "Great Vibes", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:pinyon:400", "Pinyon", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:parisienne:400", "Parisienne", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:uncial:400", "Uncial", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:lobster:400", "Lobster", FontChoice.Kind.DOWNLOAD),
        FontChoice("dl:cinzeldeco:700", "Engraved", FontChoice.Kind.DOWNLOAD),
    )
}

/** 字体的管理：哪些能用、下载字体、导入和删除自己的字体。状态都在主线程改。 */
object FontStore {
    /** 自己导入的字体。 */
    var custom by mutableStateOf<List<FontChoice>>(emptyList())
        private set
    /** 已经下载好的文件键。 */
    var ready by mutableStateOf<Set<String>>(emptySet())
        private set
    /** 正在下载的：文件键 → 进度 0...1。 */
    val downloading = mutableStateMapOf<String, Float>()
    /** 出错时显示的一句（导入失败、下载失败）。 */
    var note by mutableStateOf<String?>(null)

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val extensions = setOf("ttf", "otf", "ttc", "otc")

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        val dir = File(c.filesDir, "Fonts")
        File(dir, "dl").mkdirs()
        File(dir, "custom").mkdirs()
        OverlayFonts.dir = dir
        ready = OverlayFonts.downloads.keys.filter { OverlayFonts.downloadFile(it)?.exists() == true }.toSet()
        reloadCustom()
    }

    fun fonts(category: FontCategory): List<FontChoice> = when (category) {
        FontCategory.COMMON -> OverlayFonts.system
        FontCategory.CHINESE -> OverlayFonts.chineseBuiltin + OverlayFonts.chineseDownload
        FontCategory.LATIN -> OverlayFonts.latin
        FontCategory.MINE -> custom
    }

    /** 这个字体现在能不能直接用。 */
    fun isReady(choice: FontChoice): Boolean =
        choice.kind != FontChoice.Kind.DOWNLOAD || OverlayFonts.downloadKey(choice.id) in ready

    /** 下载进度；没在下载时为 null。 */
    fun progress(choice: FontChoice): Float? = OverlayFonts.downloadKey(choice.id)?.let { downloading[it] }

    // MARK: 下载

    fun download(choice: FontChoice, done: (Boolean) -> Unit) {
        val key = OverlayFonts.downloadKey(choice.id) ?: return done(false)
        if (downloading.containsKey(key)) return
        val spec = OverlayFonts.downloads[key] ?: return done(false)
        val target = OverlayFonts.downloadFile(key) ?: return done(false)
        if (target.exists()) {
            ready = ready + key
            done(true)
            return
        }
        downloading[key] = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(target.parentFile, target.name + ".part")
                    client.newCall(Request.Builder().url(spec.url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("空")
                        val total = body.contentLength()
                        var got = 0L
                        var lastShown = -1
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    if (total > 0) {
                                        val pct = (got * 100 / total).toInt()
                                        if (pct != lastShown) {
                                            lastShown = pct
                                            scope.launch { if (downloading.containsKey(key)) downloading[key] = pct / 100f }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 确认是能用的字体再放进去
                    Font.Builder(tmp).build()
                    if (!tmp.renameTo(target)) error("保存失败")
                    true
                }.getOrElse {
                    File(target.parentFile, target.name + ".part").delete()
                    false
                }
            }
            downloading.remove(key)
            if (ok) {
                OverlayFonts.forget("dl:$key")
                ready = ready + key
            } else {
                note = "“${choice.title}”下载失败：没有联网，或者网络不稳定。"
            }
            done(ok)
        }
    }

    // MARK: 自己的字体

    private fun reloadCustom() {
        val folder = OverlayFonts.customFolder() ?: return
        val files = folder.listFiles()?.filter { it.extension.lowercase() in extensions }?.sortedBy { it.name } ?: emptyList()
        custom = files.flatMap { register(it) }
    }

    /** 读出这个文件里有哪些字体。 */
    private fun register(file: File): List<FontChoice> {
        val names = FontNames.read(file)
        val count = maxOf(1, names.size)
        val out = ArrayList<FontChoice>()
        for (i in 0 until count) {
            val ok = runCatching { Font.Builder(file).setTtcIndex(i).build() }.isSuccess
            if (!ok) continue
            val title = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            out += FontChoice("file:${file.name}#$i", title, FontChoice.Kind.CUSTOM, file.name)
        }
        return out
    }

    /** 从"文件"里选来的字体：复制进 App 自己的文件夹。返回新加进来的第一款，方便直接用上。 */
    fun importFonts(context: Context, uris: List<Uri>): FontChoice? {
        if (uris.isEmpty()) return null
        val folder = OverlayFonts.customFolder() ?: return null
        var first: FontChoice? = null
        var failed = 0
        for (uri in uris) {
            val name = displayName(context, uri) ?: "font-${System.currentTimeMillis()}.ttf"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in extensions) { failed++; continue }
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) { failed++; continue }
            var target = File(folder, name)
            if (target.exists()) {
                // 同名文件：内容一样就直接用现有的，不一样就换个名字存
                if (target.readBytes().contentEquals(bytes)) {
                    if (first == null) first = custom.firstOrNull { it.fileName == target.name }
                    continue
                }
                target = File(folder, "${name.substringBeforeLast('.')}-${java.util.UUID.randomUUID().toString().take(6)}.$ext")
            }
            runCatching { target.writeBytes(bytes) }.onFailure { failed++; return@onFailure }
            if (!target.exists()) continue
            val added = register(target)
            if (added.isEmpty()) {
                target.delete()
                failed++
            } else if (first == null) {
                first = added.first()
            }
        }
        reloadCustom()
        note = when {
            failed == 0 -> null
            failed == uris.size -> "没能读出字体：只支持 TTF、OTF、TTC 这几种字体文件。"
            else -> "有 $failed 个文件不是能用的字体，已跳过。"
        }
        return first
    }

    /** 删掉一款自己导入的字体（同一个文件里的其他字重也会一起删掉）。 */
    fun delete(choice: FontChoice) {
        if (choice.kind != FontChoice.Kind.CUSTOM) return
        val name = choice.fileName ?: return
        OverlayFonts.customFolder()?.let { File(it, name).delete() }
        OverlayFonts.forget("file:$name#")
        reloadCustom()
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

/** 从字体文件的 name 表里读出显示名（每款字体一个，TTC 里按顺序）。 */
private object FontNames {
    fun read(file: File): List<String> = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val tag = f.readInt()
            if (tag == 0x74746366) { // 'ttcf'
                f.readInt() // 版本
                val n = f.readInt()
                val offsets = (0 until n).map { f.readInt().toLong() and 0xFFFFFFFFL }
                offsets.map { fullName(f, it) ?: "" }
            } else {
                listOf(fullName(f, 0) ?: "")
            }
        }
    }.getOrDefault(emptyList())

    private fun fullName(f: RandomAccessFile, offset: Long): String? {
        f.seek(offset + 4)
        val numTables = f.readUnsignedShort()
        f.skipBytes(6)
        var nameOffset = -1L
        repeat(numTables) {
            val tag = f.readInt()
            f.readInt()
            val off = f.readInt().toLong() and 0xFFFFFFFFL
            f.readInt()
            if (tag == 0x6E616D65) nameOffset = off // 'name'
        }
        if (nameOffset < 0) return null
        f.seek(nameOffset)
        f.readUnsignedShort()
        val count = f.readUnsignedShort()
        val strings = nameOffset + f.readUnsignedShort()
        class Rec(val platform: Int, val lang: Int, val nameId: Int, val len: Int, val off: Int)
        val recs = (0 until count).map {
            val p = f.readUnsignedShort(); f.readUnsignedShort()
            val l = f.readUnsignedShort(); val id = f.readUnsignedShort()
            Rec(p, l, id, f.readUnsignedShort(), f.readUnsignedShort())
        }
        fun score(r: Rec): Int {
            val idScore = when (r.nameId) { 4 -> 30; 1 -> 10; else -> return -1 }
            val lang = when {
                r.platform == 3 && (r.lang == 0x0804 || r.lang == 0x0404 || r.lang == 0x0C04) -> 6
                r.platform == 3 && r.lang == 0x0409 -> 4
                r.platform == 3 -> 2
                r.platform == 1 && r.lang == 0 -> 1
                else -> 0
            }
            return idScore + lang
        }
        val best = recs.filter { score(it) >= 0 }.maxByOrNull { score(it) } ?: return null
        f.seek(strings + best.off)
        val raw = ByteArray(best.len)
        f.readFully(raw)
        return if (best.platform == 3 || best.platform == 0) String(raw, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
    }
}
