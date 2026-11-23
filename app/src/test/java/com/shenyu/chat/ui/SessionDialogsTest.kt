package com.shenyu.chat.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.shenyu.chat.ui.sider.NativeConfirmDialog
import com.shenyu.chat.ui.sider.NativeRenameDialog
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.theme.ShenyuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Callback contracts for the public native session dialogs; no ViewModel or device needed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w390dp-h844dp-xxhdpi")
class SessionDialogsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(content: @Composable () -> Unit) {
        // Rename requests focus after withFrameNanos in the separate Dialog composition.
        // Keep the clock advancing so that effect can finish before editing the field.
        rule.mainClock.autoAdvance = true
        rule.setContent {
            ShenyuTheme(darkTheme = false) {
                Surface(color = DsTheme.colors.bgBase, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun renameConfirmSubmitsTrimmedInputWithoutDismissing() {
        val submitted = mutableListOf<String>()
        var dismissCount = 0
        render {
            NativeRenameDialog(
                initialTitle = "原对话标题",
                onDismiss = { dismissCount++ },
                onConfirm = { submitted += it },
            )
        }

        rule.onNode(hasSetTextAction()).performTextReplacement("  新对话标题  ")
        rule.onNodeWithText("确认").assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(listOf("新对话标题"), submitted)
            assertEquals(0, dismissCount)
        }
    }

    @Test
    fun renameBlankInputDisablesConfirmAndIgnoresImeSubmit() {
        val submitted = mutableListOf<String>()
        var dismissCount = 0
        render {
            NativeRenameDialog(
                initialTitle = "原对话标题",
                onDismiss = { dismissCount++ },
                onConfirm = { submitted += it },
            )
        }

        rule.onNode(hasSetTextAction()).performTextReplacement("   ")
        rule.onNodeWithText("确认").assertIsNotEnabled()
        // Disabled button semantics alone do not cover the keyboard Done path.
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.onNodeWithText("确认").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performImeAction()

        rule.runOnIdle {
            assertTrue(submitted.isEmpty())
            assertEquals(0, dismissCount)
        }
    }

    @Test
    fun renameCancelOnlyInvokesDismissCallback() {
        val submitted = mutableListOf<String>()
        var dismissCount = 0
        render {
            NativeRenameDialog(
                initialTitle = "原对话标题",
                onDismiss = { dismissCount++ },
                onConfirm = { submitted += it },
            )
        }

        rule.onNode(hasSetTextAction()).performTextReplacement("尚未保存的标题")
        rule.onNodeWithText("取消").performClick()

        rule.runOnIdle {
            assertEquals(1, dismissCount)
            assertTrue(submitted.isEmpty())
        }
    }

    @Test
    fun confirmationActionsInvokeOnlyTheirRespectiveCallbacks() {
        var dismissCount = 0
        var confirmCount = 0
        render {
            NativeConfirmDialog(
                title = "删除对话？",
                message = "删除后无法恢复。",
                onDismiss = { dismissCount++ },
                onConfirm = { confirmCount++ },
            )
        }

        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(0, confirmCount)
        }

        // Visibility is owned by the caller: this fixture intentionally retains the
        // same dialog after cancellation to exercise the other action independently.
        rule.onNodeWithText("删除").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(1, confirmCount)
        }
    }
}
