package com.taoo.xiutu.tools.doodle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * 画的过程中用的两块位图（和预览图一样大）：一块存已经画完的笔画，一块给正在画的这一笔。
 * 手指每动一下只重画正在画的这一笔，画完的那些不用再算。
 * 所有绘制都在渲染线程里做（render 加锁）；主线程只交给它"现在应该是哪些笔画"。
 */
class DoodleRuntime private constructor(val width: Int, val height: Int, private val committed: Bitmap, private val scratch: Bitmap) {
    /** 已经画进 committed 的笔画（和传进来的列表逐项同一个对象才算没变）。 */
    private var drawn: List<DoodleStroke> = emptyList()
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** 把 committed 对齐到 strokes：只是后面多了几笔就接着画，否则从头重画。 */
    private fun sync(strokes: List<DoodleStroke>) {
        val prefix = strokes.size >= drawn.size && drawn.indices.all { drawn[it] === strokes[it] }
        val c = Canvas(committed)
        if (!prefix) {
            DoodleRenderer.clear(committed)
            for (s in strokes) DoodleRenderer.draw(s, c, width, height)
        } else {
            for (i in drawn.size until strokes.size) DoodleRenderer.draw(strokes[i], c, width, height)
        }
        drawn = strokes.toList()
    }

    /** 已完成的笔画加上正在画的这一笔，叠到 base 上。 */
    @Synchronized
    fun render(base: Bitmap, strokes: List<DoodleStroke>, active: DoodleStroke?): Bitmap {
        sync(strokes)
        val hasCommitted = strokes.any { it.brush != DoodleBrush.SPOT }
        val layers = ArrayList<Pair<Bitmap, Float>>()
        if (hasCommitted) layers += committed to 1f
        if (active != null && active.brush != DoodleBrush.SPOT) {
            if (active.brush == DoodleBrush.ERASER) {
                // 橡皮只擦笔画：把画完的那层拷一份，在副本上擦给用户看
                layers.clear()
                if (hasCommitted) {
                    val c = Canvas(scratch)
                    c.drawBitmap(committed, 0f, 0f, copyPaint)
                    DoodleRenderer.draw(active, c, width, height)
                    layers += scratch to 1f
                }
            } else {
                // 这一笔先按不透明画，整层再统一调淡：一笔之内自己叠到自己不会变深
                DoodleRenderer.clear(scratch)
                DoodleRenderer.draw(active.copy(opacity = 1f), Canvas(scratch), width, height)
                layers += scratch to active.opacity
            }
        }
        val spots = strokes.filter { it.brush == DoodleBrush.SPOT }.toMutableList()
        if (active != null && active.brush == DoodleBrush.SPOT) spots += active
        return DoodleRenderer.compose(base, layers, spots)
    }

    companion object {
        fun create(width: Int, height: Int): DoodleRuntime? {
            val a = DoodleRenderer.makeBitmap(width, height) ?: return null
            val b = DoodleRenderer.makeBitmap(width, height) ?: return null
            return DoodleRuntime(width, height, a, b)
        }
    }
}
