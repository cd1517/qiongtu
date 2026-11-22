package com.taoo.xiutu.raw

import com.taoo.xiutu.gpu.Shaders

/**
 * RAW 冲洗的着色器。中间结果都是线性光的半精度浮点（可以超过 1）。
 * 分块时 `tileOrigin` 是这一块在整张输出图里的像素位置、`fullSize` 是整张图尺寸，
 * guv() / gpx() 取整张图的归一化坐标 / 像素坐标（原点左上）。全图共用的低频图按 guv() 采样。
 */
object RawShaders {
    private const val HEAD = Shaders.HEADER + """
uniform vec2 size;
uniform vec2 tileOrigin;
uniform vec2 fullSize;
vec2 gpx() { return tileOrigin + uv * size; }
vec2 guv() { return (tileOrigin + uv * size) / fullSize; }
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
const vec3 W601 = vec3(0.299, 0.587, 0.114);
vec3 gam(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
vec3 lin(vec3 g) { return pow(max(g, vec3(0.0)), vec3(2.2)); }
"""

    /** 复制（浮点缩小用）。 */
    const val COPY = Shaders.HEADER + """
uniform sampler2D src;
void main() { o = texture(src, uv); }
"""

    /**
     * 解码后的第一步：转正方向 → 镜头畸变 / 色差 / 暗角（DNG 操作码）→ 增益图（相机空间里按通道乘）
     * → 白平衡（Bradford）→ 曝光。src 是传感器方向的线性 RGB（可能只是其中一块，srcRect 说明是哪块）。
     */
    const val DECODE = HEAD + """
uniform sampler2D src;
uniform sampler2D gainTex;
uniform vec2 gainTex_size;
uniform vec4 srcRect;
uniform int flip;
uniform int useGain;
uniform vec2 gainOrigin;
uniform vec2 gainSpacing;
uniform mat3 camM;
uniform mat3 camMinv;
uniform int warpOn;
uniform float warpK[18];
uniform vec2 warpC;
uniform int vigOn;
uniform float vigK[5];
uniform vec2 vigC;
uniform int vigFirst;
uniform vec2 sensorAspect;
uniform mat3 wb;
uniform float gainExp;

vec2 toSensor(vec2 g) {
    vec2 s = g;
    if ((flip & 4) != 0) s = s.yx;
    if ((flip & 2) != 0) s.y = 1.0 - s.y;
    if ((flip & 1) != 0) s.x = 1.0 - s.x;
    return s;
}
float cornerDist(vec2 c) {
    vec2 a = sensorAspect;
    return max(max(length(c), length(c - vec2(a.x, 0.0))), max(length(c - vec2(0.0, a.y)), length(c - a)));
}
vec2 warpPlane(vec2 s, int p) {
    vec2 a = sensorAspect;
    vec2 c = warpC * a;
    float m = cornerDist(c);
    vec2 d = (s * a - c) / m;
    float r2 = dot(d, d);
    int b = p * 6;
    float f = warpK[b] + r2 * (warpK[b + 1] + r2 * (warpK[b + 2] + r2 * warpK[b + 3]));
    float kt0 = warpK[b + 4];
    float kt1 = warpK[b + 5];
    vec2 t = vec2(kt0 * 2.0 * d.x * d.y + kt1 * (r2 + 2.0 * d.x * d.x),
                  kt1 * 2.0 * d.x * d.y + kt0 * (r2 + 2.0 * d.y * d.y));
    return (c + (d * f + t) * m) / a;
}
float vigGain(vec2 s) {
    vec2 a = sensorAspect;
    vec2 c = vigC * a;
    vec2 d = (s * a - c) / cornerDist(c);
    float r2 = dot(d, d);
    return 1.0 + r2 * (vigK[0] + r2 * (vigK[1] + r2 * (vigK[2] + r2 * (vigK[3] + r2 * vigK[4]))));
}
vec2 tex(vec2 s) { return (s - srcRect.xy) / srcRect.zw; }
void main() {
    vec2 s = toSensor(guv());
    float vg = 1.0;
    vec2 sr = s;
    vec2 sg = s;
    vec2 sb = s;
    if (vigOn == 1 && vigFirst == 0) vg *= vigGain(s);
    if (warpOn == 1) { sr = warpPlane(s, 0); sg = warpPlane(s, 1); sb = warpPlane(s, 2); }
    if (vigOn == 1 && vigFirst == 1) vg *= vigGain(sg);
    vec3 c;
    if (warpOn == 1) c = vec3(texture(src, tex(sr)).r, texture(src, tex(sg)).g, texture(src, tex(sb)).b);
    else c = texture(src, tex(s)).rgb;
    c = max(c, vec3(0.0));
    if (useGain == 1) {
        vec2 gt = ((sg - gainOrigin) / gainSpacing + 0.5) / gainTex_size;
        vec3 gn = texture(gainTex, gt).rgb;
        c = max(camM * (gn * (camMinv * c)), vec3(0.0));
    }
    c = wb * (c * vg) * gainExp;
    o = vec4(c, 1.0);
}
"""

    /** 明亮度降噪：伽马亮度上的双边滤波，按比例缩放三个通道（不改颜色）。 */
    const val LUMA_NR = HEAD + """
uniform sampler2D src;
uniform vec2 src_size;
uniform float radius;
uniform float sigmaR;
uniform float amount;
float y(vec3 c) { return pow(max(dot(c, LW), 0.0), 1.0 / 2.2); }
void main() {
    vec3 c = texture(src, uv).rgb;
    float y0 = y(c);
    vec2 px = 1.0 / src_size;
    float st = radius / 3.0;
    float ss = 2.0 * (radius * 0.5) * (radius * 0.5) + 1e-4;
    float sr = 2.0 * sigmaR * sigmaR;
    float acc = 0.0;
    float wsum = 0.0;
    for (int j = -3; j <= 3; j++) {
        for (int i = -3; i <= 3; i++) {
            vec2 off = vec2(float(i), float(j)) * st;
            float yn = y(texture(src, uv + off * px).rgb);
            float w = exp(-dot(off, off) / ss) * exp(-(yn - y0) * (yn - y0) / sr);
            acc += yn * w;
            wsum += w;
        }
    }
    float yt = mix(y0, acc / max(wsum, 1e-6), amount);
    vec3 outc;
    if (y0 > 0.01) outc = c * pow(yt / y0, 2.2);
    else outc = c + vec3(pow(max(yt, 0.0), 2.2) - pow(y0, 2.2));
    o = vec4(max(outc, vec3(0.0)), 1.0);
}
"""

    /** 颜色降噪 / 去摩尔纹：伽马空间里把色度（颜色减亮度）在邻域里平均，亮度差得多的邻居不算（不渗色）。 */
    const val CHROMA_NR = HEAD + """
uniform sampler2D src;
uniform vec2 src_size;
uniform float radius;
uniform float amount;
void main() {
    vec3 g0 = gam(texture(src, uv).rgb);
    float y0 = dot(g0, W601);
    vec3 c0 = g0 - vec3(y0);
    vec2 px = 1.0 / src_size;
    float st = radius / 3.0;
    float ss = 2.0 * (radius * 0.6) * (radius * 0.6) + 1e-4;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = -3; j <= 3; j++) {
        for (int i = -3; i <= 3; i++) {
            vec2 off = vec2(float(i), float(j)) * st;
            vec3 g = gam(texture(src, uv + off * px).rgb);
            float yy = dot(g, W601);
            float w = exp(-dot(off, off) / ss) * exp(-(yy - y0) * (yy - y0) / 0.02);
            acc += (g - vec3(yy)) * w;
            wsum += w;
        }
    }
    vec3 cn = mix(c0, acc / max(wsum, 1e-6), amount);
    o = vec4(lin(vec3(y0) + cn), 1.0);
}
"""

    /** 亮度换算成"比中灰亮几档"，压到 0...1。 */
    const val LOG_LUMA = Shaders.HEADER + """
uniform sampler2D src;
void main() {
    vec3 s = texture(src, uv).rgb;
    float l = dot(max(s, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
    float stops = clamp(log2(max(l, 0.00002) / 0.18), -9.0, 5.0);
    float v = (stops + 9.0) / 14.0;
    o = vec4(v, v, v, 1.0);
}
"""

    /** 引导滤波第一步：I 和 I²。 */
    const val MOMENTS = Shaders.HEADER + """
uniform sampler2D src;
void main() { float v = texture(src, uv).r; o = vec4(v, v * v, 0.0, 1.0); }
"""

    /** 引导滤波第二步：a、b 系数。 */
    const val COEFFICIENTS = Shaders.HEADER + """
uniform sampler2D src;
uniform float eps;
uniform float follow;
void main() {
    vec4 m = texture(src, uv);
    float variance = max(m.g - m.r * m.r, 0.0);
    float a = follow * variance / (variance + eps);
    o = vec4(a, (1.0 - a) * m.r, 0.0, 1.0);
}
"""

    /**
     * 影调：线性空间里的高光 / 阴影 / 白色色阶 / 黑色色阶（iOS RawLinearFilter），局部影调、微对比，
     * 相机曲线和暗部增益，最后是对比度 + 四段参数曲线（iOS rawTone）。ab 是引导滤波的系数（全图共用，低分辨率）。
     */
    const val TONE = HEAD + """
uniform sampler2D src;
uniform sampler2D ab;
uniform vec4 p;
uniform int linearOn;
uniform float localTone;
uniform float rawContrast;
uniform float boost;
uniform float boostShadow;
uniform int toneOn;
uniform float contrast;
uniform vec4 curve;

float shoulder(float x) {
    const float k = 0.55;
    const float d = 0.5;
    return x < k ? x : k + d * (1.0 - exp(-(x - k) / d));
}
vec3 camCurve(vec3 x) {
    vec3 y = vec3(shoulder(x.r), shoulder(x.g), shoulder(x.b));
    vec3 g = clamp(pow(max(y, vec3(0.0)), vec3(1.0 / 2.2)), 0.0, 1.0);
    g = pow(g, vec3(0.92));
    g = mix(g, g * g * (3.0 - 2.0 * g), 0.18);
    return pow(g, vec3(2.2));
}
float rawBump(float x, float c) {
    float d = (x - c) / 0.21;
    return exp(-d * d) * smoothstep(0.0, 0.06, x) * (1.0 - smoothstep(0.94, 1.0, x));
}
void main() {
    vec3 c = texture(src, uv).rgb;
    float l = dot(max(c, vec3(0.0)), LW);
    float pixel = clamp(log2(max(l, 0.00002) / 0.18), -9.0, 5.0);
    vec4 abv = texture(ab, guv());
    float area = (abv.r * ((pixel + 9.0) / 14.0) + abv.g) * 14.0 - 9.0;
    if (linearOn == 1) {
        float e = mix(area, pixel, 0.15);
        float wh = smoothstep(-0.3, 2.3, e);
        float ws = 1.0 - smoothstep(-4.5, -0.3, e);
        float stops = p.x * (p.x < 0.0 ? 2.2 : 1.2) * wh + p.y * (p.y > 0.0 ? 2.6 : 1.6) * ws;
        stops += p.z * smoothstep(-1.0, 2.0, pixel);
        stops += p.w * 1.5 * (1.0 - smoothstep(-6.0, -2.2, pixel));
        c = c * pow(2.0, stops);
        float cut = max(-p.w, 0.0) * 0.006;
        c = (c - vec3(cut)) / (1.0 - cut);
    }
    if (localTone > 0.0) {
        c *= pow(2.0, -localTone * 0.35 * clamp(area, -6.0, 4.0));
    }
    if (rawContrast > 0.0) {
        float l2 = dot(max(c, vec3(0.0)), LW);
        float st = log2(max(l2, 0.00002) / 0.18);
        c *= pow(2.0, clamp(st * rawContrast * 0.3 * smoothstep(-9.0, -5.0, st), -3.0, 2.0));
    }
    c = max(c, vec3(0.0));
    if (boost > 0.0) {
        c = mix(c, camCurve(c), boost);
        vec3 g = gam(c);
        vec3 lift = boostShadow * boost * 0.35 * g * pow(1.0 - clamp(g, 0.0, 1.0), vec3(3.0));
        c = lin(g + lift);
    }
    if (toneOn == 1) {
        vec3 g = gam(c);
        float lt = dot(g, W601);
        float xc = clamp(lt, 0.0, 1.0);
        float over = lt - xc;
        float sCurve = xc * xc * (3.0 - 2.0 * xc);
        float softer = 0.5 + (xc - 0.5) * 0.55;
        float curved = contrast >= 0.0 ? mix(xc, sCurve, contrast) : mix(xc, softer, -contrast);
        curved = curved + 0.11 * (curve.x * rawBump(curved, 0.88) + curve.y * rawBump(curved, 0.64)
                                  + curve.z * rawBump(curved, 0.36) + curve.w * rawBump(curved, 0.12));
        float x = max(curved + over, 0.0);
        float ratio = min(x / max(lt, 0.0001), 12.0);
        vec3 oc = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, lt));
        c = lin(oc);
    }
    o = vec4(c, 1.0);
}
"""

    /** 锐化：伽马亮度的反锐化掩模，threshold 以下的小起伏（噪点）不加强。 */
    const val SHARPEN = HEAD + """
uniform sampler2D src;
uniform sampler2D blurred;
uniform float amount;
uniform float threshold;
void main() {
    vec3 c = texture(src, uv).rgb;
    vec3 g = gam(c);
    float l = dot(g, W601);
    float lb = dot(gam(texture(blurred, uv).rgb), W601);
    float hp = clamp(l - lb, -0.08, 0.08);
    float m = smoothstep(threshold, threshold * 2.0, abs(hp));
    float x = max(l + amount * hp * m, 0.0);
    float ratio = min(x / max(l, 0.0001), 8.0);
    vec3 oc = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, l));
    o = vec4(lin(oc), 1.0);
}
"""

    /** 暗通道（去朦胧用）。 */
    const val DARK = Shaders.HEADER + """
uniform sampler2D src;
void main() {
    vec3 s = texture(src, uv).rgb;
    float m = min(min(s.r, s.g), s.b);
    o = vec4(vec3(clamp(m, 0.0, 1.0)), 1.0);
}
"""

    /** 去朦胧：veil 是模糊过的暗通道（全图共用）。 */
    const val DEHAZE = HEAD + """
uniform sampler2D src;
uniform sampler2D veil;
uniform float amount;
void main() {
    vec3 c = max(texture(src, uv).rgb, vec3(0.0));
    if (amount >= 0.0) {
        float v = clamp(texture(veil, guv()).r, 0.0, 1.0) * amount * 0.6;
        c = (c - vec3(v)) / max(1.0 - v, 0.25);
    } else {
        c = mix(c, vec3(0.75), -amount * 0.5);
    }
    o = vec4(max(c, vec3(0.0)), 1.0);
}
"""

    /** 清晰度（big，全图共用的大半径模糊）和纹理（small，这一块自己的小半径模糊）：只改亮度。 */
    const val LOCAL = HEAD + """
uniform sampler2D src;
uniform sampler2D big;
uniform sampler2D small;
uniform float clarity;
uniform float textureAmount;
void main() {
    vec3 g = gam(texture(src, uv).rgb);
    float l = dot(g, W601);
    float lb = dot(gam(texture(big, guv()).rgb), W601);
    float lf = dot(gam(texture(small, uv).rgb), W601);
    float edge = 2.0 * clamp(l, 0.0, 1.0) - 1.0;
    float mid = 1.0 - edge * edge;
    float dBig = clamp(l - lb, -0.18, 0.18);
    float dSmall = clamp(l - lf, -0.25, 0.25);
    float x = max(l + clarity * 1.0 * dBig * mid + textureAmount * 1.3 * dSmall, 0.0);
    float ratio = min(x / max(l, 0.0001), 8.0);
    vec3 oc = mix(vec3(x), g * ratio, smoothstep(0.0, 0.03, l));
    o = vec4(lin(oc), 1.0);
}
"""

    /**
     * 收尾：自然饱和度、饱和度、混色器、颜色分级、暗角、颗粒，最后编码成 sRGB（8 位）。
     * 混色器 / 分级 / 暗角照搬 iOS 的 rawMixer / rawGrading / rawVignette。
     */
    const val FINISH = HEAD + """
uniform sampler2D src;
uniform float vibrance;
uniform float saturation;
uniform int mixOn;
uniform vec4 h1;
uniform vec4 h2;
uniform vec4 s1;
uniform vec4 s2;
uniform vec4 l1;
uniform vec4 l2;
uniform int gradeOn;
uniform vec3 tSh;
uniform vec3 tMid;
uniform vec3 tHi;
uniform vec3 tAll;
uniform vec4 gLum;
uniform vec2 gOpts;
uniform float vignette;
uniform float vignetteMid;
uniform float grain;
uniform float grainScale;

vec3 rawRGB2HSV(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 rawHSV2RGB(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float rawBand(float h, float c, float left, float right) {
    float d = fract(h - c + 0.5) - 0.5;
    return d < 0.0 ? max(0.0, 1.0 + d / left) : max(0.0, 1.0 - d / right);
}
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
vec3 softLight(vec3 b, float n) {
    vec3 dd = mix(sqrt(b), ((16.0 * b - 12.0) * b + 4.0) * b, step(b, vec3(0.25)));
    return n <= 0.5 ? b - (1.0 - 2.0 * n) * b * (1.0 - b) : b + (2.0 * n - 1.0) * (dd - b);
}
vec3 srgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
void main() {
    vec3 c = max(texture(src, uv).rgb, vec3(0.0));
    if (vibrance != 0.0) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx > 0.0 ? (mx - mn) / mx : 0.0;
        float k = 1.0 + vibrance * (1.0 - sat);
        float l = dot(c, vec3(0.2125, 0.7154, 0.0721));
        c = max(mix(vec3(l), c, k), vec3(0.0));
    }
    if (saturation != 0.0) {
        float l = dot(c, vec3(0.2125, 0.7154, 0.0721));
        c = max(mix(vec3(l), c, 1.0 + saturation), vec3(0.0));
    }
    if (mixOn == 1) {
        vec3 g = gam(c);
        vec3 hsv = rawRGB2HSV(g);
        float h = hsv.x;
        float w0 = rawBand(h, 0.0, 40.0 / 360.0, 30.0 / 360.0);
        float w1 = rawBand(h, 30.0 / 360.0, 30.0 / 360.0, 30.0 / 360.0);
        float w2 = rawBand(h, 60.0 / 360.0, 30.0 / 360.0, 60.0 / 360.0);
        float w3 = rawBand(h, 120.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w4 = rawBand(h, 180.0 / 360.0, 60.0 / 360.0, 60.0 / 360.0);
        float w5 = rawBand(h, 240.0 / 360.0, 60.0 / 360.0, 40.0 / 360.0);
        float w6 = rawBand(h, 280.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        float w7 = rawBand(h, 320.0 / 360.0, 40.0 / 360.0, 40.0 / 360.0);
        vec4 wa = vec4(w0, w1, w2, w3);
        vec4 wb = vec4(w4, w5, w6, w7);
        float dh = dot(wa, h1) + dot(wb, h2);
        float ds = dot(wa, s1) + dot(wb, s2);
        float dl = dot(wa, l1) + dot(wb, l2);
        float sw = smoothstep(0.03, 0.25, hsv.y);
        hsv.x = fract(hsv.x + dh * sw * (30.0 / 360.0) + 1.0);
        hsv.y = clamp(hsv.y * (1.0 + ds * sw), 0.0, 1.0);
        c = lin(rawHSV2RGB(hsv) * (1.0 + 0.6 * dl * sw));
    }
    if (gradeOn == 1) {
        vec3 g = gam(c);
        float l = clamp(dot(g, W601), 0.0, 1.0);
        float pivot = 0.5 - gOpts.y * 0.3;
        float width = mix(0.08, 0.5, gOpts.x);
        float ws = 1.0 - smoothstep(pivot - width, pivot + width * 0.25, l);
        float wh = smoothstep(pivot - width * 0.25, pivot + width, l);
        float wm = max(0.0, 1.0 - ws - wh);
        vec3 oc = g + ws * tSh + wm * tMid + wh * tHi + tAll;
        oc = oc * (1.0 + 0.5 * (ws * gLum.x + wm * gLum.y + wh * gLum.z + gLum.w));
        c = lin(oc);
    }
    vec2 gp = gpx();
    if (vignette != 0.0) {
        float d = distance(gp, fullSize * 0.5) / (length(fullSize) * 0.5);
        float w = smoothstep(mix(0.05, 0.85, vignetteMid), 1.05, d);
        float gain = vignette < 0.0 ? 1.0 + vignette * 0.9 * w : 1.0 + vignette * 1.4 * w;
        c *= gain;
    }
    if (grain > 0.0) {
        float n = valueNoise(gp / grainScale);
        vec3 b = clamp(c, 0.0, 1.0);
        c = mix(c, softLight(b, n), min(grain, 1.0) * 0.45);
    }
    o = vec4(srgb(c), 1.0);
}
"""
}
