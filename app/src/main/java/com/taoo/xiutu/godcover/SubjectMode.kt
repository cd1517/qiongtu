package com.taoo.xiutu.godcover

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.taoo.xiutu.ai.AIOneShot
import com.taoo.xiutu.ml.FaceDetector
import com.taoo.xiutu.ui.SegmentBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * 封神、摄影棚共用：这张照片的主体是人，还是一个场景。
 * 人物 = 原来的玩法；场景 = 照片里的场景本身就是主体，AI 不许往里加人。
 */
enum class SubjectMode(val title: String, val symbol: String) {
    PERSON("人物", "person.fill"),
    SCENE("场景", "mountain.2.fill");

    companion object {
        /** 在本机判断：画面里有够大的人脸或人形算人物，否则算场景。远处的路人不算。 */
        suspend fun detect(image: Bitmap): SubjectMode = withContext(Dispatchers.Default) {
            val small = AIOneShot.prepared(image, 1024)
            // 最大的那张脸占画面 0.15% 以上（全身照里的脸大约就这么大）
            val face = runCatching { FaceDetector.detect(small).firstOrNull() }.getOrNull()
            if (face != null && face.box.width() * face.box.height() >= 0.0015f) return@withContext PERSON
            // 没露脸的人（背影、侧身）：人形占画面 3% 以上
            val coverage = runCatching { personCoverage(small) }.getOrNull() ?: 0.0
            if (coverage >= 0.03) PERSON else SCENE
        }

        private val segmenter by lazy {
            Segmentation.getClient(
                SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
            )
        }

        /** 人像蒙版里置信度过半的比例。 */
        private fun personCoverage(image: Bitmap): Double {
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(image, 0)))
            val buf = mask.buffer.order(ByteOrder.nativeOrder())
            buf.rewind()
            val floats = buf.asFloatBuffer()
            val n = mask.width * mask.height
            if (n <= 0) return 0.0
            var on = 0
            var total = 0
            var i = 0
            // 隔几个点取一次就够
            while (i < n && i < floats.limit()) {
                if (floats.get(i) > 0.5f) on++
                total++
                i += 7
            }
            return if (total == 0) 0.0 else on.toDouble() / total
        }
    }
}

/** 人物 / 场景 的两段开关，放在面板最上面。prefix 是标签前面加的字（摄影棚用"拍"）。 */
@Composable
fun SubjectModeBar(selected: SubjectMode, prefix: String = "", disabled: Boolean = false, select: (SubjectMode) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(196.dp).alpha(if (disabled) 0.4f else 1f)) {
            SegmentBar(SubjectMode.entries, selected, { prefix + it.title }, { it.symbol }) { if (!disabled) select(it) }
        }
    }
}
