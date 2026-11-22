package com.taoo.xiutu.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * DNG 操作码（OpcodeList2 / OpcodeList3，大端）：
 * - GainMap（9）：镜头阴影 / 色偏校正，按 CFA 位置分通道，解码时总是应用；
 * - WarpRectilinear（1）：畸变和横向色差；FixVignetteRadial（3）：径向暗角。这两个是"镜头校正"开关管的。
 * 坐标都按传感器有效区域归一化（原点左上）。
 */
class RawOpcodes private constructor() {
    /** 一张增益图（已经分到 RGB 三个通道上的合成版）。 */
    class Gain(val cols: Int, val rows: Int, val originX: Float, val originY: Float, val spacingX: Float, val spacingY: Float, val rgb: FloatArray)

    class Warp(val planes: Int, val k: FloatArray /* 每个平面 kr0..kr3, kt0, kt1 */, val cx: Float, val cy: Float)

    class Vignette(val k: FloatArray /* k0..k4 */, val cx: Float, val cy: Float)

    var gain: Gain? = null
        private set
    var warp: Warp? = null
        private set
    var vignette: Vignette? = null
        private set
    /** OpcodeList3 里暗角排在畸变前面。 */
    var vignetteFirst = false
        private set

    val lensSupported: Boolean get() = warp != null || vignette != null

    private class RawGain(
        val top: Int, val left: Int, val plane: Int, val planes: Int, val rowPitch: Int, val colPitch: Int,
        val pointsV: Int, val pointsH: Int, val spacingV: Double, val spacingH: Double,
        val originV: Double, val originH: Double, val mapPlanes: Int, val gains: FloatArray,
    ) {
        fun at(u: Double, v: Double, p: Int): Float {
            val fx = ((u - originH) / spacingH).coerceIn(0.0, (pointsH - 1).toDouble())
            val fy = ((v - originV) / spacingV).coerceIn(0.0, (pointsV - 1).toDouble())
            val x0 = floor(fx).toInt().coerceIn(0, pointsH - 1)
            val y0 = floor(fy).toInt().coerceIn(0, pointsV - 1)
            val x1 = (x0 + 1).coerceAtMost(pointsH - 1)
            val y1 = (y0 + 1).coerceAtMost(pointsV - 1)
            val tx = (fx - x0).toFloat()
            val ty = (fy - y0).toFloat()
            val pp = p.coerceIn(0, mapPlanes - 1)
            fun g(x: Int, y: Int) = gains[(y * pointsH + x) * mapPlanes + pp]
            val a = g(x0, y0) + (g(x1, y0) - g(x0, y0)) * tx
            val b = g(x0, y1) + (g(x1, y1) - g(x0, y1)) * tx
            return a + (b - a) * ty
        }
    }

    companion object {
        /** cfa = 传感器 (0,0)(0,1)(1,0)(1,1) 的颜色（0 红 1 绿 2 蓝 3 第二绿），-1 = 不是 CFA。 */
        fun parse(list2: ByteArray?, list3: ByteArray?, cfa: IntArray): RawOpcodes {
            val out = RawOpcodes()
            val gains = ArrayList<RawGain>()
            for ((index, bytes) in listOf(list2, list3).withIndex()) {
                if (bytes == null || bytes.size < 4) continue
                runCatching {
                    val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    val count = b.int
                    var seenWarp = false
                    repeat(count) {
                        val id = b.int
                        b.int // 版本
                        b.int // 标志
                        val len = b.int
                        val start = b.position()
                        when (id) {
                            9 -> gains += readGain(b)
                            1 -> if (index == 1 && out.warp == null) {
                                val planes = b.int.coerceIn(1, 3)
                                val k = FloatArray(18)
                                for (p in 0 until planes) for (j in 0 until 6) k[p * 6 + j] = b.double.toFloat()
                                for (p in planes until 3) for (j in 0 until 6) k[p * 6 + j] = k[(planes - 1) * 6 + j]
                                out.warp = Warp(planes, k, b.double.toFloat(), b.double.toFloat())
                                seenWarp = true
                            }
                            3 -> if (out.vignette == null) {
                                val k = FloatArray(5) { b.double.toFloat() }
                                out.vignette = Vignette(k, b.double.toFloat(), b.double.toFloat())
                                out.vignetteFirst = index == 1 && !seenWarp
                            }
                        }
                        b.position(start + len)
                    }
                }
            }
            out.gain = combine(gains, cfa)
            return out
        }

        private fun readGain(b: ByteBuffer): RawGain {
            val top = b.int
            val left = b.int
            b.int; b.int // bottom, right
            val plane = b.int
            val planes = b.int
            val rowPitch = b.int
            val colPitch = b.int
            val pointsV = b.int
            val pointsH = b.int
            val spacingV = b.double
            val spacingH = b.double
            val originV = b.double
            val originH = b.double
            val mapPlanes = b.int.coerceAtLeast(1)
            val n = pointsV * pointsH * mapPlanes
            require(n in 1..(1 shl 20))
            val g = FloatArray(n) { b.float }
            return RawGain(top, left, plane, planes, rowPitch, colPitch, pointsV.coerceAtLeast(1), pointsH.coerceAtLeast(1),
                if (spacingV > 0) spacingV else 1.0, if (spacingH > 0) spacingH else 1.0, originV, originH, mapPlanes, g)
        }

        /** 把各张增益图按颜色并成一张 RGB（取第一张的网格）。 */
        private fun combine(maps: List<RawGain>, cfa: IntArray): Gain? {
            if (maps.isEmpty()) return null
            val ref = maps[0]
            val cols = ref.pointsH.coerceIn(2, 256)
            val rows = ref.pointsV.coerceIn(2, 256)
            val spX = if (ref.pointsH > 1) ref.spacingH * (ref.pointsH - 1) / (cols - 1) else 1.0 / (cols - 1)
            val spY = if (ref.pointsV > 1) ref.spacingV * (ref.pointsV - 1) / (rows - 1) else 1.0 / (rows - 1)
            val oX = if (ref.pointsH > 1) ref.originH else 0.0
            val oY = if (ref.pointsV > 1) ref.originV else 0.0
            val sum = FloatArray(cols * rows * 3)
            val cnt = IntArray(3)
            // 每张图属于哪个通道（以及从它的哪个平面取）
            val assign = ArrayList<Triple<RawGain, Int, Int>>()
            for (m in maps) {
                if (m.mapPlanes >= 3 || (m.planes >= 3 && m.plane == 0 && m.mapPlanes == 1 && m.rowPitch <= 1)) {
                    // 线性 DNG：每个平面一个颜色
                    for (c in 0..2) assign += Triple(m, c, if (m.mapPlanes >= 3) c else 0)
                } else if (m.rowPitch == 2 && m.colPitch == 2 && cfa[0] >= 0) {
                    val color = cfa[(m.top and 1) * 2 + (m.left and 1)]
                    assign += Triple(m, if (color == 3) 1 else color.coerceIn(0, 2), 0)
                } else if (m.planes == 1 || m.plane in 0..2) {
                    val c = if (m.planes > 1) m.plane.coerceIn(0, 2) else -1
                    if (c >= 0) assign += Triple(m, c, 0) else for (k in 0..2) assign += Triple(m, k, 0)
                }
            }
            if (assign.isEmpty()) return null
            for ((m, c, p) in assign) {
                cnt[c]++
                for (y in 0 until rows) for (x in 0 until cols) {
                    sum[(y * cols + x) * 3 + c] += m.at(oX + x * spX, oY + y * spY, p)
                }
            }
            for (i in 0 until cols * rows) for (c in 0..2) {
                sum[i * 3 + c] = if (cnt[c] > 0) sum[i * 3 + c] / cnt[c] else 1f
            }
            return Gain(cols, rows, oX.toFloat(), oY.toFloat(), spX.toFloat(), spY.toFloat(), sum)
        }
    }
}
