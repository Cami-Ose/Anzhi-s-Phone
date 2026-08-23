package com.anzhi.os.ui.dashboard.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.dashboard.MessageItem
import com.anzhi.os.ui.components.CornerCard
import com.anzhi.os.ui.theme.*

/**
 * 消息 Widget — 显示重要通知和安知消息。
 *
 * 只显示 important 级别的通知，最多 3 条。
 * 安知消息（speak action）用紫色标记。
 *
 * @param messages 消息列表
 */
@Composable
fun MessagesWidget(
    messages: List<MessageItem> = emptyList()
) {
    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Column {
            Text(
                text = "消息",
                style = AnzhiTypography.widgetTitle,
                color = AnzhiTextTertiary
            )
            Spacer(Modifier.height(4.dp))

            if (messages.isEmpty()) {
                Text(
                    text = "没有新消息",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
            } else {
                messages.take(3).forEach { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        val nameColor = when {
                            msg.isAnzhiMessage -> AnzhiPrimary
                            msg.isImportant -> AnzhiAccentOrange
                            else -> AnzhiTextSecondary
                        }
                        Text(
                            text = msg.sender + "：",
                            style = AnzhiTypography.bodySmall,
                            color = nameColor
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = msg.text,
                            style = AnzhiTypography.bodySmall,
                            color = AnzhiTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
