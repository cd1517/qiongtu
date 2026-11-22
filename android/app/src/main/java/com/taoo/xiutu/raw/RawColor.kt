package com.taoo.xiutu.raw

import kotlin.math.sqrt

/**
 * 白平衡的颜色换算：色温 / 色调 ↔ 色度坐标（按 DNG SDK 的 Robertson 等温线算法，色调刻度 -3000），
 * 以及把"场景光源的白"适应到 D65 的 Bradford 矩阵（在线性 sRGB 里用）。
 * RAW 按日光（D65）白平衡解码，再用这个矩阵换到指定的色温色调。
 */
object RawColor {
    private class Ruvt(val r: Double, val u: Double, val v: Double, val t: Double)

    private val table = arrayOf(
        Ruvt(0.0, 0.18006, 0.26352, -0.24341), Ruvt(10.0, 0.18066, 0.26589, -0.25479),
        Ruvt(20.0, 0.18133, 0.26846, -0.26876), Ruvt(30.0, 0.18208, 0.27119, -0.28539),
        Ruvt(40.0, 0.18293, 0.27407, -0.30470), Ruvt(50.0, 0.18388, 0.27709, -0.32675),
        Ruvt(60.0, 0.18494, 0.28021, -0.35156), Ruvt(70.0, 0.18611, 0.28342, -0.37915),
        Ruvt(80.0, 0.18740, 0.28668, -0.40955), Ruvt(90.0, 0.18880, 0.28997, -0.44278),
        Ruvt(100.0, 0.19032, 0.29326, -0.47888), Ruvt(125.0, 0.19462, 0.30141, -0.58204),
        Ruvt(150.0, 0.19962, 0.30921, -0.70471), Ruvt(175.0, 0.20525, 0.31647, -0.84901),
        Ruvt(200.0, 0.21142, 0.32312, -1.0182), Ruvt(225.0, 0.21807, 0.32909, -1.2168),
        Ruvt(250.0, 0.22511, 0.33439, -1.4512), Ruvt(275.0, 0.23247, 0.33904, -1.7298),
        Ruvt(300.0, 0.24010, 0.34308, -2.0637), Ruvt(325.0, 0.24702, 0.34655, -2.4681),
        Ruvt(350.0, 0.25591, 0.34951, -2.9641), Ruvt(375.0, 0.26400, 0.35200, -3.5814),
        Ruvt(400.0, 0.27218, 0.35407, -4.3633), Ruvt(425.0, 0.28039, 0.35577, -5.3762),
        Ruvt(450.0, 0.28863, 0.35714, -6.7262), Ruvt(475.0, 0.29685, 0.35823, -8.5955),
        Ruvt(500.0, 0.30505, 0.35907, -11.324), Ruvt(525.0, 0.31320, 0.35968, -15.628),
        Ruvt(550.0, 0.32129, 0.36011, -23.325), Ruvt(575.0, 0.32931, 0.36038, -40.770),
        Ruvt(600.0, 0.33724, 0.36051, -116.45),
    )
    private const val TINT_SCALE = -3000.0

    /** 色温（K）+ 色调 → xy。 */
    fun xy(temperature: Double, tint: Double): DoubleArray {
        val r = 1.0e6 / temperature
        val offset = tint / TINT_SCALE
        for (i in 0..29) {
            if (r < table[i + 1].r || i == 29) {
                val a = table[i]
                val b = table[i + 1]
                val f = (b.r - r) / (b.r - a.r)
                var u = a.u * f + b.u * (1 - f)
                var v = a.v * f + b.v * (1 - f)
                var uu1 = 1.0
                var vv1 = a.t
                var uu2 = 1.0
                var vv2 = b.t
                val len1 = sqrt(1 + vv1 * vv1)
                val len2 = sqrt(1 + vv2 * vv2)
                uu1 /= len1; vv1 /= len1; uu2 /= len2; vv2 /= len2
                var uu3 = uu1 * f + uu2 * (1 - f)
                var vv3 = vv1 * f + vv2 * (1 - f)
                val len3 = sqrt(uu3 * uu3 + vv3 * vv3)
                uu3 /= len3; vv3 /= len3
                u += uu3 * offset
                v += vv3 * offset
                return doubleArrayOf(1.5 * u / (u - 4 * v + 2), v / (u - 4 * v + 2))
            }
        }
        return doubleArrayOf(0.3127, 0.3290)
    }

    /** xy → 色温（K）+ 色调。 */
    fun temperatureTint(x: Double, y: Double): DoubleArray {
        val u = 2 * x / (1.5 - x + 6 * y)
        val v = 3 * y / (1.5 - x + 6 * y)
        var lastDt = 0.0
        var lastDu = 0.0
        var lastDv = 0.0
        for (i in 1..30) {
            var du = 1.0
            var dv = table[i].t
            var len = sqrt(1 + dv * dv)
            du /= len; dv /= len
            var uu = u - table[i].u
            var vv = v - table[i].v
            var dt = -uu * dv + vv * du
            if (dt <= 0 || i == 30) {
                if (dt > 0) dt = 0.0
                dt = -dt
                val f = if (i == 1) 0.0 else dt / (lastDt + dt)
                val temperature = 1.0e6 / (table[i - 1].r * f + table[i].r * (1 - f))
                uu = u - (table[i - 1].u * f + table[i].u * (1 - f))
                vv = v - (table[i - 1].v * f + table[i].v * (1 - f))
                du = du * (1 - f) + lastDu * f
                dv = dv * (1 - f) + lastDv * f
                len = sqrt(du * du + dv * dv)
                du /= len; dv /= len
                return doubleArrayOf(temperature, (uu * du + vv * dv) * TINT_SCALE)
            }
            lastDt = dt; lastDu = du; lastDv = dv
        }
        return doubleArrayOf(6504.0, 0.0)
    }

    // 线性 sRGB ↔ XYZ（D65），行优先
    val SRGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    val XYZ_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    /** 线性 sRGB 颜色 → xy。 */
    fun xyOfRgb(r: Double, g: Double, b: Double): DoubleArray {
        val xyz = mul(SRGB_TO_XYZ, doubleArrayOf(r, g, b))
        val s = xyz[0] + xyz[1] + xyz[2]
        if (s <= 1e-9) return doubleArrayOf(0.3127, 0.3290)
        return doubleArrayOf(xyz[0] / s, xyz[1] / s)
    }

    /** 把色度为 xy 的光源下的白，适应成 D65 白。返回线性 sRGB 里的 3×3 矩阵（行优先）。 */
    fun adaptation(x: Double, y: Double): DoubleArray {
        val src = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val ls = mul(BRADFORD, src)
        val ld = mul(BRADFORD, D65)
        val diag = doubleArrayOf(ld[0] / ls[0], 0.0, 0.0, 0.0, ld[1] / ls[1], 0.0, 0.0, 0.0, ld[2] / ls[2])
        val m = matmul(invert(BRADFORD), matmul(diag, BRADFORD))
        return matmul(XYZ_TO_SRGB, matmul(m, SRGB_TO_XYZ))
    }

    fun mul(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun matmul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return o
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val k = 1 / det
        return doubleArrayOf(
            (e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k,
            (f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k,
            (d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k,
        )
    }

    /** 行优先 3×3 → GL 的列优先 mat3。 */
    fun gl(m: DoubleArray) = FloatArray(9) { m[(it % 3) * 3 + it / 3].toFloat() }
}
