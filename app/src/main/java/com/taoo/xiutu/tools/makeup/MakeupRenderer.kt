package com.taoo.xiutu.tools.makeup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.taoo.xiutu.gpu.Gpu
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.ml.FaceInfo
import com.taoo.xiutu.tools.color.ColorGL
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一层妆：一张蒙版 + 怎么和皮肤混在一起。 */
private class MakeupLayer(
    val shape: MKShape,
    /** 蒙版边缘再羽化多少（这张脸方框的比例）。 */
    val blur: Float = 0f,
    val mode: Mode,
) {
    sealed class Mode(val amount: Float) {
        /** 正片叠底：像粉质的彩妆，叠上去变色、略微变深，皮肤纹理还在。 */
        class Multiply(val color: MakeupColor, a: Float) : Mode(a)
        /** 滤色：高光、珠光、眼神光。 */
        class Screen(val color: MakeupColor, a: Float) : Mode(a)
        /**
         * 换色：口红、美瞳。保留原来的明暗纹理，颜色换成指定的。base = 这块地方原来的平均亮度，
         * shine = 光泽（-1 哑光 … 1 水光），lift = 平均亮度往新颜色的亮度靠多少（1 = 完全变成新颜色的深浅）。
         */
        class Tint(val color: MakeupColor, a: Float, val base: Float, val shine: Float, val lift: Float) : Mode(a)
    }
}

object MakeupRenderer {
    /**
     * iOS 版三个 CIColorKernel（mkMultiply / mkScreen / mkTint）合成一个着色器，mode 选哪一个。
     * 颜色运算在 sRGB（伽马）值上做，和 iOS 一样；位图本来就是 sRGB，不用换算。
     */
    private const val LAYER = Shaders.HEADER + """
uniform sampler2D src;
uniform sampler2D mask;
uniform vec2 size;
uniform vec4 color;
uniform vec4 p;
uniform int mode;
uniform vec2 orig;
uniform float side;
void main() {
    vec4 px = texture(src, uv);
    vec3 c = px.a > 0.0 ? px.rgb / px.a : vec3(0.0);
    vec2 mp = (orig + uv * size) / side;
    float mv = (mp.x < 0.0 || mp.y < 0.0 || mp.x > 1.0 || mp.y > 1.0) ? 0.0 : texture(mask, mp).a;
    float a = clamp(mv * color.a, 0.0, 1.0);
    vec3 r;
    if (mode == 0) {
        r = c * mix(vec3(1.0), color.rgb, a);
    } else if (mode == 1) {
        vec3 add = color.rgb * a;
        vec3 room = 1.0 - clamp(c, 0.0, 1.0);
        r = c + add * room;
    } else {
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float tl = max(dot(color.rgb, vec3(0.299, 0.587, 0.114)), 0.02);
        float shine = p.y;
        float target = p.x + (tl - p.x) * p.z;
        float delta = l - p.x;
        float k = delta > 0.0 ? (0.62 + 0.3 * shine) : 0.9;
        float ln = clamp(target + delta * k, 0.0, 1.0);
        vec3 col = ln <= tl ? color.rgb * (ln / tl) : mix(color.rgb, vec3(1.0), (ln - tl) / max(1.0 - tl, 0.001));
        float spec = smoothstep(0.78, 0.97, l) * (0.25 + 0.75 * max(shine, 0.0));
        col = mix(col, max(col, c), spec);
        r = mix(c, col, a);
    }
    o = vec4(clamp(r, 0.0, 1.0) * px.a, px.a);
}
"""

    private const val TILE = 2048
    private val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /**
     * 给一张脸上妆，直接画在 target 上。target 是整张图（fullW × fullH 像素）里从 (offX, offY) 开始的那一块，
     * 整张图时 off 为 0。只处理这张脸方框里的像素，大图按块送进 GPU。
     */
    fun render(makeup: FaceMakeup, target: Bitmap, fullW: Float, fullH: Float, offX: Int, offY: Int) {
        val g = MKFace.create(makeup.face, fullW, fullH) ?: return
        val roi = g.roi
        val x0 = maxOf(floor(max(roi.minX, 0f)).toInt(), offX)
        val y0 = maxOf(floor(max(roi.minY, 0f)).toInt(), offY)
        val x1 = minOf(ceil(min(roi.maxX, fullW)).toInt(), offX + target.width)
        val y1 = minOf(ceil(min(roi.maxY, fullH)).toInt(), offY + target.height)
        if (x1 - x0 < 4 || y1 - y0 < 4) return

        val layers = layers(makeup, g).filter { it.mode.amount > 0 }
        if (layers.isEmpty()) return
        val faceKey = key(makeup.face, fullW, fullH)
        val fine = if (roi.width <= 1400) 1024 else 2048

        // 先把每层的蒙版（画好、羽化好）准备齐，都有缓存
        val prepared = layers.mapNotNull { layer ->
            val size = layer.shape.raster(fine)
            // iOS 在原图上按 max(0.5, blur × 方框边长) 像素羽化；这里换算成蒙版自己的像素
            val sigma = if (layer.blur > 0) max(0.5f, layer.blur * roi.width) * size / roi.width else 0f
            mask(faceKey, layer.shape, size, sigma, g, fullW, fullH)?.let { layer to it }
        }
        if (prepared.isEmpty()) return

        val tile = min(TILE, Gpu.maxTextureSize)
        val canvas = Canvas(target)
        var ty = y0
        while (ty < y1) {
            val th = min(tile, y1 - ty)
            var tx = x0
            while (tx < x1) {
                val tw = min(tile, x1 - tx)
                val sub = Bitmap.createBitmap(target, tx - offX, ty - offY, tw, th)
                val orig = floatArrayOf(tx - roi.minX, ty - roi.minY)
                val piece = Gpu.run {
                    var t = upload(sub)
                    for ((layer, mask) in prepared) {
                        val argb = argbMask(mask)
                        val m = upload(argb)
                        argb.recycle()
                        val out = pass(LAYER, mapOf("src" to t, "mask" to m), uniforms(layer.mode, orig, roi.width), tw, th)
                        release(t)
                        release(m)
                        t = out
                    }
                    read(t)
                }
                canvas.drawBitmap(piece, (tx - offX).toFloat(), (ty - offY).toFloat(), replace)
                piece.recycle()
                if (sub !== target) sub.recycle()
                tx += tw
            }
            ty += th
        }
    }

    private fun uniforms(mode: MakeupLayer.Mode, orig: FloatArray, side: Float): Map<String, Any> {
        val (c, kind, p) = when (mode) {
            is MakeupLayer.Mode.Multiply -> Triple(mode.color, 0, floatArrayOf(0f, 0f, 0f, 0f))
            is MakeupLayer.Mode.Screen -> Triple(mode.color, 1, floatArrayOf(0f, 0f, 0f, 0f))
            is MakeupLayer.Mode.Tint -> Triple(mode.color, 2, floatArrayOf(mode.base, mode.shine, mode.lift, 0f))
        }
        return mapOf(
            "color" to floatArrayOf(c.r, c.g, c.b, mode.amount),
            "p" to p,
            "mode" to kind,
            "orig" to orig,
            "side" to side,
        )
    }

    /** 只有 alpha 的蒙版画成白色 RGBA（r = g = b = a），才能传进 GPU。 */
    private fun argbMask(a8: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(a8.width, a8.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(a8, 0f, 0f, Paint().apply { color = Color.WHITE })
        return out
    }

    /** 画好并羽化好的蒙版（ALPHA_8）。sigma 是蒙版像素下的高斯 σ（和 CIGaussianBlur 的 radius 一样）。 */
    private fun mask(faceKey: Int, shape: MKShape, size: Int, sigma: Float, g: MKFace, w: Float, h: Float): Bitmap? {
        val raw = MKMaskCache.image(MKMaskCache.Key(faceKey, shape, size)) { g.drawMask(shape, size, w, h) } ?: return null
        // 框架的模糊半径 = σ × 2.5，小于 0.5 像素时不模糊
        if (sigma * 2.5f < 0.5f) return raw
        return MKMaskCache.image(MKMaskCache.Key(faceKey, shape, size, (sigma * 100).roundToInt().coerceAtLeast(1))) {
            val argb = argbMask(raw)
            val blurred = Gpu.run {
                val t = upload(argb)
                read(with(ColorGL) { gauss(t, sigma) })
            }
            argb.recycle()
            blurred.extractAlpha().also { blurred.recycle() }
        }
    }

    /** 给缓存用的"这是哪张脸"：关键点和图片比例都一样才算同一张。 */
    private fun key(face: FaceInfo, w: Float, h: Float): Int {
        var hash = 17
        fun add(points: List<PointF>) {
            for (p in points) {
                hash = hash * 31 + (p.x * 100_000).roundToInt()
                hash = hash * 31 + (p.y * 100_000).roundToInt()
            }
        }
        add(face.contour); add(face.leftEye); add(face.rightEye); add(face.leftBrow); add(face.rightBrow)
        add(face.outerLips); add(face.innerLips); add(face.nose); add(face.noseCrest ?: emptyList())
        add(listOfNotNull(face.leftPupil, face.rightPupil))
        hash = hash * 31 + (w / max(h, 1f) * 10_000).roundToInt()
        return hash
    }

    // MARK: 每一样妆拆成哪几层

    private val shade = MakeupColor(0.60f, 0.46f, 0.38f)
    private val light = MakeupColor(1.0f, 0.96f, 0.90f)

    private fun layers(m: FaceMakeup, g: MKFace): List<MakeupLayer> {
        val list = ArrayList<MakeupLayer>()
        val d = g.eyeDist
        val fw = g.faceWidth
        val eyeWidth = max(g.eyes[0].width, g.eyes[1].width)
        fun mul(c: MakeupColor, a: Float) = MakeupLayer.Mode.Multiply(c, a)
        fun scr(c: MakeupColor, a: Float) = MakeupLayer.Mode.Screen(c, a)

        // 修容：阴影
        val c = m.contour
        if (c.noseShade > 0) list += MakeupLayer(MKShape.NoseShade, d * 0.06f, mul(shade, 0.85f * c.noseShade))
        if (c.jaw > 0) list += MakeupLayer(MKShape.ShJaw, fw * 0.045f, mul(shade, 0.90f * c.jaw))
        if (c.cheekShade > 0) list += MakeupLayer(MKShape.ShCheek, fw * 0.05f, mul(shade, 0.85f * c.cheekShade))
        if (c.temple > 0) list += MakeupLayer(MKShape.ShTemple, mode = mul(shade, 0.75f * c.temple))

        // 腮红
        val blush = m.blush
        if (blush != null && blush.amount > 0) {
            list += MakeupLayer(MKShape.Blush(blush.placement), mode = mul(blush.color.normalized.mix(MakeupColor.white, 0.2f), 0.62f * blush.amount))
            // 很浅的粉色光靠叠底显不出来，再提一点亮
            val pale = mkSmoothstep(0.55f, 0.8f, blush.color.luma)
            if (pale > 0) list += MakeupLayer(MKShape.Blush(blush.placement), mode = scr(blush.color, 0.16f * pale * blush.amount))
            if (blush.glow) list += MakeupLayer(MKShape.BlushGlow(blush.placement), mode = scr(light, 0.24f * blush.amount))
        }

        // 雀斑
        if (c.freckleAmount > 0) {
            list += MakeupLayer(MKShape.Freckles(c.freckles), d * 0.004f, mul(MakeupColor(0.70f, 0.50f, 0.38f), 0.55f * c.freckleAmount))
        }

        // 修容：高光
        if (c.noseLight > 0) list += MakeupLayer(MKShape.NoseLight, d * 0.035f, scr(light, 0.44f * c.noseLight))
        if (c.forehead > 0) list += MakeupLayer(MKShape.HlForehead, mode = scr(light, 0.32f * c.forehead))
        if (c.cheekLight > 0) list += MakeupLayer(MKShape.HlCheek, mode = scr(light, 0.27f * c.cheekLight))
        if (c.chinLight > 0) list += MakeupLayer(MKShape.HlChin, mode = scr(light, 0.32f * c.chinLight))
        if (c.cupid > 0) list += MakeupLayer(MKShape.HlCupid, mode = scr(light, 0.2f * c.cupid))
        if (c.underEye > 0) list += MakeupLayer(MKShape.HlUnderEye, mode = scr(light, 0.28f * c.underEye))

        // 眉毛
        val brow = m.brow
        val browLength = g.brows.mapNotNull { it?.length }.maxOrNull()
        if (brow != null && brow.amount > 0 && browLength != null) {
            val soft = brow.shape == MKBrowShape.SOFT
            list += MakeupLayer(MKShape.BrowFill(brow.shape), browLength * (if (soft) 0.07f else 0.035f),
                mul(brow.color.mix(MakeupColor.white, 0.18f), (if (soft) 0.6f else 0.82f) * brow.amount))
            list += MakeupLayer(MKShape.BrowHair(brow.shape), browLength * 0.004f, mul(brow.color, (if (soft) 0.2f else 0.5f) * brow.amount))
        }

        // 眼影
        val s = m.eye.shadow
        if (s != null && s.amount > 0) {
            list += MakeupLayer(MKShape.ShadowCrease, eyeWidth * 0.14f, mul(s.crease, 0.55f * s.amount))
            list += MakeupLayer(MKShape.ShadowLid, eyeWidth * 0.07f, mul(s.lid, 0.75f * s.amount))
            list += MakeupLayer(MKShape.ShadowOuter, mode = mul(s.outer, 0.48f * s.amount))
            if (s.lower > 0) {
                list += MakeupLayer(MKShape.ShadowLower, eyeWidth * 0.05f, mul(s.crease.mix(s.outer, 0.4f), 0.6f * s.lower * s.amount))
            }
            if (s.smoke > 0) list += MakeupLayer(MKShape.ShadowSmoke, eyeWidth * 0.07f, mul(s.outer, 0.5f * s.smoke * s.amount))
            if (s.shimmer > 0) list += MakeupLayer(MKShape.Shimmer, mode = scr(s.lid.mix(MakeupColor.white, 0.6f), 0.42f * s.shimmer * s.amount))
            if (s.sparkle) list += MakeupLayer(MKShape.Sparkle, eyeWidth * 0.004f, scr(MakeupColor.white, 0.85f * s.amount))
        }
        if (m.eye.foldAmount > 0) {
            list += MakeupLayer(MKShape.Fold(m.eye.fold), eyeWidth * 0.012f, mul(MakeupColor(0.45f, 0.31f, 0.27f), 0.6f * m.eye.foldAmount))
        }
        if (m.eye.aegyo > 0) {
            list += MakeupLayer(MKShape.AegyoLight, eyeWidth * 0.045f, scr(MakeupColor(1f, 0.94f, 0.90f), 0.46f * m.eye.aegyo))
            list += MakeupLayer(MKShape.AegyoShade, eyeWidth * 0.035f, mul(MakeupColor(0.72f, 0.57f, 0.53f), 0.6f * m.eye.aegyo))
        }
        val lens = m.eye.lens
        if (lens != null && lens.amount > 0) {
            list += MakeupLayer(MKShape.Lens, eyeWidth * 0.012f,
                MakeupLayer.Mode.Tint(lens.color, 0.85f * lens.amount, m.tone.iris, 0.3f, 0.34f))
            list += MakeupLayer(MKShape.LensRays, eyeWidth * 0.004f, scr(lens.color.mix(MakeupColor.white, 0.5f), 0.15f * lens.amount))
            list += MakeupLayer(MKShape.LensRing, eyeWidth * 0.008f, mul(lens.color.scaled(0.25f), 0.62f * lens.amount))
        }
        val liner = m.eye.liner
        if (liner != null && liner.amount > 0) {
            list += MakeupLayer(MKShape.Liner(liner.style), eyeWidth * 0.006f, mul(liner.color, liner.amount))
        }
        val lashes = m.eye.lashes
        if (lashes != null && lashes.amount > 0) {
            list += MakeupLayer(MKShape.Lashes(lashes.style), eyeWidth * 0.002f, mul(MakeupColor.black, lashes.amount))
        }
        if (m.eye.catchlightAmount > 0) {
            list += MakeupLayer(MKShape.Catchlight(m.eye.catchlight), eyeWidth * 0.006f, scr(MakeupColor.white, m.eye.catchlightAmount))
        }

        // 口红
        val lip = m.lip
        if (lip != null && lip.amount > 0) {
            val w = g.mouthWidth
            val (gain, shine) = when (lip.texture) {
                MKLipTexture.MATTE -> 1f to -1f
                MKLipTexture.VELVET -> 0.95f to -0.6f
                MKLipTexture.SATIN -> 0.88f to 0f
                MKLipTexture.GLOSS -> 0.78f to 0.7f
                MKLipTexture.GLAZE -> 0.84f to 1f
            }
            val shape = if (lip.style == MKLipStyle.GRADIENT) MKShape.LipGradient else MKShape.LipFull
            val blur = when (lip.style) {
                MKLipStyle.BLURRED -> w * 0.09f
                MKLipStyle.GRADIENT -> w * 0.05f
                MKLipStyle.FULL -> w * 0.032f
            }
            list += MakeupLayer(shape, blur, MakeupLayer.Mode.Tint(lip.color, gain * lip.amount, m.tone.lip, shine, 0.9f))
            if (lip.liner > 0) list += MakeupLayer(MKShape.LipLine, w * 0.014f, mul(lip.color.scaled(0.72f), 0.6f * lip.liner))
            val gloss = when (lip.texture) {
                MKLipTexture.GLAZE -> 0.5f
                MKLipTexture.GLOSS -> 0.3f
                MKLipTexture.SATIN -> 0.1f
                else -> 0f
            }
            if (gloss > 0) {
                list += MakeupLayer(MKShape.LipGloss(lip.texture == MKLipTexture.GLAZE), w * 0.012f, scr(MakeupColor.white, gloss * lip.amount))
            }
        }

        // 痣
        if (c.moles.isNotEmpty()) {
            list += MakeupLayer(MKShape.Moles(c.moles), d * 0.004f, mul(MakeupColor(0.30f, 0.20f, 0.16f), 0.9f))
        }
        return list
    }
}
