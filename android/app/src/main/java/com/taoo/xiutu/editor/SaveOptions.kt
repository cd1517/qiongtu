package com.taoo.xiutu.editor

import android.content.Context

/** 保存到相册时的画质和格式。 */
data class SaveOptions(val size: Size = Size.ORIGINAL, val format: Format = Format.JPEG) {
    enum class Size(val title: String, val maxSide: Int?, val quality: Double) {
        STANDARD("标准", 2048, 0.86),
        HIGH("高清", 4096, 0.95),
        ORIGINAL("原画质", null, 1.0),
    }

    enum class Format(val title: String, val ext: String, val mime: String) {
        JPEG("JPG", "jpg", "image/jpeg"),
        HEIC("HEIC", "heic", "image/heic"),
        PNG("PNG", "png", "image/png"),
    }

    /** 原尺寸是 full 时，这一档存出来是多大。 */
    fun pixels(full: Pair<Int, Int>): Pair<Int, Int> {
        val side = size.maxSide ?: return full
        val long = maxOf(full.first, full.second)
        if (long <= side) return full
        val k = side.toDouble() / long
        return maxOf(1, (full.first * k).toInt()) to maxOf(1, (full.second * k).toInt())
    }

    companion object {
        /** 上次选的，下次默认还是它。 */
        fun saved(context: Context): SaveOptions {
            val p = context.getSharedPreferences("save", Context.MODE_PRIVATE)
            return SaveOptions(
                runCatching { Size.valueOf(p.getString("size", "")!!) }.getOrDefault(Size.ORIGINAL),
                runCatching { Format.valueOf(p.getString("format", "")!!) }.getOrDefault(Format.JPEG),
            )
        }

        fun store(context: Context, o: SaveOptions) {
            context.getSharedPreferences("save", Context.MODE_PRIVATE).edit()
                .putString("size", o.size.name).putString("format", o.format.name).apply()
        }
    }
}
