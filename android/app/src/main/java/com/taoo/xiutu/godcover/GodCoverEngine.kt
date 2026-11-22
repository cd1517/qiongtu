package com.taoo.xiutu.godcover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * "封神"的引擎：在一个看不见的网页视图里运行原网站的代码，只用它做三件事——
 * 拼给 AI 的指令、把杂志模板画到画布上、给随机文案。界面全部是原生的。
 * 页面挂在 https://gcfont.invalid/ 下（.invalid 不会解析到真实主机），字体和照片都从本机切给它，其余请求一律拦掉。
 * 要在主线程创建；webView 需要挂进界面（1×1 不可见）才能稳定工作。
 */
@SuppressLint("SetJavaScriptEnabled")
class GodCoverEngine(context: Context) {
    val webView: WebView
    private val fonts = GodCoverFonts.make(context)
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()
    private val ids = AtomicInteger(0)
    /** 交给网页的照片：按地址取。 */
    private val photos = ConcurrentHashMap<String, ByteArray>()
    private val photoSerial = AtomicInteger(0)
    @Volatile private var destroyed = false

    private sealed class Result {
        class Text(val value: String) : Result()
        class Json(val value: String) : Result()
        class Failure(val message: String) : Result()
    }

    /** 网页把 Promise 的结果送回来：kind 0 = 出错，1 = 字符串，2 = JSON。 */
    private inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, kind: Int, value: String?) {
            val r = when (kind) {
                1 -> Result.Text(value ?: "")
                2 -> Result.Json(value ?: "null")
                else -> Result.Failure(value ?: "")
            }
            pending.remove(id)?.complete(r)
        }
    }

    private var page: ByteArray? = null

    init {
        val html = runCatching {
            context.assets.open("godcover/engine.html.gcweb").use { it.readBytes().decodeToString() }
        }.getOrNull()
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            // 网页脚本一开头就读 localStorage，不开会直接报错
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            settings.offscreenPreRaster = true
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addJavascriptInterface(Bridge(), "__gcNative")
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        android.util.Log.w("GodCover", "网页：" + m.message() + " @" + m.lineNumber())
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                    intercept(request)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.startsWith(GodCoverFonts.BASE) != true) return
                    ready.complete(Unit)
                }
            }
        }
        if (html != null) {
            // 页面本身也由拦截器给出（同源），不用 loadDataWithBaseURL：那条路会转成 data: 地址，超过 2MB 上限就加载不出来
            page = (fonts?.patch(html) ?: html).toByteArray(Charsets.UTF_8)
            webView.loadUrl(GodCoverFonts.BASE + "index.html")
        } else {
            ready.completeExceptionally(IllegalStateException("封面引擎文件缺失"))
        }
    }

    private fun empty(status: Int = 204, reason: String = "No Content") =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** 只放行本机的字体和照片；其他任何请求都返回空响应，不联网。 */
    private fun intercept(request: WebResourceRequest?): WebResourceResponse {
        val url = request?.url ?: return empty()
        if (url.scheme != "https" || url.host != GodCoverFonts.HOST) return empty()
        val seg = url.pathSegments
        if (seg.size == 1 && seg[0] == "index.html") {
            val data = page ?: return empty(404, "Not Found")
            return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(data))
        }
        if (seg.size == 2 && seg[0] == "f") {
            val n = seg[1].toIntOrNull()
            return n?.let { runCatching { fonts?.response(it) }.getOrNull() } ?: empty(404, "Not Found")
        }
        if (seg.size == 2 && seg[0] == "p") {
            val data = photos[seg[1]] ?: return empty(404, "Not Found")
            return WebResourceResponse(
                "image/jpeg", null, 200, "OK",
                mapOf("Content-Length" to data.size.toString(), "Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(data),
            )
        }
        return empty(404, "Not Found")
    }

    /**
     * 在网页里执行一段异步函数体（可以用 await），参数在函数体里叫 a。
     * 结果是字符串时原样返回，其余按 JSON 解析（JSONObject / JSONArray / 数字 / null）。
     */
    private suspend fun call(body: String, args: JSONObject = JSONObject()): Any? {
        ready.await()
        if (destroyed) throw IllegalStateException("封面引擎已关闭")
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<Result>()
        pending[id] = d
        val js = """(async function(){try{var r=await (async function(a){$body})($args);
if(typeof r==='string'){__gcNative.done($id,1,r);}else{__gcNative.done($id,2,JSON.stringify(r===undefined?null:r));}
}catch(e){__gcNative.done($id,0,String((e&&e.message)||e));}})();void 0;"""
        try {
            withContext(Dispatchers.Main.immediate) { webView.evaluateJavascript(js, null) }
            return when (val r = d.await()) {
                is Result.Text -> r.value
                is Result.Json -> JSONTokener(r.value).nextValue().takeUnless { it == JSONObject.NULL }
                is Result.Failure -> throw IllegalStateException(r.message)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** 换照片：原图会清掉之前的 AI 图。jpeg 是编好的 JPEG。 */
    suspend fun setPhoto(ai: Boolean, jpeg: ByteArray) {
        val kind = if (ai) "ai" else "original"
        val name = "$kind-${photoSerial.incrementAndGet()}.jpg"
        // 旧的同类照片不再需要
        photos.keys.filter { it.startsWith("$kind-") }.forEach { photos.remove(it) }
        photos[name] = jpeg
        call("return await gc.setPhoto(a.kind, a.url)", JSONObject().put("kind", kind).put("url", GodCoverFonts.BASE + "p/" + name))
    }

    suspend fun clearAI() {
        runCatching { call("return gc.clearAI()") }
    }

    suspend fun render(params: JSONObject): Bitmap? {
        val url = call("return await gc.render(a.p)", JSONObject().put("p", params)) as? String ?: return null
        return withContext(Dispatchers.Default) { image(url) }
    }

    /** 按 scale 倍分辨率导出封面，返回图片数据（JPEG）。 */
    suspend fun exportCover(params: JSONObject, scale: Int): ByteArray? {
        val url = call("return await gc.exportCover(a.p, a.k)", JSONObject().put("p", params).put("k", scale)) as? String
            ?: return null
        return withContext(Dispatchers.Default) { data(url) }
    }

    /** 人物模式给 AI 的指令：网页里已经过了 sanitizeGenerationPrompt。 */
    suspend fun prompt(task: JSONObject, noText: Boolean): String =
        call("return gc.prompt(a.t, a.n)", JSONObject().put("t", task).put("n", noText)) as? String ?: ""

    /** 本机拼的指令（场景模式）不经过 gc.prompt，但照样过一遍网站那套指令清理。 */
    suspend fun sanitize(prompt: String): String =
        call("return sanitizeGenerationPrompt(a.p)", JSONObject().put("p", prompt)) as? String ?: ""

    suspend fun randomText(style: String): Pair<String, String>? {
        val d = runCatching { call("return gc.randomText(a.s)", JSONObject().put("s", style)) }.getOrNull() as? JSONObject
            ?: return null
        val title = d.optString("title", "")
        val sub = d.optString("sub", "")
        if (!d.has("title") || !d.has("sub")) return null
        return title to sub
    }

    /** 摄影棚：按当前选项排出一组照片，每张一条完整指令（网页里已净化）。 */
    suspend fun studioPlan(params: JSONObject): List<JSONObject> {
        val arr = call("return gcStudio.plan(a.p)", JSONObject().put("p", params)) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 关掉网页视图，释放字体文件。主线程调用。 */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        pending.values.forEach { it.complete(Result.Failure("封面引擎已关闭")) }
        pending.clear()
        photos.clear()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("__gcNative")
        webView.destroy()
        fonts?.close()
    }

    companion object {
        fun data(fromDataURL: String): ByteArray? {
            val comma = fromDataURL.indexOf(',')
            if (comma < 0) return null
            return runCatching { Base64.decode(fromDataURL.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
        }

        fun image(fromDataURL: String): Bitmap? =
            data(fromDataURL)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}
