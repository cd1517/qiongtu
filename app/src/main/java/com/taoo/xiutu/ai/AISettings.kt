package com.taoo.xiutu.ai

import android.content.Context

// 占位：待移植（AISettings.swift）。下面列出的成员是别的模块会调用的，签名不要改。
enum class AIProvider { OPENAI;
    val shortTitle: String get() = "OpenAI"
    /** 支持蒙版（只有 OpenAI）。 */
    val supportsMask: Boolean get() = this == OPENAI
}

/** 发请求时用的一份只读配置。 */
data class AIEndpoint(
    val provider: AIProvider,
    val key: String,
    val model: String,
    val baseURL: String,
    var quality: String,
    var hires: Boolean = true,
    /** 要求尽量忠实于输入图。 */
    var highFidelity: Boolean = false,
)

object AISettings {
    fun init(context: Context) {}
    val active: AIProvider get() = AIProvider.OPENAI
    val activeShortTitle: String get() = active.shortTitle
    val activeModel: String get() = ""
    fun isActiveConfigured(): Boolean = false
    fun isConfigured(provider: AIProvider): Boolean = false
    /** 当前（或指定）服务商的请求配置；没填 Key 时为 null。 */
    fun endpoint(provider: AIProvider? = null): AIEndpoint? = null
    fun addUsage(u: AIUsage) {}
}
