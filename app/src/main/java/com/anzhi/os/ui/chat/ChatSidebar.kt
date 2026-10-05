package com.anzhi.os.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 聊天侧栏 — 显示会话历史列表，支持切换会话和搜索记忆。
 *
 * @param sessions 会话列表
 * @param currentSessionId 当前会话 ID
 * @param onSwitchSession 切换到指定会话
 * @param onNewSession 新建会话
 * @param onClose 关闭侧栏
 * @param onOpenSettings 打开 VPS 接入设置
 * @param onSearchMemory 搜索记忆（预留）
 */
@Composable
fun ChatSidebar(
    sessions: List<ChatSessionInfo> = emptyList(),
    currentSessionId: String = "",
    onSwitchSession: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    onClose: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSearchMemory: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(AnzhiDrawerBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 侧栏头部
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "时间线",
                    style = AnzhiTypography.pixelSubtitle,
                    color = AnzhiTextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "新对话",
                        style = AnzhiTypography.bodySmall,
                        color = AnzhiAccentCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onNewSession() }
                            .padding(4.dp)
                    )
                    Text(
                        text = "接入",
                        style = AnzhiTypography.bodySmall,
                        color = AnzhiTextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onOpenSettings() }
                            .padding(4.dp)
                    )
                    Text(
                        text = "✕",
                        style = AnzhiTypography.body,
                        color = AnzhiTextTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onClose() }
                            .padding(4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 搜索框（预留）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AnzhiInputBorder)
                    .padding(10.dp)
            ) {
                Text(
                    text = "搜索记忆…",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
            }

            Spacer(Modifier.height(12.dp))

            // 会话列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sessions) { session ->
                    SessionItem(
                        session = session,
                        isActive = session.sessionId == currentSessionId,
                        onClick = { onSwitchSession(session.sessionId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSessionInfo,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isActive)
        AnzhiPrimaryContainer
    else
        AnzhiDrawerItem

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "新对话" },
                style = AnzhiTypography.bodySmall,
                color = if (isActive) AnzhiTextPrimary else AnzhiTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTime(session.updatedAt) + " · ${session.messageCount}条",
                style = AnzhiTypography.chatLabel,
                color = AnzhiTextTertiary
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        diff < 604800_000 -> "${diff / 86400_000}天前"
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
}
