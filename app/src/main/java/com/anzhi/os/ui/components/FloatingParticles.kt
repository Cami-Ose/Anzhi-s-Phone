package com.anzhi.os.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 锁屏粒子动画 — 浮动光点缓慢漂移。
 *
 * 使用 Compose Animation 的 InfiniteTransition 驱动粒子位置循环。
 * 约 30 个粒子以不同速度和轨迹飘动，营造梦幻锁屏效果。
 *
 * @param particleCount 粒子数量
 * @param particleColor 粒子颜色
 * @param particleRadius 粒子半径范围 (min..max)
 * @param speed 粒子速度倍数
 */
@Composable
fun FloatingParticles(
    modifier: Modifier = Modifier,
    particleCount: Int = 30,
    particleColor: Color = AnzhiParticle,
    particleRadius: ClosedFloatingPointRange<Float> = 1.5f..3.5f,
    speed: Float = 1f
) {
    // 粒子初始状态：位置 (存储为 Float 索引 offsets)，相位 (用于 sin 漂移)
    val particles = remember {
        List(particleCount) {
            ParticleState(
                startX = Random.nextFloat(),
                startY = Random.nextFloat(),
                driftPhaseX = Random.nextFloat() * 360f,
                driftPhaseY = Random.nextFloat() * 360f,
                driftSpeed = (0.3f + Random.nextFloat() * 0.7f) * speed,
                radius = particleRadius.start + Random.nextFloat() * (particleRadius.endInclusive - particleRadius.start),
                alpha = 0.3f + Random.nextFloat() * 0.5f
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "particles")

    // 全局时间相位，所有粒子共享
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleTime"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        for (p in particles) {
            // 粒子在主路径上慢速漂移
            val dx = sin(Math.toRadians((time * p.driftSpeed + p.driftPhaseX).toDouble())).toFloat()
            val dy = cos(Math.toRadians((time * p.driftSpeed * 0.7f + p.driftPhaseY).toDouble())).toFloat()

            val x = (p.startX * w + dx * w * 0.08f).coerceIn(0f, w)
            val y = (p.startY * h + dy * h * 0.06f).coerceIn(0f, h)

            // 透明度随位置微微波动
            val alpha = (p.alpha * (0.7f + 0.3f * sin(
                Math.toRadians((time * p.driftSpeed * 0.5f).toDouble())
            ).toFloat())).coerceIn(0f, 1f)

            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = p.radius,
                center = Offset(x, y)
            )
        }
    }
}

private data class ParticleState(
    val startX: Float,
    val startY: Float,
    val driftPhaseX: Float,
    val driftPhaseY: Float,
    val driftSpeed: Float,
    val radius: Float,
    val alpha: Float
)

// 避免循环引用
private val AnzhiParticle = Color(0x4DFFFFFF)
