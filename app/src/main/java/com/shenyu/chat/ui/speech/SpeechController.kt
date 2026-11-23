package com.shenyu.chat.ui.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.shenyu.chat.data.SettingsState
import com.shenyu.chat.net.AudioClient
import com.shenyu.chat.net.ProviderEndpoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 朗读：走官方语音合成接口（/v1/tools/tts），拿裸 PCM 边收边播。
 * 与系统 TTS 相比声音一致、能过滤 Markdown 记号，也不需要手机装语音包。
 */
class SpeechController(
    private val appContext: Context,
    private val client: AudioClient,
    private val settingsProvider: () -> SettingsState,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 正在朗读的消息 id，null 表示空闲 */
    var playingMessageId by mutableStateOf<String?>(null)
        private set

    /** 最近一次失败原因，UI 读过即可置空 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var stream: InputStream? = null
    private var track: AudioTrack? = null

    /** 再点一次就是停止 */
    fun toggle(messageId: String, text: String) {
        if (playingMessageId == messageId) {
            stop()
            return
        }
        stop()
        if (text.isBlank()) return
        playingMessageId = messageId
        job = scope.launch { runPlayback(text) }
    }

    fun consumeError(): String? {
        val message = errorMessage
        errorMessage = null
        return message
    }

    private suspend fun runPlayback(text: String) {
        val settings = settingsProvider()
        if (settings.apiKey.isBlank()) {
            errorMessage = "请先在设置里填写 API Key"
            playingMessageId = null
            return
        }
        if (!settings.activeProvider.supportsVoice) {
            errorMessage = "当前供应商不支持朗读，请切换到 DeepSeek 官方供应商。"
            playingMessageId = null
            return
        }
        try {
            val input = client.openSpeechStream(
                origin = ProviderEndpoints.serviceOrigin(settings.activeProvider),
                apiKey = settings.apiKey,
                text = text,
                voiceId = settings.ttsVoice,
            )
            stream = input
            withContext(Dispatchers.IO) { pumpToTrack(input) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "朗读失败，请稍后再试"
        } finally {
            releaseAudio()
            playingMessageId = null
        }
    }

    private fun pumpToTrack(input: InputStream) {
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioClient.TTS_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioClient.TTS_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("音频播放器初始化失败")
        }
        runCatching { audioTrack.play() }.exceptionOrNull()?.let {
            throw IllegalStateException("音频播放器启动失败：${it.message}")
        }
        val chunk = ByteArray(minBuffer)
        var total = 0L
        while (true) {
            val read = runCatching { input.read(chunk) }.getOrDefault(-1)
            if (read <= 0) break
            val written = runCatching { audioTrack.write(chunk, 0, read) }.getOrDefault(-1)
            if (written < 0) throw IllegalStateException("音频写入失败（错误码 $written）")
            total += written
        }
        if (total == 0L) throw IllegalStateException("语音服务没有返回可播放的音频")
        runCatching { audioTrack.stop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseAudio()
        playingMessageId = null
    }

    private fun releaseAudio() {
        runCatching { stream?.close() }
        stream = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}

@Composable
fun rememberSpeechController(
    client: AudioClient,
    settingsProvider: () -> SettingsState,
): SpeechController {
    val context = LocalContext.current
    val controller = remember { SpeechController(context, client, settingsProvider) }
    DisposableEffect(controller) {
        onDispose { controller.shutdown() }
    }
    return controller
}
