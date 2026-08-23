package com.anzhi.os.ui.dashboard.weather

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.dashboard.WeatherData
import com.anzhi.os.ui.components.CornerCard
import com.anzhi.os.ui.theme.*

/**
 * 天气 Widget — 显示当前天气状况。
 *
 * @param weather 天气数据（从 Pixel 原生 API 获取后 push）
 */
@Composable
fun WeatherWidget(
    weather: WeatherData = WeatherData()
) {
    CornerCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "天气",
                style = AnzhiTypography.widgetTitle,
                color = AnzhiTextTertiary
            )
            Spacer(Modifier.height(4.dp))

            if (weather.available) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${weather.temp}°",
                        style = AnzhiTypography.pixelTitle,
                        color = AnzhiTextPrimary
                    )
                    Text(
                        text = weather.condition,
                        style = AnzhiTypography.body,
                        color = AnzhiTextSecondary
                    )
                    Text(
                        text = "H:${weather.high}° L:${weather.low}°",
                        style = AnzhiTypography.bodySmall,
                        color = AnzhiTextTertiary
                    )
                }
            } else {
                Text(
                    text = "天气数据暂不可用",
                    style = AnzhiTypography.bodySmall,
                    color = AnzhiTextTertiary
                )
            }
        }
    }
}
