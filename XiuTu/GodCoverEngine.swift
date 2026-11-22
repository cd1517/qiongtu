import UIKit
import WebKit

/// "封神"的引擎：在一个看不见的网页视图里运行原网站的代码，只用它做三件事——
/// 拼给 AI 的指令、把杂志模板画到画布上、给随机文案。界面全部是原生的。
@MainActor
final class GodCoverEngine: NSObject, WKNavigationDelegate {
    let webView: WKWebView
    /// 打包在 App 里的排版字体。
    private let fonts = GodCoverFonts.make()
    private var loaded = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        if let fonts { config.setURLSchemeHandler(fonts, forURLScheme: GodCoverFonts.scheme) }
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 720, height: 960), configuration: config)
        super.init()
        webView.navigationDelegate = self
        webView.isUserInteractionEnabled = false
        let url = Bundle.main.url(forResource: "engine.html", withExtension: "gcweb")
            ?? Bundle.main.url(forResource: "engine.html", withExtension: "gcweb", subdirectory: "GodCover")
        if let url, let html = try? String(contentsOf: url, encoding: .utf8) {
            if let fonts {
                webView.loadHTMLString(fonts.patch(html), baseURL: GodCoverFonts.pageURL)
            } else {
                webView.loadHTMLString(html, baseURL: URL(string: "https://godcover.invalid/"))
            }
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loaded = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }

    private func ready() async {
        if loaded { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func call(_ body: String, _ arguments: [String: Any] = [:]) async throws -> Any? {
        await ready()
        return try await webView.callAsyncJavaScript(body, arguments: arguments, in: nil, contentWorld: .page)
    }

    func setPhoto(ai: Bool, dataURL: String) async throws {
        _ = try await call("return await gc.setPhoto(kind, url)", ["kind": ai ? "ai" : "original", "url": dataURL])
    }

    func clearAI() async {
        _ = try? await call("return gc.clearAI()")
    }

    func render(_ params: [String: Any]) async throws -> UIImage? {
        let url = try await call("return await gc.render(p)", ["p": params]) as? String
        return url.flatMap(Self.image(fromDataURL:))
    }

    func export(_ params: [String: Any], scale: Int) async throws -> Data? {
        let url = try await call("return await gc.exportCover(p, k)", ["p": params, "k": scale]) as? String
        return url.flatMap(Self.data(fromDataURL:))
    }

    func prompt(task: [String: Any], noText: Bool) async throws -> String {
        (try await call("return gc.prompt(t, n)", ["t": task, "n": noText]) as? String) ?? ""
    }

    /// 场景模式的指令是在本机拼的，不经过 gc.prompt，但照样过一遍网站那套指令清理。
    func sanitize(_ prompt: String) async throws -> String {
        (try await call("return sanitizeGenerationPrompt(p)", ["p": prompt]) as? String) ?? ""
    }

    func randomText(style: String) async -> (title: String, sub: String)? {
        guard let d = (try? await call("return gc.randomText(s)", ["s": style])) as? [String: Any],
              let title = d["title"] as? String, let sub = d["sub"] as? String else { return nil }
        return (title, sub)
    }

    /// 摄影棚：按当前选项排出一组照片，每张一条完整指令。
    func studioPlan(_ params: [String: Any]) async throws -> [[String: Any]] {
        (try await call("return gcStudio.plan(p)", ["p": params]) as? [[String: Any]]) ?? []
    }

    static func data(fromDataURL url: String) -> Data? {
        guard let comma = url.firstIndex(of: ",") else { return nil }
        return Data(base64Encoded: String(url[url.index(after: comma)...]), options: .ignoreUnknownCharacters)
    }

    static func image(fromDataURL url: String) -> UIImage? {
        data(fromDataURL: url).flatMap { UIImage(data: $0) }
    }
}
