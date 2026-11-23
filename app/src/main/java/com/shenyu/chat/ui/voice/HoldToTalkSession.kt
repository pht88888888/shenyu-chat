package com.shenyu.chat.ui.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope

/** Capture boundary shared by the microphone and deterministic gesture/ASR tests. */
interface VoiceCapture {
    val amplitude: Float
    fun start(scope: CoroutineScope): Boolean
    fun elapsedMs(): Long
    fun stop(): ByteArray
    fun cancel()
}

enum class VoiceInputPhase { Idle, Recording, Transcribing }

data class VoiceInputUiState(
    val phase: VoiceInputPhase = VoiceInputPhase.Idle,
    val cancelRequested: Boolean = false,
    val amplitude: Float = 0f,
) {
    val visible: Boolean get() = phase != VoiceInputPhase.Idle
}

/** One press owns one recording and at most one ASR result. Navigation invalidates it. */
internal class HoldToTalkSession(private val capture: VoiceCapture) {
    var phase by mutableStateOf(VoiceInputPhase.Idle)
        private set
    var cancelRequested by mutableStateOf(false)
        private set
    var hint by mutableStateOf<String?>(null)
        private set
    private var generation = 0L

    fun start(scope: CoroutineScope): Boolean {
        if (phase != VoiceInputPhase.Idle) return false
        hint = null
        cancelRequested = false
        if (!capture.start(scope)) {
            hint = "麦克风不可用，请检查系统权限"
            return false
        }
        generation++
        phase = VoiceInputPhase.Recording
        return true
    }

    fun updateCancel(cancel: Boolean) {
        if (phase == VoiceInputPhase.Recording) cancelRequested = cancel
    }

    fun showHint(message: String) { hint = message }

    fun cancel() {
        generation++
        capture.cancel()
        phase = VoiceInputPhase.Idle
        cancelRequested = false
    }

    fun release(
        transcribe: (ByteArray, (Result<String>) -> Unit) -> Unit,
        send: (String) -> Unit,
    ) {
        if (phase != VoiceInputPhase.Recording) return
        if (cancelRequested) {
            cancel()
            return
        }
        val duration = capture.elapsedMs()
        val pcm = capture.stop()
        phase = VoiceInputPhase.Idle
        if (duration < 300L) {
            hint = "说话时间太短，请重试"
            return
        }
        if (pcm.isEmpty()) {
            hint = "没有录到声音，请重试"
            return
        }
        phase = VoiceInputPhase.Transcribing
        val request = generation
        val callback: (Result<String>) -> Unit = callback@{ result ->
            if (request != generation || phase != VoiceInputPhase.Transcribing) return@callback
            // Consume before invoking the send callback, including synchronous ASR adapters.
            generation++
            phase = VoiceInputPhase.Idle
            result.fold(
                onSuccess = { spoken ->
                    if (spoken.isBlank()) hint = "没有识别出内容，请重试"
                    else send(spoken.trim())
                },
                onFailure = { hint = it.message ?: "识别失败，请重试" },
            )
        }
        try {
            transcribe(pcm, callback)
        } catch (error: Exception) {
            callback(Result.failure(error))
        }
    }
}
