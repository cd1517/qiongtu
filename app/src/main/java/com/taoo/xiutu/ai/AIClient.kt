package com.taoo.xiutu.ai

// 占位：待移植（AIClient.swift）。下面列出的成员是别的模块会调用的，签名不要改。

/** 发给服务商的一次改图请求。 */
data class AIPayload(
    val prompt: String,
    /** 要改的图。OpenAI 用 PNG，其他家用 JPEG。 */
    val image: ByteArray,
    val imageMime: String,
    /** OpenAI 蒙版：透明的地方 = 允许修改的区域。 */
    val maskPNG: ByteArray?,
    /** 给不支持蒙版的服务商用：同一张图，把要改的区域标红。 */
    val markedJPEG: ByteArray?,
    /** 输出尺寸，例如 "1024x1536"。 */
    val size: String,
    /** 额外的参考图。 */
    val extraImages: List<ByteArray> = emptyList(),
)

/** 一次请求的实际用量。 */
data class AIUsage(val model: String, val inputText: Int, val inputImage: Int, val output: Int) {
    val cost: Double? get() = null
    val summary: String get() = ""
}

class AIError(val text: String, val status: Int = 0, val raw: String = "") : Exception(text)

object AIClient {
    /** 取走最近一次成功请求的用量（取完清空）。 */
    fun takeLastUsage(): AIUsage? = null
    /** 改图，返回图片字节。 */
    suspend fun edit(endpoint: AIEndpoint, payload: AIPayload): ByteArray = throw AIError("未实现")
    /** 文生图（贴纸）。 */
    suspend fun generate(endpoint: AIEndpoint, prompt: String, size: String, transparent: Boolean): ByteArray = throw AIError("未实现")
}
