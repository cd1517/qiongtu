package com.taoo.xiutu.editor

import android.graphics.Bitmap
import java.util.UUID

/** 请求单独编辑一个图层的内容（或者图层下面的照片）。 */
class LayerEditRequest(
    val title: String,
    /** 从这张图开始。 */
    val source: Bitmap,
    /** 已经做过的修改。 */
    val ops: List<EditOp>,
    /** 点"完成"时把新的修改步骤交回来。 */
    val onDone: (List<EditOp>) -> Unit,
) {
    val id: String = UUID.randomUUID().toString()
}
