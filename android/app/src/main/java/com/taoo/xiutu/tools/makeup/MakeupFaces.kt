package com.taoo.xiutu.tools.makeup

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.taoo.xiutu.core.Img
import com.taoo.xiutu.ml.FaceDetector
import com.taoo.xiutu.ml.FaceInfo
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 找脸。ML Kit 只给画面里最显眼的那张脸算五官轮廓，其余的脸没有轮廓、没法上妆；
 * 这里把每张缺轮廓的脸单独裁出来再找一次（裁出来以后它就是最显眼的那张），坐标换回整张图。
 * 要在后台线程调用。
 */
object MakeupFaces {
    fun detect(image: Bitmap): List<FaceInfo> {
        // 缺轮廓的脸裁出来再找一次，这件事 FaceDetector 已经做了
        return FaceDetector.detect(image)
    }

    private fun refine(image: Bitmap, face: FaceInfo): FaceInfo? {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val bw = face.box.width() * w
        val bh = face.box.height() * h
        val side = max(bw, bh) * 2.2f
        val cx = face.box.centerX() * w
        val cy = face.box.centerY() * h
        val x0 = max(0, (cx - side / 2).roundToInt())
        val y0 = max(0, (cy - side / 2).roundToInt())
        val x1 = min(image.width, (cx + side / 2).roundToInt())
        val y1 = min(image.height, (cy + side / 2).roundToInt())
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 24 || ch < 24) return null
        var crop = Bitmap.createBitmap(image, x0, y0, cw, ch)
        // 脸太小时放大一点，轮廓点才找得准
        if (max(cw, ch) < 480) {
            val k = 480f / max(cw, ch)
            crop = Img.scaled(crop, (cw * k).roundToInt(), (ch * k).roundToInt())
        }
        fun map(p: PointF) = PointF((x0 + p.x * cw) / w, (y0 + p.y * ch) / h)
        // 裁出来的图里可能还带着别人的半张脸：取换回去以后离原来那张脸最近、而且落在它框里的
        val candidates = FaceDetector.detect(crop).filter { it.hasReliableLandmarks }
        val best = candidates.minByOrNull { c ->
            val m = map(PointF(c.box.centerX(), c.box.centerY()))
            hypot(m.x - face.box.centerX(), m.y - face.box.centerY())
        } ?: return null
        val center = map(PointF(best.box.centerX(), best.box.centerY()))
        if (!face.box.contains(center.x, center.y)) return null
        val tl = map(PointF(best.box.left, best.box.top))
        val br = map(PointF(best.box.right, best.box.bottom))
        return best.copy(
            box = RectF(tl.x, tl.y, br.x, br.y),
            contour = best.contour.map(::map),
            leftEye = best.leftEye.map(::map),
            rightEye = best.rightEye.map(::map),
            leftBrow = best.leftBrow.map(::map),
            rightBrow = best.rightBrow.map(::map),
            outerLips = best.outerLips.map(::map),
            innerLips = best.innerLips.map(::map),
            nose = best.nose.map(::map),
            leftPupil = best.leftPupil?.let(::map),
            rightPupil = best.rightPupil?.let(::map),
            noseCrest = best.noseCrest?.map(::map),
            medianLine = best.medianLine?.map(::map),
        ).takeIf { it.hasReliableLandmarks }
    }
}
