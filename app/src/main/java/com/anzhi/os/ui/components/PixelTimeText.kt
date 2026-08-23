package com.anzhi.os.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.anzhi.os.ui.theme.AnzhiTypography
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * 像素字体时间 — 大号锁屏时间显示，每秒刷新。
 * 数字变化时带淡入淡出动画。
 *
 * @param timeText 时间文本（null = 自动获取）
 * @param showSeconds 是否显示秒数
 */
@Composable
fun PixelTimeText(
    timeText: String? = null,
    showSeconds: Boolean = false,
    modifier: Modifier = Modifier
) {
    var displayText by remember { mutableStateOf(timeText ?: currentTime(showSeconds)) }

    // 每秒刷新时间
    if (timeText == null) {
        LaunchedEffect(showSeconds) {
            while (true) {
                delay(1000L)
                displayText = currentTime(showSeconds)
            }
        }
    }

    AnimatedContent(
        targetState = displayText,
        transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
        },
        label = "time",
        modifier = modifier
    ) { time ->
        Text(
            text = time,
            style = AnzhiTypography.lockTime,
            textAlign = TextAlign.Center
        )
    }
}

private fun currentTime(showSeconds: Boolean): String {
    val fmt = if (showSeconds) "HH:mm:ss" else "HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
}
