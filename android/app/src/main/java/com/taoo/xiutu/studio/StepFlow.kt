package com.taoo.xiutu.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taoo.xiutu.ui.Theme
import com.taoo.xiutu.ui.press
import com.taoo.xiutu.ui.sym
import com.taoo.xiutu.ui.tap

/**
 * 一步一步走的流程：顶上是步骤条，底下是"上一步 / 下一步"。
 * 走过的步骤随时能点回去改；没走到的要按顺序一步步过去。
 */
@Composable
fun <T> StepBar(steps: List<T>, current: T, reached: T, title: (T) -> String, disabled: Boolean = false, select: (T) -> Unit) {
    val reachedIndex = steps.indexOf(reached).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().background(Theme.panel).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, step ->
            val isCurrent = step == current
            val open = i <= reachedIndex
            Row(
                Modifier.weight(1f).tap(enabled = open && !disabled) { select(step) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (isCurrent) Theme.accent else if (open) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (open) Color.Transparent else Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (open && !isCurrent) {
                        Icon(sym("checkmark"), null, tint = Theme.accent, modifier = Modifier.size(11.dp))
                    } else {
                        Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isCurrent) Color.Black else Theme.dim)
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    title(step), fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) Color.White else if (open) Color.White.copy(alpha = 0.8f) else Theme.dim,
                )
            }
            if (i < steps.size - 1) {
                Box(Modifier.width(10.dp).height(1.5.dp).background(if (i < reachedIndex) Theme.accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f)))
            }
        }
    }
}

/** 底部的上一步 / 下一步。最后一步只留"上一步"（开始按钮在面板里）。 */
@Composable
fun StepFooter(
    isFirst: Boolean,
    isLast: Boolean,
    canAdvance: Boolean = true,
    nextTitle: String = "下一步",
    disabled: Boolean = false,
    back: () -> Unit,
    next: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Theme.panel).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
            .alpha(if (disabled) 0.4f else 1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!isFirst) {
            Row(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(CircleShape).background(Theme.chip).press(!disabled, back),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(sym("chevron.left"), null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("上一步", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        if (!isLast) {
            Row(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(CircleShape)
                    .background(if (canAdvance) Theme.accent else Theme.accent.copy(alpha = 0.35f))
                    .press(!disabled && canAdvance, next),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(nextTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.width(6.dp))
                Icon(sym("chevron.right"), null, tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}
