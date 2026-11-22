package com.taoo.xiutu.core

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 草稿箱：编辑中每做完一步就把当前画面存一份，App 意外退出后下次打开可以接着编辑。
 * 只存一份：原图 + 当前画面。正常退出编辑器（保存后返回、或者放弃修改）就删掉。
 */
object DraftStore {
    private lateinit var folder: File
    private val lock = Any()
    /** 每次清空加一；清空之前排队的写入发现对不上就不写了。 */
    private var generation = 0

    fun init(context: Context) {
        folder = File(context.filesDir, "Draft").apply { mkdirs() }
    }

    val originalFile: File get() = File(folder, "original.bin")
    private val snapshotFile: File get() = File(folder, "snapshot.webp")

    val exists: Boolean get() = originalFile.exists() && snapshotFile.exists()

    /** 草稿最后一次写入的时间（毫秒）。 */
    val date: Long? get() = snapshotFile.takeIf { it.exists() }?.lastModified()

    /** 当前的写入批次号，排队写之前先拿一个。 */
    val token: Int get() = synchronized(lock) { generation }

    /** 写入草稿。original 为 null 时保留已有的原图（同一次编辑原图只写一次）。 */
    fun write(original: ByteArray?, snapshot: Bitmap, token: Int): Boolean {
        val data = ByteArrayOutputStream().also { snapshot.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it) }.toByteArray()
        synchronized(lock) {
            if (token != generation) return false
            if (original != null) atomicWrite(originalFile, original)
            atomicWrite(snapshotFile, data)
        }
        return true
    }

    fun clear() {
        synchronized(lock) {
            generation++
            originalFile.delete()
            snapshotFile.delete()
        }
    }

    /** 读出当前画面。 */
    fun loadSnapshot(): Bitmap? = if (snapshotFile.exists()) Img.decode(snapshotFile) else null

    /** 弹窗里显示的小图。 */
    fun thumbnail(maxSide: Int = 600): Bitmap? = if (snapshotFile.exists()) Img.decode(snapshotFile, maxSide) else null

    private fun atomicWrite(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
