package com.taoo.xiutu.tools.adjust

import com.taoo.xiutu.ai.AIPreset

/** 调色里要交给 AI 做的功能：按钮上带 "AI" 角标。点了把已经调好的保留成一步，转到 AI 修图直接生成。 */
enum class AdjustAI(val title: String, val symbol: String) {
    EXPOSURE("AI曝光", "camera.metering.center.weighted.average"),
    FLASH("闪光灯", "bolt"),
    FLASH_PRO("增强闪光", "bolt.circle"),
    COLOR_RESTORE("色彩还原", "paintbrush.pointed"),
    HD("画质清晰", "4k.tv"),
    DENOISE("AI降噪", "wand.and.stars"),
    LOW_LIGHT("暗光修复", "lightbulb.max");

    val preset: AIPreset
        get() = when (this) {
            EXPOSURE -> AIPreset(title, symbol,
                "你是一位摄影大师兼调色师。先判断这张照片在曝光上的毛病（过曝或欠曝、主体太暗、高光溢出、暗部死黑、反差不足或过头），然后把它调成曝光准确、明暗层次完整、自然好看的照片，不要改变原来的色调风格。$KEEP_SHAPE",
                toneOnly = true)
            FLASH -> AIPreset(title, symbol,
                "模拟机顶闪光灯正面直打的效果：近处的人物和主体被一束直射的光照亮，肤色明亮通透，背景相对压暗，像夜里用闪光灯拍的快照。只改光线、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。",
                toneOnly = true)
            FLASH_PRO -> AIPreset(title, symbol,
                "模拟专业离机闪光灯加柔光箱的布光：主体被一盏有方向感的柔和主光照亮，脸和身体的轮廓立体，眼神光清晰，背景明显压暗，主体从背景里跳出来，像影棚里拍的大片。只改光线、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。",
                toneOnly = true)
            COLOR_RESTORE -> AIPreset(title, symbol,
                "还原这张照片真实准确的颜色：校正白平衡和偏色，让肤色、白色和中性灰看起来自然，把被混合光源、滤镜或褪色带偏的颜色拉回来，饱和度自然不过头。$KEEP_SHAPE",
                toneOnly = true)
            HD -> AIPreset(title, symbol,
                "提升这张照片的清晰度和画质：找回细节和纹理，去掉模糊、噪点和压缩痕迹，边缘干净锐利，皮肤保留真实质感，不要涂抹感。人物长相、构图、颜色和光线都不变。")
            DENOISE -> AIPreset(title, symbol,
                "去掉这张照片的噪点、彩色杂点和压缩痕迹，保留真实的细节、纹理和皮肤质感，不要涂抹感。人物长相、构图、颜色和光线都不变。")
            LOW_LIGHT -> AIPreset(title, symbol,
                "修复暗光环境下拍的照片：把整体和人物提亮到正常，找回暗部的层次和颜色，压住路灯、招牌这些高光不要溢出，保留夜晚的氛围，不要发灰。$KEEP_SHAPE",
                toneOnly = true)
        }

    companion object {
        /** 只改光线和颜色的指令都带这一句。 */
        private const val KEEP_SHAPE = "只改曝光、明暗和颜色；不要移动、裁剪、旋转或改变画面里任何东西的形状和位置，结果要和原图逐像素对齐。"
    }
}

/** 调色面板那一排按钮上的一项：一个滑块、一个子面板，或者一个 AI 功能。 */
sealed interface AdjustItem {
    data class Key(val key: AdjustKey) : AdjustItem
    data class Sub(val sub: AdjustSub) : AdjustItem
    data class AI(val ai: AdjustAI) : AdjustItem
}

/** 每一组里按钮的顺序。 */
val AdjustGroup.items: List<AdjustItem>
    get() {
        fun k(key: AdjustKey) = AdjustItem.Key(key)
        fun s(sub: AdjustSub) = AdjustItem.Sub(sub)
        fun a(ai: AdjustAI) = AdjustItem.AI(ai)
        return when (this) {
            AdjustGroup.LIGHT -> listOf(s(AdjustSub.LOCAL), k(AdjustKey.BRIGHTNESS), k(AdjustKey.CONTRAST), k(AdjustKey.EXPOSURE),
                a(AdjustAI.EXPOSURE), a(AdjustAI.FLASH), k(AdjustKey.LIGHT_SENSE), k(AdjustKey.HIGHLIGHTS), k(AdjustKey.SHADOWS),
                k(AdjustKey.AMBIANCE), k(AdjustKey.FILL_LIGHT))
            AdjustGroup.COLOR -> listOf(k(AdjustKey.SATURATION), k(AdjustKey.VIBRANCE), k(AdjustKey.TEMPERATURE), k(AdjustKey.TINT),
                a(AdjustAI.COLOR_RESTORE), s(AdjustSub.HSL), k(AdjustKey.DEHAZE), k(AdjustKey.FADE), s(AdjustSub.SKIN),
                s(AdjustSub.SPLIT), s(AdjustSub.CURVE))
            AdjustGroup.DETAIL -> listOf(k(AdjustKey.SHARPEN), a(AdjustAI.HD), k(AdjustKey.CLARITY), k(AdjustKey.DENOISE),
                a(AdjustAI.DENOISE), k(AdjustKey.GRAIN), k(AdjustKey.FRINGE), k(AdjustKey.VIGNETTE))
            AdjustGroup.TONE -> listOf(a(AdjustAI.FLASH), a(AdjustAI.FLASH_PRO), k(AdjustKey.NATURAL_LIGHT), k(AdjustKey.LOW_LIGHT),
                k(AdjustKey.BACKLIGHT), a(AdjustAI.LOW_LIGHT), k(AdjustKey.EVEN_LIGHT), k(AdjustKey.MOOD_LIGHT), k(AdjustKey.BLOOM))
        }
    }
