package com.anzhi.os.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.anzhi.os.R

/**
 * 安知手机 字体方案。
 *
 * 主字体: fusion-pixel.otf（像素风格）— 用于时间、标题
 * 回退字体: system default — 用于正文、小字
 */

// 懒加载像素字体（需将 fusion-pixel.otf 放入 res/font/）
// 若资源不存在，回退到系统默认等宽字体
private var _pixelFontFamily: FontFamily? = null

/** 获取像素字体族。首次调用时从资源加载。 */
@Composable
fun pixelFontFamily(): FontFamily {
    if (_pixelFontFamily == null) {
        _pixelFontFamily = try {
            FontFamily(Font(R.font.fusion_pixel, FontWeight.Normal))
        } catch (_: Exception) {
            FontFamily.Monospace
        }
    }
    return _pixelFontFamily!!
}

/** 安知手机 排版系统 */
object AnzhiTypography {

    /** 超大像素时间（锁屏）— fusion-pixel 72sp */
    val lockTime: TextStyle @Composable get() = TextStyle(
        color = AnzhiTextPrimary,
        fontFamily = pixelFontFamily(),
        fontSize = 72.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 4.sp,
    )

    /** 像素标题 — 24sp */
    val pixelTitle: TextStyle @Composable get() = TextStyle(
        color = AnzhiTextPrimary,
        fontFamily = pixelFontFamily(),
        fontSize = 24.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 像素副标题 — 16sp */
    val pixelSubtitle: TextStyle @Composable get() = TextStyle(
        color = AnzhiTextSecondary,
        fontFamily = pixelFontFamily(),
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 状态栏时间 — 14sp */
    val statusTime: TextStyle @Composable get() = TextStyle(
        color = AnzhiTextPrimary,
        fontFamily = pixelFontFamily(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 正文 — system default 16sp */
    val body: TextStyle = TextStyle(
        color = AnzhiTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    )

    /** 小字正文 — 13sp */
    val bodySmall: TextStyle = TextStyle(
        color = AnzhiTextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    )

    /** 按钮标签 — 14sp medium */
    val label: TextStyle = TextStyle(
        color = AnzhiTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** 聊天用户名标签 — 12sp */
    val chatLabel: TextStyle @Composable get() = TextStyle(
        color = AnzhiTextTertiary,
        fontFamily = pixelFontFamily(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 聊天正文 — 15sp */
    val chatBody: TextStyle = TextStyle(
        color = AnzhiTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
    )

    /** 仪表盘 Widget 标题 — 13sp */
    val widgetTitle: TextStyle = TextStyle(
        color = AnzhiTextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    )

    /** 临时卡标题 — 15sp medium */
    val tempCardTitle: TextStyle = TextStyle(
        color = AnzhiTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}
