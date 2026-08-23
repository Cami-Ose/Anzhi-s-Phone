package com.anzhi.os.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.AnzhiColors
import com.anzhi.os.ui.theme.AnzhiSurfaceHigh

/**
 * 四角锚点卡片容器 — 安知仪表盘组件通用容器。
 *
 * 在卡片四角绘制短的 L 形装饰线（锚点），
 * 中间区域用半透明背景。
 *
 * @param anchorSize 锚点边长
 * @param anchorStrokeWidth 锚点线宽
 * @param anchorColor 锚点颜色
 * @param cornerRadius 圆角半径
 * @param contentPadding 内容内边距
 */
@Composable
fun CornerCard(
    modifier: Modifier = Modifier,
    anchorSize: Dp = 16.dp,
    anchorStrokeWidth: Dp = 1.5.dp,
    anchorColor: Color = AnzhiColors.wifiOn.copy(alpha = 0.3f),
    cornerRadius: Dp = 0.dp,
    contentPadding: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        // 四角锚点
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val a = anchorSize.toPx()
            val sw = anchorStrokeWidth.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)

            // 左上
            drawLine(anchorColor, Offset(0f, a), Offset(0f, 0f), sw, pathEffect = dash)
            drawLine(anchorColor, Offset(0f, 0f), Offset(a, 0f), sw, pathEffect = dash)
            // 右上
            drawLine(anchorColor, Offset(w - a, 0f), Offset(w, 0f), sw, pathEffect = dash)
            drawLine(anchorColor, Offset(w, 0f), Offset(w, a), sw, pathEffect = dash)
            // 左下
            drawLine(anchorColor, Offset(0f, h - a), Offset(0f, h), sw, pathEffect = dash)
            drawLine(anchorColor, Offset(0f, h), Offset(a, h), sw, pathEffect = dash)
            // 右下
            drawLine(anchorColor, Offset(w - a, h), Offset(w, h), sw, pathEffect = dash)
            drawLine(anchorColor, Offset(w, h - a), Offset(w, h), sw, pathEffect = dash)
        }

        // 内容区域（半透明背景）
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(anchorSize / 2)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRect(
                    color = AnzhiSurfaceHigh,
                    topLeft = Offset.Zero,
                    size = size
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(contentPadding)
            ) {
                content()
            }
        }
    }
}
