import SwiftUI

/// AI 设置：选服务商、填 API Key、选型号，看各家的用量。
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    private let settings = AISettings.shared
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(AIProvider.allCases) { provider in
                        Button { settings.setActive(provider) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: settings.active == provider ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(settings.active == provider ? Theme.accent : Theme.dim)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(provider.title).foregroundStyle(.white)
                                    Text(settings.isConfigured(provider) ? settings.config(provider).model : "未设置")
                                        .font(.caption)
                                        .foregroundStyle(Theme.dim)
                                }
                            }
                        }
                    }
                } header: {
                    Text("AI 修图使用哪一家")
                }

                Section {
                    ForEach(AIProvider.allCases) { provider in
                        NavigationLink {
                            ProviderSettingsView(provider: provider)
                        } label: {
                            HStack {
                                Text(provider.title)
                                Spacer()
                                Text(settings.key(provider).isEmpty ? "未填 Key"
                                     : (settings.isVerified(provider) ? "已通过测试" : "未通过测试"))
                                    .font(.caption)
                                    .foregroundStyle(settings.key(provider).isEmpty ? Theme.dim
                                                     : (settings.isVerified(provider) ? Color.green : Color.orange))
                            }
                        }
                    }
                } header: {
                    Text("API Key 和型号")
                }

                Section {
                    Picker("画质（OpenAI）", selection: Binding(get: { settings.quality }, set: { settings.setQuality($0) })) {
                        ForEach(AISettings.qualities, id: \.id) { item in
                            Text(item.title).tag(item.id)
                        }
                    }
                    Toggle("高清输出", isOn: Binding(get: { settings.hires }, set: { settings.setHires($0) }))
                } header: {
                    Text("输出画质")
                }

                usageSection

                rateSection

                Section {
                } footer: {
                    Text("API Key 只保存在这台设备的钥匙串里。使用 AI 时，当前画面会发送给选中的服务商。")
                }
            }
            .navigationTitle("AI 设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .confirmationDialog("把所有服务商的用量统计清零？", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("清零", role: .destructive) { settings.resetUsage() }
                Button("取消", role: .cancel) {}
            }
        }
        .preferredColorScheme(.dark)
    }

    /// 各家当前型号的费率（估算费用就是按这个算的）。
    private var rateSection: some View {
        Section {
            ForEach(AIProvider.allCases.filter { $0 != .custom }) { provider in
                let config = settings.config(provider)
                let model = config.model.isEmpty ? (provider.builtinModels.first?.id ?? "") : config.model
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(provider.title)
                        Spacer()
                        Text(model)
                            .font(.caption)
                            .foregroundStyle(Theme.dim)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Text(AIPricing.rateText(provider: provider, model: model,
                                            overseas: AIClient.isOverseas(config.baseURL)) ?? "—")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(Theme.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        } header: {
            Text("费率")
        }
    }

    /// 各家的累计用量：次数、token、张数和估算费用；按币种各有一行合计。
    private var usageSection: some View {
        let used = AIProvider.allCases.filter { settings.stats($0).requests > 0 }
        return Section {
            if used.isEmpty {
                Text("还没有用量").foregroundStyle(Theme.dim)
            } else {
                ForEach(used) { provider in
                    UsageRow(title: provider.title, stats: settings.stats(provider), currency: provider.currency)
                }
                ForEach(AICurrency.allCases, id: \.self) { currency in
                    let total = settings.usageTotal(currency)
                    if total.requests > 0 {
                        UsageRow(title: currency == .usd ? "合计（美元）" : "合计（人民币）", stats: total,
                                 currency: currency, bold: true)
                    }
                }
                Button("清零", role: .destructive) { confirmReset = true }
            }
        } header: {
            Text("用量统计")
        }
    }
}

/// 用量的一行：左边名字和明细，右边估算费用。
struct UsageRow: View {
    let title: String
    let stats: AIProviderStats
    let currency: AICurrency
    var bold = false

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).fontWeight(bold ? .semibold : .regular)
                Text(detail)
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(Theme.dim)
            }
            Spacer()
            Text(costText)
                .fontWeight(bold ? .semibold : .regular)
                .monospacedDigit()
                .foregroundStyle(bold ? Theme.accent : Color.white)
        }
    }

    private var detail: String {
        var parts = ["\(stats.requests) 次"]
        if stats.inputTokens + stats.outputTokens > 0 {
            parts.append("输入 \(Self.compact(stats.inputTokens))")
            parts.append("输出 \(Self.compact(stats.outputTokens)) token")
        }
        parts.append("\(stats.images) 张")
        if stats.unpriced > 0, stats.unpriced < stats.requests { parts.append("\(stats.unpriced) 次无价目") }
        return parts.joined(separator: " · ")
    }

    /// 一次都算不出价格时显示"—"，不编数字。
    private var costText: String {
        guard stats.unpriced < stats.requests else { return "—" }
        return "约 \(currency.symbol)\(String(format: "%.2f", stats.cost))"
    }

    static func compact(_ n: Int) -> String {
        if n >= 1_000_000 { return String(format: "%.2fM", Double(n) / 1_000_000) }
        if n >= 10_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return "\(n)"
    }
}

struct ProviderSettingsView: View {
    let provider: AIProvider

    private let settings = AISettings.shared
    @State private var key = ""
    @State private var showKey = false
    @State private var baseURL = ""
    @State private var manualModel = ""
    @State private var busy = false
    @State private var status: String?
    @State private var statusOK = false

    var body: some View {
        let config = settings.config(provider)
        let stats = settings.stats(provider)
        Form {
            Section {
                HStack {
                    Group {
                        if showKey {
                            TextField("粘贴 API Key", text: $key)
                        } else {
                            SecureField("粘贴 API Key", text: $key)
                        }
                    }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                    Button { showKey.toggle() } label: {
                        Image(systemName: showKey ? "eye.slash" : "eye").foregroundStyle(Theme.dim)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("API Key")
            }

            Section {
                ForEach(settings.modelChoices(provider), id: \.self) { id in
                    Button { settings.update(provider) { $0.model = id } } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(id)
                                    .font(.system(.subheadline, design: .monospaced))
                                    .foregroundStyle(.white)
                                if let note = provider.builtinModels.first(where: { $0.id == id })?.note {
                                    Text(note).font(.caption).foregroundStyle(Theme.dim)
                                }
                            }
                            Spacer()
                            if config.model == id {
                                Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                            }
                        }
                    }
                }
                HStack {
                    TextField("手动填写型号 ID", text: $manualModel)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.subheadline, design: .monospaced))
                    Button("使用") {
                        let id = manualModel.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !id.isEmpty else { return }
                        settings.update(provider) { $0.model = id }
                        manualModel = ""
                    }
                    .disabled(manualModel.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } header: {
                Text("型号")
            }

            Section {
                TextField("https://…", text: $baseURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .font(.system(.footnote, design: .monospaced))
                // 官方的几个地域：点一下换过去。
                ForEach(provider.baseURLChoices, id: \.url) { choice in
                    Button { baseURL = choice.url } label: {
                        HStack {
                            Text(choice.title).foregroundStyle(.white)
                            Spacer()
                            if baseURL == choice.url {
                                Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                            }
                        }
                    }
                }
                if provider.baseURLChoices.isEmpty, !provider.defaultBaseURL.isEmpty, baseURL != provider.defaultBaseURL {
                    Button("恢复官方地址") { baseURL = provider.defaultBaseURL }
                }
            } header: {
                Text("接口地址")
            }

            Group {
                Section {
                    Button {
                        Task { await test() }
                    } label: {
                        HStack {
                            Text(provider.canListModels ? "测试连接并刷新型号列表" : "测试连接")
                            Spacer()
                            if busy { ProgressView() }
                        }
                    }
                    .disabled(busy || key.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let status {
                        Text(status)
                            .font(.footnote)
                            .foregroundStyle(statusOK ? Color.green : Color.red)
                    }
                }
            }

            if stats.requests > 0 {
                Section {
                    UsageRow(title: "累计", stats: stats, currency: provider.currency)
                    Button("清零", role: .destructive) { settings.resetUsage(provider) }
                } header: {
                    Text("用量统计")
                }
            }

            Section {
                Button(settings.active == provider ? "当前正在使用" : "设为当前使用") {
                    settings.setActive(provider)
                }
                .disabled(settings.active == provider)
            }
        }
        .navigationTitle(provider.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            key = settings.key(provider)
            baseURL = settings.config(provider).baseURL
        }
        .onChange(of: key) { _, value in settings.setKey(value, for: provider) }
        .onChange(of: baseURL) { _, value in
            let new = value.trimmingCharacters(in: .whitespacesAndNewlines)
            settings.update(provider) {
                // 地址（地域）换了也要重新测试。
                if $0.baseURL != new { $0.verified = false }
                $0.baseURL = new
            }
        }
        .onDisappear {
            // 填了 Key 还没测就离开：自动测一次，通过了才算可用。
            if !settings.key(provider).isEmpty, !settings.isVerified(provider) {
                Task { await settings.verify(provider) }
            }
        }
    }

    private func test() async {
        busy = true
        status = nil
        let failure = await settings.verify(provider)
        statusOK = failure == nil
        if let failure {
            status = failure
        } else if provider.canListModels {
            let count = settings.config(provider).fetchedModels.count
            status = count == 0 ? "连接成功，但没有找到图像型号。" : "连接成功，找到 \(count) 个型号。"
        } else {
            status = "连接成功。"
        }
        busy = false
    }
}
