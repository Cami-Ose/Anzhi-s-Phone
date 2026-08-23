package com.anzhi.os.ui.dashboard.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.dashboard.CalendarEvent
import com.anzhi.os.ui.components.CornerCard
import com.anzhi.os.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日历 Widget — 显示即将到来的日程。
 *
 * @param events 日程列表
 */
@Composable
fun CalendarWidget(
    events: List<CalendarEvent> = emptyList()
) {
    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Column {
            Text(
                text = "日历",
                style = AnzhiTypography.widgetTitle,
                color = AnzhiTextTertiary
            )
            Spacer(Modifier.height(4.dp))

            if (events.isEmpty()) {
                Text(
                    text = "今天没有日程",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
            } else {
                events.take(4).forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = formatEventTime(event),
                            style = AnzhiTypography.bodySmall,
                            color = AnzhiAccentCyan,
                            modifier = Modifier.width(48.dp)
                        )
                        Text(
                            text = event.title,
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

private fun formatEventTime(event: CalendarEvent): String {
    if (event.allDay) return "全天"
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(event.startTime))
}
