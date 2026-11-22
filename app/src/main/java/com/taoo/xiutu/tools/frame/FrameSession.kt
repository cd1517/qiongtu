package com.taoo.xiutu.tools.frame

import androidx.compose.runtime.Composable
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession

// 占位：待移植
class FrameSession(model: EditorModel, tool: Tool) : ToolSession(model, tool) {
    override fun commitOps(): List<EditOp> = emptyList()
    @Composable override fun Panel() {}
}
