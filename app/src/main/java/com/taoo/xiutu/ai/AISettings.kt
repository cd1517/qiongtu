package com.taoo.xiutu.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 内置型号：ID 和一句话名字。 */
data class AIModel(val id: String, val note: String)

/** 可以接入的 AI 服务商。 */
enum class AIProvider(val raw: String) {
    OPENAI("openai"), GEMINI("gemini"), MINIMAX("minimax"), GROK("grok"),
    QWEN("qwen"), DOUBAO("doubao"), FLUX("flux"), CUSTOM("custom");

    val title: String
        get() = when (this) {
            OPENAI -> "OpenAI"
            GEMINI -> "Google Gemini"
            MINIMAX -> "MiniMax"
            GROK -> "xAI Grok"
            QWEN -> "通义千问（阿里云百炼）"
            DOUBAO -> "豆包 Seedream（火山方舟）"
            FLUX -> "FLUX（Black Forest Labs）"
            CUSTOM -> "自定义（OpenAI 兼容）"
        }

    val shortTitle: String
        get() = when (this) {
            OPENAI -> "OpenAI"
            GEMINI -> "Gemini"
            MINIMAX -> "MiniMax"
            GROK -> "Grok"
            QWEN -> "千问"
            DOUBAO -> "豆包"
            FLUX -> "FLUX"
            CUSTOM -> "自定义"
        }

    val defaultBaseURL: String
        get() = when (this) {
            OPENAI -> "https://api.openai.com/v1"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            MINIMAX -> "https://api.minimax.io/v1"
            GROK -> "https://api.x.ai/v1"
            QWEN -> "https://dashscope.aliyuncs.com/api/v1"
            DOUBAO -> "https://ark.cn-beijing.volces.com/api/v3"
            FLUX -> "https://api.bfl.ai/v1"
            CUSTOM -> ""
        }

    /** 官方的几个接入地址（标题 to 地址）。不同地域的 Key 不通用，第一个是默认的。 */
    val baseURLChoices: List<Pair<String, String>>
        get() = when (this) {
            MINIMAX -> listOf("国际站" to "https://api.minimax.io/v1", "国内站" to "https://api.minimaxi.com/v1")
            QWEN -> listOf("北京" to "https://dashscope.aliyuncs.com/api/v1", "新加坡" to "https://dashscope-intl.aliyuncs.com/api/v1")
            DOUBAO -> listOf("北京" to "https://ark.cn-beijing.volces.com/api/v3", "海外 BytePlus" to "https://ark.ap-southeast.bytepluses.com/api/v3")
            FLUX -> listOf("全球" to "https://api.bfl.ai/v1", "欧洲" to "https://api.eu.bfl.ai/v1", "美国" to "https://api.us.bfl.ai/v1")
            else -> emptyList()
        }

    /** 内置的型号列表（2026-11 查官方文档得到）。 */
    val builtinModels: List<AIModel>
        get() = when (this) {
            OPENAI -> listOf(
                AIModel("gpt-image-2.6-sunburst", "2.6 精细版，改图最准"),
                AIModel("gpt-image-2.6-flare", "2.6 快速版"),
                AIModel("gpt-image-2.5-sunburst", "2.5 精细版"),
                AIModel("gpt-image-2.5-flare", "2.5 快速版"),
                AIModel("gpt-image-2", "旧版，2027-02 停用"),
            )
            GEMINI -> listOf(
                AIModel("gemini-nano-banana-2.1", "Nano Banana 2.1，官方推荐"),
                AIModel("gemini-3-pro-image", "Nano Banana Pro"),
                AIModel("gemini-3.1-flash-image", "Nano Banana 2"),
                AIModel("gemini-3.1-flash-lite-image", "Nano Banana 2 Lite，便宜"),
                AIModel("gemini-2.5-flash-image", "旧版"),
            )
            MINIMAX -> listOf(
                AIModel("MiniMax-H3-Image", "标准版"),
                AIModel("MiniMax-H3-Image-Highspeed", "高速版"),
            )
            GROK -> listOf(
                AIModel("grok-imagine-image-2.0", "Imagine 2.0"),
                AIModel("grok-imagine-image", "旧版，便宜"),
            )
            QWEN -> listOf(
                AIModel("qwen-image-3.0-pro", "3.0 Pro"),
                AIModel("qwen-image-3.0", "3.0 标准版"),
                AIModel("qwen-image-2.1-pro", "2.1 Pro"),
                AIModel("qwen-image-2.1-turbo", "2.1 Turbo，便宜"),
                AIModel("qwen-image-2.0-pro", "2.0 Pro"),
                AIModel("qwen-image-2.0", "2.0 加速版"),
            )
            DOUBAO -> listOf(
                AIModel("doubao-seedream-5-5-261028", "Seedream 5.5"),
                AIModel("doubao-seedream-5-0-pro-260628", "Seedream 5.0 Pro"),
                AIModel("doubao-seedream-5-0-flash-260915", "Seedream 5.0 Flash，便宜"),
            )
            FLUX -> listOf(
                AIModel("flux-3-image", "FLUX 3 Image"),
                AIModel("flux-3-image-klein", "FLUX 3 Klein，快"),
                AIModel("flux-kontext-pro", "Kontext Pro，旧版"),
                AIModel("flux-kontext-max", "Kontext Max，旧版"),
            )
            CUSTOM -> emptyList()
        }

    /** 能不能联网拉取型号列表。 */
    val canListModels: Boolean get() = this == OPENAI || this == GEMINI || this == GROK || this == CUSTOM

    /** 涂抹区域能不能作为蒙版直接传给接口。不能的改用"标红参考图"。 */
    val supportsMask: Boolean get() = this == OPENAI || this == CUSTOM

    /** 有没有不带输入图的文生图接口（AIClient.generate）。 */
    val canGenerate: Boolean get() = this != GEMINI

    /** 这家按什么币种结算。 */
    val currency: AICurrency get() = if (this == QWEN || this == DOUBAO) AICurrency.CNY else AICurrency.USD

    companion object {
        fun from(raw: String?): AIProvider? = entries.firstOrNull { it.raw == raw }
    }
}

/** 一家服务商的累计用量。 */
@Serializable
data class AIProviderStats(
    val requests: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val images: Int = 0,
    /** 能算出价格的那些请求的费用合计，按这家的结算币种。 */
    val cost: Double = 0.0,
    /** 其中有几次请求算不出价格。 */
    val unpriced: Int = 0,
)

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
) {
    // Key 不进 toString，免得被意外打进日志
    override fun toString(): String = "AIEndpoint(provider=$provider, model=$model, baseURL=$baseURL)"
}

/** 一家服务商的配置（不含 Key）。 */
@Serializable
data class ProviderConfig(
    val model: String,
    val baseURL: String,
    val fetchedModels: List<String> = emptyList(),
    /** 测试连接有没有通过。null = 老数据，算通过。 */
    val verified: Boolean? = null,
)

/**
 * API Key 用 Android Keystore 里的 AES-GCM 密钥加密后存进 SharedPreferences。
 * 密钥本身不出 Keystore；明文 Key 不落盘、不打日志。
 */
private object KeyVault {
    private const val ALIAS = "com.taoo.xiutu.apikey"
    private const val PREFS = "xiutu.apikeys"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun get(account: String): String {
        val stored = prefs?.getString(account, null) ?: return ""
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            if (bytes.size <= 12) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun set(value: String, account: String) {
        val p = prefs ?: return
        if (value.isEmpty()) {
            p.edit().remove(account).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            p.edit().putString(account, Base64.getEncoder().encodeToString(out)).apply()
        } catch (_: Exception) {
            p.edit().remove(account).apply()
        }
    }
}

@Serializable
private data class StoredSettings(
    val active: String,
    val quality: String,
    val configs: Map<String, ProviderConfig>,
)

/** AI 设置。状态都是 Compose 可观察的，主页和设置页会跟着刷新。 */
object AISettings {
    val qualities: List<Pair<String, String>> = listOf(
        "auto" to "自动", "low" to "低（省钱）", "medium" to "中", "high" to "高",
    )

    private const val STORE_KEY = "ai.settings.v1"
    private const val USAGE_KEY = "ai.usage.v1"
    private const val HIRES_OFF_KEY = "ai.hiresOff"

    private var prefs: SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeState by mutableStateOf(AIProvider.OPENAI)
    val active: AIProvider get() = activeState
    val activeShortTitle: String get() = active.shortTitle
    val activeModel: String get() = config(active).model

    private var qualityState by mutableStateOf("auto")
    val quality: String get() = qualityState

    private var hiresState by mutableStateOf(true)
    /** 高清输出：按图片实际大小要结果，更清晰也更贵。 */
    val hires: Boolean get() = hiresState

    private var configs by mutableStateOf(defaultConfigs())
    private var keys by mutableStateOf<Map<AIProvider, String>>(emptyMap())

    /** 各家的累计用量。每次请求成功后由 AIClient 统一记进来。 */
    var usage by mutableStateOf<Map<AIProvider, AIProviderStats>>(emptyMap())
        private set

    private fun defaultConfigs(): Map<AIProvider, ProviderConfig> =
        AIProvider.entries.associateWith { ProviderConfig(it.builtinModels.firstOrNull()?.id ?: "", it.defaultBaseURL) }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)
        prefs = p
        KeyVault.init(context.applicationContext)
        val loaded = defaultConfigs().toMutableMap()
        p.getString(STORE_KEY, null)?.let { text ->
            runCatching { json.decodeFromString<StoredSettings>(text) }.getOrNull()?.let { s ->
                AIProvider.from(s.active)?.let { activeState = it }
                qualityState = s.quality
                for ((k, v) in s.configs) AIProvider.from(k)?.let { loaded[it] = v }
            }
        }
        configs = loaded
        hiresState = !p.getBoolean(HIRES_OFF_KEY, false)
        keys = AIProvider.entries.associateWith { KeyVault.get(it.raw) }
        usage = loadUsage(p)
    }

    private fun save() {
        val p = prefs ?: return
        val s = StoredSettings(active.raw, quality, configs.mapKeys { it.key.raw })
        p.edit().putString(STORE_KEY, json.encodeToString(StoredSettings.serializer(), s)).apply()
    }

    fun setActive(provider: AIProvider) {
        activeState = provider
        save()
    }

    // 用量统计

    private fun loadUsage(p: SharedPreferences): Map<AIProvider, AIProviderStats> {
        val text = p.getString(USAGE_KEY, null) ?: return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, AIProviderStats>>(text) }.getOrNull() ?: return emptyMap()
        return stored.mapNotNull { (k, v) -> AIProvider.from(k)?.let { it to v } }.toMap()
    }

    private fun saveUsage() {
        val p = prefs ?: return
        p.edit().putString(USAGE_KEY, json.encodeToString(usage.mapKeys { it.key.raw })).apply()
    }

    fun stats(provider: AIProvider): AIProviderStats = usage[provider] ?: AIProviderStats()

    /** 由 AIClient 在请求成功后调用；别的地方不要再调，否则会重复计数。 */
    fun addUsage(u: AIUsage) {
        val s = stats(u.provider)
        val amount = u.amount
        usage = usage + (u.provider to s.copy(
            requests = s.requests + 1,
            inputTokens = s.inputTokens + u.inputText + u.inputImage,
            outputTokens = s.outputTokens + u.output,
            images = s.images + u.images,
            cost = s.cost + (amount ?: 0.0),
            unpriced = s.unpriced + if (amount == null) 1 else 0,
        ))
        saveUsage()
    }

    /** provider 为 null 时全部清零。 */
    fun resetUsage(provider: AIProvider? = null) {
        usage = if (provider != null) usage - provider else emptyMap()
        saveUsage()
    }

    /** 按某个币种结算的几家加起来。 */
    fun usageTotal(currency: AICurrency): AIProviderStats {
        var t = AIProviderStats()
        for ((p, s) in usage) {
            if (p.currency != currency) continue
            t = t.copy(
                requests = t.requests + s.requests,
                inputTokens = t.inputTokens + s.inputTokens,
                outputTokens = t.outputTokens + s.outputTokens,
                images = t.images + s.images,
                cost = t.cost + s.cost,
                unpriced = t.unpriced + s.unpriced,
            )
        }
        return t
    }

    /** 累计估算花费（美元）和总请求数。 */
    val spendTotal: Double get() = usageTotal(AICurrency.USD).cost
    val spendCount: Int get() = usage.values.sumOf { it.requests }

    /** iOS 版留下的接口：AIClient 已经统一记账，这里不再累加。 */
    @Suppress("UNUSED_PARAMETER")
    fun addSpend(cost: Double) {}

    fun resetSpend() = resetUsage()

    fun setHires(on: Boolean) {
        hiresState = on
        prefs?.edit()?.putBoolean(HIRES_OFF_KEY, !on)?.apply()
    }

    fun setQuality(value: String) {
        qualityState = value
        save()
    }

    fun config(provider: AIProvider): ProviderConfig =
        configs[provider] ?: ProviderConfig("", provider.defaultBaseURL)

    fun update(provider: AIProvider, change: (ProviderConfig) -> ProviderConfig) {
        configs = configs + (provider to change(config(provider)))
        save()
    }

    fun key(provider: AIProvider): String = keys[provider] ?: ""

    fun setKey(value: String, provider: AIProvider) {
        val trimmed = value.trim()
        if (trimmed == key(provider)) return
        // Key 换了就要重新测试
        update(provider) { it.copy(verified = false) }
        keys = keys + (provider to trimmed)
        KeyVault.set(trimmed, provider.raw)
    }

    /** 这家的 Key 有没有通过测试连接。 */
    fun isVerified(provider: AIProvider): Boolean =
        key(provider).isNotEmpty() && (config(provider).verified ?: true)

    /** 测试这家的连接；通过了才算可用。返回 null = 通过，否则是没通过的原因。 */
    suspend fun verify(provider: AIProvider): String? {
        val endpoint = probeEndpoint(provider) ?: return "还没有填 Key。"
        return try {
            val models = AIClient.verify(endpoint)
            withContext(Dispatchers.Main.immediate) {
                // 等结果的这会儿 Key 或地址被改过，这次结果不算数
                if (endpoint.key != key(provider) || endpoint.baseURL != config(provider).baseURL.trim()) {
                    "Key 或接口地址刚改过，请再测一次。"
                } else {
                    update(provider) { c -> c.copy(verified = true, fetchedModels = if (provider.canListModels) models else c.fetchedModels) }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? AIError)?.status ?: 0
            withContext(Dispatchers.Main.immediate) {
                if (status == 401 || status == 403) update(provider) { it.copy(verified = false) }
            }
            (e as? AIError)?.text ?: "测试失败。"
        }
    }

    /** 不等结果地测一次（离开设置页时用）。 */
    fun verifyInBackground(provider: AIProvider) {
        scope.launch { verify(provider) }
    }

    fun isActiveConfigured(): Boolean = isConfigured(active)

    fun isConfigured(provider: AIProvider): Boolean {
        val c = config(provider)
        return key(provider).isNotEmpty() && c.model.isNotEmpty() && c.baseURL.isNotEmpty() && isVerified(provider)
    }

    /** 内置型号 + 联网拉到的型号 + 当前手填的型号，去重。 */
    fun modelChoices(provider: AIProvider): List<String> {
        val c = config(provider)
        return (provider.builtinModels.map { it.id } + c.fetchedModels + c.model).filter { it.isNotEmpty() }.distinct()
    }

    /** 当前（或指定）服务商的请求配置；没配好时为 null。 */
    fun endpoint(provider: AIProvider? = null): AIEndpoint? {
        val p = provider ?: active
        if (!isConfigured(p)) return null
        val c = config(p)
        return AIEndpoint(p, key(p), c.model, c.baseURL.trim(), quality, hires)
    }

    /** 只要有 Key 就能用来测连接、拉型号。 */
    fun probeEndpoint(provider: AIProvider): AIEndpoint? {
        val c = config(provider)
        if (key(provider).isEmpty() || c.baseURL.isEmpty()) return null
        return AIEndpoint(provider, key(provider), c.model, c.baseURL.trim(), quality, hires)
    }
}
