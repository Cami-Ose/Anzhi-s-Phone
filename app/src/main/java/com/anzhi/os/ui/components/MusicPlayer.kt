package com.anzhi.os.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.R
import com.anzhi.os.ui.theme.*

/**
 * 音乐控制器 — 控制面板内置的紧凑型音乐播放控件。
 *
 * @param title 当前歌曲标题
 * @param artist 艺术家
 * @param isPlaying 是否正在播放
 * @param progress 播放进度 0f..1f
 * @param albumArtRes 专辑封面资源 ID（可选）
 * @param onPlayPause 播放/暂停
 * @param onNext 下一首
 * @param onPrev 上一首
 */
@Composable
fun MusicPlayer(
    title: String = "",
    artist: String = "",
    isPlaying: Boolean = false,
    progress: Float = 0f,
    albumArtRes: Int? = null,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AnzhiSurfaceHigh, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .animateContentSize()
    ) {
        // 标题
        Text(
            text = if (title.isNotBlank()) "♪ $title" else "未在播放",
            style = AnzhiTypography.tempCardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = AnzhiTextPrimary
        )
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                style = AnzhiTypography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = AnzhiTextTertiary
            )
        }

        Spacer(Modifier.height(8.dp))

        // 进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(AnzhiSliderTrack)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(AnzhiAccentCyan)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 上一首
            ControlButton(
                iconRes = R.drawable.ic_skip_previous,
                onClick = onPrev
            )
            // 播放/暂停
            ControlButton(
                iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                onClick = onPlayPause,
                size = 44.dp,
                isPrimary = true
            )
            // 下一首
            ControlButton(
                iconRes = R.drawable.ic_skip_next,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun ControlButton(
    iconRes: Int,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    isPrimary: Boolean = false
) {
    val bg = if (isPrimary) AnzhiPrimary.copy(alpha = 0.3f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = AnzhiTextPrimary,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
