package com.taoo.xiutu.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import com.taoo.xiutu.editor.SaveOptions
import java.io.ByteArrayOutputStream
import java.io.File

/** 编码和存相册。 */
object Saver {
    fun encode(b: Bitmap, options: SaveOptions): ByteArray {
        val q = (options.size.quality * 100).toInt().coerceIn(1, 100)
        return when (options.format) {
            SaveOptions.Format.JPEG -> ByteArrayOutputStream().also { opaque(b).compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            SaveOptions.Format.PNG -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            SaveOptions.Format.HEIC -> runCatching { heic(b, q) }.getOrElse {
                // 设备不支持 HEIC 编码时退回 JPG
                ByteArrayOutputStream().also { opaque(b).compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            }
        }
    }

    private fun opaque(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return out
    }

    private fun heic(b: Bitmap, q: Int): ByteArray {
        val tmp = File.createTempFile("xiutu", ".heic")
        try {
            val w = HeifWriter.Builder(tmp.absolutePath, b.width, b.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(q).setMaxImages(1).build()
            w.start()
            w.addBitmap(b)
            w.stop(10_000)
            w.close()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    /** 存进系统相册的 Pictures/穷图。 */
    fun saveToGallery(context: Context, data: ByteArray, format: SaveOptions.Format) {
        val name = "XiuTu_" + System.currentTimeMillis() + "." + format.ext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/穷图")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法写入相册")
        r.openOutputStream(uri)?.use { it.write(data) } ?: error("无法写入相册")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
    }

    /** 存一张位图到相册（封神、摄影棚等直接出图的功能用）。 */
    fun saveBitmap(context: Context, b: Bitmap, format: SaveOptions.Format = SaveOptions.Format.JPEG): ByteArray {
        val data = encode(b, SaveOptions(SaveOptions.Size.ORIGINAL, format))
        saveToGallery(context, data, format)
        return data
    }
}
