package com.anzhi.os.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anzhi.os.ui.theme.*

/**
 * 聊天输入框 — 底部固定，带发送按钮和锁定状态。
 *
 * @param text 当前输入文本
 * @param onTextChange 文本变化回调
 * @param onSend 发送回调
 * @param locked 是否锁定（安知思考时禁止输入）
 */
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    locked: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AnzhiInputBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding(),  // 避开系统导航栏
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 输入框
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(AnzhiInputBorder)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { if (!locked) onTextChange(it) },
                textStyle = TextStyle(
                    color = AnzhiTextPrimary,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(AnzhiPrimary),
                modifier = Modifier.fillMaxWidth(),
                enabled = !locked,
                decorationBox = { innerTextField ->
                    if (text.isEmpty() && !locked) {
                        Text(
                            text = "给安知发消息…",
                            style = TextStyle(
                                color = AnzhiTextTertiary,
                                fontSize = 15.sp,
                            )
                        )
                    }
                    innerTextField()
                }
            )
        }

        Spacer(Modifier.width(8.dp))

        // 发送按钮
        val sendEnabled = text.isNotBlank() && !locked

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (sendEnabled) AnzhiPrimary else AnzhiInputBorder)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = sendEnabled
                ) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "↑",
                style = TextStyle(
                    color = if (sendEnabled) AnzhiTextPrimary else AnzhiTextTertiary,
                    fontSize = 18.sp,
                )
            )
        }
    }
}
