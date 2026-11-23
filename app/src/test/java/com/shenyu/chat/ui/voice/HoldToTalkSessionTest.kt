package com.shenyu.chat.ui.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observable microphone/ASR/send contracts, without a device, recorder, or test dispatcher. */
class HoldToTalkSessionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val capture = FakeCapture()
    private val session = HoldToTalkSession(capture)
    private val asr = DeferredAsr()
    private val sent = mutableListOf<String>()
    private val send: (String) -> Unit = { sent += it }

    @After
    fun closeScope() {
        scope.cancel()
    }

    @Test
    fun normalReleaseTranscribesCapturedAudioAndSendsTrimmedTextExactlyOnce() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)

        assertEquals(1, capture.stops)
        assertEquals(0, capture.cancels)
        assertEquals(1, asr.requests.size)
        assertArrayEquals(capture.pcm, asr.requests.single().pcm)
        assertTrue(sent.isEmpty())
        assertEquals(VoiceInputPhase.Transcribing, session.phase)

        val result = asr.requests.single().complete
        result(Result.success("  你好，世界  \n"))
        result(Result.success("重复回调不得发送"))
        result(Result.failure(IllegalStateException("迟到失败不得覆盖状态")))

        assertEquals(listOf("你好，世界"), sent)
        assertEquals(VoiceInputPhase.Idle, session.phase)
        assertNull(session.hint)
    }

    @Test
    fun synchronousAsrAndReentrantSendCannotConsumeTheSameRecordingTwice() {
        var requests = 0
        var sends = 0
        lateinit var callback: (Result<String>) -> Unit
        val synchronousAsr: (ByteArray, (Result<String>) -> Unit) -> Unit = { _, complete ->
            requests++
            callback = complete
            complete(Result.success("  同步结果  "))
            complete(Result.success("同步重复"))
        }
        assertTrue(session.start(scope))
        session.release(synchronousAsr) { text ->
            sends++
            sent += text
            // UI send handlers may synchronously re-enter the release/result adapters.
            session.release(asr.transcribe, send)
            callback(Result.success("send内的重入结果"))
        }

        assertEquals(1, requests)
        assertEquals(1, sends)
        assertEquals(listOf("同步结果"), sent)
        assertEquals(1, capture.stops)
        assertTrue(asr.requests.isEmpty())
    }

    @Test
    fun cancelRecordingDiscardsCaptureWithoutAsrOrSend() {
        assertTrue(session.start(scope))
        session.cancel()
        session.release(asr.transcribe, send)

        assertEquals(1, capture.cancels)
        assertEquals(0, capture.stops)
        assertTrue(asr.requests.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(VoiceInputPhase.Idle, session.phase)
        assertFalse(session.cancelRequested)
    }

    @Test
    fun releasingWhileSlidUpCancelsWithoutStoppingForAsr() {
        assertTrue(session.start(scope))
        session.updateCancel(true)
        session.release(asr.transcribe, send)

        assertEquals(1, capture.cancels)
        assertEquals(0, capture.stops)
        assertTrue(asr.requests.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun movingBackAfterSlideUpAllowsNormalSend() {
        assertTrue(session.start(scope))
        session.updateCancel(true)
        assertTrue(session.cancelRequested)
        session.updateCancel(false)
        assertFalse(session.cancelRequested)
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.success("移回后发送"))

        assertEquals(listOf("移回后发送"), sent)
        assertEquals(1, capture.stops)
        assertEquals(0, capture.cancels)
    }

    @Test
    fun shortRecordingDoesNotInvokeAsrAndShowsRetryHint() {
        capture.durationMs = 299L
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)

        assertEquals(1, capture.stops)
        assertTrue(asr.requests.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals("说话时间太短，请重试", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun recordingAtMinimumDurationCanBeSent() {
        capture.durationMs = 300L
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.success("有效录音"))

        assertEquals(listOf("有效录音"), sent)
        assertNull(session.hint)
    }

    @Test
    fun emptyAudioDoesNotInvokeAsrAndShowsNoAudioHint() {
        capture.pcm = byteArrayOf()
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)

        assertTrue(asr.requests.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals("没有录到声音，请重试", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun blankRecognitionShowsHintWithoutSending() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.success(" \t\n "))
        asr.requests.single().complete(Result.success("空结果后的重复不得发送"))

        assertTrue(sent.isEmpty())
        assertEquals("没有识别出内容，请重试", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun asrFailureShowsItsMessageAndConsumesTheResult() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.failure(IllegalStateException("网络不可用")))
        asr.requests.single().complete(Result.success("失败后重复成功不得发送"))

        assertTrue(sent.isEmpty())
        assertEquals("网络不可用", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun asrFailureWithoutMessageShowsFallbackHint() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.failure(IllegalStateException()))

        assertTrue(sent.isEmpty())
        assertEquals("识别失败，请重试", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun throwingAsrAdapterEndsTranscriptionAndShowsFailureHint() {
        assertTrue(session.start(scope))
        session.release({ _, _ -> throw IllegalStateException("识别服务启动失败") }, send)

        assertTrue(sent.isEmpty())
        assertEquals("识别服务启动失败", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun cancelDuringAsrInvalidatesBothLateSuccessAndLateFailure() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        val oldResult = asr.requests.single().complete
        session.cancel()
        session.showHint("当前页面的提示")
        oldResult(Result.success("离开后不得自动发送"))
        oldResult(Result.failure(IllegalStateException("旧请求失败")))

        assertTrue(sent.isEmpty())
        assertEquals("当前页面的提示", session.hint)
        assertEquals(VoiceInputPhase.Idle, session.phase)
    }

    @Test
    fun newRecordingRejectsOldCallbacksEvenWhileNewAsrIsPending() {
        assertTrue(session.start(scope))
        session.release(asr.transcribe, send)
        val oldResult = asr.requests.single().complete
        session.cancel()
        assertTrue(session.start(scope))
        oldResult(Result.success("录音中迟到的旧结果"))
        assertEquals(VoiceInputPhase.Recording, session.phase)
        assertTrue(sent.isEmpty())

        session.release(asr.transcribe, send)
        val newResult = asr.requests.last().complete
        oldResult(Result.failure(IllegalStateException("新识别中迟到的旧失败")))
        oldResult(Result.success("新识别中迟到的旧成功"))
        assertEquals(VoiceInputPhase.Transcribing, session.phase)
        assertNull(session.hint)
        assertTrue(sent.isEmpty())
        newResult(Result.success("  新请求结果  "))
        oldResult(Result.success("新请求完成后迟到的旧成功"))

        assertEquals(listOf("新请求结果"), sent)
        assertEquals(2, capture.starts)
        assertEquals(2, asr.requests.size)
    }

    @Test
    fun doublePressAndDoubleReleaseDoNotCreateAnotherCaptureOrRequest() {
        assertTrue(session.start(scope))
        assertFalse(session.start(scope))
        session.release(asr.transcribe, send)
        session.release(asr.transcribe, send)
        assertFalse(session.start(scope))

        assertEquals(1, capture.starts)
        assertEquals(1, capture.stops)
        assertEquals(1, asr.requests.size)
        asr.requests.single().complete(Result.success("只发一次"))
        session.release(asr.transcribe, send)
        assertEquals(listOf("只发一次"), sent)
        assertEquals(1, asr.requests.size)
    }

    @Test
    fun startFailureShowsMicrophoneHintAndDoesNotEnterRecording() {
        capture.canStart = false
        assertFalse(session.start(scope))
        session.release(asr.transcribe, send)

        assertEquals(VoiceInputPhase.Idle, session.phase)
        assertEquals("麦克风不可用，请检查系统权限", session.hint)
        assertEquals(0, capture.stops)
        assertTrue(asr.requests.isEmpty())
        assertTrue(sent.isEmpty())

        capture.canStart = true
        assertTrue(session.start(scope))
        assertNull(session.hint)
        session.release(asr.transcribe, send)
        asr.requests.single().complete(Result.success("重试成功"))
        assertEquals(listOf("重试成功"), sent)
    }

    private class FakeCapture : VoiceCapture {
        override val amplitude: Float = 0.5f
        var canStart = true
        var durationMs = 1_000L
        var pcm = byteArrayOf(1, 0, 2, 0)
        var starts = 0
        var stops = 0
        var cancels = 0

        override fun start(scope: CoroutineScope): Boolean {
            starts++
            return canStart
        }

        override fun elapsedMs(): Long = durationMs

        override fun stop(): ByteArray {
            stops++
            return pcm
        }

        override fun cancel() {
            cancels++
        }
    }

    private class DeferredAsr {
        data class Request(val pcm: ByteArray, val complete: (Result<String>) -> Unit)
        val requests = mutableListOf<Request>()
        val transcribe: (ByteArray, (Result<String>) -> Unit) -> Unit = { pcm, complete ->
            requests += Request(pcm.copyOf(), complete)
        }
    }
}
