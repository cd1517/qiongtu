import Foundation
import WebKit

/// 封神排版用的字体：全部打包在 App 里，不从外网加载。
///
/// 原网站是从谷歌字体引用的（12 个字体家族，中文、日文按字符范围切成很多小文件）。
/// 这里把那些文件原样拼成一个大文件（fonts.bin）加一份位置索引，样式表里的地址换成 gcfont://app/f/<编号>，
/// 网页视图要哪一块，就从大文件里切哪一块给它，用到的字才会被读进内存。
/// 字体都是开源授权（SIL OFL / Apache）。
final class GodCoverFonts: NSObject, WKURLSchemeHandler {
    static let scheme = "gcfont"
    /// 网页本身也挂在这个地址下面：https 的页面不许加载自定义协议的字体（会被当成不安全内容拦掉），
    /// 页面和字体同一个来源就没有这个问题。
    static let pageURL = URL(string: "gcfont://app/")!

    private let blob: Data
    private let index: [(offset: Int, length: Int, mime: String)]
    /// 样式表（@font-face 列表），地址已经指向 gcfont://。
    let css: String

    private static func resource(_ name: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: "gcweb")
            ?? Bundle.main.url(forResource: name, withExtension: "gcweb", subdirectory: "GodCover")
    }

    /// 文件不齐就返回 nil，这时保持原样（从网上加载）。
    init?(blobURL: URL?, indexURL: URL?, cssURL: URL?) {
        guard let blobURL, let indexURL, let cssURL,
              let blob = try? Data(contentsOf: blobURL, options: .alwaysMapped),
              let raw = try? Data(contentsOf: indexURL),
              let list = (try? JSONSerialization.jsonObject(with: raw)) as? [[Any]],
              let css = try? String(contentsOf: cssURL, encoding: .utf8) else { return nil }
        self.blob = blob
        self.css = css
        self.index = list.compactMap { item in
            guard item.count == 3, let o = item[0] as? Int, let l = item[1] as? Int, let m = item[2] as? String,
                  o >= 0, l > 0, o + l <= blob.count else { return nil }
            return (o, l, m)
        }
        super.init()
    }

    static func make() -> GodCoverFonts? {
        GodCoverFonts(blobURL: resource("fonts.bin"), indexURL: resource("fonts.index.json"), cssURL: resource("fonts.css"))
    }

    /// 把网页里引用谷歌字体的那一行换成本机的样式表。
    func patch(_ html: String) -> String {
        guard let range = html.range(of: #"<link[^>]*fonts\.googleapis\.com[^>]*>"#, options: .regularExpression) else {
            return html
        }
        return html.replacingCharacters(in: range, with: "<style>\n" + css + "\n</style>")
    }

    // MARK: WKURLSchemeHandler

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, let number = Int(url.lastPathComponent), index.indices.contains(number) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let entry = index[number]
        let data = blob.subdata(in: entry.offset..<(entry.offset + entry.length))
        // 字体是跨来源加载的，要带上允许跨来源的响应头，网页才肯用。
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Type": entry.mime,
            "Content-Length": "\(data.count)",
            "Access-Control-Allow-Origin": "*",
            "Cache-Control": "max-age=31536000, immutable",
        ])!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}
