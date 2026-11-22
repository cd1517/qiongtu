package com.taoo.xiutu.manga

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ai.AIFailureView
import com.taoo.xiutu.ai.AISettings
import com.taoo.xiutu.ai.BottomTabBar
import com.taoo.xiutu.ai.ResultViewer
import com.taoo.xiutu.settings.SettingsSheet
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private enum class DetailPage(val title: String) { PORTRAIT("立绘"), SHEET("三视图") }

/** 角色详情：看立绘和三视图，生成或重画三视图，让角色出场，删除。 */
@Composable
fun CharacterDetailScreen(
    characterID: String,
    isInCast: (String) -> Boolean,
    toggleCast: (MangaCharacter) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(DetailPage.PORTRAIT) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val character = CharacterStore.characters.firstOrNull { it.id == characterID }

    fun makeSheet() {
        if (running) return
        if (!AISettings.isConfigured(AISettings.active)) {
            aiError = "还没有设置 ${AISettings.active.shortTitle} 的 API Key。"
            return
        }
        aiError = null
        note = null
        running = true
        started = System.currentTimeMillis()
        job = scope.launch {
            try {
                val cost = CharacterStore.generateSheet(characterID)
                running = false
                page = DetailPage.SHEET
                note = cost?.let { "三视图画好了，这次约 \$${String.format(Locale.US, "%.3f", it)}。" } ?: "三视图画好了。"
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: Exception) {
                running = false
                aiError = e.aiMessage()
            }
        }
    }

    BackHandler(enabled = viewing == null, onBack = onClose)

    Box(Modifier.fillMaxSize().background(Theme.bg).tap { }) {
        if (character != null) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(sym("xmark"), null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(character.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(44.dp).alpha(if (running) 0.4f else 1f).tap(enabled = !running) { confirmDelete = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(sym("trash"), null, tint = Color.White, modifier = Modifier.size(19.dp)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val file = if (page == DetailPage.PORTRAIT) character.imageFile else character.sheetFile
                    val image by produceState(file?.let { CharacterStore.cached(it) }, file) {
                        value = file?.let { CharacterStore.imageAsync(it) }
                    }
                    val shown = image
                    if (shown != null) {
                        val bitmap = remember(shown) { shown.asImageBitmap() }
                        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap, null, contentScale = ContentScale.Fit,
                                modifier = Modifier.aspectRatio(shown.width.toFloat() / shown.height).clip(RoundedCornerShape(6.dp))
                                    .tap { viewing = shown },
                            )
                        }
                    } else if (file == null) {
                        Icon(sym("person.3.sequence"), null, tint = Theme.dim, modifier = Modifier.size(44.dp))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${character.artStyle.displayName} · ${character.gender.displayName} · ${character.ageGroup.displayName}",
                            fontSize = 12.sp, color = Theme.dim, modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        ProviderMenu(openSettings = { showSettings = true })
                    }
                    if (character.bio.isNotEmpty()) {
                        Text(character.bio, fontSize = 12.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    }
                    if (running) {
                        val seconds = elapsedSeconds(started)
                        RunningRow("正在画三视图… $seconds 秒", "取消", verticalPadding = 10) {
                            job?.cancel()
                            running = false
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AccentButton(
                                if (character.sheetFile == null) "画三视图" else "重画三视图",
                                "person.3.sequence.fill", Modifier.weight(1f), onClick = ::makeSheet,
                            )
                            Box(
                                Modifier.size(104.dp, 44.dp).clip(CircleShape).background(Theme.chip).press { toggleCast(character) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (isInCast(character.id)) "取消出场" else "让它出场", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                        note?.let { Text(it, fontSize = 11.sp, color = Theme.accent, modifier = Modifier.fillMaxWidth()) }
                        AIFailureView(aiError, retry = ::makeSheet)
                    }
                }
                BottomTabBar(DetailPage.entries, { it.title }, page) { page = it }
            }
        }
        viewing?.let { ResultViewer(it, onClose = { viewing = null }) }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Theme.panel,
            title = { Text("删除这个角色？", color = Color.White, fontSize = 17.sp) },
            text = { Text("立绘和三视图都会从角色库里删掉，不能恢复。", color = Theme.dim, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    character?.let {
                        if (isInCast(it.id)) toggleCast(it)
                        CharacterStore.remove(it)
                    }
                    onClose()
                }) { Text("删除", color = Theme.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Color.White) }
            },
        )
    }
}
