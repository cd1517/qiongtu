package com.taoo.xiutu.godcover

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.taoo.xiutu.ai.AIProvider
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.delay

/** 把引擎的网页视图挂进界面：1×1、几乎透明、不接收点击。网页本身按 720×960 排版。 */
@Composable
fun EngineHost(webView: WebView) {
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true
                (webView.parent as? ViewGroup)?.removeView(webView)
                addView(webView, FrameLayout.LayoutParams(720, 960))
            }
        },
        modifier = Modifier.size(1.dp).alpha(0.01f),
    )
}

/** 当前服务商的小标签，点开可以在已经填了 Key 的几家之间快速切换（紧凑模式：只显示服务商名字）。 */
@Composable
fun ProviderMenu(openSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.widthIn(max = 90.dp).tap { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(sym("gearshape"), null, tint = Theme.dim, modifier = Modifier.size(14.dp))
            Text(AISettings.active.shortTitle, fontSize = 12.sp, color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Theme.chip) {
            for (provider in AIProvider.entries.filter { AISettings.isConfigured(it) }) {
                DropdownMenuItem(
                    text = { Text("${provider.shortTitle} · ${AISettings.config(provider).model}", color = Color.White, fontSize = 14.sp) },
                    leadingIcon = if (AISettings.active == provider) {
                        { Icon(sym("checkmark"), null, tint = Theme.accent, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        AISettings.setActive(provider)
                        open = false
                    },
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            DropdownMenuItem(
                text = { Text("AI 设置…", color = Color.White, fontSize = 14.sp) },
                onClick = {
                    open = false
                    openSettings()
                },
            )
        }
    }
}

/** 从 start（elapsedRealtime）到现在过了几秒，每秒刷新。 */
@Composable
fun elapsedSeconds(start: Long): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return maxOf(0L, (now - start) / 1000)
}

/** 在一行里让内容不透明度 0（还没判断完主体时先不亮出菜单）。 */
fun Modifier.hiddenIf(hidden: Boolean): Modifier = alpha(if (hidden) 0f else 1f)
