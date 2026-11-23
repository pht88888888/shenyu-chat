package com.shenyu.chat.ui.voice

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.shenyu.chat.ui.chatInput.ChatInput
import com.shenyu.chat.ui.theme.ShenyuTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w390dp-h844dp-xxhdpi")
class ChatInputVoiceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private class Capture : VoiceCapture {
        override val amplitude = .6f
        var starts = 0
        var stops = 0
        override fun start(scope: CoroutineScope): Boolean { starts++; return true }
        override fun elapsedMs() = 1000L
        override fun stop(): ByteArray { stops++; return byteArrayOf(1, 2) }
        override fun cancel() { }
    }
    private val capture = Capture()
    private val sent = mutableListOf<String>()
    private var callback: ((Result<String>) -> Unit)? = null
    private var asrRequests = 0
    private var ui = VoiceInputUiState()
    private var draft = ""

    private fun render() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        rule.setContent {
            var text by remember { mutableStateOf("") }
            ShenyuTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    ChatInput(
                        thinkingEnabled = false, searchEnabled = false,
                        onToggleThinking = {}, onToggleSearch = {},
                        streaming = false, onSend = { s, _ -> sent += s }, onStop = {},
                        onTranscribe = { _, cb -> asrRequests++; callback = cb },
                        draftText = text, onDraftChange = { text = it; draft = it },
                        voiceCapture = capture, onVoiceStateChange = { ui = it },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("语音输入").performClick()
    }

    @Test fun releaseTranscribesAndAutomaticallySendsWithoutHiddenDraft() {
        render()
        rule.onNodeWithText("按住说话").performTouchInput { down(center) }
        rule.runOnIdle { assertEquals(VoiceInputPhase.Recording, ui.phase) }
        rule.onNodeWithText("按住说话").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(VoiceInputPhase.Transcribing, ui.phase)
            assertEquals(1, asrRequests)
            assertTrue(sent.isEmpty())
            callback!!(Result.success("  语音自动发送  "))
        }
        rule.runOnIdle {
            assertEquals(listOf("语音自动发送"), sent)
            assertEquals("", draft)
            assertEquals(VoiceInputPhase.Idle, ui.phase)
            callback!!(Result.success("重复回调"))
            assertEquals(1, sent.size)
        }
        rule.onNodeWithContentDescription("切回键盘").assertExists()
    }

    @Test fun upwardDragCancelsWithoutTranscriptionOrSend() {
        render()
        rule.onNodeWithText("按住说话").performTouchInput { down(center) }
        rule.onNodeWithText("按住说话").performTouchInput { moveBy(Offset(0f, -400f)) }
        rule.runOnIdle { assertTrue("state=$ui, starts=${capture.starts}, ASR=$asrRequests", ui.cancelRequested) }
        rule.onNodeWithText("按住说话").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(0, asrRequests)
            assertTrue(sent.isEmpty())
            assertEquals(VoiceInputPhase.Idle, ui.phase)
        }
    }

    @Test fun failedRecognitionShowsRecoverableErrorWithoutSend() {
        render()
        rule.onNodeWithText("按住说话").performTouchInput { down(center); up() }
        rule.runOnIdle { callback!!(Result.failure(IllegalStateException("识别网络失败"))) }
        rule.onNodeWithText("识别网络失败").assertExists()
        rule.runOnIdle { assertTrue(sent.isEmpty()); assertEquals(VoiceInputPhase.Idle, ui.phase) }
    }
}
