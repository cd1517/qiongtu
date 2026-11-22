package com.taoo.xiutu.raw

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Half
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 解码出来的线性 RGB（半精度，传感器方向，行优先），以及相机信息。
 * LibRaw 解的指向原生内存（close 时释放）；系统解码退路的在 Java 直接缓冲里。
 */
class RawImage private constructor(
    /** 传感器方向的尺寸。 */
    val width: Int,
    val height: Int,
    val pixels: ByteBuffer,
    private var handle: Long,
    val info: RawInfo,
) : Closeable {
    /** 转正以后的尺寸。 */
    val orientedWidth: Int get() = if (info.flip and 4 != 0) height else width
    val orientedHeight: Int get() = if (info.flip and 4 != 0) width else height

    private val shorts by lazy { pixels.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer() }

    /** 传感器归一化坐标 (u, v) 附近 n×n 像素的平均颜色（线性）。 */
    fun average(u: Float, v: Float, n: Int = 5): FloatArray {
        val cx = (u * width).toInt().coerceIn(0, width - 1)
        val cy = (v * height).toInt().coerceIn(0, height - 1)
        val acc = FloatArray(3)
        var count = 0
        val r = n / 2
        for (y in (cy - r)..(cy + r)) {
            if (y !in 0 until height) continue
            for (x in (cx - r)..(cx + r)) {
                if (x !in 0 until width) continue
                val i = (y * width + x) * 3
                for (c in 0..2) acc[c] += Half.toFloat(shorts.get(i + c))
                count++
            }
        }
        if (count > 0) for (c in 0..2) acc[c] /= count
        return acc
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            RawNative.release(handle)
            handle = 0L
        }
    }

    companion object {
        /** LibRaw 解码。half = 半尺寸快速解；maxDim > 0 时缩到长边不超过它。 */
        fun decode(file: File, half: Boolean, maxDim: Int): RawImage? {
            if (!RawNative.loaded) return null
            val h = runCatching { RawNative.decode(file.absolutePath, half, maxDim) }.getOrDefault(0L)
            if (h == 0L) return null
            return runCatching {
                val m = RawNative.meta(h)
                val t = RawNative.text(h)
                val info = RawInfo(
                    flip = m[2].toInt(),
                    sensorWidth = m[3].toInt(), sensorHeight = m[4].toInt(),
                    preMul = DoubleArray(4) { m[5 + it] }, camMul = DoubleArray(4) { m[9 + it] },
                    rgbCam = DoubleArray(9) { m[13 + (it / 3) * 4 + it % 3] },
                    baselineExposure = m[25], iso = m[26], isDng = m[36] != 0.0,
                    cfa = IntArray(4) { m[32 + it].toInt() },
                    make = t[0], model = t[1], lens = t[2],
                    opcodes2 = RawNative.opcodes(h, 1), opcodes3 = RawNative.opcodes(h, 2),
                    decodedWidth = m[37].toInt(), decodedHeight = m[38].toInt(),
                    linearized = true,
                )
                RawImage(m[0].toInt(), m[1].toInt(), RawNative.pixels(h), h, info)
            }.getOrElse {
                RawNative.release(h)
                null
            }
        }

        /**
         * 退路：LibRaw 解不开的 DNG（比如有损压缩的）交给系统解码成 8 位成品图，再换回线性。
         * 这时没有高光余量，白平衡按"已经校正过"处理（拍摄白平衡 = D65）。
         */
        fun decodeSystem(file: File, maxDim: Int): RawImage? = runCatching {
            val src = ImageDecoder.createSource(file)
            val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                val long = max(info.size.width, info.size.height)
                if (maxDim in 1 until long) {
                    val k = maxDim.toFloat() / long
                    d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
                }
            }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            val w = bmp.width
            val h = bmp.height
            val lut = ShortArray(256) {
                val c = it / 255.0
                val l = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
                Half.toHalf(l.toFloat())
            }
            val buf = ByteBuffer.allocateDirect(w * h * 3 * 2).order(ByteOrder.nativeOrder())
            val sb = buf.asShortBuffer()
            val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val p = row[x]
                    sb.put(lut[(p shr 16) and 0xff]); sb.put(lut[(p shr 8) and 0xff]); sb.put(lut[p and 0xff])
                }
            }
            bmp.recycle()
            val info = RawInfo(
                flip = 0, sensorWidth = w, sensorHeight = h,
                preMul = doubleArrayOf(1.0, 1.0, 1.0, 1.0), camMul = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
                rgbCam = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                baselineExposure = 0.0, iso = 0.0, isDng = true, cfa = intArrayOf(-1, -1, -1, -1),
                make = "", model = "", lens = "", opcodes2 = null, opcodes3 = null,
                decodedWidth = w, decodedHeight = h, linearized = false,
            )
            RawImage(w, h, buf, 0L, info)
        }.getOrNull()
    }
}

/** 相机和解码信息。 */
class RawInfo(
    /** LibRaw 的方向：4 = 先转置，2 = 上下翻，1 = 左右翻。 */
    val flip: Int,
    /** 原尺寸传感器有效区域（传感器方向）。 */
    val sensorWidth: Int,
    val sensorHeight: Int,
    val preMul: DoubleArray,
    val camMul: DoubleArray,
    /** 相机（按日光系数缩放后）→ 线性 sRGB，行优先 3×3。 */
    val rgbCam: DoubleArray,
    val baselineExposure: Double,
    val iso: Double,
    val isDng: Boolean,
    val cfa: IntArray,
    val make: String,
    val model: String,
    val lens: String,
    val opcodes2: ByteArray?,
    val opcodes3: ByteArray?,
    val decodedWidth: Int,
    val decodedHeight: Int,
    /** true = LibRaw 解的真线性数据；false = 系统解码的成品图换回线性。 */
    val linearized: Boolean,
) {
    /** 高光余量：LibRaw 的"混合"高光模式按最大的日光系数归一，乘回最大 / 最小就是传感器饱和 = 1。 */
    val headroom: Double get() {
        if (!linearized) return 1.0
        val p = preMul.copyOf()
        if (p[3] == 0.0) p[3] = p[1]
        val mx = p.max()
        val mn = p.filter { it > 0 }.minOrNull() ?: return 1.0
        return if (mx > 0 && mn > 0) mx / mn else 1.0
    }

    /** 拍摄时的白平衡在线性 sRGB 里的"白"。 */
    fun asShotWhite(): DoubleArray {
        val cm = camMul
        if (!linearized || cm[0] <= 0 || cm[1] <= 0 || cm[2] <= 0) return doubleArrayOf(1.0, 1.0, 1.0)
        val v = DoubleArray(3) { c -> preMul[c] / cm[c] }
        val w = RawColor.mul(rgbCam, v)
        val m = w.max()
        if (m <= 0 || w.any { it <= 0 }) return doubleArrayOf(1.0, 1.0, 1.0)
        return DoubleArray(3) { w[it] / m }
    }
}
