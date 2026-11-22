package com.taoo.xiutu.raw

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/** RAW 冲洗页面的状态（iOS RawModel）。解码、渲染、冲洗都在同一条工作线程上排队。 */
class RawModel(val name: String) {
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    /** 直方图：红绿蓝各 256 格，已经按 iOS 的 ×40 归一到 0...1。 */
    var histogram by mutableStateOf<FloatArray?>(null)
        private set
    var values by mutableStateOf<Map<RawKey, Float>>(emptyMap())
        private set
    var lensCorrection by mutableStateOf(true)
        private set
    var group by mutableStateOf(RawGroup.LIGHT)
        private set
    var key by mutableStateOf(RawKey.exposure)
    var mixColor by mutableStateOf(MixColor.RED)
        private set
    var gradeZone by mutableStateOf(GradeZone.SHADOWS)
        private set
    /** 白平衡吸管是否打开：打开时点一下画面里本该是灰色或白色的地方。 */
    var pickingWhiteBalance by mutableStateOf(false)
    var comparing by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    private var developer by mutableStateOf<RawDeveloper?>(null)
    private var original: Bitmap? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xiutu-raw") }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val pending = AtomicReference<(() -> Unit)?>(null)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    val shown: Bitmap? get() = if (comparing) (original ?: preview) else preview
    val sizeText: String get() {
        val d = developer ?: return ""
        val mp = d.nativeWidth.toDouble() * d.nativeHeight / 1_000_000
        return "${d.nativeWidth}×${d.nativeHeight} · ${String.format("%.0f", mp)} MP"
    }
    val lensCorrectionSupported: Boolean get() = developer?.lensCorrectionSupported ?: false
    val isModified: Boolean get() = values.isNotEmpty() || !lensCorrection
    val ready: Boolean get() = developer != null && preview != null

    fun load(file: File) {
        worker.execute {
            val d = runCatching { RawDeveloper.open(file) }.onFailure { Log.w("XiuTuRaw", "open", it) }.getOrNull()
            main.post {
                if (closed) { d?.let { dev -> worker.execute { dev.close() } }; return@post }
                if (d == null) {
                    errorText = "这个文件解不开：可能不是 RAW，或者这款相机 / 这种压缩方式的 RAW 还不支持。\n可以先用 Adobe DNG Converter 转成标准 DNG 再试。"
                } else {
                    developer = d
                    render()
                }
            }
        }
    }

    /** 当前一级菜单下要显示的滑块。 */
    fun keys(group: RawGroup): List<RawKey> = when (group) {
        RawGroup.LIGHT -> RawKey.lightKeys
        RawGroup.CURVE -> RawKey.curveKeys
        RawGroup.COLOR -> RawKey.colorKeys
        RawGroup.MIXER -> ColorChannel.entries.map { RawKey.mix(mixColor, it) }
        RawGroup.GRADING -> {
            val zone = ColorChannel.entries.map { RawKey.grade(gradeZone, it) }
            if (gradeZone == GradeZone.GLOBAL) zone + listOf(RawKey.gradeBlending, RawKey.gradeBalance) else zone
        }
        RawGroup.EFFECTS -> RawKey.effectKeys
        RawGroup.DETAIL -> RawKey.detailKeys.filter { developer?.supports(it) ?: true }
        RawGroup.OPTICS -> emptyList()
    }

    fun select(group: RawGroup) {
        this.group = group
        pickingWhiteBalance = false
        keys(group).firstOrNull()?.let { key = it }
    }

    fun select(color: MixColor) {
        val channel = ColorChannel.entries.firstOrNull { RawKey.mix(mixColor, it) == key } ?: ColorChannel.HUE
        mixColor = color
        key = RawKey.mix(color, channel)
    }

    fun select(zone: GradeZone) {
        val channel = ColorChannel.entries.firstOrNull { RawKey.grade(gradeZone, it) == key } ?: ColorChannel.HUE
        gradeZone = zone
        key = RawKey.grade(zone, channel)
    }

    fun isChanged(color: MixColor) = ColorChannel.entries.any { isChanged(RawKey.mix(color, it)) }
    fun isChanged(zone: GradeZone) = ColorChannel.entries.any { isChanged(RawKey.grade(zone, it)) }
    fun isChanged(group: RawGroup): Boolean = when (group) {
        RawGroup.MIXER -> MixColor.entries.any { isChanged(it) }
        RawGroup.GRADING -> GradeZone.entries.any { isChanged(it) } || isChanged(RawKey.gradeBlending) || isChanged(RawKey.gradeBalance)
        RawGroup.OPTICS -> !lensCorrection
        else -> keys(group).any { isChanged(it) }
    }

    fun value(key: RawKey): Float = values[key] ?: defaultValue(key)
    fun defaultValue(key: RawKey): Float = developer?.defaultValue(key) ?: key.neutral
    fun isChanged(key: RawKey): Boolean = values.containsKey(key)

    fun set(key: RawKey, value: Float) {
        val base = defaultValue(key)
        val span = key.range.endInclusive - key.range.start
        // 离默认值很近时吸附回默认值
        values = if (abs(value - base) < span * 0.008f) values - key else values + (key to value)
        render()
    }

    fun reset(key: RawKey) {
        values = values - key
        render()
    }

    fun resetAll() {
        values = emptyMap()
        lensCorrection = true
        render()
    }

    fun toggleLensCorrection(on: Boolean) {
        lensCorrection = on
        render()
    }

    fun applyWhiteBalance(preset: WhiteBalancePreset?) {
        values = if (preset != null) values + mapOf(RawKey.temperature to preset.temperature, RawKey.tint to preset.tint)
        else values - RawKey.temperature - RawKey.tint
        render()
    }

    /** 吸管：把点到的地方当作中性灰来定白平衡。 */
    fun pickWhiteBalance(point: PointF) {
        pickingWhiteBalance = false
        val d = developer ?: return
        val r = d.whiteBalance(point) ?: return
        val t = RawKey.temperature.range
        val n = RawKey.tint.range
        values = values + mapOf(
            RawKey.temperature to r.first.coerceIn(t.start, t.endInclusive),
            RawKey.tint to r.second.coerceIn(n.start, n.endInclusive),
        )
        render()
    }

    /** 只渲染最新的一帧：还没开始的旧请求直接被替换。 */
    private fun render() {
        val d = developer ?: return
        val snapshot = values
        val lens = lensCorrection
        val untouched = snapshot.isEmpty() && lens
        val job = {
            val img = runCatching { d.image(snapshot, lens, false) }.onFailure { Log.w("XiuTuRaw", "render", it) }.getOrNull()
            if (img != null) {
                val h = histogramOf(img)
                main.post {
                    if (!closed) {
                        preview = img
                        histogram = h
                        if (untouched || original == null) original = original ?: img
                    }
                }
            }
        }
        if (pending.getAndSet(job) == null) {
            worker.execute { if (!closed) pending.getAndSet(null)?.invoke() else pending.set(null) }
        }
    }

    /** 按原始分辨率冲洗。 */
    suspend fun develop(): Bitmap? {
        val d = developer ?: return null
        val snapshot = values
        val lens = lensCorrection
        busy = true
        try {
            return withContext(dispatcher) {
                runCatching { d.image(snapshot, lens, true) }.onFailure { Log.w("XiuTuRaw", "develop", it) }.getOrNull()
            }
        } finally {
            busy = false
        }
    }

    /** 冲洗好的图写成高质量 JPEG 交给图片编辑。 */
    suspend fun developToFile(folder: File): File? {
        val img = develop() ?: return null
        busy = true
        try {
            return withContext(dispatcher) {
                runCatching {
                    val base = name.substringBeforeLast('.', name).ifEmpty { "raw" }
                    folder.mkdirs()
                    val f = File(folder, "$base-RAW.jpg")
                    f.outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 98, it) }
                    img.recycle()
                    f
                }.onFailure { Log.w("XiuTuRaw", "write", it) }.getOrNull()
            }
        } finally {
            busy = false
        }
    }

    fun close() {
        closed = true
        pending.set(null)
        val d = developer
        worker.execute { d?.close() }
        worker.shutdown()
    }

    private fun histogramOf(b: Bitmap): FloatArray {
        val bins = IntArray(256 * 3)
        val w = b.width
        val h = b.height
        val step = max(1, (w * h / 200_000.0).let { kotlin.math.sqrt(it) }.toInt())
        val row = IntArray(w)
        var total = 0
        var y = 0
        while (y < h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                bins[(p shr 16) and 0xff]++
                bins[256 + ((p shr 8) and 0xff)]++
                bins[512 + (p and 0xff)]++
                total++
                x += step
            }
            y += step
        }
        val k = if (total > 0) 40f / total else 0f
        return FloatArray(bins.size) { (bins[it] * k).coerceAtMost(1f) }
    }
}
