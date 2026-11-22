package com.taoo.xiutu.tools.makeup

/** 一个有名字的颜色。 */
class MakeupSwatch(val name: String, hex: Int) {
    val color = MakeupColor.hex(hex)
}

/** 美妆的全部预设。都是自己调的参数，名字只是描述风格。 */
object MakeupCatalog {
    // MARK: 口红

    val lipColors = listOf(
        MakeupSwatch("豆沙", 0xB5626A), MakeupSwatch("正红", 0xC81E2C), MakeupSwatch("枫叶红", 0xB5432E), MakeupSwatch("烂番茄", 0xC8402C),
        MakeupSwatch("蜜桃", 0xF0876E), MakeupSwatch("奶茶", 0xC28A72), MakeupSwatch("裸粉", 0xDC9A96), MakeupSwatch("玫瑰", 0xC2466A),
        MakeupSwatch("莓果", 0x9C2350), MakeupSwatch("珊瑚", 0xF0705A), MakeupSwatch("斩男色", 0xD8545A), MakeupSwatch("姨妈红", 0x8E1B2A),
        MakeupSwatch("复古红", 0xA8232B), MakeupSwatch("西柚", 0xF06858), MakeupSwatch("樱花粉", 0xF2A0B0), MakeupSwatch("芭比粉", 0xE85A92),
        MakeupSwatch("肉桂", 0xA85C4A), MakeupSwatch("焦糖", 0xA65A3C), MakeupSwatch("南瓜", 0xD2603A), MakeupSwatch("酒红", 0x7A1830),
        MakeupSwatch("干枯玫瑰", 0xB0606C), MakeupSwatch("奶杏", 0xD9A08A), MakeupSwatch("蜜橘", 0xEE7A4A), MakeupSwatch("梅子", 0x7E2E52),
    )

    fun lipColor(name: String): MakeupColor = (lipColors.firstOrNull { it.name == name } ?: lipColors[0]).color

    class LipPreset(val name: String, val lip: LipMakeup)

    private fun lip(name: String, color: String, texture: MKLipTexture, style: MKLipStyle = MKLipStyle.FULL,
                    amount: Float = 0.8f, liner: Float = 0f) =
        LipPreset(name, LipMakeup(lipColor(color), amount, texture, style, liner))

    val lipPresets = listOf(
        lip("气质裸粉", "裸粉", MKLipTexture.SATIN, amount = 0.75f),
        lip("柔感杏仁", "奶杏", MKLipTexture.VELVET, MKLipStyle.BLURRED, amount = 0.8f),
        lip("兔系唇", "樱花粉", MKLipTexture.GLOSS, MKLipStyle.GRADIENT, amount = 0.85f),
        lip("元气粉", "芭比粉", MKLipTexture.GLOSS, amount = 0.6f),
        lip("哑光正红", "正红", MKLipTexture.MATTE, amount = 0.85f),
        lip("丝绒豆沙", "豆沙", MKLipTexture.VELVET, amount = 0.85f),
        lip("水光蜜桃", "蜜桃", MKLipTexture.GLOSS, amount = 0.75f),
        lip("咬唇西柚", "西柚", MKLipTexture.SATIN, MKLipStyle.GRADIENT, amount = 0.85f),
        lip("镜面玫瑰", "玫瑰", MKLipTexture.GLAZE, amount = 0.75f),
        lip("复古港风", "复古红", MKLipTexture.MATTE, amount = 0.9f, liner = 0.5f),
        lip("奶茶雾面", "奶茶", MKLipTexture.VELVET, MKLipStyle.BLURRED, amount = 0.85f),
        lip("斩男水红", "斩男色", MKLipTexture.GLOSS, amount = 0.75f),
        lip("莓果咬唇", "莓果", MKLipTexture.SATIN, MKLipStyle.GRADIENT, amount = 0.85f),
        lip("枫叶哑光", "枫叶红", MKLipTexture.MATTE, amount = 0.85f),
        lip("烂番茄", "烂番茄", MKLipTexture.VELVET, amount = 0.85f),
        lip("干枯玫瑰", "干枯玫瑰", MKLipTexture.VELVET, amount = 0.85f),
        lip("肉桂奶茶", "肉桂", MKLipTexture.MATTE, MKLipStyle.BLURRED, amount = 0.85f),
        lip("酒红镜面", "酒红", MKLipTexture.GLAZE, amount = 0.8f),
        lip("樱花果冻", "樱花粉", MKLipTexture.GLAZE, amount = 0.55f),
        lip("蜜橘元气", "蜜橘", MKLipTexture.GLOSS, amount = 0.7f),
        lip("梅子雾面", "梅子", MKLipTexture.VELVET, MKLipStyle.BLURRED, amount = 0.85f),
        lip("血橙咬唇", "南瓜", MKLipTexture.SATIN, MKLipStyle.GRADIENT, amount = 0.85f),
        lip("焦糖哑光", "焦糖", MKLipTexture.MATTE, amount = 0.85f),
        lip("姨妈红丝绒", "姨妈红", MKLipTexture.VELVET, amount = 0.9f, liner = 0.3f),
        lip("珊瑚水光", "珊瑚", MKLipTexture.GLOSS, amount = 0.7f),
        lip("裸感润唇", "裸粉", MKLipTexture.GLOSS, amount = 0.45f),
    )

    // MARK: 眉毛

    val browColors = listOf(
        MakeupSwatch("自然黑", 0x2A2422), MakeupSwatch("深棕", 0x3C2A20), MakeupSwatch("浅棕", 0x6A4A36), MakeupSwatch("灰棕", 0x55483F),
        MakeupSwatch("亚麻", 0x8A6E54), MakeupSwatch("红棕", 0x5E3324),
    )

    fun browPreset(shape: MKBrowShape): BrowMakeup = when (shape) {
        MKBrowShape.SOFT -> BrowMakeup(shape, browColors[2].color, 0.55f)
        MKBrowShape.WILLOW -> BrowMakeup(shape, browColors[0].color, 0.6f)
        MKBrowShape.WILD, MKBrowShape.SWORD -> BrowMakeup(shape, browColors[0].color, 0.65f)
        else -> BrowMakeup(shape, browColors[1].color, 0.6f)
    }

    // MARK: 眼妆

    class ShadowPreset(val name: String, val shadow: ShadowMakeup)

    private fun shadow(name: String, lid: Int, crease: Int, outer: Int, lower: Float = 0.3f,
                       shimmer: Float = 0f, sparkle: Boolean = false, smoke: Float = 0f) =
        ShadowPreset(name, ShadowMakeup(MakeupColor.hex(lid), MakeupColor.hex(crease), MakeupColor.hex(outer), lower, shimmer, sparkle, smoke))

    val shadows = listOf(
        shadow("大地", 0xB98A68, 0x8A5A3C, 0x5A3824),
        shadow("奶茶", 0xD2AE96, 0xA87E68, 0x7A5646, lower = 0.25f),
        shadow("蜜桃", 0xF2A68A, 0xE0785C, 0xB85840, lower = 0.35f, shimmer = 0.2f),
        shadow("珊瑚橘", 0xF5A27E, 0xE8724E, 0xB8502E, lower = 0.35f, shimmer = 0.3f),
        shadow("樱花", 0xF4B8C4, 0xE890A4, 0xC06A84, lower = 0.4f, shimmer = 0.3f),
        shadow("玫瑰", 0xE09AA6, 0xC0607A, 0x8A3C56, shimmer = 0.2f),
        shadow("酒红", 0xC07A84, 0x96404E, 0x641E30, lower = 0.5f),
        shadow("枫叶", 0xD98A5A, 0xB85A34, 0x7E3A22, lower = 0.35f, shimmer = 0.1f),
        shadow("香槟", 0xEBD2B0, 0xC8A078, 0x9A7250, lower = 0.2f, shimmer = 1f),
        shadow("金棕", 0xD8B078, 0xA87840, 0x6E4A24, shimmer = 0.8f),
        shadow("灰雾", 0xB0A8A8, 0x807880, 0x504A54),
        shadow("烟熏", 0x6A6468, 0x3C383C, 0x1E1C20, lower = 0.8f, smoke = 0.8f),
        shadow("紫藤", 0xC4A8D8, 0x9A78B8, 0x6A4E8A, shimmer = 0.4f),
        shadow("海盐蓝", 0xA8C4DC, 0x7A9CC0, 0x4E6E96, shimmer = 0.3f),
        shadow("橄榄", 0xA8A878, 0x7E7E4E, 0x56562E),
        shadow("星河", 0xC8C0E0, 0x8E86B8, 0x4E4878, shimmer = 0.8f, sparkle = true),
    )

    fun shadow(name: String, amount: Float = 0.7f, lower: Float? = null): ShadowMakeup {
        val s = (shadows.firstOrNull { it.name == name } ?: shadows[0]).shadow.copy(amount = amount)
        return if (lower != null) s.copy(lower = lower) else s
    }

    val linerColors = listOf(
        MakeupSwatch("黑色", 0x121010), MakeupSwatch("深棕", 0x4A2E22), MakeupSwatch("灰棕", 0x5A4C46), MakeupSwatch("酒红", 0x5E1C28),
        MakeupSwatch("藏蓝", 0x1C2A4E),
    )

    val lensColors = listOf(
        MakeupSwatch("自然棕", 0x9A6A3A), MakeupSwatch("巧克力", 0x6A4428), MakeupSwatch("蜜糖", 0xC0924E), MakeupSwatch("烟灰", 0x8A9098),
        MakeupSwatch("蓝灰", 0x6E8AA8), MakeupSwatch("冰蓝", 0x7EB0D8), MakeupSwatch("墨绿", 0x6A8A62), MakeupSwatch("雾紫", 0x9A7AB8),
        MakeupSwatch("樱粉", 0xD090A4), MakeupSwatch("黑曜", 0x2A2420),
    )

    class EyePreset(val name: String, val eye: EyeMakeup)

    private val brownLiner = MakeupColor.hex(0x4A2E22)

    val eyePresets = listOf(
        EyePreset("淡颜", EyeMakeup(
            shadow = shadow("奶茶", amount = 0.5f), liner = LinerMakeup(MKLinerStyle.THIN, amount = 0.6f), aegyo = 0.6f,
        )),
        EyePreset("温柔", EyeMakeup(
            shadow = shadow("蜜桃", amount = 0.6f), liner = LinerMakeup(MKLinerStyle.PUPPY, brownLiner, 0.7f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.7f), aegyo = 0.5f,
        )),
        EyePreset("淡雅", EyeMakeup(
            shadow = shadow("香槟", amount = 0.6f), liner = LinerMakeup(MKLinerStyle.THIN, brownLiner, 0.6f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.55f),
        )),
        EyePreset("灰雾电波", EyeMakeup(
            shadow = shadow("灰雾", amount = 0.7f), liner = LinerMakeup(MKLinerStyle.WING, amount = 0.8f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.8f), aegyo = 0.5f,
        )),
        EyePreset("消肿", EyeMakeup(
            shadow = shadow("大地", amount = 0.65f, lower = 0.15f), liner = LinerMakeup(MKLinerStyle.NATURAL, brownLiner, 0.7f),
        )),
        EyePreset("纯欲", EyeMakeup(
            shadow = shadow("樱花", amount = 0.6f, lower = 0.5f), liner = LinerMakeup(MKLinerStyle.THIN, brownLiner, 0.5f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.8f), aegyo = 0.9f,
            catchlight = MKCatchlight.DOT, catchlightAmount = 0.6f,
        )),
        EyePreset("元气", EyeMakeup(
            shadow = shadow("珊瑚橘", amount = 0.6f), liner = LinerMakeup(MKLinerStyle.NATURAL, amount = 0.7f),
            lashes = LashMakeup(MKLashStyle.SUNFLOWER, 0.8f), aegyo = 0.5f,
            catchlight = MKCatchlight.TWIN, catchlightAmount = 0.6f,
        )),
        EyePreset("日杂", EyeMakeup(
            shadow = shadow("大地", amount = 0.45f), liner = LinerMakeup(MKLinerStyle.NATURAL, brownLiner, 0.6f),
            lashes = LashMakeup(MKLashStyle.SUNFLOWER, 0.9f),
        )),
        EyePreset("狗狗眼", EyeMakeup(
            shadow = shadow("奶茶", amount = 0.6f, lower = 0.5f), liner = LinerMakeup(MKLinerStyle.PUPPY, amount = 0.8f),
            lashes = LashMakeup(MKLashStyle.DOLL, 0.7f), aegyo = 0.6f,
        )),
        EyePreset("猫系", EyeMakeup(
            shadow = shadow("玫瑰", amount = 0.6f), liner = LinerMakeup(MKLinerStyle.CAT, amount = 0.85f),
            lashes = LashMakeup(MKLashStyle.DENSE, 0.7f),
        )),
        EyePreset("港风", EyeMakeup(
            shadow = shadow("金棕", amount = 0.7f), liner = LinerMakeup(MKLinerStyle.WING, amount = 0.9f),
            lashes = LashMakeup(MKLashStyle.DENSE, 0.85f),
        )),
        EyePreset("微醺", EyeMakeup(
            shadow = shadow("酒红", amount = 0.6f, lower = 0.6f), liner = LinerMakeup(MKLinerStyle.NATURAL, brownLiner, 0.6f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.6f), aegyo = 0.4f,
        )),
        EyePreset("小烟熏", EyeMakeup(
            shadow = shadow("烟熏", amount = 0.7f), liner = LinerMakeup(MKLinerStyle.CAT, amount = 0.9f),
            lashes = LashMakeup(MKLashStyle.DENSE, 0.9f),
        )),
        EyePreset("欧美", EyeMakeup(
            shadow = shadow("大地", amount = 0.9f).copy(smoke = 0.3f), liner = LinerMakeup(MKLinerStyle.CAT, amount = 0.9f),
            lashes = LashMakeup(MKLashStyle.DOLL, 0.9f), fold = MKFoldStyle.PARALLEL, foldAmount = 0.5f,
        )),
        EyePreset("清冷", EyeMakeup(
            shadow = shadow("海盐蓝", amount = 0.5f), liner = LinerMakeup(MKLinerStyle.THIN, amount = 0.7f),
            lashes = LashMakeup(MKLashStyle.NATURAL, 0.5f),
        )),
        EyePreset("人鱼姬", EyeMakeup(
            shadow = shadow("紫藤", amount = 0.7f).copy(shimmer = 0.8f, sparkle = true), liner = LinerMakeup(MKLinerStyle.WING, amount = 0.7f),
            lashes = LashMakeup(MKLashStyle.DENSE, 0.7f),
        )),
        EyePreset("星河", EyeMakeup(
            shadow = shadow("星河", amount = 0.7f), liner = LinerMakeup(MKLinerStyle.WING, amount = 0.8f),
            lashes = LashMakeup(MKLashStyle.DENSE, 0.8f), catchlight = MKCatchlight.STAR, catchlightAmount = 0.7f,
        )),
    )

    fun eyePreset(name: String): EyeMakeup = (eyePresets.firstOrNull { it.name == name } ?: eyePresets[0]).eye

    // MARK: 修容

    class ContourPreset(val name: String, val contour: ContourMakeup)

    val contourPresets = listOf(
        ContourPreset("自然立体", ContourMakeup(noseShade = 0.5f, noseLight = 0.5f, cheekLight = 0.4f, jaw = 0.35f)),
        ContourPreset("立体鼻", ContourMakeup(noseShade = 0.9f, noseLight = 0.8f)),
        ContourPreset("Doll", ContourMakeup(forehead = 0.7f, chinLight = 0.7f, cheekLight = 0.6f, underEye = 0.6f, noseLight = 0.5f, noseShade = 0.3f)),
        ContourPreset("纯欲", ContourMakeup(underEye = 0.8f, cupid = 0.8f, noseLight = 0.5f, cheekLight = 0.4f)),
        ContourPreset("光泽感", ContourMakeup(forehead = 0.8f, cheekLight = 0.9f, chinLight = 0.7f, cupid = 0.7f, noseLight = 0.7f)),
        ContourPreset("小V脸", ContourMakeup(jaw = 0.9f, cheekShade = 0.7f, chinLight = 0.5f)),
        ContourPreset("混血骨相", ContourMakeup(noseShade = 0.8f, noseLight = 0.6f, cheekShade = 0.7f, temple = 0.6f, jaw = 0.5f, cheekLight = 0.5f)),
        ContourPreset("幼态", ContourMakeup(forehead = 0.7f, chinLight = 0.6f, underEye = 0.7f, cheekLight = 0.3f)),
        ContourPreset("高级脸", ContourMakeup(cheekShade = 0.9f, jaw = 0.6f, temple = 0.5f, noseShade = 0.5f, cheekLight = 0.6f)),
        ContourPreset("小头", ContourMakeup(temple = 0.9f, jaw = 0.7f, forehead = 0.3f)),
    )

    fun contourPreset(name: String): ContourMakeup = (contourPresets.firstOrNull { it.name == name } ?: contourPresets[0]).contour

    // MARK: 腮红

    val blushColors = listOf(
        MakeupSwatch("蜜桃", 0xF7A07E), MakeupSwatch("樱花粉", 0xF7A8C0), MakeupSwatch("玫瑰", 0xD8607A), MakeupSwatch("火龙果", 0xE8457A),
        MakeupSwatch("珊瑚", 0xF58A6E), MakeupSwatch("奶杏", 0xE8B090), MakeupSwatch("元气橘", 0xF58A4A), MakeupSwatch("腮紫", 0xC89AD8),
        MakeupSwatch("砖红", 0xC8604A), MakeupSwatch("豆沙", 0xC87878), MakeupSwatch("肉桂", 0xC88A6A), MakeupSwatch("桃红", 0xF06890),
    )

    fun blushColor(name: String): MakeupColor = (blushColors.firstOrNull { it.name == name } ?: blushColors[0]).color

    class BlushPreset(val name: String, val blush: BlushMakeup)

    private fun blush(name: String, color: String, placement: MKBlushPlacement, glow: Boolean = false, amount: Float = 0.55f) =
        BlushPreset(name, BlushMakeup(blushColor(color), placement, glow, amount))

    val blushPresets = listOf(
        blush("火龙果", "火龙果", MKBlushPlacement.APPLE),
        blush("光感粉", "樱花粉", MKBlushPlacement.APPLE, glow = true, amount = 0.65f),
        blush("氛围", "珊瑚", MKBlushPlacement.SWEEP),
        blush("粉团", "樱花粉", MKBlushPlacement.DOLL, amount = 0.7f),
        blush("腮紫", "腮紫", MKBlushPlacement.UNDER_EYE, amount = 0.6f),
        blush("蜜桃", "蜜桃", MKBlushPlacement.APPLE, amount = 0.6f),
        blush("奶杏", "奶杏", MKBlushPlacement.SWEEP, amount = 0.65f),
        blush("纯欲", "桃红", MKBlushPlacement.UNDER_EYE, amount = 0.5f),
        blush("晒伤", "砖红", MKBlushPlacement.SUNBURN, amount = 0.5f),
        blush("微醺", "玫瑰", MKBlushPlacement.DRUNK, amount = 0.5f),
        blush("元气橘", "元气橘", MKBlushPlacement.APPLE, glow = true),
        blush("高级棕", "肉桂", MKBlushPlacement.SWEEP, amount = 0.6f),
        blush("玫瑰", "玫瑰", MKBlushPlacement.HIGH, amount = 0.5f),
        blush("豆沙", "豆沙", MKBlushPlacement.APPLE, amount = 0.55f),
    )

    fun blushPreset(name: String, amount: Float? = null): BlushMakeup {
        val b = (blushPresets.firstOrNull { it.name == name } ?: blushPresets[0]).blush
        return if (amount != null) b.copy(amount = amount) else b
    }

    // MARK: 妆容（一键整套）

    data class LookPreset(
        val name: String,
        val lip: LipMakeup? = null,
        val brow: BrowMakeup? = null,
        val eye: EyeMakeup = EyeMakeup(),
        val contour: ContourMakeup = ContourMakeup(),
        val blush: BlushMakeup? = null,
    )

    private fun look(name: String, lip: String?, brow: MKBrowShape?, eye: String?, contour: String?, blush: String?,
                     tweak: ((LookPreset) -> LookPreset)? = null): LookPreset {
        var l = LookPreset(name)
        if (lip != null) l = l.copy(lip = (lipPresets.firstOrNull { it.name == lip } ?: lipPresets[0]).lip)
        if (brow != null) l = l.copy(brow = browPreset(brow))
        if (eye != null) l = l.copy(eye = eyePreset(eye))
        if (contour != null) l = l.copy(contour = contourPreset(contour))
        if (blush != null) l = l.copy(blush = blushPreset(blush))
        return tweak?.invoke(l) ?: l
    }

    val looks: List<LookPreset> = listOf(
        look("白开水", "裸感润唇", MKBrowShape.NATURAL, null, null, "蜜桃") { l ->
            l.copy(brow = l.brow?.copy(amount = 0.4f), blush = l.blush?.copy(amount = 0.35f),
                eye = l.eye.copy(aegyo = 0.4f, lashes = LashMakeup(MKLashStyle.NATURAL, 0.45f)))
        },
        look("淡颜", "气质裸粉", MKBrowShape.SOFT, "淡颜", "自然立体", "奶杏") { l -> l.copy(blush = l.blush?.copy(amount = 0.4f)) },
        look("伪素颜", "樱花果冻", MKBrowShape.NATURAL, "淡雅", "光泽感", "光感粉") { l ->
            l.copy(brow = l.brow?.copy(amount = 0.45f), blush = l.blush?.copy(amount = 0.4f), contour = l.contour.copy(forehead = 0.4f))
        },
        look("纯欲", "兔系唇", MKBrowShape.SOFT, "纯欲", "纯欲", "纯欲"),
        look("蜜桃", "水光蜜桃", MKBrowShape.NATURAL, "温柔", "幼态", "蜜桃"),
        look("韩系", "咬唇西柚", MKBrowShape.STRAIGHT, "淡颜", "自然立体", "氛围") { l -> l.copy(blush = l.blush?.copy(amount = 0.4f)) },
        look("日杂", "斩男水红", MKBrowShape.NATURAL, "日杂", null, "晒伤") { l ->
            l.copy(contour = l.contour.copy(freckles = MKFreckleStyle.FEW, freckleAmount = 0.5f))
        },
        look("元气", "蜜橘元气", MKBrowShape.NATURAL, "元气", "幼态", "元气橘"),
        look("甜妹", "元气粉", MKBrowShape.SOFT, "狗狗眼", "Doll", "粉团"),
        look("微醺", "莓果咬唇", MKBrowShape.NATURAL, "微醺", null, "微醺"),
        look("千金", "镜面玫瑰", MKBrowShape.WILLOW, "淡雅", "光泽感", "光感粉") { l ->
            l.copy(eye = l.eye.copy(lashes = LashMakeup(MKLashStyle.DENSE, 0.7f), catchlight = MKCatchlight.TWIN, catchlightAmount = 0.5f))
        },
        look("清冷", "干枯玫瑰", MKBrowShape.WILLOW, "清冷", "高级脸", null),
        look("玫瑰", "丝绒豆沙", MKBrowShape.NATURAL, "猫系", "自然立体", "玫瑰"),
        look("枫叶", "枫叶哑光", MKBrowShape.STRAIGHT, "消肿", "自然立体", "高级棕") { l ->
            l.copy(eye = l.eye.copy(shadow = shadow("枫叶", amount = 0.65f)))
        },
        look("港风", "复古港风", MKBrowShape.WILD, "港风", "混血骨相", "高级棕") { l -> l.copy(blush = l.blush?.copy(amount = 0.4f)) },
        look("复古", "哑光正红", MKBrowShape.ARCH, "灰雾电波", "自然立体", null) { l ->
            l.copy(contour = l.contour.copy(moles = listOf(MKMoleSpot.Lip)))
        },
        look("甜酷", "梅子雾面", MKBrowShape.SWORD, "猫系", "小V脸", "腮紫") { l -> l.copy(blush = l.blush?.copy(amount = 0.4f)) },
        look("欧美", "焦糖哑光", MKBrowShape.ARCH, "欧美", "混血骨相", "高级棕") { l -> l.copy(lip = l.lip?.copy(liner = 0.4f)) },
        look("小烟熏", "酒红镜面", MKBrowShape.SWORD, "小烟熏", "高级脸", null),
        look("混血", "奶茶雾面", MKBrowShape.WILD, "欧美", "混血骨相", "奶杏") { l ->
            l.copy(eye = l.eye.copy(lens = LensMakeup(lensColors[4].color, 0.55f)),
                contour = l.contour.copy(freckles = MKFreckleStyle.NATURAL, freckleAmount = 0.45f))
        },
        look("人鱼姬", "樱花果冻", MKBrowShape.SOFT, "人鱼姬", "光泽感", "腮紫") { l ->
            l.copy(eye = l.eye.copy(lens = LensMakeup(lensColors[7].color, 0.5f)))
        },
        look("星河", "镜面玫瑰", MKBrowShape.NATURAL, "星河", "光泽感", "光感粉"),
    )
}

private fun s01(v: Float, k: Float) = (v * k).coerceIn(0f, 1f)

/** 套上一整套妆（原有的自己点的痣保留）。k 是整体浓淡：1 = 预设本来的样子。 */
fun FaceMakeup.applying(look: MakeupCatalog.LookPreset, strength: Float): FaceMakeup {
    val keptMoles = contour.moles.filterIsInstance<MKMoleSpot.Custom>()
    return copy(
        lip = look.lip, brow = look.brow, eye = look.eye, blush = look.blush,
        contour = look.contour.copy(moles = look.contour.moles + keptMoles),
    ).scaled(strength)
}

/** 所有浓淡一起乘上 k。 */
fun FaceMakeup.scaled(k: Float): FaceMakeup = copy(
    lip = lip?.copy(amount = s01(lip.amount, k), liner = s01(lip.liner, k)),
    brow = brow?.copy(amount = s01(brow.amount, k)),
    blush = blush?.copy(amount = s01(blush.amount, k)),
    eye = eye.scaled(k),
    contour = contour.scaledSculpt(k).copy(freckleAmount = s01(contour.freckleAmount, k)),
)

fun EyeMakeup.scaled(k: Float): EyeMakeup = copy(
    shadow = shadow?.copy(amount = s01(shadow.amount, k)),
    liner = liner?.copy(amount = s01(liner.amount, k)),
    lashes = lashes?.copy(amount = s01(lashes.amount, k)),
    lens = lens?.copy(amount = s01(lens.amount, k)),
    aegyo = s01(aegyo, k),
    foldAmount = s01(foldAmount, k),
    catchlightAmount = s01(catchlightAmount, k),
)

/** 立体 / 高光 / 阴影这几项一起乘上 k（雀斑和痣不动）。 */
fun ContourMakeup.scaledSculpt(k: Float): ContourMakeup = copy(
    noseShade = s01(noseShade, k), noseLight = s01(noseLight, k), forehead = s01(forehead, k), cheekLight = s01(cheekLight, k),
    chinLight = s01(chinLight, k), cupid = s01(cupid, k), underEye = s01(underEye, k), jaw = s01(jaw, k),
    cheekShade = s01(cheekShade, k), temple = s01(temple, k),
)

/** 换一套立体修容，雀斑和痣保留。 */
fun ContourMakeup.withSculpt(other: ContourMakeup): ContourMakeup = copy(
    noseShade = other.noseShade, noseLight = other.noseLight, forehead = other.forehead, cheekLight = other.cheekLight,
    chinLight = other.chinLight, cupid = other.cupid, underEye = other.underEye, jaw = other.jaw,
    cheekShade = other.cheekShade, temple = other.temple,
)
