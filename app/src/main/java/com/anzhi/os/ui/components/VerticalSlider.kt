package com.anzhi.os.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.AnzhiColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 竖向滑条 — 用于亮度/音量调节。
 *
 * @param value 当前值 (0..max)
 * @param onValueChange 值变化回调
 * @param max 最大值
 * @param trackWidth 滑条轨道宽度
 * @param activeColor 已填充区域颜色
 * @param inactiveColor 未填充区域颜色
 */
@Composable
fun VerticalSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    max: Int = 100,
    modifier: Modifier = Modifier,
    trackWidth: Dp = 4.dp,
    activeColor: Color = AnzhiColors.sliderProgress,
    inactiveColor: Color = AnzhiColors.sliderTrack
) {
    var currentValue by remember(value) { mutableStateOf(value) }

    Box(
        modifier = modifier
            .width(trackWidth + 20.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = 1f - (offset.y / size.height)
                    currentValue = (fraction * max).roundToInt()
                        .coerceIn(0, max)
                    onValueChange(currentValue)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val fraction = 1f - (change.position.y / size.height)
                    currentValue = (fraction * max).roundToInt()
                        .coerceIn(0, max)
                    onValueChange(currentValue)
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val trackW = trackWidth.toPx()
            val left = (w - trackW) / 2f

            // 背景轨道
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(trackW, h),
                cornerRadius = CornerRadius(trackW / 2)
            )

            // 已填充区域
            val fillH = (currentValue.toFloat() / max) * h
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(left, h - fillH),
                size = androidx.compose.ui.geometry.Size(trackW, fillH),
                cornerRadius = CornerRadius(trackW / 2)
            )

            // 滑块手柄
            val thumbY = h - fillH
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(w / 2, thumbY)
            )
            drawCircle(
                color = activeColor,
                radius = 4.dp.toPx(),
                center = Offset(w / 2, thumbY)
            )
        }
    }
}
