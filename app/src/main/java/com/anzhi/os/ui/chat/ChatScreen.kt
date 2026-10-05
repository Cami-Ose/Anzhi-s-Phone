package com.anzhi.os.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.components.AnzhiStatusBar
import com.anzhi.os.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 聊天全屏 — 消息列表 + 输入框 + 侧栏手势。
 *
 * 右滑打开侧栏，左滑关闭。
 * 消息自动滚动到最新。
 *
 * @param state 聊天状态
 * @param onSendMessage 发送消息
 * @param onTextChange 输入文本变化
 * @param onSwitchSession 切换会话
 * @param onNewSession 新建会话
 * @param onToggleSidebar 切换侧栏
 * @param onClose 关闭聊天
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onSendMessage: () -> Unit = {},
    onTextChange: (String) -> Unit = {},
    onSwitchSession: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    onToggleSidebar: () -> Unit = {},
    onClose: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 新消息时自动滚动到底部
    val messageCount = state.messages.size
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // 主聊天区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { /* 手势由外部处理 */ },
                        onHorizontalDrag = { _, _ -> }
                    )
                }
        ) {
            // 顶部状态栏
            AnzhiStatusBar(
                sidePadding = 16.dp,
                timeText = currentTimeForChat()  // lazy static
            )

            // 聊天头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "≡",
                    style = AnzhiTypography.pixelTitle,
                    color = AnzhiTextSecondary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = { onToggleSidebar() },
                                onHorizontalDrag = { change, _ -> change.consume() }
                            )
                        }
                )
                Text(
                    text = "与安知聊天",
                    style = AnzhiTypography.pixelSubtitle,
                    color = AnzhiTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    style = AnzhiTypography.body,
                    color = AnzhiTextTertiary,
                    modifier = Modifier.padding(4.dp)
                )
            }

            // 连接/CDP 状态提示
            if (state.captchaRequired) {
                Text(
                    text = "⚠ Google 以为我是机器人…需要你在 Gemini 网页上点验证 🦓",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiAccentOrange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AnzhiAccentOrange.copy(alpha = 0.1f))
                        .padding(8.dp)
                )
            }

            if (!state.brainOnline) {
                Text(
                    text = "大脑已切换到备用系统，回复可能较慢",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiSystemMsg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AnzhiSystemMsg.copy(alpha = 0.1f))
                        .padding(8.dp)
                )
            }

            // 消息列表
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.messages.isEmpty()) {
                    // 空态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "给安知发条消息吧~\n长按电源键或在这里输入",
                            style = AnzhiTypography.body,
                            color = AnzhiTextTertiary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = state.messages,
                        key = { it.id }
                    ) { msg ->
                        MessageBubble(message = msg)
                    }

                    // 正在输入指示
                    if (state.status == ChatStatus.THINKING) {
                        item {
                            TypingIndicator(visible = true)
                        }
                    }
                }
            }

            // 输入框
            ChatInput(
                text = state.inputText,
                onTextChange = onTextChange,
                onSend = onSendMessage,
                locked = state.inputLocked || state.status == ChatStatus.LOCKED
            )
        }

        // 侧栏覆盖
        AnimatedVisibility(
            visible = state.sidebarOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(300)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(250)
            ),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ChatSidebar(
                sessions = state.sessions,
                currentSessionId = state.currentSessionId,
                onSwitchSession = onSwitchSession,
                onNewSession = onNewSession,
                onClose = onToggleSidebar,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

private fun currentTimeForChat(): String {
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
}
