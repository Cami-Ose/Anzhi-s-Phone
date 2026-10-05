package com.anzhi.os.ui.lockscreen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anzhi.os.ui.components.AnzhiStatusBar
import com.anzhi.os.ui.components.FloatingParticles
import com.anzhi.os.ui.components.PixelTimeText
import com.anzhi.os.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 安知锁屏全屏 — 像素时间 + 粒子动画 + 上滑解锁。
 *
 * 解锁手势：检测到足够幅度的上滑后调用 onUnlock。
 * 锁定文字区域：底部显示安知留下的持久/瞬态信息。
 *
 * @param persistentMessage 安知留下的持久锁屏文字
 * @param transientMessage 瞬态提示文字（3.5秒后消失）
 * @param batteryPercent 电量
 * @param batteryCharging 充电状态
 * @param wifiOn WiFi 状态
 * @param dndOn 勿扰模式
 * @param locationText 位置文字
 * @param onUnlock 上滑解锁回调
 */
@Composable
fun LockScreen(
    persistentMessage: String? = null,
    transientMessage: String? = null,
    batteryPercent: Int = 50,
    batteryCharging: Boolean = false,
    wifiOn: Boolean = false,
    dndOn: Boolean = false,
    locationText: String = "",
    onUnlock: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 上滑解锁手势检测
    var dragDistance by remember { mutableStateOf(0f) }
    val unlockThreshold = 300f

    // 日期文本
    val dateText = remember {
        SimpleDateFormat("MM月dd日 · EEEE", Locale.CHINESE).format(Date())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 背景网格
        com.anzhi.os.ui.components.GridOverlay(
            modifier = Modifier.fillMaxSize(),
            lineAlpha = 0.04f,
            gridSpacingPx = 48f
        )

        // 粒子动画
        FloatingParticles(
            modifier = Modifier.fillMaxSize(),
            particleCount = 25,
            speed = 0.8f
        )

        // 主内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // 状态栏
            AnzhiStatusBar(
                wifiOn = wifiOn,
                batteryPercent = batteryPercent,
                batteryCharging = batteryCharging,
                dndOn = dndOn,
                locationText = locationText,
                sidePadding = 0.dp
            )

            Spacer(Modifier.weight(1.2f))

            // 像素时间
            PixelTimeText(
                showSeconds = false,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            // 日期
            Text(
                text = dateText,
                style = AnzhiTypography.pixelSubtitle,
                textAlign = TextAlign.Center,
                color = AnzhiTextSecondary
            )

            Spacer(Modifier.height(20.dp))

            // 安知标识横带：开机第一眼就知道这是谁的机子
            EnzoBrandBand()

            Spacer(Modifier.weight(0.8f))

            // 锁屏信息区域
            LockScreenMessage(
                persistentMessage = persistentMessage,
                transientMessage = transientMessage
            )

            Spacer(Modifier.height(60.dp))

            // 上滑提示
            val unlockAlpha = if (dragDistance > 0)
                ((unlockThreshold - dragDistance) / unlockThreshold).coerceIn(0f, 1f)
            else 0.6f

            Text(
                text = if (dragDistance > unlockThreshold * 0.5f) "松开解锁" else "上滑解锁",
                style = AnzhiTypography.bodySmall,
                color = AnzhiUnlockHint.copy(alpha = unlockAlpha),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
        }

        // 上滑手势层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            if (dragDistance > unlockThreshold) {
                                onUnlock()
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.y < 0) {  // 上滑
                                dragDistance -= dragAmount.y
                            }
                        }
                    )
                }
        )
    }
}

/**
 * 安知标识横带 — 一根粉色定位柱 + 像素字 enzosphere + 一行欢迎语。
 * 故意左对齐：整屏其余元素都是居中，这一块的偏是"这是我们的机器"的记号。
 */
@Composable
private fun EnzoBrandBand() {
    val brandPink = Color(0xFFFF6FA3)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(54.dp)
                .background(brandPink)
        )
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "enzosphere",
                style = AnzhiTypography.pixelTitle.copy(fontSize = 36.sp, letterSpacing = 3.sp),
                color = AnzhiTextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "welcome home · 欢迎回家",
                style = AnzhiTypography.pixelSubtitle.copy(fontSize = 14.sp, letterSpacing = 4.sp),
                color = brandPink
            )
        }
    }
}

@Composable
private fun LockScreenMessage(
    persistentMessage: String?,
    transientMessage: String?
) {
    // 瞬态消息优先显示（3.5秒后消失）
    var showTransient by remember(transientMessage) {
        mutableStateOf(transientMessage != null)
    }

    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            showTransient = true
            delay(3500L)
            showTransient = false
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        // 瞬态消息
        AnimatedVisibility(
            visible = showTransient && transientMessage != null,
            enter = fadeIn(tween(200)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(300))
        ) {
            Text(
                text = transientMessage ?: "",
                style = AnzhiTypography.body,
                color = AnzhiAccentCyan,
                textAlign = TextAlign.Center
            )
        }

        // 持久消息
        AnimatedVisibility(
            visible = !showTransient && persistentMessage != null,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            persistentMessage?.let { msg ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "安知说：",
                        style = AnzhiTypography.chatLabel,
                        color = AnzhiTextTertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = msg,
                        style = AnzhiTypography.body,
                        color = AnzhiTextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
