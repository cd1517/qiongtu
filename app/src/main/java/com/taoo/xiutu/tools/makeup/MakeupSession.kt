package com.taoo.xiutu.tools.makeup

import androidx.compose.runtime.Composable
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession

// 占位：待移植
class MakeupSession(model: EditorModel) : ToolSession(model, Tool.MAKEUP) {
    override fun commitOps(): List<EditOp> = emptyList()
    @Composable override fun Panel() {}
}
