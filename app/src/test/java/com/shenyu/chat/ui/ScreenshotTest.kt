package com.shenyu.chat.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.shenyu.chat.chat.ChatViewModel
import com.shenyu.chat.data.Block
import com.shenyu.chat.data.ChatDatabase
import com.shenyu.chat.data.Message
import com.shenyu.chat.data.MessageStatus
import com.shenyu.chat.data.Role
import com.shenyu.chat.data.SearchHit
import com.shenyu.chat.data.SearchStatus
import com.shenyu.chat.chat.SessionHeaderUi
import com.shenyu.chat.data.SettingsState
import com.shenyu.chat.data.SettingsStore
import com.shenyu.chat.ui.search.SearchPanel
import com.shenyu.chat.ui.search.SourceItem
import com.shenyu.chat.ui.session.MessageList
import com.shenyu.chat.ui.settings.SettingsScreen
import com.shenyu.chat.ui.sider.SiderPanel
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.theme.ShenyuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 用 Robolectric 原生图形把界面真实渲染成 PNG，供与官方 demo 截图逐像素比对。
 * 只在开发机上跑，不进 APK。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // ---------- 截图工具 ----------

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("/tmp/shenyu-shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * 直接把 decorView 画到 Bitmap：Robolectric 原生图形下唯一可靠的截图方式。
     * Dialog 承载的界面（设置页 / 来源面板）要显式传 dialog = true —— 否则上一支用例
     * 残留的 Dialog 会被误截，出现多张图完全一样的情况。
     */
    private fun capture(name: String, dialog: Boolean = false) {
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val view = if (dialog) {
            val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            requireNotNull(d) { "期望有 Dialog，但没有取到：$name" }
            d.window!!.decorView
        } else {
            rule.activity.window.decorView
        }
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ShenyuTheme(darkTheme = dark) {
                Surface(color = DsTheme.colors.bgBase, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    // ---------- 假数据 ----------

    private val userText = "帮我看看 2025 年新能源汽车的销量，并对比一下比亚迪和特斯拉。"

    private val assistantBlocks = listOf(
        Block.Thinking("先拆解一下需求：需要最新的年度销量数据，还要两家公司的对比。", done = true, seconds = 6),
        Block.Search(
            kind = "search",
            queries = listOf("2025 新能源汽车销量"),
            status = SearchStatus.DONE,
            hits = listOf(
                SearchHit(
                    title = "2025 年中国新能源汽车销量突破 1600 万辆",
                    url = "https://example.com/a",
                    snippet = "中汽协数据显示，2025 年新能源汽车产销继续领跑全球。",
                    date = "2025-11-02",
                    siteName = "中汽协",
                ),
                SearchHit(
                    title = "比亚迪与特斯拉的年度对比",
                    url = "https://example.com/b",
                    snippet = "从交付量、毛利率与海外市场三条线看两家的差异。",
                    date = "2025-10-28",
                    siteName = "第一财经",
                ),
            ),
        ),
        Block.Text(
            "## 结论\n\n2025 年国内新能源汽车销量继续增长，**比亚迪**在总量上保持领先，特斯拉则更依赖单车利润。\n\n" +
                "- 比亚迪：插混与纯电双线\n- 特斯拉：Model Y 仍是主力\n\n" +
                "```kotlin\nval growth = 1.32\n```\n\n| 厂商 | 交付量 |\n| --- | --- |\n| 比亚迪 | 420 万 |\n| 特斯拉 | 180 万 |\n"
        ),
    )

    private val messages = listOf(
        Message(role = Role.USER, blocks = listOf(Block.Text(userText))),
        Message(
            role = Role.ASSISTANT,
            status = MessageStatus.FINISHED,
            blocks = assistantBlocks,
            variants = listOf(Message(role = Role.ASSISTANT, blocks = listOf(Block.Text("旧版本")))),
        ),
    )

    private fun viewModel(): ChatViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsStore(context)
        // 截图必须停留在主页面，不能让首次启动的 Key 设置弹窗遮住待测界面。
        settings.update {
            it.updateProvider(it.activeProviderId) { provider -> provider.copy(apiKey = "test-not-a-real-key") }
                .copy(themeMode = com.shenyu.chat.data.ThemeMode.LIGHT)
        }
        return ChatViewModel(context, ChatDatabase(context), settings)
    }

    // ---------- 用例 ----------

    @Test
    fun appWelcomeLight() {
        render { ShenyuApp(viewModel = viewModel()) }
        capture("01-app-welcome-light")
    }

    @Test
    fun appWelcomeDark() {
        // ShenyuApp 自己按 settings.themeMode 决定明暗，所以要显式把它切成深色
        val vm = viewModel()
        vm.settings.update { it.copy(themeMode = com.shenyu.chat.data.ThemeMode.DARK) }
        render(dark = true) { ShenyuApp(viewModel = vm) }
        capture("02-app-welcome-dark")
    }

    @Test
    fun messagesLight() {
        render {
            MessageList(messages = messages, streaming = false, onRegenerate = {})
        }
        capture("03-messages-light")
    }

    @Test
    fun messagesDark() {
        render(dark = true) {
            MessageList(messages = messages, streaming = true, onRegenerate = {})
        }
        capture("04-messages-dark-streaming")
    }

    @Test
    fun sider() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            SessionHeaderUi(id = "1", title = "2025 新能源汽车销量对比", updatedAt = now, pinned = true),
            SessionHeaderUi(id = "2", title = "Kotlin 协程的调度器怎么选", updatedAt = now - 3_600_000, pinned = false),
            SessionHeaderUi(id = "3", title = "帮我把这段 Python 改写成 Rust", updatedAt = now - 86_400_000, pinned = false),
            SessionHeaderUi(id = "4", title = "周末去哪儿玩", updatedAt = now - 3 * 86_400_000, pinned = false),
        )
        render {
            // 侧栏在真机上固定 300dp 宽，这里按同样宽度渲染，方便和官方 300dp 截图对齐比较
            Box(Modifier.width(300.dp).fillMaxHeight()) {
                SiderPanel(
                    sessions = sessions,
                    activeId = "1",
                    onSelect = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onNewChat = {},
                    onOpenSettings = {},
                    onSetPinned = { _, _ -> },
                    onDeleteMany = {},
                )
            }
        }
        capture("05-sider")
    }

    @Test
    fun settingsGeneral() {
        render {
            SettingsScreen(
                settings = SettingsState(),
                transfer = null,
                onClose = {},
                onUpdate = {},
                onDeleteAll = {},
                onVerifyKey = { _, _ -> },
                onExport = {},
                onImport = { _, _ -> },
                onImportUrl = { _, _ -> },
            )
        }
        capture("06-settings-general", dialog = true)
    }

    @Test
    fun sourcePanel() {
        val sources = listOf(
            SourceItem(
                host = "example.com",
                title = "2025 年中国新能源汽车销量突破 1600 万辆",
                url = "https://example.com/a",
                snippet = "中汽协数据显示，2025 年新能源汽车产销继续领跑全球，渗透率超过五成。",
                date = "2025-11-02",
                siteName = "中汽协",
            ),
            SourceItem(
                host = "example.com",
                title = "比亚迪与特斯拉的年度对比",
                url = "https://example.com/b",
                snippet = "从交付量、毛利率与海外市场三条线看两家的差异。",
                date = "2025-10-28",
                siteName = "第一财经",
            ),
        )
        render {
            SearchPanel(visible = true, sources = sources, onClose = {}, highlightIndex = 0)
        }
        capture("07-sources", dialog = true)
    }

    /** 富文本：代码块（长行软换行）、行内代码、公式、引用、有序/无序列表、链接。 */
    @Test
    fun richMarkdown() {
        val blocks = listOf(
            Block.Text(
                "### 一、代码块\n\n" +
                    "```kotlin\nfun main() {\n    val list = listOf(1, 2, 3)\n" +
                    "    println(list.map { it * 2 }.filter { it > 2 }.sum())\n}\n```\n\n" +
                    "### 二、行内元素\n\n" +
                    "行内代码 `const x = 1`、链接 [DeepSeek](https://www.deepseek.com)、**加粗**、*斜体*、~~删除线~~。\n\n" +
                    "### 三、公式\n\n" +
                    "质能方程 \$E = mc^2\$，以及块级公式：\n\n" +
                    "\$\$\\int_{0}^{1} x^2 \\, dx = \\frac{1}{3}\$\$\n\n" +
                    "### 四、引用与列表\n\n" +
                    "> 引用一段官方文档里的话，看看左侧竖线和字色。\n\n" +
                    "1. 第一步\n2. 第二步\n\n---\n\n" +
                    "最后一段收尾。"
            ),
        )
        val msgs = listOf(
            Message(role = Role.USER, blocks = listOf(Block.Text("给我一段富文本示例"))),
            Message(role = Role.ASSISTANT, status = MessageStatus.FINISHED, blocks = blocks),
        )
        render { MessageList(messages = msgs, streaming = false, onRegenerate = {}) }
        capture("08-message-rich")
    }

    /** 输入区：带附件的草稿 + 生成中（停止按钮）。 */
    @Test
    fun inputStates() {
        render {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
            ) {
                com.shenyu.chat.ui.chatInput.ChatInput(
                    streaming = true,
                    thinkingEnabled = true,
                    searchEnabled = false,
                    onToggleThinking = {},
                    onToggleSearch = {},
                    onSend = { _, _ -> },
                    onStop = {},
                    onTranscribe = { _, _ -> },
                    draftText = "再看看 2024 年上半年的数据",
                )
            }
        }
        capture("09-input-streaming")
    }

    /** 设置 - 数据管理（切到第二个分组）。 */
    @Test
    fun settingsData() {
        render {
            SettingsScreen(
                settings = SettingsState(
                    providers = listOf(SettingsState.OFFICIAL_PROVIDER.copy(apiKey = "sk-1234567890abcdef")),
                    activeProviderId = SettingsState.OFFICIAL_PROVIDER_ID,
                ),
                transfer = null,
                onClose = {},
                onUpdate = {},
                onDeleteAll = {},
                onVerifyKey = { _, _ -> },
                onExport = {},
                onImport = { _, _ -> },
                onImportUrl = { _, _ -> },
            )
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("数据管理").performClick()
        capture("10-settings-data", dialog = true)
    }

    /** 供应商列表与编辑弹窗。 */
    @Test
    fun settingsProviderDialogs() {
        render {
            SettingsScreen(
                settings = SettingsState(
                    providers = listOf(
                        SettingsState.OFFICIAL_PROVIDER.copy(apiKey = "sk-1234567890abcdef"),
                        com.shenyu.chat.data.ProviderConfig(
                            id = "custom-1",
                            name = "我的中转站",
                            baseUrl = "https://relay.example.com/v1",
                            apiKey = "sk-custom",
                            model = "deepseek-chat",
                            protocol = com.shenyu.chat.data.ProviderProtocol.ANTHROPIC,
                        ),
                    ),
                    activeProviderId = SettingsState.OFFICIAL_PROVIDER_ID,
                ),
                transfer = null,
                onClose = {},
                onUpdate = {},
                onDeleteAll = {},
                onVerifyKey = { _, _ -> },
                onExport = {},
                onImport = { _, _ -> },
                onImportUrl = { _, _ -> },
            )
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("数据管理").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("DeepSeek 官方").performClick()
        capture("16-provider-list", dialog = true)
        rule.onNodeWithText("添加自定义供应商").performClick()
        capture("17-provider-editor", dialog = true)
    }

    /** 用户截图同尺寸的原生渲染；系统栏只留安全区，不伪造操作系统图标。 */
    @Config(sdk = [33], qualifiers = "w360dp-h792dp-xxhdpi")
    @Test
    fun officialViewportInteractions() {
        val vm = viewModel()
        vm.settings.update { it.copy(thinkingEnabled = true, searchEnabled = true) }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = ChatDatabase(context)
        val now = System.currentTimeMillis()
        listOf("Qoder大赛组队攻略", "初次问候", "今日头条动态速览", "TCP/IP开启命令", "R8混淆详解", "贵港校招最新资讯", "Cloudflare记录区别", "DeepSeek API全家桶").forEachIndexed { index, title ->
            db.upsertSession(com.shenyu.chat.data.Session(
                id = "reference-$index", title = title, pinned = index == 0,
                updatedAt = now - if (index < 5) index * 1000L else 86_400_000L + index * 1000L,
            ))
        }
        vm.reloadSessions()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
        render { ShenyuApp(vm) }
        rule.runOnIdle {
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 114, 0, 0))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 48))
                .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        capture("12-official-welcome")
        rule.onNodeWithContentDescription("语音输入").performClick()
        capture("13-official-voice")
        rule.onNodeWithText("按住说话").assertExists()
        rule.onNodeWithContentDescription("开启自动朗读").performClick()
        capture("14-official-toast")
        rule.onNodeWithText("已开启自动朗读").assertExists()
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithContentDescription("会话列表").performClick()
        capture("15-official-sidebar")
        rule.onNodeWithText("深语用户").assertExists()
        rule.onNodeWithText("Qoder大赛组队攻略").performTouchInput { longClick() }
        rule.onNodeWithText("重命名").assertExists()
        rule.onNodeWithText("多选").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("已选择 1 个对话").assertExists()
        rule.onNodeWithContentDescription("退出多选").performClick()
        rule.mainClock.advanceTimeBy(500)
        // 静态截图使用手动时钟；Popup 则需要后续的 Android layout + Compose 帧。
        // 单次 advanceTimeBy 会先跑完时间再处理 layout，Popup 的二阶段布局尚未就绪。
        // 恢复自动推进，让 waitForIdle 排空弹窗注册、定位及入场动画，不跳过任何断言。
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("用户菜单").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("设置").assertExists()
        rule.onNodeWithText("语音通话").assertExists()
        rule.onNodeWithText("新建对话").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("深语用户").assertDoesNotExist()
        rule.onNodeWithContentDescription("会话列表").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription("收起会话列表").assertExists()
        // 露边节点大部分在屏外；对整屏注入触摸，DOWN 必须落在可见的右侧露边。
        rule.onRoot().performTouchInput {
            swipe(Offset(width - 30f, height / 2f), Offset(90f, height / 2f), durationMillis = 300)
        }
        rule.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertFalse(vm.state.value.siderOpen)
    }

    @Config(sdk = [33], qualifiers = "w360dp-h792dp-xxhdpi")
    @Test
    fun accountMenuIsolated() {
        var settingsCalls = 0
        var callCalls = 0
        var newChatCalls = 0
        render {
            Box(Modifier.width(296.dp).fillMaxHeight()) {
                SiderPanel(
                    sessions = emptyList(), activeId = null,
                    onSelect = {}, onDelete = {}, onRename = { _, _ -> },
                    onNewChat = { newChatCalls++ }, onOpenSettings = { settingsCalls++ },
                    onOpenCall = { callCalls++ },
                    onSetPinned = { _, _ -> }, onDeleteMany = {},
                )
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("用户菜单").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("设置").assertExists()
        rule.onNodeWithText("语音通话").assertExists()
        rule.onNodeWithText("新建对话").assertExists()
        rule.onNodeWithText("设置").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(1, settingsCalls) }
        rule.onNodeWithContentDescription("用户菜单").performClick()
        rule.onNodeWithText("语音通话").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(1, callCalls) }
        rule.onNodeWithContentDescription("用户菜单").performClick()
        rule.onNodeWithText("新建对话").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(1, newChatCalls) }
    }

    /** 并行搜索：多个搜索步骤应折叠成一行（含站点图标）。 */
    @Test
    fun parallelSearchStreaming() {
        val blocks = listOf(
            Block.Thinking("用户想知道骁龙 8 Gen 3 手机玩 Minecraft 为什么发烫，需要并行搜索原因、优化和散热方案。", done = false),
            Block.Search(
                kind = "deep_search",
                queries = listOf("骁龙8Gen3 发热原因"),
                status = SearchStatus.RUNNING,
                hits = emptyList(),
            ),
            Block.Search(
                kind = "deep_search",
                queries = listOf("Minecraft 渲染距离 优化"),
                status = SearchStatus.DONE,
                hits = listOf(
                    SearchHit("骁龙 8 Gen 3 发热原因分析", "https://zhihu.com/a", "峰值功耗高", "2025-11-01", "知乎"),
                    SearchHit("Minecraft 帧率限制", "https://minecraft.wiki/b", "帧率上限 120", "2025-10-20", "Minecraft Wiki"),
                    SearchHit("荣耀俱乐部：降低亮度与音量", "https://hihonor.com/c", "降低亮度", "2025-10-11", "荣耀俱乐部"),
                ),
            ),
            Block.Search(
                kind = "search",
                queries = listOf("散热背夹推荐"),
                status = SearchStatus.DONE,
                hits = listOf(
                    SearchHit("散热背夹实测", "https://bilibili.com/d", "降温 8 度", "2025-09-30", "哔哩哔哩"),
                ),
            ),
        )
        val msgs = listOf(
            Message(role = Role.USER, blocks = listOf(Block.Text("为什么我的骁龙8 Gen 3 手机玩 Minecraft 会发烫？"))),
            Message(role = Role.ASSISTANT, status = MessageStatus.STREAMING, blocks = blocks),
        )
        render { MessageList(messages = msgs, streaming = true, onRegenerate = {}) }
        capture("11-parallel-search")
    }
}
