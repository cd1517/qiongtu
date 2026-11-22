package com.taoo.xiutu.ai

import android.graphics.Bitmap

// 占位：待移植（AIShared.swift 的 AIOneShot）。签名不要改。
object AIOneShot {
    fun prepared(image: Bitmap, maxSide: Int): Bitmap = image
    fun size(matching: Bitmap): String = "1024x1536"
    /** 用当前服务商，按"指令 + 参考图"生成一张图。第一张是主图。 */
    suspend fun run(prompt: String, images: List<Bitmap>, size: String = "1024x1536"): Pair<Bitmap, AIUsage?> = throw AIError("未实现")
}
