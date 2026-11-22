package com.taoo.xiutu.raw

import android.graphics.Bitmap
import android.graphics.PointF
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * RAW 解码和调节。解码用 LibRaw（各家相机的 RAW 和 DNG），输出没套曲线、没截断高光的线性数据；
 * 曝光、白平衡、高光 / 阴影 / 白色 / 黑色色阶、相机曲线都在这份线性数据上做（GPU 半精度浮点），
 * 所以能找回 JPEG 里已经丢掉的高光和暗部；其余的色调、曲线、混色、分级、效果接着在高动态数据上做。
 */
class RawDeveloper private constructor(
    val file: File,
    /** 预览用的缩小版（长边 PREVIEW_SIDE）。 */
    val preview: RawImage,
) {
    val info: RawInfo get() = preview.info
    val opcodes: RawOpcodes = RawOpcodes.parse(info.opcodes2, info.opcodes3, info.cfa)
    val lensCorrectionSupported: Boolean get() = opcodes.lensSupported

    /** 原尺寸（转正后）。 */
    val nativeWidth: Int get() = if (info.flip and 4 != 0) info.sensorHeight else info.sensorWidth
    val nativeHeight: Int get() = if (info.flip and 4 != 0) info.sensorWidth else info.sensorHeight

    /** 这个文件"原样冲洗"时各滑块的值（色温色调是相机记录的拍摄白平衡）。 */
    val defaults: Map<RawKey, Float>
    private val asShot: DoubleArray = info.asShotWhite()

    init {
        val d = HashMap<RawKey, Float>()
        val xy = RawColor.xyOfRgb(asShot[0], asShot[1], asShot[2])
        val tt = RawColor.temperatureTint(xy[0], xy[1])
        d[RawKey.temperature] = tt[0].toFloat().coerceIn(RawKey.temperature.range.start, RawKey.temperature.range.endInclusive)
        d[RawKey.tint] = tt[1].toFloat().coerceIn(RawKey.tint.range.start, RawKey.tint.range.endInclusive)
        // 解码阶段的默认量：降噪随感光度走，锐化给一点（LibRaw 本身不锐化）
        val stops = if (info.iso > 100) ln(info.iso / 100) / ln(2.0) else 0.0
        d[RawKey.sharpness] = if (info.linearized) 0.3f else 0f
        d[RawKey.detail] = 1f
        d[RawKey.luminanceNoise] = if (info.linearized) (0.08 * stops).coerceIn(0.0, 0.5).toFloat() else 0f
        d[RawKey.colorNoise] = if (info.linearized) (0.25 + 0.05 * stops).coerceIn(0.25, 0.5).toFloat() else 0f
        d[RawKey.moire] = 0f
        d[RawKey.rawContrast] = 0f
        d[RawKey.localTone] = 0f
        d[RawKey.boost] = if (info.linearized) 1f else 0f
        d[RawKey.boostShadow] = if (info.linearized) 1f else 0f
        defaults = d
    }

    fun supports(key: RawKey): Boolean = true

    fun defaultValue(key: RawKey): Float = defaults[key] ?: key.neutral

    /** 白平衡吸管：把画面上的一个点当作中性灰，返回对应的色温和色调。point 是归一化坐标（转正后，原点左上）。 */
    fun whiteBalance(point: PointF): Pair<Float, Float>? {
        val s = toSensor(point.x, point.y)
        val c = preview.average(s.x, s.y, 5)
        if (c.any { !it.isFinite() } || c.max() <= 1e-5f) return null
        val xy = RawColor.xyOfRgb(c[0].toDouble(), c[1].toDouble(), c[2].toDouble())
        val tt = RawColor.temperatureTint(xy[0], xy[1])
        if (!tt[0].isFinite() || !tt[1].isFinite() || tt[0] <= 0) return null
        return tt[0].toFloat() to tt[1].toFloat()
    }

    private fun toSensor(x: Float, y: Float): PointF {
        var u = x
        var v = y
        if (info.flip and 4 != 0) { val t = u; u = v; v = t }
        if (info.flip and 2 != 0) v = 1 - v
        if (info.flip and 1 != 0) u = 1 - u
        return PointF(u, v)
    }

    /** 白平衡矩阵：把指定色温色调（或拍摄白平衡）的白适应到 D65。 */
    fun whiteBalanceMatrix(values: Map<RawKey, Float>): DoubleArray {
        val t = values[RawKey.temperature]
        val n = values[RawKey.tint]
        val xy = if (t == null && n == null) RawColor.xyOfRgb(asShot[0], asShot[1], asShot[2])
        else RawColor.xy((t ?: defaultValue(RawKey.temperature)).toDouble(), (n ?: defaultValue(RawKey.tint)).toDouble())
        return RawColor.adaptation(xy[0], xy[1])
    }

    /** 按给定的滑块值冲洗。full = false 出预览（长边 PREVIEW_SIDE），true 按原始分辨率（重新完整解码一次）。 */
    fun image(values: Map<RawKey, Float>, lensCorrection: Boolean, full: Boolean): Bitmap? {
        if (!full) return RawRender.render(this, values, lensCorrection, null)
        // 超大的（全景相机一亿像素级）用半尺寸解，免得手机内存不够
        val huge = info.sensorWidth.toLong() * info.sensorHeight > MAX_FULL_PIXELS
        val big = RawImage.decode(file, huge, 0)
            ?: (if (!info.linearized) RawImage.decodeSystem(file, 0) else null)
            ?: return null
        return try {
            RawRender.render(this, values, lensCorrection, big)
        } finally {
            big.close()
        }
    }

    fun close() = preview.close()

    companion object {
        const val PREVIEW_SIDE = 1800
        /** 超过这么多像素时"原尺寸"按半尺寸冲洗。 */
        const val MAX_FULL_PIXELS = 50_000_000L

        /** 打开一个 RAW 文件：先用 LibRaw，DNG 解不开时退回系统解码。 */
        fun open(file: File): RawDeveloper? {
            val img = RawImage.decode(file, true, PREVIEW_SIDE)
                ?: (if (file.extension.equals("dng", true)) RawImage.decodeSystem(file, PREVIEW_SIDE) else null)
                ?: return null
            if (img.width < 1 || img.height < 1) { img.close(); return null }
            return RawDeveloper(file, img)
        }

        fun lastError(): String = runCatching { RawNative.lastError() }.getOrDefault("")

        fun dim(w: Int, h: Int) = max(w, h).toFloat()
    }
}
