package com.taoo.xiutu.ai

import androidx.compose.runtime.Composable
import com.taoo.xiutu.editor.AiToolApi
import com.taoo.xiutu.editor.EditOp
import com.taoo.xiutu.editor.EditorModel
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.editor.ToolSession

// 占位：待移植
class AISession(model: EditorModel) : ToolSession(model, Tool.AI), AiToolApi {
    override fun commitOps(): List<EditOp> = emptyList()
    override fun setup(group: AIPresetGroup, prompt: String, preset: AIPreset?, run: Boolean) {}
    @Composable override fun Panel() {}
}
