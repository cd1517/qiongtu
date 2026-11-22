package com.taoo.xiutu.tools.shape

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.taoo.xiutu.core.Img
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max

/**
 * 用 ML Kit 人体姿态识别找出肩、肘、腕、胯、膝、踝和头的位置，全部在本机完成（iOS 用的是 Vision）。
 * ML Kit 一张图只给出最显眼的那个人。会阻塞，必须在后台线程调用。
 */
object ShapeBodyDetector {
    private val lock = Any()

    /** 关键点在画面里的可信度门槛。 */
    private const val MIN_LIKELIHOOD = 0.5f

    /** 返回画面里的人，最大的排最前。坐标是单位坐标（原图长边为 1，原点左上角）。 */
    fun detect(image: Bitmap): List<ShapeBody> = synchronized(lock) {
        runCatching {
            val input = Img.soft(image)
            val client = PoseDetection.getClient(
                AccuratePoseDetectorOptions.Builder()
                    .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                    .build()
            )
            try {
                val pose = Tasks.await(client.process(InputImage.fromBitmap(input, 0)), 30, TimeUnit.SECONDS)
                listOfNotNull(build(pose, input.width, input.height))
            } finally {
                client.close()
            }
        }.getOrElse { emptyList() }
    }

    private fun build(pose: Pose, width: Int, height: Int): ShapeBody? {
        if (pose.allPoseLandmarks.isEmpty()) return null
        val long = max(max(width, height), 1).toFloat()
        fun point(type: Int): V2? {
            val p = pose.getPoseLandmark(type) ?: return null
            if (p.inFrameLikelihood <= MIN_LIKELIHOOD) return null
            return V2(p.position.x / long, p.position.y / long)
        }
        val personLeft = point(PoseLandmark.LEFT_SHOULDER) ?: return null
        val personRight = point(PoseLandmark.RIGHT_SHOULDER) ?: return null
        // 面对镜头时，这个人自己的右边在画面左边；背对镜头时反过来。
        val facing = personRight.x <= personLeft.x
        fun limb(useRight: Boolean) = ShapeBody.Limb(
            shoulder = point(if (useRight) PoseLandmark.RIGHT_SHOULDER else PoseLandmark.LEFT_SHOULDER),
            elbow = point(if (useRight) PoseLandmark.RIGHT_ELBOW else PoseLandmark.LEFT_ELBOW),
            wrist = point(if (useRight) PoseLandmark.RIGHT_WRIST else PoseLandmark.LEFT_WRIST),
            hip = point(if (useRight) PoseLandmark.RIGHT_HIP else PoseLandmark.LEFT_HIP),
            knee = point(if (useRight) PoseLandmark.RIGHT_KNEE else PoseLandmark.LEFT_KNEE),
            ankle = point(if (useRight) PoseLandmark.RIGHT_ANKLE else PoseLandmark.LEFT_ANKLE),
        )
        var body = ShapeBody(left = limb(facing), right = limb(!facing))
        val ls = body.left.shoulder ?: return null
        val rs = body.right.shoulder ?: return null
        val shoulderWidth = (rs - ls).length
        if (shoulderWidth <= 0.01f) return null
        val mid = (ls + rs) / 2f

        // 头：用鼻子、眼睛、耳朵估出中心和大小。
        val nose = point(PoseLandmark.NOSE)
        val leftEar = point(PoseLandmark.LEFT_EAR)
        val rightEar = point(PoseLandmark.RIGHT_EAR)
        val leftEye = point(PoseLandmark.LEFT_EYE)
        val rightEye = point(PoseLandmark.RIGHT_EYE)
        val face = listOfNotNull(nose, leftEar, rightEar, leftEye, rightEye)
        if (face.isNotEmpty()) {
            var center = nose ?: V2(face.sumOf { it.x.toDouble() }.toFloat() / face.size, face.sumOf { it.y.toDouble() }.toFloat() / face.size)
            var radius = shoulderWidth * 0.42f
            if (leftEar != null && rightEar != null) {
                radius = hypot(leftEar.x - rightEar.x, leftEar.y - rightEar.y) * 0.72f
            } else if (leftEye != null && rightEye != null) {
                radius = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y) * 1.7f
            }
            radius = radius.coerceIn(shoulderWidth * 0.28f, shoulderWidth * 0.75f)
            // 鼻子在脸的中间偏下，整个头的中心还要再往上一点。
            val away = center - mid
            val length = max(away.length, 0.0001f)
            center += away / length * (radius * 0.2f)
            body = body.copy(head = center, headRadius = radius)
        }
        return body
    }
}
