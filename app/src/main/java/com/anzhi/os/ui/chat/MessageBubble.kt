package com.anzhi.os.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.*

/**
 * 聊天气泡 — 用户（紫色）/ 安知（深色）/ 系统（灰色）三种样式。
 *
 * @param message 消息数据
 * @param modifier Modifier
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isSystem) {
            // 系统消息：居中灰色小字
            Text(
                text = message.content,
                style = AnzhiTypography.bodySmall,
                color = AnzhiSystemMsg,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .align(Alignment.CenterHorizontally)
            )
        } else {
            // 用户名标签
            Text(
                text = if (isUser) "Cami" else "安知",
                style = AnzhiTypography.chatLabel,
                color = if (isUser) AnzhiPrimary else AnzhiAccentCyan
            )
            Spacer(Modifier.height(2.dp))

            // 气泡
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    ))
                    .background(
                        when {
                            isUser -> AnzhiUserBubble
                            else -> AnzhiAnzhiBubble
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 流式渲染指示
                if (message.isStreaming) {
                    Text(
                        text = message.content + "▊",
                        style = AnzhiTypography.chatBody,
                        color = AnzhiTextPrimary
                    )
                } else {
                    Text(
                        text = message.content,
                        style = AnzhiTypography.chatBody,
                        color = AnzhiTextPrimary
                    )
                }
            }
        }
    }
}

/**
 * 正在输入指示器 — 安知思考时的三点动画占位。
 */
@Composable
fun TypingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AnzhiAnzhiBubble)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AnzhiTextSecondary)
                )
            }
        }
    }
}
