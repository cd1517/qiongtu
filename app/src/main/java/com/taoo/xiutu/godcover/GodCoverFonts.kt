package com.taoo.xiutu.godcover

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 封神排版用的字体：全部打包在 App 里，不从外网加载。
 * 原网站引用的谷歌字体文件拼成一个大文件（fonts.bin）加一份位置索引；
 * 样式表里的地址换成 https://gcfont.invalid/f/<编号>，网页要哪一块就从大文件里切哪一块，用到的字才读进内存。
 * 字体都是开源授权（SIL OFL / Apache）。
 */
class GodCoverFonts private constructor(
    private val afd: AssetFileDescriptor,
    private val channel: FileChannel,
    private val index: List<Entry>,
    /** @font-face 列表，地址已指向 gcfont.invalid。 */
    val css: String,
) {
    private class Entry(val offset: Long, val length: Int, val mime: String)

    /** 把网页里引用谷歌字体的那一行换成本机的样式表。 */
    fun patch(html: String): String {
        val m = Regex("""<link[^>]*fonts\.googleapis\.com[^>]*>""").find(html) ?: return html
        return html.replaceRange(m.range, "<style>\n$css\n</style>")
    }

    /** 第 number 块字体的响应；编号不对返回 null。 */
    fun response(number: Int): WebResourceResponse? {
        val e = index.getOrNull(number) ?: return null
        val buf = ByteBuffer.allocate(e.length)
        var pos = afd.startOffset + e.offset
        // 按位置读，不移动文件指针，多线程同时读也安全
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        if (buf.hasRemaining()) return null
        return WebResourceResponse(
            e.mime, null, 200, "OK",
            mapOf(
                "Content-Length" to e.length.toString(),
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=31536000, immutable",
            ),
            ByteArrayInputStream(buf.array()),
        )
    }

    fun close() {
        runCatching { channel.close() }
        runCatching { afd.close() }
    }

    companion object {
        const val HOST = "gcfont.invalid"
        const val BASE = "https://$HOST/"

        /** 文件不齐就返回 nil，这时网页里的字体退回系统字体（不联网）。 */
        fun make(context: Context): GodCoverFonts? = runCatching {
            val assets = context.assets
            val afd = assets.openFd("godcover/fonts.bin.gcweb")
            val channel = FileInputStream(afd.fileDescriptor).channel
            val total = afd.declaredLength.takeIf { it >= 0 } ?: afd.length
            val list = JSONArray(assets.open("godcover/fonts.index.json.gcweb").use { it.readBytes().decodeToString() })
            val index = (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONArray(i) ?: return@mapNotNull null
                if (item.length() != 3) return@mapNotNull null
                val o = item.optLong(0, -1)
                val l = item.optInt(1, 0)
                val m = item.optString(2)
                if (o < 0 || l <= 0 || o + l > total) null else Entry(o, l, m)
            }
            val css = assets.open("godcover/fonts.css.gcweb").use { it.readBytes().decodeToString() }
                .replace("gcfont://app/f/", BASE + "f/")
            GodCoverFonts(afd, channel, index, css)
        }.getOrNull()
    }
}
