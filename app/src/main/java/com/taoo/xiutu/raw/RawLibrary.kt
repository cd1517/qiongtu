package com.taoo.xiutu.raw

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.taoo.xiutu.picker.PhotoLibrary
import java.io.File

/** 相册里的 RAW（对应 iOS 的"RAW"智能相簿），以及把选中的文件拷进缓存给 LibRaw 读。 */
object RawLibrary {
    val extensions = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl", "raw",
        "pef", "ptx", "srw", "x3f", "3fr", "fff", "iiq", "mos", "mef", "mrw", "erf", "kdc", "dcr", "k25", "gpr", "cap", "rwz",
    )

    private val mimes = setOf(
        "image/x-adobe-dng", "image/dng", "image/x-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-srf", "image/x-sony-sr2",
        "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-panasonic-raw", "image/x-pentax-pef",
        "image/x-samsung-srw", "image/x-sigma-x3f", "image/x-hasselblad-3fr", "image/x-leica-rwl", "image/x-kodak-dcr",
        "image/x-kodak-kdc", "image/x-minolta-mrw", "image/x-epson-erf", "image/x-mamiya-mef", "image/x-phaseone-iiq",
        "image/x-dcraw",
    )

    class Item(val uri: Uri, val name: String)

    fun isRaw(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

    /** 相册里所有 RAW，新的在前。 */
    fun items(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val placeholders = mimes.joinToString(",") { "?" }
        val sel = "${MediaStore.Images.Media.MIME_TYPE} IN ($placeholders) OR ${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/x-%'"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE),
            sel, mimes.toTypedArray(), "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime !in mimes && !isRaw(name)) continue
                out += Item(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), name)
            }
        }
        return out
    }

    /** 缩略图：系统的，没有就取 RAW 里内嵌的。后台线程调用。 */
    suspend fun thumb(context: Context, uri: Uri): Bitmap? =
        PhotoLibrary.thumb(context, uri) ?: runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).thumbnailBitmap }
        }.getOrNull()

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** 拷到缓存里（LibRaw 按路径读）。阻塞，后台线程调用。 */
    fun copy(context: Context, uri: Uri, name: String): File? = runCatching {
        val folder = File(context.cacheDir, "raw-input")
        folder.deleteRecursively()
        folder.mkdirs()
        val ext = name.substringAfterLast('.', "dng").lowercase().ifEmpty { "dng" }
        val f = File(folder, "input.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: return null
        if (f.length() <= 0) null else f
    }.getOrNull()
}
