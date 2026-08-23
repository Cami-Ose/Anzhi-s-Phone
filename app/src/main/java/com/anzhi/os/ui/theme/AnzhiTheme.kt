package com.anzhi.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 安知手机 Material3 主题。
 *
 * 深色基调 + 紫罗兰主色（#8B5CF6）。
 * 使用 darkColorScheme 作为基座，覆盖所有 token 以匹配安知视觉语言。
 */

private val AnzhiColorScheme = darkColorScheme(
    primary = AnzhiPrimary,
    onPrimary = AnzhiOnPrimary,
    primaryContainer = AnzhiPrimaryContainer,
    secondary = AnzhiAccentBlue,
    tertiary = AnzhiAccentPurple,
    background = AnzhiBackground,
    surface = AnzhiSurface,
    surfaceVariant = AnzhiSurfaceVariant,
    onBackground = AnzhiTextPrimary,
    onSurface = AnzhiOnSurface,
    onSurfaceVariant = AnzhiOnSurfaceVariant,
    outline = AnzhiInputBorder,
    error = AnzhiAccentRed,
    onError = AnzhiTextPrimary,
    scrim = AnzhiScrim
)

@Composable
fun AnzhiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AnzhiColorScheme,
        content = content
    )
}

/** 便捷访问安知自定义色的扩展 */
object AnzhiColors {
    val wifiOn get() = AnzhiWifiOn
    val batteryGood get() = AnzhiBatteryGood
    val batteryLow get() = AnzhiBatteryLow
    val batteryCharging get() = AnzhiBatteryCharging
    val dndOn get() = AnzhiDndOn
    val gridLine get() = AnzhiGridLine
    val gridDot get() = AnzhiGridDot
    val particle get() = AnzhiParticle
    val unlockHint get() = AnzhiUnlockHint
    val userBubble get() = AnzhiUserBubble
    val anzhiBubble get() = AnzhiAnzhiBubble
    val systemMsg get() = AnzhiSystemMsg
    val inputBg get() = AnzhiInputBg
    val toggleTrackOn get() = AnzhiToggleTrackOn
    val toggleTrackOff get() = AnzhiToggleTrackOff
    val toggleThumb get() = AnzhiToggleThumb
    val sliderTrack get() = AnzhiSliderTrack
    val sliderProgress get() = AnzhiSliderProgress
    val drawerBg get() = AnzhiDrawerBg
    val drawerItem get() = AnzhiDrawerItem
}
