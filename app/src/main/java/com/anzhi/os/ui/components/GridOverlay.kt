package com.anzhi.os.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import com.anzhi.os.ui.theme.AnzhiColors

/**
 * 网格底纹覆盖层 — 安知手机 标志性视觉元素。
 *
 * 在 Canvas 上绘制纵横网格线 + 交叉点圆点。
 * 通常作为内容底部的 decoration 层。
 *
 * @param gridSpacing 网格间距（dp），默认 48dp
 * @param lineAlpha 网格线透明度
 * @param dotRadius 交叉点圆点半径
 */
@Composable
fun GridOverlay(
    modifier: Modifier = Modifier,
    gridSpacingPx: Float = 48f,
    lineAlpha: Float = 0.08f,
    dotRadius: Float = 1.5f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 竖线
        var x = gridSpacingPx
        while (x < w) {
            drawLine(
                color = AnzhiColors.gridLine,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
            )
            x += gridSpacingPx
        }

        // 横线
        var y = gridSpacingPx
        while (y < h) {
            drawLine(
                color = AnzhiColors.gridLine,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
            )
            y += gridSpacingPx
        }

        // 交叉点圆点
        x = gridSpacingPx
        while (x < w) {
            y = gridSpacingPx
            while (y < h) {
                drawCircle(
                    color = AnzhiColors.gridDot,
                    radius = dotRadius,
                    center = Offset(x, y)
                )
                y += gridSpacingPx
            }
            x += gridSpacingPx
        }
    }
}
