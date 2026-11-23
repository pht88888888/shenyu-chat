package com.shenyu.chat.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.theme.DsTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Drawn in the existing main window: the original press keeps its pointer ownership. */
@Composable
fun VoiceRecordingOverlay(state: VoiceInputUiState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(150)),
    ) {
        val cancel = state.cancelRequested
        val tint by androidx.compose.animation.animateColorAsState(
            if (cancel) Color(0xFFE46876) else Color(0xFF65A0FC),
            tween(160), label = "recordingTint",
        )
        val level by animateFloatAsState(state.amplitude, tween(90), label = "microphoneLevel")
        val phase by rememberInfiniteTransition(label = "voiceWave").animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "wavePhase",
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Screenshot reference: fade begins near 65% screen height, saturated
            // blue below 85%, with soft radial shoulders rather than a hard rectangle.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(
                    0f to Color.Transparent,
                    .62f to Color.Transparent,
                    .73f to tint.copy(alpha = .16f),
                    .85f to tint.copy(alpha = .83f),
                    1f to tint,
                ))
                drawRect(Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = .35f), Color.Transparent),
                    center = Offset(size.width * .5f, size.height * .91f),
                    radius = size.width * .9f,
                ))
            }
            val transcribing = state.phase == VoiceInputPhase.Transcribing
            val foreground = if (DsTheme.colors.isDark) Color.White else Color(0xFFF4F7FD)
            Text(
                text = when {
                    transcribing -> "正在识别…"
                    cancel -> "松开取消"
                    else -> "松手发送，上滑取消"
                },
                color = foreground,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().offset(y = maxHeight * .765f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Box(
                Modifier.fillMaxWidth().height(40.dp).offset(y = maxHeight * .84f),
                contentAlignment = Alignment.Center,
            ) {
                if (transcribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp), color = Color.White, strokeWidth = 2.dp,
                    )
                } else Canvas(Modifier.fillMaxWidth(.74f).height(34.dp)) {
                    val count = 43
                    val spacing = size.width / (count - 1)
                    val stroke = 2.dp.toPx()
                    for (i in 0 until count) {
                        val relative = (i - (count - 1) / 2f) / ((count - 1) / 2f)
                        val envelope = exp(-relative * relative * 4.5f)
                        val oscillation = .25f + .75f * abs(sin(phase * 2 + i * 1.71f))
                        val height = 3.dp.toPx() + size.height * envelope * oscillation * (.14f + .86f * level)
                        val x = spacing * i
                        drawLine(
                            Color.White.copy(alpha = .16f + .63f * (1 - abs(relative))),
                            Offset(x, (size.height - height) / 2),
                            Offset(x, (size.height + height) / 2),
                            strokeWidth = stroke, cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
