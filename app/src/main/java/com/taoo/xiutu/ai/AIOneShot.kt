package com.taoo.xiutu.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.taoo.xiutu.core.Saver
import com.taoo.xiutu.history.HistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 封神、摄影棚、漫画人生共用的小工具。 */
object AIOneShot {
    /** 重新画一遍：限制最长边，得到一张不透明的普通 ARGB 位图。 */
    fun prepared(image: Bitmap, maxSide: Int): Bitmap {
        val longest = max(image.width, image.height)
        val k = min(1f, maxSide.toFloat() / max(longest, 1))
        val w = max(1, (image.width * k).roundToInt())
        val h = max(1, (image.height * k).roundToInt())
        val src = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        out.setHasAlpha(false)
        return out
    }

    /** 按照片的横竖挑输出尺寸。 */
    fun size(matching: Bitmap): String {
        val ratio = matching.width.toFloat() / max(matching.height, 1)
        if (ratio > 1.15f) return "1536x1024"
        if (ratio < 0.87f) return "1024x1536"
        return "1024x1024"
    }

    /** 用当前服务商，按"指令 + 参考图"生成一张图。第一张是主图。 */
    suspend fun run(prompt: String, images: List<Bitmap>, size: String = "1024x1536"): Pair<Bitmap, AIUsage?> {
        val endpoint = withContext(Dispatchers.Main.immediate) { AISettings.endpoint() }
            ?: throw AIError("还没有设置 ${AISettings.active.shortTitle} 的 API Key。")
        if (images.isEmpty()) throw AIError("还没有选照片。")
        val usePNG = endpoint.provider.supportsMask
        val encoded = withContext(Dispatchers.Default) {
            images.map { image ->
                val upload = prepared(image, 1536)
                ByteArrayOutputStream().also {
                    if (usePNG) upload.compress(Bitmap.CompressFormat.PNG, 100, it)
                    else upload.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }.toByteArray().takeIf { it.isNotEmpty() } ?: throw AIError("图片编码失败。")
            }
        }
        val payload = AIPayload(
            prompt, encoded[0], if (usePNG) "image/png" else "image/jpeg",
            maskPNG = null, markedJPEG = null, size = size, extraImages = encoded.drop(1),
        )
        AIClient.takeLastUsage()
        val data = AIClient.edit(endpoint, payload)
        val usage = AIClient.takeLastUsage()
        val result = withContext(Dispatchers.Default) {
            val raw = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw AIError("服务商返回的不是有效图片。")
            prepared(raw, 4096)
        }
        return result to usage
    }

    /** 存到相册；开着"存入历史图库"时同时在 App 里留一份。source 是历史图库里显示的来源。 */
    suspend fun saveToPhotos(context: Context, image: Bitmap, source: String = "AI 生成"): Boolean = withContext(Dispatchers.IO) {
        try {
            val data = Saver.saveBitmap(context, image)
            if (HistoryStore.enabled) HistoryStore.add(context, data, "jpg", source)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
