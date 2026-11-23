package com.shenyu.chat.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.shenyu.chat.chat.ChatViewModel
import com.shenyu.chat.chat.SessionHeaderUi
import com.shenyu.chat.data.MessageStatus
import com.shenyu.chat.data.Role
import com.shenyu.chat.data.ThemeMode
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.base.DsToastHost
import com.shenyu.chat.ui.chatInput.ChatInput
import com.shenyu.chat.ui.session.MessageList
import com.shenyu.chat.ui.session.WelcomeScreen
import com.shenyu.chat.ui.settings.SettingsScreen
import com.shenyu.chat.ui.speech.rememberSpeechController
import com.shenyu.chat.ui.sider.SiderPanel
import com.shenyu.chat.ui.theme.DsSize
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.theme.ShenyuTheme
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun ShenyuApp(viewModel: ChatViewModel) {
    val settings by viewModel.settings.state.collectAsState()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    ShenyuTheme(darkTheme = dark) {
        val state by viewModel.state.collectAsState()
        var settingsOpen by remember { mutableStateOf(false) }
        // 语音通话页（官方 call_deep_seek）
        var callOpen by remember { mutableStateOf(false) }
        var voiceInputState by remember { mutableStateOf(com.shenyu.chat.ui.voice.VoiceInputUiState()) }
        // 草稿按会话存在内存里（官方 drafts.ts 同样只在本次页面存活）
        val drafts = remember { mutableStateMapOf<String, String>() }
        val speech = rememberSpeechController(
            client = viewModel.audioClient,
            settingsProvider = { viewModel.settingsSnapshot() },
        )
        val speechError = speech.errorMessage
        LaunchedEffect(speechError) {
            speechError?.let {
                viewModel.toast(it)
                speech.consumeError()
            }
        }

        Surface(color = DsTheme.colors.bgBase, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                com.shenyu.chat.ui.base.PushDrawer(
                    open = state.siderOpen,
                    onOpenChange = viewModel::setSiderOpen,
                    drawerContent = { drawerModifier ->
                    SiderPanel(
                        sessions = state.sessions,
                        activeId = state.activeId,
                        modifier = drawerModifier,
                        onOpenCall = {
                            viewModel.setSiderOpen(false)
                            callOpen = true
                        },
                        onSelect = { id -> viewModel.selectSession(id) },
                        onDelete = { id -> viewModel.deleteSession(id) },
                        onRename = { id, title -> viewModel.renameSession(id, title) },
                        onNewChat = { viewModel.newSession() },
                        onOpenSettings = {
                            viewModel.setSiderOpen(false)
                            settingsOpen = true
                        },
                        onSetPinned = { ids, pinned -> viewModel.setPinned(ids, pinned) },
                        onDeleteMany = { ids -> viewModel.deleteSessions(ids) },
                        bodyMatchIds = state.bodyMatchIds,
                        onSearchBody = { q -> viewModel.searchBody(q) },
                        onClose = { viewModel.setSiderOpen(false) },
                    )
                    },
                ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    ChatTopBar(
                        title = state.activeTitle.ifBlank { "深语" },
                        showTitle = !state.isEmptyWelcome,
                        autoSpeak = settings.autoSpeak,
                        onToggleAutoSpeak = {
                            val enabled = !settings.autoSpeak
                            viewModel.settings.update { it.copy(autoSpeak = enabled) }
                            if (!enabled) speech.stop()
                            viewModel.toast(if (enabled) "已开启自动朗读" else "已关闭自动朗读")
                        },
                        onOpenSider = { viewModel.setSiderOpen(true) },
                        onNewChat = { viewModel.newSession() },
                    )
                    // 官方：切换会话时停止朗读
                    LaunchedEffect(state.activeId) { speech.stop() }

                    // 自动朗读：只在「本次流式刚结束」时读一遍最后一条回答
                    var wasStreaming by remember { mutableStateOf(false) }
                    LaunchedEffect(state.streaming) {
                        if (wasStreaming && !state.streaming && settings.autoSpeak) {
                            val last = state.messages.lastOrNull { it.role == Role.ASSISTANT }
                            if (last != null &&
                                last.status != MessageStatus.STOPPED &&
                                last.plainText.isNotBlank()
                            ) {
                                speech.toggle(last.id, last.plainText)
                            }
                        }
                        wasStreaming = state.streaming
                    }

                    Box(Modifier.weight(1f)) {
                        if (state.isEmptyWelcome) {
                            WelcomeScreen(onPick = { prompt -> viewModel.send(prompt) })
                        } else {
                            // 字体大小：只放大消息区（正文/代码/表格），顶栏与输入框保持原样
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalDensity provides
                                    androidx.compose.ui.unit.Density(
                                        density = androidx.compose.ui.platform.LocalDensity.current.density,
                                        fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale *
                                            settings.fontScale,
                                    ),
                            ) {
                            MessageList(
                                messages = state.messages,
                                streaming = state.streaming,
                                onRegenerate = { viewModel.regenerate() },
                                onSpeak = { message -> speech.toggle(message.id, message.plainText) },
                                playingMessageId = speech.playingMessageId,
                                onSwitchVariant = { id, number -> viewModel.switchVariant(id, number) },
                                onEditMessage = { message, newText ->
                                    viewModel.editMessageAndResend(message.id, newText)
                                },
                                onEditMessageBranch = { message, newText ->
                                    viewModel.editMessageAndBranch(message.id, newText)
                                },
                                onContinue = { viewModel.continueGenerating() },
                                onFeedback = { id, value -> viewModel.setFeedback(id, value) },
                            )
                            }
                        }
                    }
                    ChatInput(
                        streaming = state.streaming,
                        hasMessages = state.messages.isNotEmpty(),
                        onOpenCall = { callOpen = true },
                        thinkingEnabled = settings.thinkingEnabled,
                        searchEnabled = settings.searchEnabled,
                        onToggleThinking = { enabled ->
                            viewModel.settings.update { it.copy(thinkingEnabled = enabled) }
                        },
                        onToggleSearch = { enabled ->
                            viewModel.settings.update { it.copy(searchEnabled = enabled) }
                        },
                        onSend = { text, attachments ->
                            // 官方：发送时先停止朗读
                            speech.stop()
                            viewModel.send(text, attachments)
                        },
                        onStop = { viewModel.stopGenerating() },
                        onTranscribe = { pcm, callback -> viewModel.transcribeVoice(pcm, callback) },
                        voiceContextKey = state.activeId,
                        onVoiceStateChange = { voiceInputState = it },
                        draftText = drafts[state.activeId.orEmpty()].orEmpty(),
                        onDraftChange = { drafts[state.activeId.orEmpty()] = it },
                    )
                }

                }

                com.shenyu.chat.ui.voice.VoiceRecordingOverlay(voiceInputState, Modifier.fillMaxSize())
                DsToastHost(message = state.toast, onDismiss = { viewModel.consumeToast() })
            }
        }

        if (callOpen) {
            com.shenyu.chat.ui.voice.CallScreen(
                streaming = state.streaming,
                answerText = state.messages.lastOrNull { it.role == com.shenyu.chat.data.Role.ASSISTANT }
                    ?.plainText.orEmpty(),
                onUserSaid = { text -> viewModel.send(text) },
                onTranscribe = { pcm, callback -> viewModel.transcribeVoice(pcm, callback) },
                onSpeak = { text -> speech.toggle("call", text) },
                onClose = {
                    speech.stop()
                    callOpen = false
                },
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                settings = settings,
                onClose = { settingsOpen = false },
                onUpdate = { transform -> viewModel.settings.update(transform) },
                onDeleteAll = {
                    viewModel.deleteAllSessions()
                    viewModel.toast("已删除全部对话")
                },
                onVerifyKey = { provider, callback -> viewModel.verifyApiKey(provider, callback) },
                onExport = { callback -> viewModel.exportHistory(callback) },
                onImport = { uri, callback -> viewModel.importHistory(uri, callback) },
                onImportUrl = { url, callback -> viewModel.importFromUrl(url, callback) },
                transfer = state.transfer,
            )
        }

        LaunchedEffect(state.initialized) {
            if (state.initialized && !settings.hasApiKey) {
                settingsOpen = true
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    showTitle: Boolean,
    autoSpeak: Boolean,
    onToggleAutoSpeak: () -> Unit,
    onOpenSider: () -> Unit,
    onNewChat: () -> Unit,
    onOpenCall: () -> Unit = {},
) {
    // Same-device header: keep icon centers at y=180px and the list origin at y=246px.
    // With a 108px status bar and 2.75px/dp this is a 50dp header (rounded).
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(start = 6.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(
            icon = DsIcons.Menu,
            contentDescription = "会话列表",
            onClick = onOpenSider,
            iconSize = 24.dp,
        )
        Text(
            text = if (showTitle) title else "",
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 12.dp)
                .offset { androidx.compose.ui.unit.IntOffset(-1, -1) },
            color = if (DsTheme.colors.isDark) DsTheme.colors.labelPrimary else Color(0xFF191B23),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        // 官方顶栏右侧：自动朗读开关（喇叭）+ 新建对话（⊕），首页也显示
        TopBarIconButton(
            icon = if (autoSpeak) com.shenyu.chat.ui.base.AutoTtsIcons.On else com.shenyu.chat.ui.base.AutoTtsIcons.Off,
            contentDescription = if (autoSpeak) "关闭自动朗读" else "开启自动朗读",
            iconSize = 24.dp,
            onClick = onToggleAutoSpeak,
        )
        // 官方两个图标之间留白更宽
        Spacer(Modifier.width(12.dp))
        TopBarIconButton(
            icon = com.shenyu.chat.ui.base.OfficialIcons.NewChatCircle,
            contentDescription = "新建对话",
            iconSize = 24.dp,
            onClick = onNewChat,
        )
    }
}

@Composable
private fun TopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) DsTheme.colors.selector else Color.Transparent,
        label = "topBarPress",
    )
    Box(
        Modifier
            .size(DsSize.controlL)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DsTheme.colors.labelPrimary,
            modifier = Modifier.size(iconSize).then(
                if (icon === com.shenyu.chat.ui.base.AutoTtsIcons.On || icon === com.shenyu.chat.ui.base.AutoTtsIcons.Off)
                    Modifier.offset { androidx.compose.ui.unit.IntOffset(0, -1) }
                else Modifier,
            ),
        )
    }
}

/** 供预览/测试使用的最小状态。 */
internal fun emptySessions(): List<SessionHeaderUi> = emptyList()
