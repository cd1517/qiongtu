package com.taoo.xiutu.raw

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/** RAW 冲洗的一级菜单，对应桌面 RAW 软件里的各个面板。 */
enum class RawGroup(val title: String) {
    LIGHT("光线"), CURVE("曲线"), COLOR("色彩"), MIXER("混色"), GRADING("分级"), EFFECTS("效果"), DETAIL("细节"), OPTICS("光学")
}

/** 混色器里的八个颜色。 */
enum class MixColor(val title: String, val swatch: Color) {
    RED("红", Color(0.93f, 0.22f, 0.2f)),
    ORANGE("橙", Color(0.97f, 0.55f, 0.15f)),
    YELLOW("黄", Color(0.96f, 0.84f, 0.2f)),
    GREEN("绿", Color(0.3f, 0.75f, 0.3f)),
    AQUA("浅绿", Color(0.25f, 0.8f, 0.78f)),
    BLUE("蓝", Color(0.22f, 0.45f, 0.95f)),
    PURPLE("紫", Color(0.6f, 0.35f, 0.9f)),
    MAGENTA("洋红", Color(0.9f, 0.3f, 0.75f)),
}

/** 颜色分级的四个区。 */
enum class GradeZone(val title: String) { SHADOWS("阴影"), MIDTONES("中间调"), HIGHLIGHTS("高光"), GLOBAL("全局") }

enum class ColorChannel(val key: String, val title: String, val symbol: String) {
    HUE("hue", "色相", "paintpalette"), SAT("sat", "饱和度", "drop"), LUM("lum", "明亮度", "sun.max")
}

/** RAW 冲洗里的一个滑块。按 id 判等。 */
class RawKey(
    val id: String,
    val title: String,
    val symbol: String,
    val range: ClosedFloatingPointRange<Float>,
    val style: Style = Style.PERCENT,
    /** 文件本身没有规定时的默认值。 */
    val neutral: Float = 0f,
) {
    enum class Style { STOPS, PERCENT, KELVIN, SIGNED, DEGREES }

    override fun equals(other: Any?) = other is RawKey && other.id == id
    override fun hashCode() = id.hashCode()

    fun format(value: Float): String = when (style) {
        Style.STOPS -> String.format("%+.2f", value)
        Style.KELVIN -> "${value.roundToInt()}K"
        Style.SIGNED -> String.format("%+.0f", value)
        Style.DEGREES -> "${value.roundToInt()}°"
        Style.PERCENT -> String.format("%.0f", value * 100)
    }

    companion object {
        private fun pm(id: String, title: String, symbol: String) = RawKey(id, title, symbol, -1f..1f)
        private fun unit(id: String, title: String, symbol: String, max: Float = 1f, neutral: Float = 0f) =
            RawKey(id, title, symbol, 0f..max, neutral = neutral)

        // 光线
        val exposure = RawKey("exposure", "曝光", "plusminus.circle", -5f..5f, Style.STOPS)
        val contrast = pm("contrast", "对比度", "circle.lefthalf.filled")
        val highlights = pm("highlights", "高光", "sun.haze")
        val shadows = pm("shadows", "阴影", "moon")
        val whites = pm("whites", "白色色阶", "circle")
        val blacks = pm("blacks", "黑色色阶", "circle.fill")
        // 曲线（参数曲线的四段）
        val curveHighlights = pm("curve.highlights", "高光区", "sun.max")
        val curveLights = pm("curve.lights", "亮调", "sun.min")
        val curveDarks = pm("curve.darks", "暗调", "cloud")
        val curveShadows = pm("curve.shadows", "阴影区", "moon.stars")
        // 色彩
        val temperature = RawKey("temperature", "色温", "thermometer.medium", 2000f..15000f, Style.KELVIN, 5500f)
        val tint = RawKey("tint", "色调", "eyedropper", -150f..150f, Style.SIGNED)
        val vibrance = pm("vibrance", "自然饱和度", "sparkles")
        val saturation = pm("saturation", "饱和度", "drop")
        // 效果
        val texture = pm("texture", "纹理", "square.grid.3x3")
        val clarity = pm("clarity", "清晰度", "diamond")
        val dehaze = pm("dehaze", "去朦胧", "cloud.fog")
        val vignette = pm("vignette", "暗角", "smallcircle.filled.circle")
        val vignetteMid = unit("vignette.mid", "暗角中点", "circle.dashed", neutral = 0.5f)
        val grain = unit("grain", "颗粒", "aqi.medium")
        val grainSize = unit("grain.size", "颗粒大小", "circle.grid.3x3", neutral = 0.25f)
        // 细节（iOS 在 RAW 解码阶段做；这里在线性数据上用着色器做）
        val sharpness = unit("sharpness", "锐化", "triangle")
        val detail = unit("detail", "细节", "diamond", max = 3f)
        val luminanceNoise = unit("noise.luminance", "明亮度降噪", "wand.and.stars")
        val colorNoise = unit("noise.color", "颜色降噪", "paintbrush")
        val moire = unit("moire", "去摩尔纹", "waveform")
        val rawContrast = unit("raw.contrast", "微对比", "circle.righthalf.filled")
        val localTone = unit("raw.localtone", "局部影调", "square.stack.3d.up")
        val boost = unit("raw.boost", "相机曲线", "point.topleft.down.curvedto.point.bottomright.up", neutral = 1f)
        val boostShadow = unit("raw.boostshadow", "暗部增益", "moon.haze", max = 2f)
        // 颜色分级的全局项
        val gradeBlending = unit("grade.blending", "混合", "circle.lefthalf.striped.horizontal", neutral = 0.5f)
        val gradeBalance = pm("grade.balance", "平衡", "scalemass")

        private val mixCache = HashMap<String, RawKey>()
        fun mix(color: MixColor, channel: ColorChannel): RawKey = synchronized(mixCache) {
            val id = "mix.${color.ordinal}.${channel.key}"
            mixCache.getOrPut(id) { RawKey(id, channel.title, channel.symbol, -1f..1f) }
        }

        fun grade(zone: GradeZone, channel: ColorChannel): RawKey = synchronized(mixCache) {
            val id = "grade.${zone.ordinal}.${channel.key}"
            mixCache.getOrPut(id) {
                when (channel) {
                    ColorChannel.HUE -> RawKey(id, "色相", channel.symbol, 0f..360f, Style.DEGREES)
                    ColorChannel.SAT -> RawKey(id, "饱和度", channel.symbol, 0f..1f)
                    ColorChannel.LUM -> RawKey(id, "明亮度", channel.symbol, -1f..1f)
                }
            }
        }

        val lightKeys = listOf(exposure, contrast, highlights, shadows, whites, blacks)
        val curveKeys = listOf(curveHighlights, curveLights, curveDarks, curveShadows)
        val colorKeys = listOf(temperature, tint, vibrance, saturation)
        val effectKeys = listOf(texture, clarity, dehaze, vignette, vignetteMid, grain, grainSize)
        val detailKeys = listOf(sharpness, detail, luminanceNoise, colorNoise, moire, rawContrast, localTone, boost, boostShadow)
    }
}

/** 白平衡预设。 */
data class WhiteBalancePreset(val title: String, val temperature: Float, val tint: Float) {
    companion object {
        val all = listOf(
            WhiteBalancePreset("日光", 5500f, 10f),
            WhiteBalancePreset("阴天", 6500f, 10f),
            WhiteBalancePreset("阴影", 7500f, 10f),
            WhiteBalancePreset("白炽灯", 2850f, 0f),
            WhiteBalancePreset("荧光灯", 3800f, 21f),
            WhiteBalancePreset("闪光灯", 5500f, 0f),
        )
    }
}
