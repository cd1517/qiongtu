package com.taoo.xiutu.tools.layers

import androidx.compose.runtime.Composable
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession

// 占位：待移植
class LayerSession(model: EditorModel) : ToolSession(model, Tool.LAYERS) {
    override fun commitOps(): List<EditOp> = emptyList()
    @Composable override fun Panel() {}
}
