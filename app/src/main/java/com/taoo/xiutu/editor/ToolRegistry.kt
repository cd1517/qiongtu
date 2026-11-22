package com.taoo.xiutu.editor

import com.taoo.xiutu.ai.AISession
import com.taoo.xiutu.tools.adjust.AdjustSession
import com.taoo.xiutu.tools.crop.CropSession
import com.taoo.xiutu.tools.filter.FilterSession
import com.taoo.xiutu.tools.mosaic.MosaicSession
import com.taoo.xiutu.tools.overlay.OverlaySession

/** 工具 → 会话。还没做的工具返回 null，菜单里也不显示。 */
object ToolRegistry {
    fun create(t: Tool, model: EditorModel): ToolSession? = when (t) {
        Tool.CROP -> CropSession(model)
        Tool.ADJUST -> AdjustSession(model)
        Tool.FILTER -> FilterSession(model)
        Tool.MOSAIC -> MosaicSession(model)
        Tool.TEXT, Tool.STICKER -> OverlaySession(model, t)
        Tool.AI -> AISession(model)
        else -> null
    }

    private val ready = setOf(Tool.CROP, Tool.ADJUST, Tool.FILTER, Tool.MOSAIC, Tool.TEXT, Tool.STICKER, Tool.AI)

    fun available(t: Tool) = t in ready
}
