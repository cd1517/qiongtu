package com.taoo.xiutu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.editor.Tool
import com.taoo.xiutu.history.HistoryStore
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.*

/** 主页：一个大的"开始编辑"，加几个直达某个工具的快捷入口。 */
@Composable
fun HomeScreen(
    start: (Tool?) -> Unit,
    startCover: () -> Unit,
    startStudio: () -> Unit,
    startManga: () -> Unit,
    startHistory: () -> Unit,
    startRaw: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val shortcuts = listOf(Tool.CROP, Tool.FILTER, Tool.ADJUST, Tool.MOSAIC)
    Box(Modifier.fillMaxSize().background(Theme.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("穷图", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).background(Theme.chip).tap { showSettings = true }, contentAlignment = Alignment.Center) {
                    Icon(sym("gearshape.fill"), "设置", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for (t in shortcuts) {
                    Column(Modifier.weight(1f).press { start(t) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(sym(t.symbol), null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Text(t.title, fontSize = 13.sp, color = Color.White)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).press { start(null) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(sym("plus.app.fill"), null, tint = Color.Black, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("开始编辑", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureButton("camera.filters", "RAW 冲洗", action = startRaw)
                FeatureButton("crown.fill", "封神", action = startCover)
                FeatureButton("camera.aperture", "摄影棚", action = startStudio)
                FeatureButton("book.pages.fill", "漫画人生", action = startManga)
                FeatureButton(if (HistoryStore.hasPassword) "lock.fill" else "photo.stack", "历史图库", value = "${HistoryStore.count} 张", action = startHistory)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).press { showSettings = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(sym("sparkles"), null, tint = Theme.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("AI 修图：${AISettings.activeShortTitle}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    if (AISettings.isActiveConfigured()) Text(AISettings.activeModel, fontSize = 12.sp, color = Theme.dim)
                }
                Icon(sym("chevron.right"), null, tint = Theme.dim, modifier = Modifier.size(14.dp))
            }
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

@Composable
private fun FeatureButton(symbol: String, title: String, value: String? = null, badge: String? = null, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Theme.panel).press(onClick = action).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Theme.accent), contentAlignment = Alignment.Center) {
                Icon(sym(symbol), null, tint = Color.Black, modifier = Modifier.size(22.dp))
            }
            if (badge != null) CornerBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (value != null) Text(value, fontSize = 12.sp, color = Theme.dim)
        }
        Icon(sym("chevron.right"), null, tint = Theme.dim, modifier = Modifier.size(14.dp))
    }
}
