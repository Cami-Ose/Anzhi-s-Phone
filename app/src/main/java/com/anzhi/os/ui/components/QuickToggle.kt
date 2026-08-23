package com.anzhi.os.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.AnzhiColors
import com.anzhi.os.ui.theme.AnzhiTextSecondary
import com.anzhi.os.ui.theme.AnzhiTypography

/**
 * 快捷开关图标网格 — 用于控制面板。
 *
 * @param items 开关列表
 * @param columns 列数
 * @param itemSize 每个开关的尺寸
 */
data class ToggleItem(
    val id: String,
    val label: String,
    val iconRes: Int,
    val isActive: Boolean,
    val onToggle: () -> Unit
)

@Composable
fun QuickToggleGrid(
    items: List<ToggleItem>,
    columns: Int = 4,
    modifier: Modifier = Modifier,
    itemSize: Dp = 64.dp
) {
    val rows = (items.size + columns - 1) / columns

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0 until rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    if (index < items.size) {
                        ToggleButton(
                            item = items[index],
                            size = itemSize
                        )
                    } else {
                        Spacer(modifier = Modifier.size(itemSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleButton(
    item: ToggleItem,
    size: Dp
) {
    val bgColor = if (item.isActive)
        AnzhiColors.toggleTrackOn.copy(alpha = 0.2f)
    else
        AnzhiSurfaceVariant

    val iconTint = if (item.isActive)
        AnzhiColors.toggleTrackOn
    else
        AnzhiTextSecondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { item.onToggle() },
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = item.iconRes),
            contentDescription = item.label,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = AnzhiTypography.widgetTitle,
            textAlign = TextAlign.Center,
            color = iconTint
        )
    }
}

private val AnzhiSurfaceVariant = Color(0x0DFFFFFF).copy(alpha = 0.05f)
