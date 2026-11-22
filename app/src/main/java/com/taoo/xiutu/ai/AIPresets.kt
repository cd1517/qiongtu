package com.taoo.xiutu.ai

/** 一条快捷指令。 */
data class AIPreset(
    val title: String,
    val symbol: String,
    val prompt: String,
    /** 没涂抹时自动只处理头部（修跑焦）。 */
    val faceCrop: Boolean = false,
    /** 美发：没涂抹时自动把头和头发这一块发给 AI。 */
    val hairCrop: Boolean = false,
    /** 只采用 AI 的光影和颜色、细节保留原图。 */
    val toneOnly: Boolean = false,
)

// 占位：待移植（照搬 iOS AIEdit.swift 的全部分组和指令）
enum class AIPresetGroup(val title: String, val symbol: String) {
    FIX("修复", "wand.and.stars"),
    LIGHT("光线", "lightbulb"),
    REMOVE("消除", "eraser"),
    COMPOSE("构图", "crop"),
    MASTER("大师", "camera.aperture"),
    CAMERA("相机", "camera"),
    BODY("AI 美体", "figure.stand"),
    HAIR_STYLE("发型", "scissors"),
    HAIR_COLOR("染发", "paintpalette"),
    HAIR_CARE("发质发量", "wand.and.stars");

    val isHair get() = this in hairGroups
    val menuTitle get() = if (this == CAMERA) "相机质感" else title
    val presets: List<AIPreset> get() = listOf(AIPreset(title, symbol, ""))

    companion object {
        val hairGroups = listOf(HAIR_STYLE, HAIR_COLOR, HAIR_CARE)
        val editGroups = listOf(FIX, LIGHT, REMOVE, COMPOSE, MASTER, CAMERA)
    }
}
