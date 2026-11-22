package com.taoo.xiutu.tools.skin

import androidx.compose.runtime.Composable
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession

// 占位：待移植
class SkinSession(model: EditorModel) : ToolSession(model, Tool.SKIN) {
    override fun commitOps(): List<EditOp> = emptyList()
    @Composable override fun Panel() {}
}
