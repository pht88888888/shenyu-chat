package com.shenyu.chat.ui.chatInput

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.shenyu.chat.ui.theme.DsTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * 录音中的实时频谱（对齐官方 Spectrum.tsx）：一排竖条，从低频到高频。
 *
 * 本项目没有真实 FFT 振幅，改用合成动画：单一时钟驱动每根条以不同相位/频率在
 * min..max 高度间循环。`recording == false` 时通过 [animateFloatAsState] 把整体增益
 * 平滑回落到 0，所有条归位到最小高度。
 *
 * 设计稿：条宽 2px、间距 3px、静止 5px、最高 36px，最多 60 根。
 */
private val BarWidth = 2.dp
private val BarGap = 3.dp
private val BarMin = 5.dp
private val BarMax = 36.dp
private const val MaxBars = 60

@Composable
fun Spectrum(
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = DsTheme.colors.brandPrimary
    val transition = rememberInfiniteTransition(label = "spectrum")
    // 0..2π 循环，作为合成波形的「时间轴」
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "clock",
    )
    // 录音时增益升到 1，停止录音时平滑回落到 0（条高归位最小）。
    val gain by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "gain",
    )

    BoxWithConstraints(
        modifier = modifier.height(BarMax),
        contentAlignment = Alignment.Center,
    ) {
        val count = ((maxWidth + BarGap) / (BarWidth + BarGap))
            .toInt()
            .coerceIn(1, MaxBars)
        Row(
            horizontalArrangement = Arrangement.spacedBy(BarGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { index ->
                // 每根条用不同频率 + 相位，避免整排同步
                val speed = 0.8f + (index % 5) * 0.36f
                val phase = index * 0.7f
                val wave = 0.5f + 0.5f * sin(clock * speed + phase)
                val fraction = wave * gain
                val height = BarMin + (BarMax - BarMin) * fraction
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(BarWidth)
                        .height(height)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color),
                )
            }
        }
    }
}
