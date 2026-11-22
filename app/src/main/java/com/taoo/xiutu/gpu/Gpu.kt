package com.taoo.xiutu.gpu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 离屏 GPU 着色器。所有 GL 调用都在一条专用线程上。
 *
 * 约定：片元着色器用 GLSL ES 3.00（#version 300 es），输入变量 `vec2 uv`，
 * uv 原点在图片左上角（和 Bitmap 的行顺序一致），输出 `out vec4 o`。
 * 采样器 uniform 名字用 sampler2D，第一张输入一般叫 `src`。
 * 纹理内容是预乘 alpha 的 RGBA（和 Bitmap 一样）。
 */
object Gpu {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "xiutu-gpu").apply { priority = Thread.MAX_PRIORITY } }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val programs = HashMap<String, Int>()
    private var quad: FloatBuffer? = null
    var maxTextureSize = 4096
        private set
    /** 能否渲染到半精度浮点纹理（RGBA16F）。不能时 emptyFloat 退回 RGBA8。 */
    var floatRenderable = false
        private set

    const val VERTEX = """#version 300 es
in vec2 pos;
out vec2 uv;
void main() {
    // 让 uv.y = 0 对应图片第一行（左上角）
    uv = vec2(pos.x * 0.5 + 0.5, pos.y * 0.5 + 0.5);
    gl_Position = vec4(pos, 0.0, 1.0);
}"""

    /** 在 GPU 线程上执行一段渲染；阻塞等待结果。不要在 GPU 线程里再调 run。 */
    fun <T> run(block: GpuScope.() -> T): T {
        if (Thread.currentThread().name == "xiutu-gpu") {
            return GpuScope().useScope(block)
        }
        val f: Future<T> = thread.submit<T> {
            ensure()
            GpuScope().useScope(block)
        }
        return f.get()
    }

    /** 单步滤镜：src 过一遍着色器，输出同尺寸（或指定尺寸）的新 Bitmap。 */
    fun filter(
        src: Bitmap,
        frag: String,
        uniforms: Map<String, Any> = emptyMap(),
        extra: Map<String, Bitmap> = emptyMap(),
        width: Int = src.width,
        height: Int = src.height,
    ): Bitmap = run {
        val t = upload(src)
        val ex = extra.mapValues { upload(it.value) }
        val out = pass(frag, mapOf("src" to t) + ex, uniforms, width, height)
        read(out)
    }

    private fun ensure() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        val cfg = configs[0] ?: error("没有可用的 EGL 配置")
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val m = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, m, 0)
        maxTextureSize = m[0].coerceAtLeast(2048)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val ver = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        floatRenderable = "GL_EXT_color_buffer_float" in ext || "GL_EXT_color_buffer_half_float" in ext ||
            Regex("""OpenGL ES 3\.[2-9]""").containsMatchIn(ver)
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
    }

    private val sizeRef = Regex("""\b(size|\w+_size)\b""")

    /** 用到了 size / X_size 却没声明的，自动补上 uniform 声明。 */
    private fun withSizeUniforms(frag: String): String {
        val missing = sizeRef.findAll(frag).map { it.value }.toSet()
            .filter { !Regex("""\b(uniform|in|out|const|float|vec2|vec3|vec4|int)\s+(highp\s+|mediump\s+)?(vec2\s+)?\Q$it\E\b""").containsMatchIn(frag) }
        if (missing.isEmpty()) return frag
        val decl = missing.joinToString("") { "uniform vec2 $it;\n" }
        val m = Regex("""precision\s+\w+\s+float\s*;\s*\n""").find(frag) ?: return frag
        return frag.substring(0, m.range.last + 1) + decl + frag.substring(m.range.last + 1)
    }

    internal fun program(frag: String): Int = programs.getOrPut(frag) {
        val frag = withSizeUniforms(frag)
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("着色器编译失败：" + GLES30.glGetShaderInfoLog(s) + "\n" + src.take(400))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, frag))
        GLES30.glBindAttribLocation(p, 0, "pos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("着色器链接失败：" + GLES30.glGetProgramInfoLog(p))
        p
    }

    internal fun drawQuad() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/** 一张 GPU 纹理。只在创建它的 run 块里有效。 */
class Tex internal constructor(val id: Int, val width: Int, val height: Int, val float: Boolean = false)

/** run 块里可用的操作。块结束时这次创建的纹理全部释放。 */
class GpuScope internal constructor() {
    private val textures = ArrayList<Int>()
    private val fbo = IntArray(1)

    internal fun <T> useScope(block: GpuScope.() -> T): T {
        try {
            return block()
        } finally {
            if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
            if (fbo[0] != 0) GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
    }

    private fun newTexture(w: Int, h: Int, linear: Boolean = true): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val f = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, f)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, f)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        textures += t[0]
        return t[0]
    }

    /** 把 Bitmap 传到 GPU。 */
    fun upload(bitmap: Bitmap): Tex {
        val b = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val id = newTexture(b.width, b.height)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        return Tex(id, b.width, b.height)
    }

<<<<<<< HEAD
    /** 提前释放这次 run 里创建的一张纹理（长链条逐层叠加时省显存）。之后不能再用它。 */
    fun release(tex: Tex) {
        if (textures.remove(tex.id)) GLES30.glDeleteTextures(1, intArrayOf(tex.id), 0)
    }

=======
    /** 新建一张空的半精度浮点纹理（RGBA16F，可超过 1、可为负）。设备不支持渲染到浮点纹理时退回 RGBA8。 */
    fun emptyFloat(width: Int, height: Int): Tex {
        if (!Gpu.floatRenderable) return empty(width, height)
        val id = newTexture(width, height)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        return Tex(id, width, height, true)
    }

    /**
     * 上传半精度浮点数据（每像素 channels 个 half，行优先，第一行 = 图片第一行）。
     * 可以只取其中一块：整张宽 rowLength，从 (x, y) 起取 width × height。
     */
    fun uploadHalf(data: java.nio.Buffer, width: Int, height: Int, channels: Int = 3, rowLength: Int = width, x: Int = 0, y: Int = 0): Tex {
        val id = newTexture(width, height)
        val (internal, format) = when (channels) {
            1 -> GLES30.GL_R16F to GLES30.GL_RED
            2 -> GLES30.GL_RG16F to GLES30.GL_RG
            3 -> GLES30.GL_RGB16F to GLES30.GL_RGB
            else -> GLES30.GL_RGBA16F to GLES30.GL_RGBA
        }
        data.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowLength)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, x)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, y)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GLES30.GL_HALF_FLOAT, data)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
        return Tex(id, width, height, true)
    }

    /** 跑一遍着色器，输出到半精度浮点纹理（见 emptyFloat）。 */
    fun passFloat(frag: String, inputs: Map<String, Tex>, uniforms: Map<String, Any> = emptyMap(), width: Int, height: Int): Tex =
        pass(frag, inputs, uniforms, width, height, float = true)

>>>>>>> p2-raw
    /** 新建一张空纹理（RGBA8）。 */
    fun empty(width: Int, height: Int): Tex {
        val id = newTexture(width, height)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return Tex(id, width, height)
    }

    /**
     * 跑一遍着色器，输出到新纹理。
     * uniforms 支持 Float / Int / Boolean / FloatArray(1-4 或 9/16 当矩阵) / List<Float>。
     * 自动提供 `vec2 size`（输出像素尺寸）；每个输入纹理 X 还会提供 `vec2 X_size`。用到而没声明的会自动补声明。
     */
    fun pass(frag: String, inputs: Map<String, Tex>, uniforms: Map<String, Any> = emptyMap(), width: Int, height: Int, float: Boolean = false): Tex {
        val out = if (float) emptyFloat(width, height) else empty(width, height)
        val p = Gpu.program(frag)
        if (fbo[0] == 0) GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, out.id, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(p)
        var unit = 0
        for ((name, tex) in inputs) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, name), unit)
            val sl = GLES30.glGetUniformLocation(p, name + "_size")
            if (sl >= 0) GLES30.glUniform2f(sl, tex.width.toFloat(), tex.height.toFloat())
            unit++
        }
        val sizeLoc = GLES30.glGetUniformLocation(p, "size")
        if (sizeLoc >= 0) GLES30.glUniform2f(sizeLoc, width.toFloat(), height.toFloat())
        for ((name, v) in uniforms) {
            val loc = GLES30.glGetUniformLocation(p, name)
            if (loc < 0) continue
            when (v) {
                is Float -> GLES30.glUniform1f(loc, v)
                is Double -> GLES30.glUniform1f(loc, v.toFloat())
                is Int -> GLES30.glUniform1i(loc, v)
                is Boolean -> GLES30.glUniform1i(loc, if (v) 1 else 0)
                is FloatArray -> when (v.size) {
                    1 -> GLES30.glUniform1f(loc, v[0])
                    2 -> GLES30.glUniform2f(loc, v[0], v[1])
                    3 -> GLES30.glUniform3f(loc, v[0], v[1], v[2])
                    4 -> GLES30.glUniform4f(loc, v[0], v[1], v[2], v[3])
                    9 -> GLES30.glUniformMatrix3fv(loc, 1, false, v, 0)
                    16 -> GLES30.glUniformMatrix4fv(loc, 1, false, v, 0)
                    else -> GLES30.glUniform1fv(loc, v.size, v, 0)
                }
                is List<*> -> {
                    val a = v.map { (it as Number).toFloat() }.toFloatArray()
                    GLES30.glUniform1fv(loc, a.size, a, 0)
                }
            }
        }
        Gpu.drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return out
    }

    /** 读回成 Bitmap。浮点纹理先转成 RGBA8（超出 0...1 的截断）。 */
    fun read(tex: Tex): Bitmap {
        if (tex.float) return read(pass(Shaders.COPY, mapOf("src" to tex), emptyMap(), tex.width, tex.height))
        if (fbo[0] == 0) GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex.id, 0)
        val buf = ByteBuffer.allocateDirect(tex.width * tex.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, tex.width, tex.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.position(0)
        val bmp = Bitmap.createBitmap(tex.width, tex.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    /** 可分离高斯模糊。radius 是像素。 */
    fun blur(src: Tex, radius: Float): Tex {
        if (radius < 0.5f) return src
        // 半径大时先缩小再模糊，速度和效果都更好
        var input = src
        var r = radius
        var scale = 1f
        while (r > 24f && input.width > 32 && input.height > 32) {
            val w = (input.width / 2).coerceAtLeast(1)
            val h = (input.height / 2).coerceAtLeast(1)
            input = pass(Shaders.COPY, mapOf("src" to input), emptyMap(), w, h, src.float)
            r /= 2f
            scale *= 2f
        }
        // 浮点纹理模糊后仍是浮点
        val f = src.float
        val h1 = pass(Shaders.BLUR, mapOf("src" to input), mapOf("dir" to floatArrayOf(1f, 0f), "radius" to r), input.width, input.height, f)
        val v1 = pass(Shaders.BLUR, mapOf("src" to h1), mapOf("dir" to floatArrayOf(0f, 1f), "radius" to r), input.width, input.height, f)
        return if (scale == 1f) v1 else pass(Shaders.COPY, mapOf("src" to v1), emptyMap(), src.width, src.height, f)
    }
}

/** 常用着色器片段。 */
object Shaders {
    const val HEADER = """#version 300 es
precision highp float;
in vec2 uv;
out vec4 o;
"""

    const val COPY = HEADER + """
uniform sampler2D src;
void main() { o = texture(src, uv); }
"""

    const val BLUR = HEADER + """
uniform sampler2D src;
uniform vec2 src_size;
uniform vec2 dir;
uniform float radius;
void main() {
    float sigma = max(radius / 2.5, 0.5);
    int n = int(min(ceil(radius), 48.0));
    vec2 px = dir / src_size;
    vec4 acc = texture(src, uv);
    float wsum = 1.0;
    for (int i = 1; i <= 48; i++) {
        if (i > n) break;
        float w = exp(-float(i * i) / (2.0 * sigma * sigma));
        acc += (texture(src, uv + px * float(i)) + texture(src, uv - px * float(i))) * w;
        wsum += 2.0 * w;
    }
    o = acc / wsum;
}
"""

    /** 按蒙版混合：mask 的红色通道 = 1 时取 fg。 */
    const val BLEND_MASK = HEADER + """
uniform sampler2D src;
uniform sampler2D fg;
uniform sampler2D mask;
void main() {
    float m = texture(mask, uv).r;
    o = mix(texture(src, uv), texture(fg, uv), m);
}
"""

    /** 线性混合：t = 0 原图，1 = fg。 */
    const val MIX = HEADER + """
uniform sampler2D src;
uniform sampler2D fg;
uniform float t;
void main() { o = mix(texture(src, uv), texture(fg, uv), t); }
"""

    /** 常用的颜色函数，拼在着色器正文前面用。 */
    const val COLOR_FUNCS = """
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 srgbToLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 linearToSrgb(vec3 c) { c = max(c, 0.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
"""
}
