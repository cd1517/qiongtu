import Foundation

/// 结算币种。各家按自己的币种显示，不互相换算。
enum AICurrency: String, Codable, CaseIterable {
    case usd, cny

    var symbol: String { self == .usd ? "$" : "¥" }
}

/// 各家的价目表，全部集中在这一个文件里。
/// 都是 2026-11-22 从官方页面读到的数字；价格变了只改这里。读不到可靠价格的型号不估价（返回 nil，界面上显示"—"）。
///
/// - OpenAI：沿用之前查到的 GPT-Image 2.5 / 2.6 价目（文字输入 $5、图片输入 $8、图片输出 $30，每百万 token）。
/// - Gemini：https://ai.google.dev/gemini-api/docs/pricing （Standard / Paid Tier）
/// - MiniMax：https://platform.minimax.io/docs/pricing/overview
/// - xAI：https://docs.x.ai/developers/models （按张；接口返回里的 cost_in_usd_ticks 是实际费用，优先用它）
/// - 通义千问：https://help.aliyun.com/zh/model-studio/model-pricing （按张，人民币；北京和新加坡两套价）
/// - 豆包 Seedream：https://docs.volcengine.com/docs/ark/model-pricing （按张，人民币，页面更新于 2026-11-19）
/// - FLUX：https://docs.bfl.ml/quick_start/pricing （1 credit = $0.01；接口返回里的 cost 是实际扣的 credit，直接用它，不另外估）
enum AIPricing {
    /// 按 token 计价的型号：每百万 token 的美元价。
    private struct TokenRate {
        let prefix: String
        let inputText: Double
        let inputImage: Double
        let outputImage: Double
        /// 输出里文字 / 思考部分的价格（只有 Gemini 分开算）。
        var outputText: Double = 0
        /// 输出按张算的固定价（Gemini 2.5 Flash Image）。
        var perImage: Double? = nil
    }

    private static let tokenRates: [AIProvider: [TokenRate]] = [
        .openai: [
            TokenRate(prefix: "gpt-image-2.5", inputText: 5, inputImage: 8, outputImage: 30),
            TokenRate(prefix: "gpt-image-2.6", inputText: 5, inputImage: 8, outputImage: 30),
        ],
        .gemini: [
            TokenRate(prefix: "gemini-nano-banana-2.1", inputText: 1.5, inputImage: 1.5, outputImage: 30, outputText: 7.5),
            // 名字更长的要排在前面，否则会被 "gemini-3.1-flash-image" 先匹配走。
            TokenRate(prefix: "gemini-3.1-flash-lite-image", inputText: 0.25, inputImage: 0.25, outputImage: 30, outputText: 1.5),
            TokenRate(prefix: "gemini-3.1-flash-image", inputText: 0.5, inputImage: 0.5, outputImage: 60, outputText: 3),
            TokenRate(prefix: "gemini-3-pro-image", inputText: 2, inputImage: 2, outputImage: 120, outputText: 12),
            TokenRate(prefix: "gemini-2.5-flash-image", inputText: 0.3, inputImage: 0.3, outputImage: 0, perImage: 0.039),
        ],
        .minimax: [
            TokenRate(prefix: "MiniMax-H3-Image-Highspeed", inputText: 2.3, inputImage: 3, outputImage: 7.6),
            TokenRate(prefix: "MiniMax-H3-Image", inputText: 2.3, inputImage: 1.5, outputImage: 3.8),
        ],
    ]

    /// 按张计价的型号。low / high 是输出图总像素不超过 / 超过 split 时的单价；不分档的两个填一样。
    private struct ImageRate {
        let prefix: String
        let input: Double
        /// 前几张输入图不收费。
        var freeInputs = 0
        let low: Double
        let high: Double
        var split = 0
    }

    private static let grok: [ImageRate] = [
        ImageRate(prefix: "grok-imagine-image-2.0", input: 0, low: 0.04, high: 0.04),
        ImageRate(prefix: "grok-imagine-image", input: 0, low: 0.02, high: 0.02),
    ]

    /// 千问 3.0 的 1k / 2k 档以 2,250,000 像素为界。
    private static let qwenBeijing: [ImageRate] = [
        ImageRate(prefix: "qwen-image-3.0-pro", input: 0.02, low: 0.25, high: 0.5, split: 2_250_000),
        ImageRate(prefix: "qwen-image-3.0", input: 0.02, low: 0.18, high: 0.18),
        ImageRate(prefix: "qwen-image-2.1-pro", input: 0, low: 0.25, high: 0.25),
        ImageRate(prefix: "qwen-image-2.1-turbo", input: 0, low: 0.1, high: 0.1),
        ImageRate(prefix: "qwen-image-2.0-pro", input: 0, low: 0.5, high: 0.5),
        ImageRate(prefix: "qwen-image-2.0", input: 0, low: 0.2, high: 0.2),
    ]

    private static let qwenSingapore: [ImageRate] = [
        ImageRate(prefix: "qwen-image-3.0-pro", input: 0.022483, low: 0.299768, high: 0.562065, split: 2_250_000),
        ImageRate(prefix: "qwen-image-3.0", input: 0.022483, low: 0.224826, high: 0.224826),
        ImageRate(prefix: "qwen-image-2.1-pro", input: 0, low: 0.283404, high: 0.283404),
        ImageRate(prefix: "qwen-image-2.1-turbo", input: 0, low: 0.113362, high: 0.113362),
        ImageRate(prefix: "qwen-image-2.0-pro", input: 0, low: 0.550443, high: 0.550443),
        ImageRate(prefix: "qwen-image-2.0", input: 0, low: 0.256873, high: 0.256873),
    ]

    /// 豆包的分档以 2.61M 像素为界（官方写的是 P ≤ 2.61M）。只有国内方舟的人民币价；海外 BytePlus 不估。
    private static let doubao: [ImageRate] = [
        ImageRate(prefix: "doubao-seedream-5-5", input: 0.05, low: 0.13, high: 0.23, split: 2_610_000),
        ImageRate(prefix: "doubao-seedream-5-0-pro", input: 0.02, freeInputs: 1, low: 0.3, high: 0.6, split: 2_610_000),
        ImageRate(prefix: "doubao-seedream-5-0-flash", input: 0, low: 0.12, high: 0.12),
        ImageRate(prefix: "doubao-seedream-5-0-lite", input: 0, low: 0.22, high: 0.22),
        ImageRate(prefix: "doubao-seedream-4-5", input: 0, low: 0.25, high: 0.25),
        ImageRate(prefix: "doubao-seedream-4-0", input: 0, low: 0.2, high: 0.2),
    ]

    /// 这次请求的估算费用，按这家服务商的结算币种；没有可靠价格时返回 nil。
    static func cost(_ u: AIUsage) -> Double? {
        // 服务商自己报了费用的，以它为准。
        if let reported = u.reportedCost { return reported }
        switch u.provider {
        case .openai, .gemini, .minimax:
            guard let rate = tokenRates[u.provider]?.first(where: { u.model.hasPrefix($0.prefix) }) else { return nil }
            let imageOut = max(0, u.output - u.outputText)
            var total = Double(u.inputText) * rate.inputText + Double(u.inputImage) * rate.inputImage
                + Double(u.outputText) * rate.outputText
            if rate.perImage == nil { total += Double(imageOut) * rate.outputImage }
            return total / 1_000_000 + (rate.perImage ?? 0) * Double(u.images)
        case .grok:
            return perImage(u, grok)
        case .qwen:
            return perImage(u, u.overseas ? qwenSingapore : qwenBeijing)
        case .doubao:
            return u.overseas ? nil : perImage(u, doubao)
        case .flux, .custom:
            return nil
        }
    }

    private static func perImage(_ u: AIUsage, _ table: [ImageRate]) -> Double? {
        guard let rate = table.first(where: { u.model.hasPrefix($0.prefix) }) else { return nil }
        // 不知道输出多大时按低档算。
        let output = rate.split > 0 && u.outputPixels > rate.split ? rate.high : rate.low
        return Double(max(0, u.inputImages - rate.freeInputs)) * rate.input + Double(u.images) * output
    }

    // MARK: 费率说明

    private static func money(_ v: Double, _ currency: AICurrency) -> String {
        var text = String(format: "%.4f", v)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return currency.symbol + text
    }

    /// 这家的这个型号怎么收费，写成一行给人看。没有价目时返回 nil。
    static func rateText(provider: AIProvider, model: String, overseas: Bool) -> String? {
        let currency = provider.currency
        func m(_ v: Double) -> String { money(v, currency) }
        switch provider {
        case .openai, .gemini, .minimax:
            guard let r = tokenRates[provider]?.first(where: { model.hasPrefix($0.prefix) }) else { return nil }
            var parts: [String] = []
            if r.inputText == r.inputImage {
                parts.append("输入 \(m(r.inputText))")
            } else {
                parts.append("文字输入 \(m(r.inputText))")
                parts.append("图片输入 \(m(r.inputImage))")
            }
            if r.outputText > 0 { parts.append("文字输出 \(m(r.outputText))") }
            if let per = r.perImage {
                return parts.joined(separator: " · ") + "（每百万 token）· 出图 \(m(per))/张"
            }
            parts.append("图片输出 \(m(r.outputImage))")
            return parts.joined(separator: " · ") + "（每百万 token）"
        case .grok:
            guard let r = grok.first(where: { model.hasPrefix($0.prefix) }) else { return nil }
            return "出图 \(m(r.low))/张（以接口返回的实际费用为准）"
        case .qwen, .doubao:
            if provider == .doubao, overseas { return nil }
            let table = provider == .qwen ? (overseas ? qwenSingapore : qwenBeijing) : doubao
            guard let r = table.first(where: { model.hasPrefix($0.prefix) }) else { return nil }
            var text = "出图 \(m(r.low))/张"
            if r.split > 0, r.high != r.low {
                text += "，超过 \(String(format: "%.2f", Double(r.split) / 1_000_000)) 百万像素 \(m(r.high))/张"
            }
            if r.input > 0 {
                text += " · 输入图 \(m(r.input))/张"
                if r.freeInputs > 0 { text += "（前 \(r.freeInputs) 张免费）" }
            }
            return text
        case .flux:
            return "按接口返回的实际扣费计（1 credit = $0.01）"
        case .custom:
            return nil
        }
    }
}
