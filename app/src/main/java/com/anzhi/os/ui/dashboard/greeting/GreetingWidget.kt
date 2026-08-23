package com.anzhi.os.ui.dashboard.greeting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.components.CornerCard
import com.anzhi.os.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 问候 Widget — 显示时间·日期·安知问候语。
 *
 * @param time 时间字符串（如 "14:30"）
 * @param dayOfWeek 星期几（如 "周二"）
 * @param date 日期字符串（如 "2026年7月3日"）
 * @param overrideText 安知覆写的问候文字（非 null 时替代默认）
 */
@Composable
fun GreetingWidget(
    time: String = "",
    dayOfWeek: String = "",
    date: String = "",
    overrideText: String? = null
) {
    val defaultGreeting = if (overrideText != null) overrideText else getDefaultGreeting()

    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = defaultGreeting,
                style = AnzhiTypography.pixelSubtitle,
                color = AnzhiAccentCyan
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = time.ifBlank { currentTime() },
                style = AnzhiTypography.pixelTitle,
                color = AnzhiTextPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(dayOfWeek.ifBlank { currentDayOfWeek() })
                    append(" · ")
                    append(date.ifBlank { currentDate() })
                },
                style = AnzhiTypography.bodySmall,
                color = AnzhiTextTertiary
            )
        }
    }
}

private fun getDefaultGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour in 6..11 -> "早安 Cami 🌤"
        hour in 12..17 -> "下午好 Cami ☀"
        hour in 18..23 -> "晚上好 Cami 🌙"
        else -> "还不睡呀 Cami 🌜"
    }
}

private fun currentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun currentDayOfWeek(): String {
    val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    return days[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
}

private fun currentDate(): String {
    val cal = Calendar.getInstance()
    return "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
}
