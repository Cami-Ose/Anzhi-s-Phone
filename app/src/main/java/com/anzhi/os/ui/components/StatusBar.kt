package com.anzhi.os.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzhi.os.R
import com.anzhi.os.ui.theme.AnzhiColors
import com.anzhi.os.ui.theme.AnzhiTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安知状态栏 — 时间 · 位置 · WiFi · 电量。
 *
 * @param timeText 时间文本（null = 自动获取系统时间）
 * @param locationText 位置文本
 * @param wifiOn WiFi 连接状态
 * @param batteryPercent 电量百分比 0-100
 * @param batteryCharging 是否正在充电
 * @param dndOn 勿扰模式状态
 * @param sidePadding 左右内边距（适配全屏/非全屏）
 */
@Composable
fun AnzhiStatusBar(
    timeText: String? = null,
    locationText: String = "",
    wifiOn: Boolean = false,
    batteryPercent: Int = 50,
    batteryCharging: Boolean = false,
    dndOn: Boolean = false,
    sidePadding: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val time = timeText ?: currentTime()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding, vertical = 6.dp)
            .statusBarsPadding(),  // 避开系统状态栏区域
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：时间 · 位置
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = time,
                style = AnzhiTypography.statusTime,
                color = AnzhiColors.wifiOn
            )
            if (locationText.isNotBlank()) {
                Text(
                    text = locationText,
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiColors.wifiOn.copy(alpha = 0.7f)
                )
            }
        }

        // 右侧：DND · WiFi · 电量
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dndOn) {
                StatusIcon(
                    iconRes = R.drawable.ic_dnd,  // 需添加
                    tint = AnzhiColors.dndOn,
                    contentDesc = "勿扰模式"
                )
            }

            StatusIcon(
                iconRes = if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off,
                tint = if (wifiOn) AnzhiColors.wifiOn else Color.Gray,
                contentDesc = if (wifiOn) "WiFi 已连接" else "WiFi 已断开"
            )

            BatteryIndicator(
                percent = batteryPercent,
                charging = batteryCharging
            )
        }
    }
}

@Composable
private fun StatusIcon(
    iconRes: Int,
    tint: Color,
    contentDesc: String
) {
    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = contentDesc,
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun BatteryIndicator(
    percent: Int,
    charging: Boolean
) {
    val tint = when {
        charging -> AnzhiColors.batteryCharging
        percent <= 15 -> AnzhiColors.batteryLow
        percent <= 30 -> AnzhiAccentOrange  // cross-module reference
        else -> AnzhiColors.batteryGood
    }
    val iconRes = when {
        charging -> R.drawable.ic_battery_charging
        percent <= 15 -> R.drawable.ic_battery_low
        percent <= 50 -> R.drawable.ic_battery_medium
        else -> R.drawable.ic_battery_full
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = "电量 $percent%",
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "$percent%",
            style = AnzhiTypography.bodySmall,
            color = tint
        )
    }
}

// fallback for cross-module color ref
private val AnzhiAccentOrange = Color(0xFFFFA726)

private fun currentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
