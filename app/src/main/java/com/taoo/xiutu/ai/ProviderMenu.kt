package com.taoo.xiutu.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap

/**
 * 当前服务商的小标签，点开可以在已经填了 Key 的几家之间快速切换。
 * compact = 只显示服务商名字；iconOnly = 只显示齿轮。
 */
@Composable
fun ProviderMenu(openSettings: () -> Unit, compact: Boolean = false, iconOnly: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active = AISettings.active
    Box(modifier) {
        Row(
            Modifier.widthIn(max = if (iconOnly) 30.dp else if (compact) 90.dp else 190.dp).tap { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(sym("gearshape"), null, tint = Theme.dim, modifier = Modifier.size(if (iconOnly) 20.dp else 14.dp))
            if (!iconOnly) {
                Spacer(Modifier.width(5.dp))
                val label = if (AISettings.isConfigured(active) && !compact) "${active.shortTitle} · ${AISettings.config(active).model}" else active.shortTitle
                Text(label, fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Theme.chip) {
            for (provider in AIProvider.entries.filter { AISettings.isConfigured(it) }) {
                DropdownMenuItem(
                    text = { Text("${provider.shortTitle} · ${AISettings.config(provider).model}", color = Color.White) },
                    trailingIcon = if (provider == active) ({ Icon(sym("checkmark"), null, tint = Color.White) }) else null,
                    onClick = {
                        open = false
                        AISettings.setActive(provider)
                    },
                )
            }
            HorizontalDivider(color = Theme.dim.copy(alpha = 0.3f))
            DropdownMenuItem(text = { Text("AI 设置…", color = Color.White) }, onClick = {
                open = false
                openSettings()
            })
        }
    }
}
