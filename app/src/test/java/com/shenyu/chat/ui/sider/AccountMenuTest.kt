package com.shenyu.chat.ui.sider

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.theme.ShenyuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class AccountMenuPositionTest {
    private val provider = AccountMenuPositionProvider(shadowMargin = 44, horizontalInset = 44, gap = 19)

    @Test
    fun bottomRowExpandsUpWithSurfaceGapAndShadowMargin() {
        val result = provider.calculatePosition(
            IntRect(0, 2100, 864, 2260), IntSize(1080, 2292), LayoutDirection.Ltr, IntSize(660, 487),
        )
        assertEquals(IntOffset(0, 1638), result)
        // Surface bottom excludes the 44px transparent margin: 19px above the anchor.
        assertEquals(2081, result.y + 487 - 44)
    }

    @Test
    fun rightToLeftAlignsSurfaceWithTheOtherEndOfTheRow() {
        assertEquals(
            IntOffset(204, 1638),
            provider.calculatePosition(
                IntRect(0, 2100, 864, 2260), IntSize(1080, 2292), LayoutDirection.Rtl, IntSize(660, 487),
            ),
        )
    }

    @Test
    fun anchorsNearWindowEdgesCannotPushPopupOutsideWindow() {
        assertEquals(
            IntOffset(420, 0),
            provider.calculatePosition(
                IntRect(1000, 100, 1080, 200), IntSize(1080, 2292), LayoutDirection.Ltr, IntSize(660, 487),
            ),
        )
        assertEquals(
            IntOffset(0, 1805),
            provider.calculatePosition(
                IntRect(-20, 3000, 864, 3160), IntSize(1080, 2292), LayoutDirection.Ltr, IntSize(660, 487),
            ),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w390dp-h844dp-xxhdpi")
class AccountMenuTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun assertAction(title: String, expected: String, dark: Boolean = false) {
        val open = mutableStateOf(true)
        val events = mutableListOf<String>()
        rule.setContent {
            ShenyuTheme(darkTheme = dark) {
                Surface(color = DsTheme.colors.bgBase, modifier = Modifier.fillMaxSize()) {
                    Box {
                        AccountMenu(
                            expanded = open.value,
                            onDismissRequest = { events += "dismiss"; open.value = false },
                            onOpenSettings = { events += "settings" },
                            onOpenCall = { events += "call" },
                            onNewChat = { events += "new" },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText(title).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("用户菜单卡片").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("dismiss", expected), events) }
    }

    @Test
    fun settingsClosesMenuBeforeCallingSettings() = assertAction("设置", "settings")

    @Test
    fun voiceCallClosesMenuBeforeCallingVoiceCallInDarkTheme() = assertAction("语音通话", "call", dark = true)

    @Test
    fun newConversationClosesMenuBeforeCallingNewConversation() = assertAction("新建对话", "new")

    @Test
    fun avatarRowAndOverflowUseTheSameMenuAndCloseAfterSelection() {
        var settingsCount = 0
        rule.setContent {
            ShenyuTheme(darkTheme = false) {
                SiderPanel(
                    sessions = emptyList(), activeId = null,
                    modifier = Modifier.fillMaxSize(), onSelect = {}, onDelete = {}, onRename = { _, _ -> },
                    onNewChat = {}, onOpenSettings = { settingsCount++ },
                    onSetPinned = { _, _ -> }, onDeleteMany = {},
                )
            }
        }
        rule.onNodeWithText("深语用户").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithContentDescription("用户菜单卡片").assertDoesNotExist()
        rule.onNodeWithContentDescription("用户菜单").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithContentDescription("用户菜单卡片").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, settingsCount) }
    }
}
