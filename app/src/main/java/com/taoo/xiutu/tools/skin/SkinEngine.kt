package com.taoo.xiutu.tools.skin

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.gpu.GpuScope
import com.taoo.xiutu.gpu.Shaders
import com.taoo.xiutu.gpu.Tex
import com.taoo.xiutu.ml.FaceInfo
import com.taoo.xiutu.tools.color.ColorGL
import com.taoo.xiutu.tools.color.TileInfo
import com.taoo.xiutu.tools.color.Tiler
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 皮肤工具的图像处理（iOS SkinEngine 的 GPU 版）。
 *
 * 思路：每张脸只处理它周围的一块（脸 + 额头 + 脖子）。
 * - 哪里是皮肤 = 按五官画出来的范围 × 颜色像不像这张脸的肤色；
 * - 磨皮走"分频"：只把中间那一档（色块、痘印、细纹）抹平，毛孔级的细纹理留着；
 * - 丰盈 / 提拉是在对应位置补光、填暗，再加一点点变形。
 *
 * 颜色运算和 iOS 一样在线性光里做；纹理是 8 位 sRGB，着色器里读进来先换成线性，写出去再换回。
 * 加权平均图存成 (sRGB 颜色 × 权重, 权重)。大的平滑图在缩小版上算，全分辨率只做逐像素合成（大图分块）。
 */
internal object SkinEngine {

    // MARK: - 着色器

    private const val LIB = """
float skL(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 skLin(vec3 g) { g = clamp(g, 0.0, 1.0); return mix(g / 12.92, pow((g + 0.055) / 1.055, vec3(2.4)), step(0.04045, g)); }
vec3 skGam(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
// 预乘的 sRGB 采样 → 未预乘的线性颜色
vec3 skRead(vec4 s) { return skLin(s.a > 0.0 ? s.rgb / s.a : vec3(0.0)); }
vec4 skWrite(vec3 c, float a) { return vec4(skGam(c) * a, a); }
// 加权平均图 → 线性颜色
vec3 skUn(vec4 p) { return skLin(p.rgb / max(p.a, 0.004)); }
vec3 skGamOf(vec4 s) { return s.a > 0.0 ? s.rgb / s.a : vec3(0.0); }
float skLike(vec3 c, vec3 t) {
    vec3 a = max(c, vec3(0.002));
    vec3 m = max(t, vec3(0.002));
    float u = log(a.r / a.g) - log(m.r / m.g);
    float v = log(a.b / a.g) - log(m.b / m.g);
    float su = u < 0.0 ? 0.34 : 0.26;
    float sv = v > 0.0 ? 0.50 : 0.36;
    float d2 = (u * u) / (su * su) + (v * v) / (sv * sv);
    float l = skL(a) / max(skL(m), 0.002);
    return exp(-0.5 * d2) * smoothstep(0.08, 0.30, l);
}
float skSat(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    return (mx - min(c.r, min(c.g, c.b))) / max(mx, 0.0005);
}
float skBodyLike(vec3 c, vec3 t) {
    vec3 a = max(c, vec3(0.002));
    vec3 m = max(t, vec3(0.002));
    float u = log(a.r / a.g) - log(m.r / m.g);
    float v = log(a.b / a.g) - log(m.b / m.g);
    float su = u < 0.0 ? 0.26 : 0.20;
    float sv = v > 0.0 ? 0.34 : 0.24;
    float l = skL(a) / max(skL(m), 0.002);
    float tone = exp(-0.5 * ((u * u) / (su * su) + (v * v) / (sv * sv)));
    return tone * smoothstep(0.08, 0.30, l) * (1.0 - smoothstep(1.8, 2.6, l))
         * smoothstep(0.35, 0.70, skSat(a) / max(skSat(m), 0.02));
}
"""

    /** 只统计皮肤的加权图（缩小版上）。 */
    private const val WEIGHT = Shaders.HEADER + LIB + """
uniform sampler2D src;
uniform sampler2D ma;
uniform sampler2D mb;
uniform vec3 tone;
void main() {
    vec4 s = texture(src, uv);
    float w = max(texture(ma, uv).r, texture(mb, uv).g) * skLike(skRead(s), tone);
    o = vec4(skGamOf(s) * w, w);
}
"""

    private const val DEV = Shaders.HEADER + LIB + """
uniform sampler2D src;
uniform sampler2D mean;
void main() {
    float g = sqrt(max(skL(skRead(texture(src, uv))), 0.0));
    float gm = sqrt(max(skL(skRead(texture(mean, uv))), 0.0));
    float v = min(abs(g - gm) * 4.0, 1.0);
    o = vec4(v, v, v, 1.0);
}
"""

    private const val COEF = Shaders.HEADER + """
uniform sampler2D src;
uniform float e;
void main() {
    float v = texture(src, uv).r * 0.25;
    float a = (v * v) / (v * v + e);
    o = vec4(a, a, a, 1.0);
}
"""

    /** 一张脸的磨皮 / 肤色 / 丰盈（全分辨率，一块一块地做）。 */
    private const val RETOUCH = Shaders.HEADER + ColorGL.TILE_HEAD + LIB + """
uniform sampler2D src;
uniform sampler2D fineT;
uniform sampler2D meanT;
uniform sampler2D coefT;
uniform sampler2D midT;
uniform sampler2D wideT;
uniform sampler2D maT;
uniform sampler2D mbT;
uniform sampler2D mcT;
uniform sampler2D mdT;
uniform sampler2D mfT;
uniform vec4 kp;
uniform vec4 kw;
uniform vec4 kx;
uniform vec4 kt;
uniform vec4 ke;
uniform vec4 ka;
uniform vec4 kb;
uniform float teeth;
uniform vec3 tone;
uniform float doTex;
uniform float doTone;
uniform float doPlump;

vec3 skTexture(vec3 c, vec3 fine, vec3 base, vec4 mid, vec4 wide, vec4 ma, vec4 mb, vec4 mc, vec4 p, vec4 w, vec4 x, vec3 tone) {
    vec3 s = c;
    float conf = smoothstep(0.04, 0.30, mid.a);
    float confW = smoothstep(0.04, 0.30, wide.a);
    vec3 m = skUn(mid);
    vec3 wd = skUn(wide);
    float skin = ma.r * smoothstep(0.12, 0.55, mid.a);
    vec3 dF = c - fine;
    float lc = max(skL(c), 0.0005);
    float lm = max(skL(m), 0.0005);
    float lf = max(skL(fine), 0.0005);
    float lb = max(skL(base), 0.0005);
    // 比周围皮肤暗很多的东西（发丝、痣、鼻孔）不去抹它。x.w = 1：只有人脸框，按亮度和肤色逐像素让开五官。
    float feature = smoothstep(mix(0.30, 0.45, x.w), mix(0.55, 0.75, x.w), lf / lm);
    skin *= feature * mix(1.0, smoothstep(0.02, 0.25, skLike(fine, tone)), x.w);

    float lift = w.x * ma.g * ma.r + w.y * ma.b + w.z * mb.r + w.w * mb.g + x.x * mb.b + x.y * mc.r;
    lift = min(lift, 1.0) * conf;
    float ln = mix(lf, lc, 0.6);
    float crease = max(lm - ln, 0.0);
    float flatten = x.z * ma.g * ma.r * conf;
    float bump = max(ln - lm, 0.0);
    c *= max(lc + crease * lift * 0.92 - bump * flatten * 0.7, 0.0) / lc;
    vec3 b2 = base * (max(lb + (lm - lb) * max(lift * 0.9, flatten * 0.7), 0.0) / lb);

    float dip = (lm - lf) / lm;
    float red = s.r / lc - m.r / lm;
    float blem = smoothstep(0.05, 0.18, max(dip, 0.0) + max(red, 0.0) * 0.6);
    c = mix(c, m + dF * 0.85, p.w * blem * skin * conf);
    b2 = mix(b2, m, p.w * blem * skin * conf);

    float sm = max(p.x, x.z * ma.g * 0.8) * skin;
    b2 = mix(b2, m, 0.45 * p.x * conf);
    c = mix(c, b2 + dF * mix(1.0, 0.62, p.x), sm);

    float lw = max(skL(wd), 0.0005);
    vec3 chroma = (wd / lw) / max(m / lm, vec3(0.05));
    vec3 g = clamp(chroma * pow(lw / lm, 0.30), vec3(0.75), vec3(1.35));
    c *= mix(vec3(1.0), g, p.z * skin * conf * confW);

    c += clamp(dF, vec3(-0.10 * lc), vec3(0.10 * lc)) * p.y * 0.65 * skin;
    return max(c, vec3(0.0));
}

vec3 skTone(vec3 c, vec4 mid, vec4 wide, vec4 ma, vec4 mc, vec4 mf, vec4 t, vec4 e, float teeth, vec3 tone) {
    float conf = smoothstep(0.04, 0.30, mid.a);
    float confW = smoothstep(0.04, 0.30, wide.a);
    vec3 m = skUn(mid);
    vec3 wd = skUn(wide);
    float lm = max(skL(m), 0.0005);
    float lw = max(skL(wd), 0.0005);
    float lt = max(skL(tone), 0.002);

    float l = max(skL(c), 0.0005);
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float sat = (mx - mn) / max(mx, 0.0005);
    float satW = (max(wd.r, max(wd.g, wd.b)) - min(wd.r, min(wd.g, wd.b))) / max(max(wd.r, max(wd.g, wd.b)), 0.0005);
    float shine = smoothstep(1.06, 1.45, l / lw) * (1.0 - smoothstep(0.70, 1.0, sat / max(satW, 0.02))) * ma.r * conf * confW * t.w;
    float ln = mix(l, min(l, lw * 1.10), shine * 0.85);
    c *= ln / l;
    c = mix(c, (wd / lw) * skL(c), shine * 0.6);

    float under = mc.r * e.x * conf * confW;
    vec3 want = mix(wd / lw, tone / lt, 0.5);
    float lcur = max(skL(c), 0.0005);
    float gl = min(clamp(pow(lw / lm, 1.4), 1.0, 1.7), max(1.0, lw * 1.05 / lcur));
    vec3 gain = clamp(want / max(m / lm, vec3(0.05)), vec3(0.8), vec3(1.25)) * gl;
    c *= mix(vec3(1.0), gain, under * 0.9);

    float skin = smoothstep(0.10, 0.50, mid.a) * mix(0.35, 1.0, skLike(c, tone));
    vec3 cw = pow(max(c, vec3(0.0)), vec3(1.0 / (1.0 + 0.42 * t.x)));
    cw = mix(vec3(skL(cw)), cw, 1.0 - 0.10 * t.x);
    c = mix(c, cw, skin);
    vec3 tint = vec3(1.0 + 0.10 * t.y + 0.10 * t.z, 1.0 - 0.04 * t.z, 1.0 - 0.14 * t.y + 0.03 * t.z);
    vec3 ct = c * tint;
    ct *= skL(c) / max(skL(ct), 0.0005);
    c = mix(c, ct, skin);

    float ei = mf.r;
    l = max(skL(c), 0.0005);
    mx = max(c.r, max(c.g, c.b));
    mn = min(c.r, min(c.g, c.b));
    sat = (mx - mn) / max(mx, 0.0005);
    float bright = smoothstep(0.35, 0.70, sqrt(l / lt));
    vec3 g2 = sqrt(max(c, vec3(0.0)));
    vec3 sc = g2 * g2 * (3.0 - 2.0 * g2);
    vec3 eb = mix(g2, sc, 0.45 * e.y) * (1.0 + 0.10 * e.y);
    eb = eb * eb;
    eb = mix(vec3(skL(eb)), eb, 1.0 + 0.2 * e.y);
    c = mix(c, eb, ei);
    float sclera = ei * (1.0 - smoothstep(0.22, 0.48, sat)) * bright;
    vec3 cs = mix(c, vec3(skL(c)), 0.5 * e.z) * (1.0 + 0.22 * e.z);
    c = mix(c, cs, sclera);
    float veins = ei * (1.0 - smoothstep(0.35, 0.65, sat)) * bright;
    c.r -= max(c.r - max(c.g, c.b) * 1.04, 0.0) * 0.85 * e.w * veins;

    l = max(skL(c), 0.0005);
    float tooth = mf.g * smoothstep(0.30, 0.60, sqrt(l / lt)) * (1.0 - smoothstep(1.30, 1.75, c.r / max(c.g, 0.0005)));
    vec3 cz = mix(c, vec3(l), 0.55 * teeth) * (1.0 + 0.25 * teeth);
    cz.b *= 1.0 + 0.04 * teeth;
    c = mix(c, cz, tooth);
    return max(c, vec3(0.0));
}

vec3 skPlump(vec3 c, vec4 mid, vec4 wide, vec4 mc, vec4 md, vec4 mf, vec4 a, vec4 b) {
    float conf = smoothstep(0.04, 0.30, mid.a) * smoothstep(0.04, 0.30, wide.a);
    vec3 m = skUn(mid);
    vec3 wd = skUn(wide);
    float lm = max(skL(m), 0.0005);
    float lw = max(skL(wd), 0.0005);
    float fill = clamp(lw / lm, 1.0, 1.6) - 1.0;
    float bright = a.x * md.b * 0.20 + a.z * mc.b * 0.24 + a.y * mc.g * 0.10 + a.w * md.r * 0.12
                 + b.x * mf.b * 0.14 + b.y * md.g * 0.14;
    float hollow = a.x * md.b * 0.4 + a.y * mc.g * 1.0 + a.z * mc.b * 0.4 + a.w * md.r * 0.9
                 + b.x * mf.b * 1.0 + b.y * md.g * 0.8;
    float l = max(skL(c), 0.0005);
    float room = (1.0 - smoothstep(0.6, 1.0, l)) * (1.0 - smoothstep(1.10, 1.60, l / lw));
    c *= 1.0 + (bright + fill * min(hollow, 1.0)) * room * conf;
    vec3 chroma = clamp((wd / lw) / max(m / lm, vec3(0.05)), vec3(0.85), vec3(1.2));
    c *= mix(vec3(1.0), chroma, a.y * mc.g * conf * 0.6);
    return max(c, vec3(0.0));
}

void main() {
    vec4 s = texture(src, uv);
    vec2 g = guv();
    vec3 c = skRead(s);
    vec4 mid = texture(midT, g);
    vec4 wide = texture(wideT, g);
    vec4 A = texture(maT, g);
    vec4 B = texture(mbT, g);
    vec4 C = texture(mcT, g);
    vec4 D = texture(mdT, g);
    vec4 F = texture(mfT, g);
    if (doTex > 0.5) {
        // 保边的底子：反差大的地方跟原图走，反差小的地方取周围的平均。
        vec3 mean = skRead(texture(meanT, g));
        float k = texture(coefT, g).r;
        vec3 base = mix(mean, c, k);
        vec3 fine = skRead(texture(fineT, uv));
        c = skTexture(c, fine, base, mid, wide, A, B, C, kp, kw, kx, tone);
    }
    if (doTone > 0.5) c = skTone(c, mid, wide, A, C, F, kt, ke, teeth, tone);
    if (doPlump > 0.5) c = skPlump(c, mid, wide, C, D, F, ka, kb);
    o = skWrite(c, s.a);
}
"""

    /** 遮瑕：涂过的地方挖掉，只留周围的颜色（缩小版上）。rectN = 这一块在整图里的归一化位置。 */
    private const val HOLE = Shaders.HEADER + LIB + """
uniform sampler2D src;
uniform sampler2D bm;
uniform vec4 rectN;
void main() {
    vec4 s = texture(src, uv);
    float w = 1.0 - smoothstep(0.02, 0.40, texture(bm, rectN.xy + uv * rectN.zw).b);
    o = vec4(skGamOf(s) * w, w);
}
"""

    private const val CONCEAL = Shaders.HEADER + ColorGL.TILE_HEAD + LIB + """
uniform sampler2D src;
uniform sampler2D nearT;
uniform sampler2D farT;
uniform sampler2D fineT;
uniform sampler2D bm;
uniform vec4 rectN;
void main() {
    vec4 s = texture(src, uv);
    vec2 g = guv();
    vec3 c = skRead(s);
    vec4 near = texture(nearT, g);
    vec4 far = texture(farT, g);
    vec3 f1 = skUn(near);
    vec3 f2 = skUn(far);
    vec3 f = mix(f2, f1, smoothstep(0.06, 0.35, near.a));
    float ok = smoothstep(0.01, 0.10, far.a);
    vec3 r = f + (c - skRead(texture(fineT, uv))) * 0.8;
    float b = texture(bm, rectN.xy + g * rectN.zw).b;
    o = skWrite(mix(c, max(r, vec3(0.0)), b * ok), s.a);
}
"""

    /** 修容笔：高光提亮，阴影压暗并略微偏暖。 */
    private const val BRUSH = Shaders.HEADER + ColorGL.TILE_HEAD + LIB + """
uniform sampler2D src;
uniform sampler2D bm;
uniform vec4 rectN;
void main() {
    vec4 s = texture(src, uv);
    vec3 c = skRead(s);
    vec4 m = texture(bm, rectN.xy + guv() * rectN.zw);
    float l = skL(c);
    float up = 1.0 + m.r * 0.32 * (1.0 - smoothstep(0.45, 1.0, l));
    vec3 down = mix(vec3(1.0), vec3(0.70, 0.64, 0.60), m.g * 0.6);
    o = skWrite(c * up * down, s.a);
}
"""

    private const val BODY = Shaders.HEADER + LIB + """
uniform sampler2D src;
uniform sampler2D body;
uniform vec3 tone;
void main() {
    vec4 s = texture(src, uv);
    float w = skBodyLike(skRead(s), tone) * texture(body, uv).r;
    o = vec4(skGamOf(s) * w, w);
}
"""

    private const val UNIFY = Shaders.HEADER + ColorGL.TILE_HEAD + LIB + """
uniform sampler2D src;
uniform sampler2D wideT;
uniform sampler2D body;
uniform vec3 tone;
uniform vec3 target;
uniform float amount;
void main() {
    vec4 s = texture(src, uv);
    vec2 g = guv();
    vec3 c = skRead(s);
    vec4 wide = texture(wideT, g);
    vec3 m = skUn(wide);
    float lm = max(skL(m), 0.0005);
    float lt = max(skL(target), 0.0005);
    vec3 chroma = (target / lt) / max(m / lm, vec3(0.05));
    vec3 gg = clamp(chroma * pow(lt / lm, 0.35), vec3(0.6), vec3(1.8));
    float w = smoothstep(0.05, 0.35, wide.a) * skBodyLike(c, tone) * texture(body, g).r;
    o = skWrite(c * mix(vec3(1.0), gg, amount * w), s.a);
}
"""

    /** 提拉 / 丰盈的变形：位移图只铺在处理范围（roiE，以这一块所在的大范围为坐标）里。 */
    private const val WARP = Shaders.HEADER + ColorGL.TILE_HEAD + """
uniform sampler2D src;
uniform sampler2D map;
uniform vec4 roiE;
uniform float shift;
void main() {
    vec2 gp = gpix();
    vec2 mu = (gp - roiE.xy) / roiE.zw;
    vec2 disp = vec2(0.0);
    if (mu.x >= 0.0 && mu.y >= 0.0 && mu.x <= 1.0 && mu.y <= 1.0) {
        disp = (texture(map, mu).rg - vec2(0.50196078)) * shift;
    }
    vec2 p = gp - disp;
    o = texture(src, (p - orig) / size);
}
"""

    // MARK: - 小工具

    /** iOS 的高斯半径约等于 sigma；框架的 blur 用"窗口半径"（sigma × 2.5）。 */
    private fun GpuScope.gauss(t: Tex, sigma: Float): Tex = blur(t, sigma * 2.5f)

    private val srcPaint = Paint().apply { blendMode = BlendMode.SRC }

    /** 把 work 里 rect 这一块拿出来处理（大图分块），再原样放回去。 */
    private fun <C> region(
        work: Bitmap,
        rect: Rect,
        margin: Int,
        prepare: GpuScope.(Bitmap) -> C,
        tile: GpuScope.(Tex, TileInfo, C) -> Tex,
    ) {
        if (rect.width() < 2 || rect.height() < 2) return
        val crop = Bitmap.createBitmap(work, rect.left, rect.top, rect.width(), rect.height())
        val out = Tiler.render(crop, margin, prepare = { prepare(this, crop) }, tile = tile)
        Canvas(work).drawBitmap(out, rect.left.toFloat(), rect.top.toFloat(), srcPaint)
        out.recycle()
        if (crop !== work) crop.recycle()
    }

    private fun rectN(rect: Rect, w: Int, h: Int) = floatArrayOf(
        rect.left / w.toFloat(), rect.top / h.toFloat(), rect.width() / w.toFloat(), rect.height() / h.toFloat(),
    )

    private val white1: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } }

    // MARK: - CPU 上的颜色函数（量肤色、算统一的目标色）

    private val linLut = FloatArray(256) {
        val g = it / 255f
        if (g <= 0.04045f) g / 12.92f else ((g + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun luma(r: Float, g: Float, b: Float) = r * 0.2126f + g * 0.7152f + b * 0.0722f

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun sat(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b))
        return (mx - min(r, min(g, b))) / max(mx, 0.0005f)
    }

    private fun bodyLike(cr: Float, cg: Float, cb: Float, t: SkinTone): Float {
        val ar = max(cr, 0.002f); val ag = max(cg, 0.002f); val ab = max(cb, 0.002f)
        val mr = max(t.r, 0.002f); val mg = max(t.g, 0.002f); val mb = max(t.b, 0.002f)
        val u = ln(ar / ag) - ln(mr / mg)
        val v = ln(ab / ag) - ln(mb / mg)
        val su = if (u < 0) 0.26f else 0.20f
        val sv = if (v > 0) 0.34f else 0.24f
        val l = luma(ar, ag, ab) / max(luma(mr, mg, mb), 0.002f)
        val tone = exp(-0.5f * ((u * u) / (su * su) + (v * v) / (sv * sv)))
        return tone * smooth(0.08f, 0.30f, l) * (1 - smooth(1.8f, 2.6f, l)) *
            smooth(0.35f, 0.70f, sat(ar, ag, ab) / max(sat(mr, mg, mb), 0.02f))
    }

    /** 美白、冷暖、红润对"平均肤色"本身的影响，和着色器里的算法一致。统一肤色时拿它当目标。 */
    fun toned(tone: SkinTone, white: Float, warmth: Float, rosy: Float): SkinTone {
        var r = max(tone.r, 0f); var g = max(tone.g, 0f); var b = max(tone.b, 0f)
        val e = 1 / (1 + 0.42f * white)
        r = r.pow(e); g = g.pow(e); b = b.pow(e)
        val l = luma(r, g, b)
        val k = 1 - 0.10f * white
        r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
        val tr = r * (1 + 0.10f * warmth + 0.10f * rosy)
        val tg = g * (1 - 0.04f * rosy)
        val tb = b * (1 - 0.14f * warmth + 0.03f * rosy)
        val s = luma(r, g, b) / max(luma(tr, tg, tb), 0.0005f)
        return SkinTone(tr * s, tg * s, tb * s)
    }

    /** 量这张脸的平均肤色（线性 RGB）。取鼻子和两颊这一带，再把颜色差得远的像素（鼻孔、阴影、头发）剔掉重算一遍。 */
    fun measureTone(image: Bitmap, face: FaceInfo): SkinTone? {
        val w = image.width
        val h = image.height
        val f = SkinFrame(face, w.toFloat(), h.toFloat())
        val c = f.at(0f, 0.55f)
        val x0 = floor(max(0f, c.x - f.d * 0.75f)).toInt()
        val y0 = floor(max(0f, c.y - f.d * 0.4f)).toInt()
        val x1 = ceil(min(w.toFloat(), c.x + f.d * 0.75f)).toInt()
        val y1 = ceil(min(h.toFloat(), c.y + f.d * 0.4f)).toInt()
        if (x1 - x0 < 2 || y1 - y0 < 2) return null
        val rw = x1 - x0
        val rh = y1 - y0
        val px = IntArray(rw * rh)
        Img.soft(image).getPixels(px, 0, rw, x0, y0, rw, rh)
        val lin = FloatArray(px.size * 4)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sa = 0.0
        for (i in px.indices) {
            val p = px[i]
            val a = ((p ushr 24) and 0xFF) / 255f
            // getPixels 给的是未预乘的颜色
            val r = linLut[(p shr 16) and 0xFF]
            val g = linLut[(p shr 8) and 0xFF]
            val b = linLut[p and 0xFF]
            lin[i * 4] = r; lin[i * 4 + 1] = g; lin[i * 4 + 2] = b; lin[i * 4 + 3] = a
            sr += r * a; sg += g * a; sb += b * a; sa += a
        }
        val n = px.size.toDouble()
        if (sa / n <= 0.01) return null
        val rough = SkinTone((sr / sa).toFloat(), (sg / sa).toFloat(), (sb / sa).toFloat())
        var wr = 0.0; var wg = 0.0; var wb = 0.0; var ws = 0.0
        for (i in px.indices) {
            val r = lin[i * 4]; val g = lin[i * 4 + 1]; val b = lin[i * 4 + 2]; val a = lin[i * 4 + 3]
            val k = bodyLike(r, g, b, rough) * a
            wr += r * k; wg += g * k; wb += b * k; ws += k
        }
        if (ws / n <= 0.02) return rough
        return SkinTone((wr / ws).toFloat(), (wg / ws).toFloat(), (wb / ws).toFloat())
    }

    // MARK: - 一张脸的修饰

    private val textureKeys = listOf(
        SkinKey.SMOOTH, SkinKey.TEXTURE, SkinKey.EVEN, SkinKey.ACNE, SkinKey.FOREHEAD_LINES, SkinKey.EYE_LINES, SkinKey.NASOLABIAL,
        SkinKey.NECK_LINES, SkinKey.LIFT_MOUTH, SkinKey.EYE_BAG, SkinKey.LIFT_FOREHEAD, SkinKey.LIFT_EYE, SkinKey.LIFT_MID,
    )
    private val toneKeys = listOf(
        SkinKey.WHITE, SkinKey.WARMTH, SkinKey.ROSY, SkinKey.DESHINE, SkinKey.DARK_CIRCLE, SkinKey.EYE_BRIGHT, SkinKey.EYE_WHITE,
        SkinKey.RED_EYE, SkinKey.TEETH,
    )
    private val plumpKeys = listOf(
        SkinKey.PLUMP_FOREHEAD, SkinKey.PLUMP_TEAR, SkinKey.PLUMP_APPLE, SkinKey.PLUMP_CHEEK, SkinKey.PLUMP_NOSE_BASE, SkinKey.PLUMP_TEMPLE,
    )
    private val warpKeys = listOf(
        SkinKey.LIFT_CONTOUR, SkinKey.LIFT_EYE, SkinKey.LIFT_MID, SkinKey.LIFT_MOUTH, SkinKey.PLUMP_APPLE, SkinKey.PLUMP_TEMPLE,
    )

    private class RetouchCtx(
        val mean: Tex, val coef: Tex, val mid: Tex, val wide: Tex,
        val ma: Tex, val mb: Tex, val mc: Tex, val md: Tex, val mf: Tex,
    )

    /** 这张脸上所有不涉及变形的修饰（就地改 work）。 */
    fun retouch(work: Bitmap, face: SkinFace) {
        val f = SkinFrame(face.face, work.width.toFloat(), work.height.toFloat())
        fun v(key: SkinKey) = if (!f.reliable && key.needsLandmarks) 0f else face[key]
        val wantsTexture = textureKeys.any { v(it) != 0f }
        val wantsTone = toneKeys.any { v(it) != 0f }
        val wantsPlump = plumpKeys.any { v(it) != 0f }
        val roi = f.roi
        if (!(wantsTexture || wantsTone || wantsPlump) || roi.width() < 16 || roi.height() < 16) return
        val masks = SkinMasks.masks(f, face.face) ?: return

        val fw = f.fw
        val tone = face.tone.array()
        val maskScale = masks.a.width / roi.width().toFloat()
        val soft = fw * 0.03f
        // 平滑图在四分之一大小上算（太大的脸再缩小一些，半径跟着换算）
        val lowSide = max(8, (max(roi.width(), roi.height()) * 0.25f).roundToInt().coerceAtMost(2048))
        val fineSigma = max(fw * 0.006f, 0.6f)
        val margin = ceil(fineSigma * 2.5f).toInt() + 2

        val uniforms = mapOf(
            "kp" to floatArrayOf(v(SkinKey.SMOOTH), v(SkinKey.TEXTURE), v(SkinKey.EVEN), v(SkinKey.ACNE)),
            // 提拉里的眼周、面中、口周除了往上提，也顺带把那一带的纹路淡化一些。
            "kw" to floatArrayOf(
                v(SkinKey.FOREHEAD_LINES), max(v(SkinKey.EYE_LINES), v(SkinKey.LIFT_EYE) * 0.5f),
                max(v(SkinKey.NASOLABIAL), v(SkinKey.LIFT_MID) * 0.6f), v(SkinKey.NECK_LINES),
            ),
            "kx" to floatArrayOf(
                v(SkinKey.LIFT_MOUTH) * 0.7f, max(v(SkinKey.EYE_BAG), v(SkinKey.LIFT_EYE) * 0.4f), v(SkinKey.LIFT_FOREHEAD),
                if (f.reliable) 0f else 1f,
            ),
            "kt" to floatArrayOf(v(SkinKey.WHITE), v(SkinKey.WARMTH), v(SkinKey.ROSY), v(SkinKey.DESHINE)),
            "ke" to floatArrayOf(v(SkinKey.DARK_CIRCLE), v(SkinKey.EYE_BRIGHT), v(SkinKey.EYE_WHITE), v(SkinKey.RED_EYE)),
            "ka" to floatArrayOf(v(SkinKey.PLUMP_FOREHEAD), v(SkinKey.PLUMP_TEAR), v(SkinKey.PLUMP_APPLE), v(SkinKey.PLUMP_CHEEK)),
            "kb" to floatArrayOf(v(SkinKey.PLUMP_NOSE_BASE), v(SkinKey.PLUMP_TEMPLE), 0f, 0f),
            "teeth" to v(SkinKey.TEETH),
            "tone" to tone,
            "doTex" to if (wantsTexture) 1f else 0f,
            "doTone" to if (wantsTone) 1f else 0f,
            "doPlump" to if (wantsPlump) 1f else 0f,
        )

        region(work, roi, margin, prepare = { crop ->
            val small = Img.downscale(crop, lowSide)
            val q = small.width / roi.width().toFloat()
            val low = upload(small)
            if (small !== crop) small.recycle()
            val ma = gauss(upload(masks.a), soft * maskScale)
            val mb = gauss(upload(masks.b), soft * maskScale)
            val mc = gauss(upload(masks.c), soft * maskScale)
            val md = gauss(upload(masks.d), soft * maskScale)
            val mf = gauss(upload(masks.fine), fw * 0.01f * maskScale)
            // 只统计皮肤的局部平均和大范围平均：边上的头发、背景不会混进来。
            val weighted = pass(WEIGHT, mapOf("src" to low, "ma" to ma, "mb" to mb), mapOf("tone" to tone), low.width, low.height)
            val mid = gauss(weighted, fw * 0.03f * q)
            val wide = gauss(weighted, fw * 0.22f * q)
            var mean = low
            var coef = low
            if (wantsTexture) {
                mean = gauss(low, fw * 0.035f * q)
                val dev = pass(DEV, mapOf("src" to low, "mean" to mean), emptyMap(), low.width, low.height)
                val c0 = pass(COEF, mapOf("src" to gauss(dev, fw * 0.035f * q)), mapOf("e" to 0.0036f), low.width, low.height)
                coef = gauss(c0, fw * 0.02f * q)
            }
            RetouchCtx(mean, coef, mid, wide, ma, mb, mc, md, mf)
        }) { t, info, c ->
            val fine = if (wantsTexture) gauss(t, fineSigma) else t
            pass(
                RETOUCH,
                mapOf(
                    "src" to t, "fineT" to fine, "meanT" to c.mean, "coefT" to c.coef, "midT" to c.mid, "wideT" to c.wide,
                    "maT" to c.ma, "mbT" to c.mb, "mcT" to c.mc, "mdT" to c.md, "mfT" to c.mf,
                ),
                uniforms + info.uniforms(), t.width, t.height,
            )
        }
    }

    // MARK: - 提拉 / 丰盈的变形

    private class Push(val center: V, val shift: V = V.ZERO, val bulge: Float = 0f, val radius: Float)

    private fun pushes(f: SkinFrame, face: SkinFace): List<Push> {
        if (!f.reliable) return emptyList()
        val out = ArrayList<Push>()
        val d = f.d
        val up = -f.ey
        val sides = listOf(0 to -1f, 1 to 1f)

        val contour = face[SkinKey.LIFT_CONTOUR]
        if (contour > 0 && f.contour.size >= 8) {
            // 下颌两侧往里、往上收，下颌线就利落了。
            val n = f.contour.size
            for ((index, weight) in listOf(n / 6 to 1.0f, n / 3 to 0.75f, n - 1 - n / 3 to 0.75f, n - 1 - n / 6 to 1.0f)) {
                val p = f.contour[index]
                val inward = f.eyeMid + f.ey * (d * 0.6f) - p
                val length = max(hypot(inward.x, inward.y), 1f)
                val dir = inward * (0.55f / length) + up * 0.75f
                val shown = min(f.side[if (index < n / 2) 0 else 1], 1f)
                out += Push(p, shift = dir * (f.fw * 0.045f * contour * weight * shown), radius = f.fw * 0.3f * max(shown, 0.6f))
            }
        }
        val eye = face[SkinKey.LIFT_EYE]
        if (eye > 0) {
            for ((i, sign) in sides) {
                if (f.side[i] <= 0.5f) continue
                val c = f.from(f.eyes[i], sign * 0.52f * f.side[i], -0.02f)
                val dir = up + f.ex * (sign * 0.3f)
                out += Push(c, shift = dir * (d * 0.055f * eye * min(f.side[i], 1f)), radius = d * 0.42f)
            }
        }
        val mid = face[SkinKey.LIFT_MID]
        if (mid > 0) {
            for ((i, sign) in sides) {
                if (f.side[i] <= 0.5f) continue
                val c = f.from(f.eyes[i], sign * 0.15f * f.side[i], 0.78f)
                out += Push(c, shift = up * (d * 0.06f * mid), radius = d * 0.5f)
            }
        }
        val mouth = face[SkinKey.LIFT_MOUTH]
        if (mouth > 0) {
            for ((i, sign) in sides) {
                val dir = up * 0.9f + f.ex * (sign * 0.25f)
                out += Push(f.mouthCorners[i], shift = dir * (d * 0.045f * mouth), radius = d * 0.3f)
            }
        }
        val apple = face[SkinKey.PLUMP_APPLE]
        if (apple > 0) {
            for ((i, sign) in sides) {
                if (f.side[i] <= 0.5f) continue
                out += Push(f.from(f.eyes[i], sign * 0.14f * f.side[i], 0.68f), bulge = 0.10f * apple, radius = d * 0.45f)
            }
        }
        val temple = face[SkinKey.PLUMP_TEMPLE]
        if (temple > 0) {
            for ((i, sign) in sides) {
                if (f.side[i] <= 0.5f) continue
                out += Push(f.from(f.eyes[i], sign * 0.52f * f.side[i], -0.42f), bulge = 0.06f * temple, radius = d * 0.36f)
            }
        }
        return out
    }

    /** 提拉和丰盈里需要挪动画面的那一小部分。位移场画在一张小网格上，单位跟着处理范围走，和分辨率无关。 */
    fun lift(work: Bitmap, face: SkinFace) {
        if (warpKeys.none { face[it] != 0f }) return
        val f = SkinFrame(face.face, work.width.toFloat(), work.height.toFloat())
        val list = pushes(f, face)
        val roi = f.roi
        if (list.isEmpty() || roi.width() < 16 || roi.height() < 16) return
        val n = 96
        val dx = FloatArray(n * n)
        val dy = FloatArray(n * n)
        var peak = 0f
        for (j in 1 until n - 1) {
            val y = roi.top + (j + 0.5f) / n * roi.height()
            for (i in 1 until n - 1) {
                val x = roi.left + (i + 0.5f) / n * roi.width()
                var sx = 0f
                var sy = 0f
                for (p in list) {
                    val ox = x - p.center.x
                    val oy = y - p.center.y
                    val t2 = (ox * ox + oy * oy) / (p.radius * p.radius)
                    if (t2 >= 1f) continue
                    val wt = (1 - t2) * (1 - t2)
                    sx += (p.shift.x + ox * p.bulge) * wt
                    sy += (p.shift.y + oy * p.bulge) * wt
                }
                dx[j * n + i] = sx
                dy[j * n + i] = sy
                peak = max(peak, max(abs(sx), abs(sy)))
            }
        }
        if (peak <= 0.05f) return
        val limit = peak * 1.05f
        // 128 = 不动；±127 = ±limit。
        val px = IntArray(n * n) { k ->
            val r = (128 + dx[k] / limit * 127).roundToInt().coerceIn(1, 255)
            val g = (128 + dy[k] / limit * 127).roundToInt().coerceIn(1, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8)
        }
        val map = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        val reach = limit + 2
        val e = Rect(
            max(0, floor(roi.left - reach).toInt()), max(0, floor(roi.top - reach).toInt()),
            min(work.width, ceil(roi.right + reach).toInt()), min(work.height, ceil(roi.bottom + reach).toInt()),
        )
        val uniforms = mapOf(
            "roiE" to floatArrayOf((roi.left - e.left).toFloat(), (roi.top - e.top).toFloat(), roi.width().toFloat(), roi.height().toFloat()),
            "shift" to limit * 255f / 127f,
        )
        region(work, e, ceil(reach).toInt() + 2, prepare = {
            // 网格上抹开一点，位移过渡就是平滑的。
            gauss(upload(map), 0.8f)
        }) { t, info, m ->
            pass(WARP, mapOf("src" to t, "map" to m), uniforms + info.uniforms(), t.width, t.height)
        }
        map.recycle()
    }

    // MARK: - 画笔

    private class BrushLayer(
        val image: Bitmap,
        /** 画过的范围（归一化，原点左上角）。 */
        val bounds: RectF,
        val hasContour: Boolean,
        val hasConceal: Boolean,
        /** 遮瑕笔、修容笔各自的平均粗细（以长边为 1）。 */
        val concealWidth: Float,
        val contourWidth: Float,
    )

    private var brushCache: Triple<List<SkinStroke>, Float, BrushLayer>? = null
    private val brushLock = Any()

    /** 把所有笔画画到一张位图上：R = 高光，G = 阴影，B = 遮瑕。 */
    private fun brushLayer(strokes: List<SkinStroke>, width: Int, height: Int): BrushLayer? {
        if (strokes.isEmpty() || width <= 0 || height <= 0) return null
        val aspect = width / height.toFloat()
        synchronized(brushLock) {
            brushCache?.let { if (it.first == strokes && abs(it.second - aspect) < 0.0005f) return it.third }
        }
        val long = max(width, height).toFloat()
        val scale = min(1f, 1024f / long)
        val w = max(1, (width * scale).roundToInt())
        val h = max(1, (height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        var box: RectF? = null
        var hasContour = false
        var hasConceal = false
        var widthSum = 0f; var widthCount = 0
        var contourSum = 0f; var contourCount = 0
        // 从外到里一圈圈叠上去，笔触边缘是渐隐的。
        val rings = listOf(1.0f to 0.2f, 0.85f to 0.25f, 0.7f to 0.33f, 0.55f to 0.5f, 0.4f to 1.0f)
        for (stroke in strokes) {
            val first = stroke.points.firstOrNull() ?: continue
            val lw = max(1f, stroke.width * max(w, h))
            var r = 0; var g = 0; var b = 0
            when (stroke.kind) {
                SkinStroke.Kind.HIGHLIGHT -> { r = 255; hasContour = true; contourSum += stroke.width; contourCount++ }
                SkinStroke.Kind.SHADOW -> { g = 255; hasContour = true; contourSum += stroke.width; contourCount++ }
                SkinStroke.Kind.CONCEAL -> { b = 255; hasConceal = true; widthSum += stroke.width; widthCount++ }
                SkinStroke.Kind.ERASE -> b = 255
            }
            val erase = stroke.kind == SkinStroke.Kind.ERASE
            // 橡皮用"正片叠底"画蓝色：R、G 被清掉，遮瑕的 B 不受影响。
            paint.blendMode = if (erase) BlendMode.MULTIPLY else BlendMode.SCREEN
            val path = if (stroke.points.size > 1) Path().apply {
                moveTo(first.x * w, first.y * h)
                for (k in 1 until stroke.points.size) lineTo(stroke.points[k].x * w, stroke.points[k].y * h)
            } else null
            for ((k, alpha) in rings) {
                val a = if (erase) 1f else min(alpha * stroke.strength, 1f)
                paint.color = Color.argb((a * 255).roundToInt(), r, g, b)
                val ring = lw * (if (erase) 1f else k)
                if (path == null) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(first.x * w, first.y * h, ring / 2, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = ring
                    canvas.drawPath(path, paint)
                }
                if (erase) break
            }
            val half = stroke.width * long / 2
            for (p in stroke.points) {
                val rect = RectF(
                    (p.x * width - half) / width, (p.y * height - half) / height,
                    (p.x * width + half) / width, (p.y * height + half) / height,
                )
                box = box?.apply { union(rect) } ?: rect
            }
        }
        val bounds = box ?: return null
        val layer = BrushLayer(
            bmp, bounds, hasContour, hasConceal,
            concealWidth = if (widthCount > 0) widthSum / widthCount else 0f,
            contourWidth = if (contourCount > 0) contourSum / contourCount else 0f,
        )
        synchronized(brushLock) { brushCache = Triple(strokes, aspect, layer) }
        return layer
    }

    /** 笔画覆盖的范围（像素），四周多留 margin 像素。 */
    private fun brushRect(layer: BrushLayer, w: Int, h: Int, margin: Float): Rect {
        val b = layer.bounds
        val x0 = max(0, floor(b.left * w - margin).toInt())
        val y0 = max(0, floor(b.top * h - margin).toInt())
        val x1 = min(w, ceil(b.right * w + margin).toInt())
        val y1 = min(h, ceil(b.bottom * h + margin).toInt())
        return if (x1 > x0 && y1 > y0) Rect(x0, y0, x1, y1) else Rect()
    }

    private class ConcealCtx(val near: Tex, val far: Tex, val bm: Tex)

    /** 遮瑕笔 / 点痘：涂过的地方用周围皮肤的颜色补上，再把原来的细纹理叠回去，不会留下一块糊的。 */
    fun conceal(work: Bitmap, strokes: List<SkinStroke>) {
        val layer = brushLayer(strokes, work.width, work.height) ?: return
        if (!layer.hasConceal) return
        val long = max(work.width, work.height).toFloat()
        val width = max(layer.concealWidth * long, 2f)
        val rect = brushRect(layer, work.width, work.height, width * 5)
        if (rect.width() < 2 || rect.height() < 2) return
        val fineSigma = max(width * 0.07f, 0.6f)
        val rn = rectN(rect, work.width, work.height)
        region(work, rect, ceil(fineSigma * 2.5f).toInt() + 2, prepare = { crop ->
            // 大范围的两张平滑图在缩小版上算（长边最多 2048），半径跟着换算
            val small = Img.downscale(crop, 2048)
            val k = small.width / crop.width.toFloat()
            val low = upload(small)
            if (small !== crop) small.recycle()
            val bm = upload(layer.image)
            val hole = pass(HOLE, mapOf("src" to low, "bm" to bm), mapOf("rectN" to rn), low.width, low.height)
            ConcealCtx(gauss(hole, width * 0.45f * k), gauss(hole, width * 1.8f * k), bm)
        }) { t, info, c ->
            val fine = gauss(t, fineSigma)
            pass(
                CONCEAL, mapOf("src" to t, "nearT" to c.near, "farT" to c.far, "fineT" to fine, "bm" to c.bm),
                mapOf("rectN" to rn) + info.uniforms(), t.width, t.height,
            )
        }
    }

    /** 修容笔：高光是提亮，阴影是压暗并略微偏暖；都是乘上去的，皮肤纹理还在。 */
    fun contour(work: Bitmap, strokes: List<SkinStroke>) {
        val layer = brushLayer(strokes, work.width, work.height) ?: return
        if (!layer.hasContour) return
        // 笔触的边缘再抹软一些，看起来像扫上去的粉，而不是画上去的线。
        val softness = max(layer.contourWidth * max(work.width, work.height) * 0.22f, 1f)
        val rect = brushRect(layer, work.width, work.height, softness * 3 + 4)
        if (rect.width() < 2 || rect.height() < 2) return
        val rn = rectN(rect, work.width, work.height)
        val k = layer.image.width / work.width.toFloat()
        region(work, rect, 0, prepare = { gauss(upload(layer.image), softness * k) }) { t, info, bm ->
            pass(BRUSH, mapOf("src" to t, "bm" to bm), mapOf("rectN" to rn) + info.uniforms(), t.width, t.height)
        }
    }

    // MARK: - 肤色统一

    /** 把全身颜色像皮肤的地方，往脸（修完之后）的肤色靠：颜色靠得多，明暗只靠一点，光影还在。 */
    fun unify(work: Bitmap, reference: SkinFace, amount: Float, body: Bitmap?) {
        val f = SkinFrame(reference.face, work.width.toFloat(), work.height.toFloat())
        val tone = reference.tone.array()
        val target = toned(reference.tone, reference[SkinKey.WHITE], reference[SkinKey.WARMTH], reference[SkinKey.ROSY]).array()
        val uniforms = mapOf("tone" to tone, "target" to target, "amount" to amount)
        region(work, Rect(0, 0, work.width, work.height), 0, prepare = { crop ->
            val small = Img.downscale(crop, 640)
            val q = small.width / crop.width.toFloat()
            val low = upload(small)
            if (small !== crop) small.recycle()
            val bodyTex = upload(body ?: white1)
            val weighted = pass(BODY, mapOf("src" to low, "body" to bodyTex), mapOf("tone" to tone), low.width, low.height)
            gauss(weighted, f.fw * 0.5f * q) to bodyTex
        }) { t, info, c ->
            pass(UNIFY, mapOf("src" to t, "wideT" to c.first, "body" to c.second), uniforms + info.uniforms(), t.width, t.height)
        }
    }
}
