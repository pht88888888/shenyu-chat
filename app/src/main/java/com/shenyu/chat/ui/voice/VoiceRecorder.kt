package com.shenyu.chat.ui.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 录音机：采集 16kHz 单声道 16bit PCM，停止时交出原始 PCM。
 * 编码成 WAV 由 net/AudioClient 的 pcmToWav 负责。
 */
class VoiceRecorder : VoiceCapture {

    companion object {
        const val SAMPLE_RATE = 16000
        /** 录满 120 秒自动停，与接口上限一致 */
        const val MAX_MS = 120_000L
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var captureGeneration = 0L
    @Volatile override var amplitude: Float = 0f
        private set
    private var startedAt = 0L

    val isRecording: Boolean get() = record != null

    /** 已录制时长（毫秒） */
    override fun elapsedMs(): Long = if (startedAt == 0L) 0 else android.os.SystemClock.elapsedRealtime() - startedAt

    /**
     * 开始录音；拿不到麦克风时返回 false。
     * 调用方（输入区）负责先申请 RECORD_AUDIO 并确认已授权，这里只处理硬件不可用的情况。
     */
    @android.annotation.SuppressLint("MissingPermission")
    override fun start(scope: CoroutineScope): Boolean {
        if (record != null) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }
        if (runCatching { rec.startRecording() }.isFailure) {
            runCatching { rec.release() }
            return false
        }
        synchronized(buffer) { buffer.reset() }
        amplitude = 0f
        startedAt = android.os.SystemClock.elapsedRealtime()
        record = rec
        val generation = ++captureGeneration
        job = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(minBuffer)
            while (generation == captureGeneration) {
                val read = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (read <= 0) break
                synchronized(buffer) {
                    if (generation == captureGeneration) {
                        buffer.write(chunk, 0, read)
                        var sum = 0.0
                        for (i in 0 until read - 1 step 2) {
                            val sample = ((chunk[i].toInt() and 255) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
                            sum += sample.toDouble() * sample
                        }
                        amplitude = (kotlin.math.sqrt(sum / (read / 2).coerceAtLeast(1)) / 6000.0).toFloat().coerceIn(0f, 1f)
                    }
                }
                if (elapsedMs() >= MAX_MS) break
            }
        }
        return true
    }

    /** 停止并返回 PCM；没有录到内容时返回空数组。 */
    override fun stop(): ByteArray {
        val rec = record ?: return ByteArray(0)
        captureGeneration++
        amplitude = 0f
        record = null
        startedAt = 0L
        runCatching { rec.stop() }
        runCatching { rec.release() }
        job?.cancel()
        job = null
        return synchronized(buffer) {
            val pcm = buffer.toByteArray()
            buffer.reset()
            pcm
        }
    }

    override fun cancel() {
        stop()
    }
}
