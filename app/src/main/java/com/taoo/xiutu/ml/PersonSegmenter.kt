package com.taoo.xiutu.ml

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.taoo.xiutu.core.Img
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 人像分割（ML Kit 自拍分割，全部在本机完成）。
 * mask 返回和输入一样大的不透明灰度图（ARGB_8888，R=G=B）：白 = 人，黑 = 背景。几乎找不到人时返回 null。
 * 会阻塞，必须在后台线程调用。
 */
object PersonSegmenter {
    private val lock = Any()

    fun mask(bitmap: Bitmap): Bitmap? = maskData(bitmap)?.toGrayBitmap()

    /** 同上，但返回 8 位数据（省一次转换）。 */
    fun maskData(bitmap: Bitmap): Mask8? = synchronized(lock) {
        runCatching {
            val input = Img.soft(bitmap)
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
            val client = Segmentation.getClient(options)
            try {
                val result = Tasks.await(client.process(InputImage.fromBitmap(input, 0)), 30, TimeUnit.SECONDS)
                val w = result.width
                val h = result.height
                val buf = result.buffer.order(ByteOrder.nativeOrder())
                buf.rewind()
                val floats = buf.asFloatBuffer()
                val px = ByteArray(w * h) { (floats.get(it).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
                var m = Mask8(px, w, h)
                if (w != input.width || h != input.height) m = Mask8.resized(m, input.width, input.height)
                // 几乎没有白色就当作没找到人
                val small = Mask8.resized(m, 64, 64)
                var found = 0
                for (i in 0 until 64 * 64) if (small[i] > 128) found++
                if (found * 200 > 64 * 64) m else null
            } finally {
                client.close()
            }
        }.getOrNull()
    }
}
