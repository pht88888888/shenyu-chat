package com.shenyu.chat.ui.chatInput

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.theme.ShenyuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tests tray callback ownership and state recovery without a gallery provider or device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w390dp-h844dp-xxhdpi")
class AttachmentPanelTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            ShenyuTheme(darkTheme = dark) {
                Surface(color = DsTheme.colors.bgBase, modifier = Modifier.fillMaxSize()) {
                    Box { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun deniedGalleryPermissionOffersPickerAndKeepsThreeActionsAvailable() {
        val actions = mutableListOf<String>()
        render {
            // No grant exists in this fixture. Public component must avoid a query and
            // expose its picker fallback rather than requesting broad storage access.
            AttachmentPanel(
                onCamera = { actions += "camera" },
                onGallery = { actions += "gallery" },
                onFile = { actions += "file" },
                onPickImage = { actions += "image" },
                selectedUris = emptySet(),
            )
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("选择照片")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("选择照片").assertIsDisplayed().performClick()
        rule.onNodeWithText("拍照").performClick()
        rule.onNodeWithText("相册").performClick()
        rule.onNodeWithText("文件").performClick()
        rule.runOnIdle { assertEquals(listOf("gallery", "camera", "gallery", "file"), actions) }
    }

    @Test
    fun loadingReportsProgressWhileFileActionRemainsUsable() {
        var opened = 0
        render {
            AttachmentPanelContent(
                recent = RecentAttachmentImages.Loading,
                onCamera = {}, onGallery = {}, onFile = { opened++ }, onPickImage = {},
                selectedUris = emptySet(),
            )
        }
        rule.onNodeWithText("正在加载最近照片…").assertIsDisplayed()
        rule.onNodeWithText("文件").performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun emptyGalleryUsesFullFallbackInDarkTheme() {
        var galleryOpened = 0
        render(dark = true) {
            AttachmentPanelContent(
                recent = RecentAttachmentImages.Ready(emptyList()),
                onCamera = {}, onGallery = { galleryOpened++ }, onFile = {}, onPickImage = {},
                selectedUris = emptySet(),
            )
        }
        rule.onNodeWithText("还没有最近照片，可从相册选择").assertIsDisplayed()
        rule.onNodeWithText("选择照片").performClick()
        rule.runOnIdle { assertEquals(1, galleryOpened) }
    }

    @Test
    fun parentOwnedSelectionSurvivesReopeningAndDoesNotDuplicateAttachment() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val selected = mutableStateOf(emptySet<String>())
        val visible = mutableStateOf(true)
        val picked = mutableListOf<Uri>()
        render {
            if (visible.value) {
                AttachmentPanelContent(
                    recent = RecentAttachmentImages.Ready(listOf(uri)),
                    onCamera = {}, onGallery = {}, onFile = {},
                    onPickImage = {
                        picked += it
                        selected.value = selected.value + it.toString()
                    },
                    selectedUris = selected.value,
                )
            }
        }
        rule.onNodeWithContentDescription("最近照片 1").assertIsNotSelected().performClick()
        rule.onNodeWithContentDescription("最近照片 1").assertIsSelected()
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("最近照片 1").assertIsSelected().performClick()
        rule.runOnIdle { assertEquals(listOf(uri), picked) }
    }
}
