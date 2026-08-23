package com.anzhi.os.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.*

/**
 * 顶部滑入临时卡片 — 突发事件/安知 speak 提示。
 *
 * 从屏幕顶部滑入，展示临时消息。
 * 支持紧急（emergency）和普通（normal）优先级。
 *
 * @param data 临时卡片数据（null = 隐藏）
 * @param onDismiss 关闭回调
 */
data class TempCardData(
    val id: String,
    val title: String,
    val text: String,
    val priority: String = "normal",  // "emergency" | "normal"
    val autoDismiss: Boolean = true
)

@Composable
fun TempOverlay(
    data: TempCardData?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = data != null,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300)
        ) + fadeIn(tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(200)
        ) + fadeOut(tween(200)),
        modifier = modifier
    ) {
        data?.let { card ->
            val bgColor = if (card.priority == "emergency")
                AnzhiAccentRed.copy(alpha = 0.15f)
            else
                AnzhiSurfaceHigh

            val accentColor = if (card.priority == "emergency")
                AnzhiAccentRed
            else
                AnzhiPrimary

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 优先级指示条
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(accentColor)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = AnzhiTypography.tempCardTitle,
                        color = accentColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = card.text,
                        style = AnzhiTypography.bodySmall,
                        color = AnzhiTextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
