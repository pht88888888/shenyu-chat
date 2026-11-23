package com.shenyu.chat.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 官方语音接口（与官方网页端同一套服务）：
 * - 识别：POST {origin}/v1/tools/asr，multipart 上传 WAV（16kHz 单声道 16bit）或 Ogg Opus，
 *   时长 ≤120 秒、体积 ≤8 MiB，返回 {"text": "..."}。
 * - 合成：POST {origin}/v1/tools/tts，返回裸 PCM（24kHz 单声道 16bit 小端）。
 * 两者都用 `Authorization: Bearer <apiKey>`。
 */
class AudioClient {

    /** TTS 输出参数，供播放端建 AudioTrack 用。 */
    companion object {
        const val TTS_SAMPLE_RATE = 24000
        const val ASR_SAMPLE_RATE = 16000
        const val ASR_MAX_SECONDS = 120
        const val ASR_MAX_BYTES = 8 * 1024 * 1024
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 语音识别：把 WAV 字节交给服务端，拿回文本。 */
    suspend fun transcribe(origin: String, apiKey: String, wav: ByteArray): String =
        withContext(Dispatchers.IO) {
            if (wav.isEmpty()) throw ApiException(ApiException.Kind.PROTOCOL, "没有录到声音")
            if (wav.size > ASR_MAX_BYTES) {
                throw ApiException(ApiException.Kind.PROTOCOL, "录音太长，请控制在 2 分钟以内")
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "file",
                    filename = "recording.wav",
                    body = wav.toRequestBody("audio/wav".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url("${origin.trimEnd('/')}/v1/tools/asr")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw toApiException(response.code, raw)
                val text = runCatching {
                    json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                    ?: throw ApiException(ApiException.Kind.PROTOCOL, raw.ifBlank { "识别失败" })
                text
            }
        }

    /**
     * 语音合成：把第一段 PCM 的输入流交给调用方边收边播。
     * 调用方负责关闭流（停止播放时直接 close 会中断连接）。
     *
     * 官方接口返回裸 PCM；部分 DeepSeek 兼容中转站只接受/只返回 WAV。
     * 这里先按官方格式请求，失败或收到 RIFF 头时再按 WAV 处理，播放端始终拿到裸 PCM。
     */
    suspend fun openSpeechStream(
        origin: String,
        apiKey: String,
        text: String,
        voiceId: String = "stella",
    ): InputStream {
        return try {
            openSpeechStream(origin, apiKey, text, voiceId, format = "pcm")
        } catch (e: ApiException) {
            if (e.status == 400 || e.status == 500) {
                openSpeechStream(origin, apiKey, text, voiceId, format = "wav")
            } else {
                throw e
            }
        }
    }

    private suspend fun openSpeechStream(
        origin: String,
        apiKey: String,
        text: String,
        voiceId: String,
        format: String,
    ): InputStream = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            put("voice_id", kotlinx.serialization.json.JsonPrimitive(voiceId))
            put("format", kotlinx.serialization.json.JsonPrimitive(format))
            put("filter_markdown", kotlinx.serialization.json.JsonPrimitive(true))
        }
        val request = Request.Builder()
            .url("${origin.trimEnd('/')}/v1/tools/tts")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)
        val response = call.execute()
        if (!response.isSuccessful) {
            val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            throw toApiException(response.code, raw)
        }
        val rawStream = response.body?.byteStream()
            ?: throw ApiException(ApiException.Kind.PROTOCOL, "服务端没有返回音频")
        val stream = stripWavHeaderIfPresent(rawStream)
        object : java.io.FilterInputStream(stream) {
            override fun close() {
                super.close()
                runCatching { call.cancel() }
                runCatching { response.close() }
            }
        }
    }

    /** 中转站可能无视 format 直接回 WAV；有 RIFF 头就跳过，只留下 24k 单声道 PCM。 */
    private fun stripWavHeaderIfPresent(input: InputStream): InputStream {
        val pushback = java.io.PushbackInputStream(input, 64)
        val header = ByteArray(12)
        val read = readAtMost(pushback, header, 0, header.size)
        val isRiff = read == header.size &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        if (!isRiff) {
            if (read > 0) pushback.unread(header, 0, read)
            return pushback
        }
        while (true) {
            val chunk = ByteArray(8)
            if (readAtMost(pushback, chunk, 0, 8) < 8) {
                throw ApiException(ApiException.Kind.PROTOCOL, "语音服务返回的音频不完整")
            }
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            if (id == "data") return pushback
            val size = leInt(chunk, 4).toLong() and 0xFFFFFFFFL
            skipFully(pushback, size + (size and 1L))
        }
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buffer, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw ApiException(ApiException.Kind.PROTOCOL, "语音服务返回的音频不完整")
            left -= n
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun toApiException(code: Int, raw: String): ApiException {
        val friendly = when (code) {
            400 -> "这段内容无法合成语音"
            401, 403 -> "API Key 无效或已失效"
            402 -> "余额不足，请到开放平台充值"
            413 -> "录音太长，请控制在 2 分钟以内"
            429 -> "请求过于频繁，稍后再试"
            else -> raw.take(300).ifBlank { "语音服务返回 $code" }
        }
        val kind = when (code) {
            401, 403 -> ApiException.Kind.AUTH
            402 -> ApiException.Kind.QUOTA
            else -> ApiException.Kind.HTTP
        }
        return ApiException(kind, friendly, code)
    }
}

/** 把 16kHz 单声道 16bit PCM 包成标准 WAV。 */
fun pcmToWav(pcm: ByteArray, sampleRate: Int = AudioClient.ASR_SAMPLE_RATE): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val out = ByteArray(44 + dataSize)
    fun writeIntLE(value: Int, offset: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
    fun writeShortLE(value: Int, offset: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
    "RIFF".toByteArray().copyInto(out, 0)
    writeIntLE(36 + dataSize, 4)
    "WAVE".toByteArray().copyInto(out, 8)
    "fmt ".toByteArray().copyInto(out, 12)
    writeIntLE(16, 16)
    writeShortLE(1, 20) // PCM
    writeShortLE(channels, 22)
    writeIntLE(sampleRate, 24)
    writeIntLE(byteRate, 28)
    writeShortLE(blockAlign, 32)
    writeShortLE(bitsPerSample, 34)
    "data".toByteArray().copyInto(out, 36)
    writeIntLE(dataSize, 40)
    pcm.copyInto(out, 44)
    return out
}
