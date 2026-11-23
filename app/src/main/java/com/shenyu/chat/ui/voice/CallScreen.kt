package com.shenyu.chat.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shenyu.chat.ui.base.DsIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音通话页（对照官方 call_* 系列：接通中 / 正在聆听 / 正在思考 / 说话或轻触打断）。
 *
 * 交互：点中央圆球开始讲话，再点结束并转写；转写结果交给 [onUserSaid] 发送，
 * 回答流式结束后自动朗读（由外部保证 [answerText] 与 [streaming] 同步）。
 */
@Composable
fun CallScreen(
    streaming: Boolean,
    answerText: String,
    onUserSaid: (String) -> Unit,
    onTranscribe: (ByteArray, (Result<String>) -> Unit) -> Unit,
    onSpeak: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder() }

    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var heardText by remember { mutableStateOf("") }
    var spokenAt by remember { mutableStateOf(0L) }
    var permissionDenied by remember { mutableStateOf(false) }

    // 上一次被朗读的回答，避免重复朗读
    var spokenText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) {
            recording = recorder.start(scope)
        }
    }

    fun startTalking() {
        if (muted || transcribing) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recording = recorder.start(scope)
    }

    fun stopTalking() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        if (pcm.isEmpty()) return
        transcribing = true
        onTranscribe(pcm) { result ->
            transcribing = false
            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    heardText = text
                    onUserSaid(text)
                }
            }
        }
    }

    // 回答流式结束 → 自动朗读一遍
    LaunchedEffect(streaming, answerText) {
        if (!streaming && answerText.isNotBlank() && answerText != spokenText) {
            spokenText = answerText
            spokenAt = System.currentTimeMillis()
            onSpeak(answerText)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) recorder.stop()
        }
    }

    val status = when {
        permissionDenied -> "需要麦克风权限才能通话"
        transcribing -> "正在识别"
        recording -> "正在聆听"
        streaming -> "正在思考"
        answerText.isNotBlank() && answerText != spokenText -> "正在思考"
        else -> "说话或轻触打断"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B0D12), Color(0xFF14171F)),
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "DeepSeek",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                text = status,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CallOrb(
                    active = recording,
                    thinking = streaming || transcribing,
                    onClick = {
                        if (recording) stopTalking() else startTalking()
                    },
                )
            }

            if (heardText.isNotBlank()) {
                Text(
                    text = "「$heardText」",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .padding(bottom = 8.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallRoundButton(
                    icon = DsIcons.CallMute,
                    tint = if (muted) Color(0xFFFF6B6B) else Color.White,
                    background = Color.White.copy(alpha = 0.12f),
                    contentDescription = if (muted) "取消静音" else "静音",
                    onClick = {
                        muted = !muted
                        if (muted && recording) {
                            recording = false
                            runCatching { recorder.stop() }
                        }
                    },
                )
                CallRoundButton(
                    icon = DsIcons.CallHangUp,
                    tint = Color.White,
                    background = Color(0xFFE5484D),
                    contentDescription = "挂断",
                    size = 68,
                    onClick = {
                        if (recorder.isRecording) runCatching { recorder.stop() }
                        onClose()
                    },
                )
            }
        }
    }
}

/** 中央呼吸圆球：录音时放大并加快脉动，思考时是缓慢的呼吸。 */
@Composable
private fun CallOrb(
    active: Boolean,
    thinking: Boolean,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (active) 760 else 2200,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val baseColor = when {
        active -> Color(0xFF426EFE)
        thinking -> Color(0xFF5B7BF7)
        else -> Color(0xFF3A3F4B)
    }

    Box(
        Modifier
            .size(240.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // 三层外圈：越靠外越淡，随 pulse 扩缩
            val rings = listOf(
                Triple(1.00f, 0.10f, 0.16f),
                Triple(0.78f, 0.16f, 0.26f),
                Triple(0.56f, 0.26f, 0.42f),
            )
            rings.forEach { (scale, alpha, extra) ->
                val r = size.minDimension / 2f * (scale + pulse * extra)
                drawCircle(
                    color = baseColor.copy(alpha = alpha * (0.6f + pulse * 0.4f)),
                    radius = r,
                    center = center,
                )
            }
            // 实心内核
            drawCircle(
                color = baseColor,
                radius = size.minDimension / 2f * (0.30f + pulse * 0.03f),
                center = center,
            )
        }
        Icon(
            imageVector = DsIcons.Mic,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun CallRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Color,
    contentDescription: String,
    size: Int = 56,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}
