package com.anzhi.os.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzhi.os.ui.theme.*

/**
 * 应用抽屉 — 左侧滑出的应用启动器。
 *
 * 显示智能排序的应用列表（基于使用频率和时间段）。
 * 点击应用 → onLaunchApp(packageName)。
 *
 * @param apps 应用列表
 * @param onLaunchApp 启动应用回调
 * @param onClose 关闭抽屉回调
 */
data class AppItem(
    val packageName: String,
    val label: String,
    val iconRes: Int  // 实际项目中应使用 PackageManager 获取
)

@Composable
fun AppDrawer(
    apps: List<AppItem> = emptyList(),
    onLaunchApp: (String) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 默认示例应用（实际数据由 AnzhiDrawerProvider 提供）
    val displayApps = remember(apps) {
        if (apps.isEmpty()) sampleApps else apps
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(AnzhiDrawerBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 顶部：标题 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "应用",
                    style = AnzhiTypography.pixelSubtitle,
                    color = AnzhiTextPrimary
                )
                Text(
                    text = "✕",
                    style = AnzhiTypography.body,
                    color = AnzhiTextTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onClose() }
                        .padding(4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 应用网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayApps) { app ->
                    AppIcon(
                        app = app,
                        onClick = { onLaunchApp(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    app: AppItem,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        // 应用图标（占位：用纯色方块 + 首字母）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AnzhiDrawerItem),
            contentAlignment = Alignment.Center
        ) {
            if (app.iconRes != 0) {
                Icon(
                    painter = painterResource(id = app.iconRes),
                    contentDescription = app.label,
                    tint = AnzhiTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Text(
                    text = app.label.take(1),
                    style = AnzhiTypography.pixelTitle,
                    color = AnzhiTextPrimary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.label,
            style = AnzhiTypography.bodySmall,
            color = AnzhiTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val sampleApps = listOf(
    AppItem("com.android.chrome", "Chrome", 0),
    AppItem("com.google.android.apps.messaging", "短信", 0),
    AppItem("com.google.android.dialer", "电话", 0),
    AppItem("com.google.android.gm", "Gmail", 0),
    AppItem("com.google.android.apps.maps", "地图", 0),
    AppItem("com.google.android.youtube", "YouTube", 0),
    AppItem("com.android.settings", "设置", 0),
    AppItem("com.google.android.calendar", "日历", 0),
    AppItem("com.google.android.apps.photos", "相册", 0),
    AppItem("com.google.android.apps.docs", "文档", 0),
    AppItem("com.android.vending", "商店", 0),
    AppItem("com.google.android.apps.wellbeing", "健康", 0),
    AppItem("com.android.clock", "时钟", 0),
    AppItem("com.android.calculator2", "计算器", 0),
    AppItem("com.android.contacts", "联系人", 0),
    AppItem("com.android.filemanager", "文件", 0)
)
