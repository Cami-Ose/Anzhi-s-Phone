package com.anzhi.os.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.components.*
import com.anzhi.os.ui.controlpanel.ControlPanel
import com.anzhi.os.ui.dashboard.calendar.CalendarWidget
import com.anzhi.os.ui.dashboard.greeting.GreetingWidget
import com.anzhi.os.ui.dashboard.messages.MessagesWidget
import com.anzhi.os.ui.dashboard.todos.TodosWidget
import com.anzhi.os.ui.dashboard.weather.WeatherWidget
import com.anzhi.os.ui.drawer.AppDrawer
import com.anzhi.os.ui.theme.*

/**
 * 仪表盘宿主屏幕 — 组件网格 + 面板管理。
 *
 * 面板切换（ControlPanel / AppDrawer / ChatScreen）通过
 * AnimatedVisibility + offset 实现侧滑覆盖，不引入 NavHost。
 *
 * @param state 当前仪表盘状态
 * @param onTogglePanel 切换面板
 * @param onDismissTempCard 关闭临时卡
 * @param onWidgetReorder 拖拽调整组件顺序（未实现手势，预留接口）
 * @param onLaunchApp 从抽屉启动 App
 * @param onCloseDashboard 下滑关闭仪表盘
 * @param onBrightnessChange 亮度变化
 * @param onVolumeChange 音量变化
 * @param onToggleWifi WiFi 开关
 * @param onToggleDnd DND 开关
 * @param onToggleChat 聊天面板切换
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    onTogglePanel: (ActivePanel) -> Unit = {},
    onDismissTempCard: () -> Unit = {},
    onWidgetReorder: (List<String>) -> Unit = {},
    onLaunchApp: (String) -> Unit = {},
    onCloseDashboard: () -> Unit = {},
    onBrightnessChange: (Int) -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onToggleWifi: (Boolean) -> Unit = {},
    onToggleDnd: (Boolean) -> Unit = {},
    onToggleChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 背景网格
        GridOverlay(
            modifier = Modifier.fillMaxSize(),
            lineAlpha = 0.03f,
            gridSpacingPx = 48f
        )

        // 主内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 下滑手势由外部 GestureDetector 处理
                    // 这里仅传递触摸事件
                }
        ) {
            // 状态栏
            AnzhiStatusBar(
                wifiOn = state.wifiOn,
                dndOn = state.dndOn,
                batteryPercent = state.batteryPercent,
                batteryCharging = state.batteryCharging,
                locationText = state.location,
                sidePadding = 16.dp
            )

            // 临时卡片（顶部滑入）
            TempOverlay(
                data = state.tempCard,
                onDismiss = onDismissTempCard
            )

            // 可滚动组件区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 按 widgetOrder 渲染组件
                for (widgetId in state.widgetOrder) {
                    when (widgetId) {
                        "greeting" -> GreetingWidget(
                            time = state.time,
                            dayOfWeek = state.dayOfWeek,
                            date = state.date,
                            overrideText = state.greetingOverride
                        )
                        "weather" -> WeatherWidget(
                            weather = state.weather
                        )
                        "messages" -> MessagesWidget(
                            messages = state.messages
                        )
                        "calendar" -> CalendarWidget(
                            events = state.calendar
                        )
                        "todos" -> TodosWidget(
                            todos = state.todos
                        )
                        "tempcard" -> TempCardWidget(
                            cards = state.tempCards,
                            onDismiss = onDismissTempCard
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))  // 底部留空间给面板
            }
        }

        // ── 面板覆盖层 ──

        // ControlPanel：从底部滑入（上滑开/下滑关）
        AnimatedVisibility(
            visible = state.activePanel == ActivePanel.CONTROL,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(250)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ControlPanel(
                wifiOn = state.wifiOn,
                dndOn = state.dndOn,
                brightness = state.brightness,
                volume = state.volume,
                bodyConnected = state.bodyConnected,
                batteryPercent = state.batteryPercent,
                onToggleWifi = onToggleWifi,
                onToggleDnd = onToggleDnd,
                onBrightnessChange = onBrightnessChange,
                onVolumeChange = onVolumeChange,
                onClose = { onTogglePanel(ActivePanel.NONE) }
            )
        }

        // AppDrawer：从左滑入
        AnimatedVisibility(
            visible = state.activePanel == ActivePanel.DRAWER,
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
            AppDrawer(
                onLaunchApp = onLaunchApp,
                onClose = { onTogglePanel(ActivePanel.NONE) }
            )
        }
    }
}

/**
 * 临时卡片组件（仪表盘内迷你版）
 */
@Composable
private fun TempCardWidget(
    cards: List<TempCardData>,
    onDismiss: () -> Unit
) {
    if (cards.isEmpty()) return

    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Column {
            Text(
                text = "临时消息",
                style = AnzhiTypography.widgetTitle,
                color = AnzhiTextTertiary
            )
            Spacer(Modifier.height(4.dp))
            cards.take(3).forEach { card ->
                Text(
                    text = card.title + "：${card.text}",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextSecondary,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
