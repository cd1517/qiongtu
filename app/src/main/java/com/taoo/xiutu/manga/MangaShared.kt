package com.taoo.xiutu.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIError
import com.taoo.xiutu.ai.AIProvider
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ui.CornerBadge
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.delay
import kotlin.math.max

/** 出错原因的文字。 */
internal fun Throwable.aiMessage(): String = (this as? AIError)?.text ?: message ?: toString()

/** 从 started（毫秒）起过了几秒，每秒刷新。 */
@Composable
internal fun elapsedSeconds(started: Long): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return max(0, ((now - started) / 1000).toInt())
}

/** 生成中：转圈 + 文字 + 取消。 */
@Composable
internal fun RunningRow(text: String, action: String, verticalPadding: Int = 22, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = verticalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
        Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Theme.accent, modifier = Modifier.tap(onClick = onAction))
    }
}

/** 黄色胶囊大按钮（带 AI 角标）。 */
@Composable
internal fun AccentButton(title: String, symbol: String?, modifier: Modifier = Modifier, badge: String? = "AI", onClick: () -> Unit) {
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(Theme.accent).press(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (symbol != null) {
                Icon(sym(symbol), null, tint = Color.Black, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(6.dp))
            }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        if (badge != null) CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-5).dp))
    }
}

/** 风格卡片：名字 + 一行描述，选中时描边。 */
@Composable
internal fun StyleCard(title: String, subtitle: String, selected: Boolean, check: Boolean? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 138.dp, height = 82.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.chip)
            .border(2.dp, if (selected) Theme.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .tap(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val fg = if (selected) Theme.accent else Color.White
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (check != null) Icon(sym(if (check) "checkmark.circle.fill" else "circle"), null, tint = fg, modifier = Modifier.size(13.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(subtitle, fontSize = 10.sp, color = Theme.dim, maxLines = 3, lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
    }
}

/** 选服务商的小菜单（iOS 的 ProviderMenu，紧凑模式只显示服务商名字）。 */
@Composable
internal fun ProviderMenu(openSettings: () -> Unit, compact: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val active = AISettings.active
    Box {
        Row(
            Modifier.widthIn(max = if (compact) 90.dp else 190.dp).tap { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(sym("gearshape"), null, tint = Theme.dim, modifier = Modifier.size(13.dp))
            Text(
                if (AISettings.isConfigured(active) && !compact) "${active.shortTitle} · ${AISettings.config(active).model}" else active.shortTitle,
                fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Theme.chip) {
            for (provider in AIProvider.entries.filter { AISettings.isConfigured(it) }) {
                DropdownMenuItem(
                    text = { Text("${provider.shortTitle} · ${AISettings.config(provider).model}", color = Color.White, fontSize = 14.sp) },
                    leadingIcon = if (provider == active) ({ Icon(sym("checkmark"), null, tint = Color.White, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = {
                        AISettings.setActive(provider)
                        open = false
                    },
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            DropdownMenuItem(text = { Text("AI 设置…", color = Color.White, fontSize = 14.sp) }, onClick = {
                open = false
                openSettings()
            })
        }
    }
}
