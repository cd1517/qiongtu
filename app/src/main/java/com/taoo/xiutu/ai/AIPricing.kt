package com.taoo.xiutu.ai

import java.util.Locale

/** 结算币种。各家按自己的币种显示，不互相换算。 */
enum class AICurrency {
    USD, CNY;

    val symbol: String get() = if (this == USD) "$" else "¥"
}

/**
 * 各家的价目表，全部集中在这一个文件里（2026-11-22 从官方页面读到的数字，和 iOS 版一致）。
 * 读不到可靠价格的型号不估价（返回 null，界面上显示"—"）。
 */
object AIPricing {
    /** 按 token 计价的型号：每百万 token 的美元价。 */
    private class TokenRate(
        val prefix: String,
        val inputText: Double,
        val inputImage: Double,
        val outputImage: Double,
        /** 输出里文字 / 思考部分的价格（只有 Gemini 分开算）。 */
        val outputText: Double = 0.0,
        /** 输出按张算的固定价（Gemini 2.5 Flash Image）。 */
        val perImage: Double? = null,
    )

    private val tokenRates: Map<AIProvider, List<TokenRate>> = mapOf(
        AIProvider.OPENAI to listOf(
            TokenRate("gpt-image-2.5", 5.0, 8.0, 30.0),
            TokenRate("gpt-image-2.6", 5.0, 8.0, 30.0),
        ),
        AIProvider.GEMINI to listOf(
            TokenRate("gemini-nano-banana-2.1", 1.5, 1.5, 30.0, outputText = 7.5),
            // 名字更长的排前面，否则会被 "gemini-3.1-flash-image" 先匹配走
            TokenRate("gemini-3.1-flash-lite-image", 0.25, 0.25, 30.0, outputText = 1.5),
            TokenRate("gemini-3.1-flash-image", 0.5, 0.5, 60.0, outputText = 3.0),
            TokenRate("gemini-3-pro-image", 2.0, 2.0, 120.0, outputText = 12.0),
            TokenRate("gemini-2.5-flash-image", 0.3, 0.3, 0.0, perImage = 0.039),
        ),
        AIProvider.MINIMAX to listOf(
            TokenRate("MiniMax-H3-Image-Highspeed", 2.3, 3.0, 7.6),
            TokenRate("MiniMax-H3-Image", 2.3, 1.5, 3.8),
        ),
    )

    /** 按张计价的型号。low / high 是输出图总像素不超过 / 超过 split 时的单价。 */
    private class ImageRate(
        val prefix: String,
        val input: Double,
        /** 前几张输入图不收费。 */
        val freeInputs: Int = 0,
        val low: Double,
        val high: Double,
        val split: Int = 0,
    )

    private val grok = listOf(
        ImageRate("grok-imagine-image-2.0", input = 0.0, low = 0.04, high = 0.04),
        ImageRate("grok-imagine-image", input = 0.0, low = 0.02, high = 0.02),
    )

    /** 千问 3.0 的 1k / 2k 档以 2,250,000 像素为界。 */
    private val qwenBeijing = listOf(
        ImageRate("qwen-image-3.0-pro", input = 0.02, low = 0.25, high = 0.5, split = 2_250_000),
        ImageRate("qwen-image-3.0", input = 0.02, low = 0.18, high = 0.18),
        ImageRate("qwen-image-2.1-pro", input = 0.0, low = 0.25, high = 0.25),
        ImageRate("qwen-image-2.1-turbo", input = 0.0, low = 0.1, high = 0.1),
        ImageRate("qwen-image-2.0-pro", input = 0.0, low = 0.5, high = 0.5),
        ImageRate("qwen-image-2.0", input = 0.0, low = 0.2, high = 0.2),
    )

    private val qwenSingapore = listOf(
        ImageRate("qwen-image-3.0-pro", input = 0.022483, low = 0.299768, high = 0.562065, split = 2_250_000),
        ImageRate("qwen-image-3.0", input = 0.022483, low = 0.224826, high = 0.224826),
        ImageRate("qwen-image-2.1-pro", input = 0.0, low = 0.283404, high = 0.283404),
        ImageRate("qwen-image-2.1-turbo", input = 0.0, low = 0.113362, high = 0.113362),
        ImageRate("qwen-image-2.0-pro", input = 0.0, low = 0.550443, high = 0.550443),
        ImageRate("qwen-image-2.0", input = 0.0, low = 0.256873, high = 0.256873),
    )

    /** 豆包以 2.61M 像素分档。只有国内方舟的人民币价；海外 BytePlus 不估。 */
    private val doubao = listOf(
        ImageRate("doubao-seedream-5-5", input = 0.05, low = 0.13, high = 0.23, split = 2_610_000),
        ImageRate("doubao-seedream-5-0-pro", input = 0.02, freeInputs = 1, low = 0.3, high = 0.6, split = 2_610_000),
        ImageRate("doubao-seedream-5-0-flash", input = 0.0, low = 0.12, high = 0.12),
        ImageRate("doubao-seedream-5-0-lite", input = 0.0, low = 0.22, high = 0.22),
        ImageRate("doubao-seedream-4-5", input = 0.0, low = 0.25, high = 0.25),
        ImageRate("doubao-seedream-4-0", input = 0.0, low = 0.2, high = 0.2),
    )

    /** 这次请求的估算费用，按这家服务商的结算币种；没有可靠价格时返回 null。 */
    fun cost(u: AIUsage): Double? {
        // 服务商自己报了费用的，以它为准
        u.reportedCost?.let { return it }
        return when (u.provider) {
            AIProvider.OPENAI, AIProvider.GEMINI, AIProvider.MINIMAX -> {
                val rate = tokenRates[u.provider]?.firstOrNull { u.model.startsWith(it.prefix) } ?: return null
                val imageOut = maxOf(0, u.output - u.outputText)
                var total = u.inputText * rate.inputText + u.inputImage * rate.inputImage + u.outputText * rate.outputText
                if (rate.perImage == null) total += imageOut * rate.outputImage
                total / 1_000_000 + (rate.perImage ?: 0.0) * u.images
            }
            AIProvider.GROK -> perImage(u, grok)
            AIProvider.QWEN -> perImage(u, if (u.overseas) qwenSingapore else qwenBeijing)
            AIProvider.DOUBAO -> if (u.overseas) null else perImage(u, doubao)
            AIProvider.FLUX, AIProvider.CUSTOM -> null
        }
    }

    private fun perImage(u: AIUsage, table: List<ImageRate>): Double? {
        val rate = table.firstOrNull { u.model.startsWith(it.prefix) } ?: return null
        // 不知道输出多大时按低档算
        val output = if (rate.split > 0 && u.outputPixels > rate.split) rate.high else rate.low
        return maxOf(0, u.inputImages - rate.freeInputs) * rate.input + u.images * output
    }

    // 费率说明

    private fun money(v: Double, currency: AICurrency): String {
        var text = String.format(Locale.US, "%.4f", v)
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
        return currency.symbol + text
    }

    /** 这家的这个型号怎么收费，写成一行。没有价目时返回 null。 */
    fun rateText(provider: AIProvider, model: String, overseas: Boolean): String? {
        val currency = provider.currency
        fun m(v: Double) = money(v, currency)
        when (provider) {
            AIProvider.OPENAI, AIProvider.GEMINI, AIProvider.MINIMAX -> {
                val r = tokenRates[provider]?.firstOrNull { model.startsWith(it.prefix) } ?: return null
                val parts = mutableListOf<String>()
                if (r.inputText == r.inputImage) {
                    parts += "输入 ${m(r.inputText)}"
                } else {
                    parts += "文字输入 ${m(r.inputText)}"
                    parts += "图片输入 ${m(r.inputImage)}"
                }
                if (r.outputText > 0) parts += "文字输出 ${m(r.outputText)}"
                r.perImage?.let { per -> return parts.joinToString(" · ") + "（每百万 token）· 出图 ${m(per)}/张" }
                parts += "图片输出 ${m(r.outputImage)}"
                return parts.joinToString(" · ") + "（每百万 token）"
            }
            AIProvider.GROK -> {
                val r = grok.firstOrNull { model.startsWith(it.prefix) } ?: return null
                return "出图 ${m(r.low)}/张（以接口返回的实际费用为准）"
            }
            AIProvider.QWEN, AIProvider.DOUBAO -> {
                if (provider == AIProvider.DOUBAO && overseas) return null
                val table = if (provider == AIProvider.QWEN) (if (overseas) qwenSingapore else qwenBeijing) else doubao
                val r = table.firstOrNull { model.startsWith(it.prefix) } ?: return null
                var text = "出图 ${m(r.low)}/张"
                if (r.split > 0 && r.high != r.low) {
                    text += "，超过 ${String.format(Locale.US, "%.2f", r.split / 1_000_000.0)} 百万像素 ${m(r.high)}/张"
                }
                if (r.input > 0) {
                    text += " · 输入图 ${m(r.input)}/张"
                    if (r.freeInputs > 0) text += "（前 ${r.freeInputs} 张免费）"
                }
                return text
            }
            AIProvider.FLUX -> return "按接口返回的实际扣费计（1 credit = $0.01）"
            AIProvider.CUSTOM -> return null
        }
    }
}
