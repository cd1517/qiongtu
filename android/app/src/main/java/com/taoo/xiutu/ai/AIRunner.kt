package com.taoo.xiutu.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.tools.mosaic.MosaicStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 准备请求 → 调接口 → 把结果对齐回原图。 */
object AIRunner {
    /** OpenAI 固定支持的三种输出尺寸，选和原图比例最接近的。 */
    private fun openAISize(w: Int, h: Int): Pair<Int, Int> {
        val sizes = listOf(1024 to 1024, 1536 to 1024, 1024 to 1536)
        val aspect = w.toDouble() / h
        return sizes.minBy { abs(ln(aspect / (it.first.toDouble() / it.second))) }
    }

    /** 自定义输出尺寸：两边都是 16 的倍数，长边不超过 2048，总像素不少于接口下限。图本来就小的话返回 null。 */
    private fun customSize(w: Int, h: Int): Pair<Int, Int>? {
        val long = max(w, h).toDouble()
        if (long <= 1100) return null
        val scale = min(1.0, 2048 / long)
        fun round16(v: Int) = max(16, (v * scale / 16).roundToInt() * 16)
        val sw = round16(w)
        val sh = round16(h)
        val ratio = sw.toDouble() / sh
        if (sw.toLong() * sh < 655_360 || ratio > 3 || ratio < 1.0 / 3) return null
        return sw to sh
    }

    /** CGRect.integral：外扩到整数像素。 */
    private fun integral(l: Float, t: Float, w: Float, h: Float) =
        Rect(floor(l).toInt(), floor(t).toInt(), ceil(l + w).toInt(), ceil(t + h).toInt())

    /**
     * crop 不为空时只把这一块（归一化坐标，原点左上角）发出去，返回的也是这一块的结果。
     * 在后台线程调用。
     */
    suspend fun run(
        endpoint0: AIEndpoint, prompt: String, proxy: Bitmap, ops: List<EditOp>,
        strokes: List<MosaicStroke>, crop: RectF? = null, appendGuidance: Boolean = true,
        reframe: AIReframe = AIReframe.ORIGINAL,
        /** 附带的参考图（满意脸型、图层融合），跟在主图后面一起发。 */
        references: List<Bitmap> = emptyList(),
    ): Bitmap {
        var endpoint = endpoint0
        var current = withContext(Dispatchers.Default) { EditOp.applyAll(ops, proxy) }
        reframe.size?.let { return runReframed(endpoint, prompt, current, it, reframe) }
        if (crop != null) {
            val r = integral(crop.left * current.width, crop.top * current.height, crop.width() * current.width, crop.height() * current.height)
            if (!r.intersect(0, 0, current.width, current.height)) throw AIError("图片编码失败。")
            current = Bitmap.createBitmap(current, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
        }
        val ew = current.width
        val eh = current.height
        val hasRegion = strokes.any { !it.erase }

        var text = prompt
        if (appendGuidance) {
            text += "\n\nEdit the provided photo as instructed above. Keep everything that is not mentioned unchanged: same framing, composition, people, pose and details. The result must look like a real photograph."
        }

        if (endpoint.provider.supportsMask) {
            val image = current
            // 把图补成接口要的尺寸（四周用边缘像素延伸），拿到结果后再把补的部分裁掉，
            // 这样结果和原图逐像素对齐，才能只替换涂抹的那一块。
            suspend fun attempt(target: Pair<Int, Int>): Bitmap {
                val (tw, th) = target
                val s = min(tw.toFloat() / ew, th.toFloat() / eh)
                val cw = (ew * s).roundToInt().toFloat()
                val ch = (eh * s).roundToInt().toFloat()
                val ox = floor((tw - cw) / 2)
                val oy = floor((th - ch) / 2)
                val png = withContext(Dispatchers.Default) {
                    val canvas = Gpu.run {
                        val t = upload(image)
                        read(pass(AIShaders.PLACE, mapOf("res" to t), mapOf("rect" to floatArrayOf(ox, oy, cw, ch)), tw, th))
                    }
                    canvas.setHasAlpha(false)
                    Img.png(canvas)
                }
                val content = RectF(ox, oy, ox + cw, oy + ch)
                var mask: ByteArray? = null
                var request = text
                if (hasRegion) {
                    mask = withContext(Dispatchers.Default) { maskPNG(strokes, tw, th, content) }
                    request += " Apply the change only inside the masked (transparent) area and blend it seamlessly with the surroundings."
                }
                val extras = if (references.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                    references.map { Img.png(AIOneShot.prepared(it, 1024)) }
                }
                val payload = AIPayload(request, png, "image/png", maskPNG = mask, markedJPEG = null, size = "${tw}x$th", extraImages = extras)
                val data = AIClient.edit(endpoint, payload)
                return withContext(Dispatchers.Default) {
                    val img = Img.decode(data) ?: throw AIError("服务商返回的不是有效图片。")
                    val fx = img.width.toFloat() / tw
                    val fy = img.height.toFloat() / th
                    val r = integral(content.left * fx, content.top * fy, content.width() * fx, content.height() * fy)
                    val cut = if (r.intersect(0, 0, img.width, img.height) && r.width() > 0 && r.height() > 0) {
                        Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height())
                    } else img
                    standalone(cut)
                }
            }

            // 有的型号不认"高保真"这个参数：被拒就去掉它再发一次（被拒的那次不计费）。
            suspend fun attemptSafely(target: Pair<Int, Int>): Bitmap = try {
                attempt(target)
            } catch (e: AIError) {
                if (endpoint.highFidelity && e.status == 400 && e.raw.lowercase().contains("fidelity")) {
                    endpoint = endpoint.copy(highFidelity = false)
                    attempt(target)
                } else throw e
            }

            // 高清输出：按这块图本身的大小要结果（长边最多 2048）；型号不接受自定义尺寸就退回标准尺寸。
            if (endpoint.hires && endpoint.provider == AIProvider.OPENAI) {
                customSize(ew, eh)?.let { custom ->
                    try {
                        return attemptSafely(custom)
                    } catch (e: AIError) {
                        if (!(e.status == 400 && e.raw.lowercase().contains("size"))) throw e
                        // 继续走标准尺寸
                    }
                }
            }
            return attemptSafely(openAISize(ew, eh))
        }

        val (jpeg, marked) = withContext(Dispatchers.Default) {
            val small = Img.downscale(current, 2048)
            val jpeg = Img.jpeg(opaque(small), 92)
            var marked: ByteArray? = null
            if (hasRegion) {
                AIRegion.maskImage(strokes, small.width, small.height)?.let { m ->
                    marked = Img.jpeg(opaque(AIRegion.overlay(small, m)), 90)
                }
            }
            jpeg to marked
        }
        val extras = if (references.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            references.map { Img.jpeg(AIOneShot.prepared(it, 1024), 92) }
        }
        if (marked != null) {
            text += " The second image is the same photo with the region to edit highlighted in red. Change only that region, and return the first photo edited, without any red marking."
        }
        val payload = AIPayload(text, jpeg, "image/jpeg", maskPNG = null, markedJPEG = marked, size = "auto", extraImages = extras)
        val data = AIClient.edit(endpoint, payload)
        return withContext(Dispatchers.Default) {
            standalone(Img.decode(data) ?: throw AIError("服务商返回的不是有效图片。"))
        }
    }

    /** 换画幅的构图：把原图居中放进新画幅，空出来的地方先用虚化放大的原图垫着，让 AI 整张重新构图。 */
    private suspend fun runReframed(endpoint: AIEndpoint, prompt: String, image: Bitmap, target: Pair<Int, Int>, reframe: AIReframe): Bitmap {
        val (tw, th) = target
        val usePNG = endpoint.provider.supportsMask
        val data = withContext(Dispatchers.Default) {
            val ew = image.width.toFloat()
            val eh = image.height.toFloat()
            val fill = max(tw / ew, th / eh)
            val fit = min(tw / ew, th / eh)
            val canvas = Gpu.run {
                val s = upload(image)
                // 垫底：原图放大铺满、虚化、压暗一点。
                val backRect = floatArrayOf((tw - ew * fill) / 2, (th - eh * fill) / 2, ew * fill, eh * fill)
                val back = blur(pass(AIShaders.PLACE, mapOf("res" to s), mapOf("rect" to backRect), tw, th), max(tw, th) * 0.03f * 2.5f)
                val frontRect = floatArrayOf((tw - ew * fit) / 2, (th - eh * fit) / 2, ew * fit, eh * fit)
                read(pass(REFRAME, mapOf("res" to s, "back" to back), mapOf("rect" to frontRect), tw, th))
            }
            canvas.setHasAlpha(false)
            if (usePNG) Img.png(canvas) else Img.jpeg(canvas, 92)
        }
        val text = prompt + "\n\n【画幅】输出一张${reframe.instruction}的照片。给你的图里只有中间清楚的那一块是原照片，四周虚化的部分只是占位，不要保留虚化。" +
            "按上面的构图要求，在新画幅里重新安排画面：原图边缘以外缺的部分，按原场景合理地补全延伸（天空、地面、建筑、背景都要连贯真实）。" +
            "人物的长相、身材、姿势和服装不变，不要拉伸或压扁任何东西。结果必须是一张完整、清晰、真实的照片。"
        val payload = AIPayload(text, data, if (usePNG) "image/png" else "image/jpeg", maskPNG = null, markedJPEG = null, size = "${tw}x$th")
        val out = AIClient.edit(endpoint, payload)
        return withContext(Dispatchers.Default) {
            standalone(Img.decode(out) ?: throw AIError("服务商返回的不是有效图片。"))
        }
    }

    /** 中间是原图，外面是压暗过的虚化垫底。 */
    private const val REFRAME = Shaders.HEADER + """
uniform sampler2D res;
uniform sampler2D back;
uniform vec4 rect;
void main() {
    vec2 p = uv * size;
    vec2 q = (p - rect.xy) / rect.zw;
    if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
        o = texture(res, q);
    } else {
        vec4 b = texture(back, uv);
        o = vec4(clamp(b.rgb - 0.08, 0.0, 1.0), 1.0);
    }
}
"""

    private fun opaque(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(b, 0f, 0f, null)
        out.setHasAlpha(false)
        return out
    }

    /**
     * 把服务商返回的图规整成一张不透明的普通位图。
     * 服务商有时会把蒙版区域原样留成透明、什么都没画：这种结果贴回去就是一块黑，直接当失败处理。
     */
    private fun standalone(image: Bitmap): Bitmap {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) throw AIError("服务商返回的图片无法处理。")
        if (image.hasAlpha()) {
            val sw = min(w, 96)
            val sh = min(h, 96)
            val probe = Bitmap.createScaledBitmap(Img.soft(image), sw, sh, true)
            val px = IntArray(sw * sh)
            probe.getPixels(px, 0, sw, 0, 0, sw, sh)
            val empty = px.count { (it ushr 24) < 128 }
            if (empty * 50 > sw * sh) {
                throw AIError("AI 返回的图片里有一块是空的，没有画上内容。可以再生成一次，或者换个说法、换一家服务商。")
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(Img.soft(image), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        out.setHasAlpha(false)
        return out
    }

    /** OpenAI 的蒙版：不透明 = 保持不动，透明 = 要修改。content 是原图在补边后画布里的位置（左上角坐标系）。 */
    private fun maskPNG(strokes: List<MosaicStroke>, w: Int, h: Int, content: RectF): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        fun mx(x: Float) = content.left + x * content.width()
        fun my(y: Float) = content.top + y * content.height()
        for (stroke in strokes) {
            val first = stroke.points.firstOrNull() ?: continue
            val lw = max(1f, stroke.width * max(content.width(), content.height()))
            paint.xfermode = if (stroke.erase) null else clear
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                c.drawCircle(mx(first.x), my(first.y), lw / 2, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lw
                val path = Path()
                path.moveTo(mx(first.x), my(first.y))
                for (i in 1 until stroke.points.size) path.lineTo(mx(stroke.points[i].x), my(stroke.points[i].y))
                c.drawPath(path, paint)
            }
        }
        return Img.png(bmp)
    }
}
