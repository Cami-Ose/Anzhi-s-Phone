package com.anzhi.os.ui.controlpanel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.components.*
import com.anzhi.os.ui.theme.*

/**
 * 控制面板 — 快速开关 + 亮度/音量滑条 + 音乐播放器。
 *
 * 从仪表盘底部滑入，覆盖半个屏幕。
 * 下滑手势或点击关闭按钮收起。
 *
 * @param wifiOn WiFi 状态
 * @param dndOn 勿扰模式
 * @param brightness 亮度 0-100
 * @param volume 音量 0-100
 * @param bodyConnected 身体连接状态（蓝牙等）
 * @param batteryPercent 电量
 * @param onToggleWifi WiFi 开关回调
 * @param onToggleDnd DND 开关回调
 * @param onBrightnessChange 亮度变化回调
 * @param onVolumeChange 音量变化回调
 * @param onClose 关闭面板回调
 */
@Composable
fun ControlPanel(
    wifiOn: Boolean = false,
    dndOn: Boolean = false,
    brightness: Int = 80,
    volume: Int = 60,
    bodyConnected: Boolean = false,
    batteryPercent: Int = 50,
    onToggleWifi: (Boolean) -> Unit = {},
    onToggleDnd: (Boolean) -> Unit = {},
    onBrightnessChange: (Int) -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 快捷开关列表
    val quickToggles = remember(wifiOn, dndOn) {
        listOf(
            ToggleItem("wifi", "WiFi", 0, wifiOn) {
                onToggleWifi(!wifiOn)
            },
            ToggleItem("bluetooth", "蓝牙", 0, bodyConnected) {
                // TODO: 蓝牙开关
            },
            ToggleItem("dnd", "勿扰", 0, dndOn) {
                onToggleDnd(!dndOn)
            },
            ToggleItem("flashlight", "手电", 0, false) {
                // TODO: 手电筒
            },
            ToggleItem("rotate", "旋转", 0, false) {
                // TODO: 自动旋转
            },
            ToggleItem("powersave", "省电", 0, false) {
                // TODO: 省电模式
            },
            ToggleItem("hotspot", "热点", 0, false) {
                // TODO: 热点
            },
            ToggleItem("cast", "投屏", 0, false) {
                // TODO: 投屏
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp, max = 500.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(AnzhiDrawerBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 顶部：拖拽指示条 + 标题 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拖拽指示条
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AnzhiTextTertiary)
                )
                Text(
                    text = "控制面板",
                    style = AnzhiTypography.widgetTitle,
                    color = AnzhiTextPrimary
                )
                Text(
                    text = "关闭",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiAccentCyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onClose() }
                        .padding(4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 快捷开关网格
            QuickToggleGrid(
                items = quickToggles,
                columns = 4
            )

            Spacer(Modifier.height(16.dp))

            // 亮度滑条
            SliderRow(
                label = "亮度",
                value = brightness,
                iconLeft = "☀",  // 简化：实际应使用 Icon
                onValueChange = onBrightnessChange
            )

            Spacer(Modifier.height(8.dp))

            // 音量滑条
            SliderRow(
                label = "音量",
                value = volume,
                iconLeft = "♪",
                onValueChange = onVolumeChange
            )

            Spacer(Modifier.height(16.dp))

            // 音乐播放器
            MusicPlayer(
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // 电池信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "电量 $batteryPercent%",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
                if (bodyConnected) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "身体已连接",
                        style = AnzhiTypography.bodySmall,
                        color = AnzhiAccentGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    iconLeft: String,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Text(
            text = iconLeft,
            style = AnzhiTypography.body,
            color = AnzhiTextSecondary,
            modifier = Modifier.width(28.dp)
        )
        Spacer(Modifier.width(4.dp))

        // 竖向滑条（横着放，实际为水平拖动）
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            // 水平滑块改用简化实现：用 Canvas 的 Slider 替代
            HorizontalSlider(
                value = value,
                onValueChange = onValueChange
            )
        }

        Spacer(Modifier.width(8.dp))

        // 数值
        Text(
            text = "$value",
            style = AnzhiTypography.bodySmall,
            color = AnzhiTextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp)
        )
    }
}

/**
 * 简化水平滑块 — 用于控制面板亮度/音量调节。
 */
@Composable
private fun HorizontalSlider(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .background(AnzhiSliderTrack)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value / 100f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AnzhiSliderProgress)
            )
        }
    }
}

// AnzhiDrawerBg 和 AnzhiAccentGreen 已通过 com.anzhi.os.ui.theme.* 导入
