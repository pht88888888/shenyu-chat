package com.shenyu.chat.ui.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shenyu.chat.ui.theme.DsTheme
import kotlin.math.abs

/**
 * 朗读按钮里的跳动波形（对照 ReadingBars.tsx / .module.css）。
 *
 * 官方是 16×16 视口里 4 根宽 2、间距 2 的圆角竖条（总宽 14 居中），
 * 朗读中在高度 4~14 之间来回，相邻条相位滞后 0.25 周期（0.4s），波从左往右传；
 * 竖条颜色用品牌色。这里用一支归一化「时钟」+ 每根不同相位重算高度来复刻，
 * 相当于把官方每根独立 SMIL 的 begin 错峰折叠到同一动画里。
 */
@Composable
fun ReadingBars(
    modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.brandPrimary,
) {
    val transition = rememberInfiniteTransition(label = "readingBars")
    // 一个完整周期 1.6s，对应官方 dur="1.6s"
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "readingBarsClock",
    )
    Row(
        modifier = modifier.size(width = 16.dp, height = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until BAR_COUNT) {
            // 相邻条滞后 0.25 周期：0s / -0.4s / -0.8s / -1.2s（对 1.6s 取模）
            val phase = (clock + index * 0.25f) % 1f
            // 三角波：phase=0 与 1 处最低(4)，phase=0.5 处最高(14)
            val peak = 1f - abs(2f * phase - 1f)
            val barHeight = (MIN_BAR_DP + (MAX_BAR_DP - MIN_BAR_DP) * peak).dp
            Box(
                Modifier
                    .width(2.dp)
                    .height(barHeight)
                    .background(color, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private const val BAR_COUNT = 4
private const val MIN_BAR_DP = 4f
private const val MAX_BAR_DP = 14f
