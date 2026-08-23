package com.anzhi.os.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.AnzhiColors

/**
 * 安知拨动开关 — 类似 iOS 风格，用于 WiFi/DND 等开关。
 *
 * @param checked 当前状态
 * @param onCheckedChange 状态变化回调
 * @param size 开关尺寸（宽度），高度 = size * 0.55
 */
@Composable
fun FusionSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val width = size
    val height = size * 0.55f
    val thumbRadius = height * 0.35f
    val trackThickness = height * 0.35f
    val thumbOffset = if (checked) width - height + thumbRadius else thumbRadius

    val trackColor by animateColorAsState(
        targetValue = if (checked) AnzhiColors.toggleTrackOn else AnzhiColors.toggleTrackOff,
        animationSpec = tween(200),
        label = "track"
    )

    val thumbAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0.7f,
        animationSpec = tween(200),
        label = "thumb"
    )

    Canvas(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(height / 2))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
    ) {
        // 轨道
        drawRoundRect(
            color = trackColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(height.toPx() / 2)
        )

        // 滑块
        drawCircle(
            color = AnzhiColors.toggleThumb.copy(alpha = thumbAlpha),
            radius = thumbRadius.toPx(),
            center = androidx.compose.ui.geometry.Offset(
                thumbOffset.toPx(),
                this.size.height / 2
            )
        )

        // 滑块微阴影
        drawCircle(
            color = Color.Black.copy(alpha = 0.15f),
            radius = thumbRadius.toPx() + 1f,
            center = androidx.compose.ui.geometry.Offset(
                thumbOffset.toPx(),
                this.size.height / 2 + 0.5f
            )
        )
    }
}
