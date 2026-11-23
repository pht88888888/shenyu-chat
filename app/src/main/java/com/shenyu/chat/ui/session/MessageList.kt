package com.shenyu.chat.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import com.shenyu.chat.ui.search.SearchPanel
import com.shenyu.chat.ui.search.SourceItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.data.Block
import com.shenyu.chat.data.Message
import com.shenyu.chat.data.MessageStatus
import com.shenyu.chat.data.Role
import com.shenyu.chat.data.SearchHit
import com.shenyu.chat.data.SearchStatus
import com.shenyu.chat.ui.base.DsBadge
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.base.DsLoadingDots
import com.shenyu.chat.ui.base.ImageThumb
import com.shenyu.chat.ui.base.SiteIcon
import com.shenyu.chat.ui.base.siteHostOf
import com.shenyu.chat.ui.markdown.MarkdownText
import com.shenyu.chat.ui.theme.DsSize
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 消息流。助手消息按「思考/搜索时间线 → 版本切换 → 正文 → 操作条」的顺序渲染。
 *
 * 滚动策略：默认贴住底部跟随流式输出；用户手指往上滑立刻停止跟随（可以自由翻阅历史），
 * 此时右下角浮出「回到最新」按钮，点一下恢复跟随。
 */
@Composable
fun MessageList(
    messages: List<Message>,
    streaming: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: (Message) -> Unit = {},
    playingMessageId: String? = null,
    onSwitchVariant: (messageId: String, targetNumber: Int) -> Unit = { _, _ -> },
    onEditMessage: (message: Message, newText: String) -> Unit = { _, _ -> },
    onEditMessageBranch: (message: Message, newText: String) -> Unit = { _, _ -> },
    onContinue: () -> Unit = {},
    onFeedback: (messageId: String, value: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val last = messages.lastOrNull()
    val lastLength = last?.plainText?.length ?: 0
    val lastThinking = last?.thinkingText?.length ?: 0

    // 是否跟随最新内容。用户一旦向上拖动就关掉，滑回底部再自动打开。
    var autoFollow by remember { mutableStateOf(true) }

    // 用户手指往下拖（available.y > 0）＝ 想往回看旧内容 → 立刻停止自动跟随。
    // 注意方向：手指往下拖时 nested scroll 给的是正 y，之前写成负值判断，导致
    // 「往上翻的时候还在自动往下跳」。
    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0.5f) {
                    autoFollow = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset.Zero
        }
    }

    // 滑回列表底部（且手指已离开）才恢复自动跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }
            .collect { (canForward, scrolling) ->
                if (!canForward && !scrolling) autoFollow = true
            }
    }

    // 新消息追加：直接定位到最后一条（不带动画，避免和手势抢焦点）
    LaunchedEffect(messages.size) {
        if (autoFollow && messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(messages.lastIndex)
            listState.scrollBy(60_000f)
        }
    }

    // 流式增长：只在已经贴底时按「实际还剩多少」补位移，避免每个 token 都大幅跳动
    LaunchedEffect(lastLength, lastThinking) {
        if (!autoFollow || messages.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (lastVisible.index < messages.lastIndex) return@LaunchedEffect
        val overflow = (lastVisible.offset + lastVisible.size) -
            (info.viewportEndOffset - info.afterContentPadding)
        if (overflow > 0) listState.scrollBy(overflow.toFloat())
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(nestedScroll),
            // Native density 2.75: user right inset=16dp; assistant adds its own 8/6dp inset.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 0.dp,
                bottom = 24.dp,
            ),
            // 官方条目之间没有统一 gap，间距由各条自身 padding 决定，这里交给各 Row
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                when (message.role) {
                    Role.USER -> UserMessageRow(
                        message = message,
                        isFirst = index == 0,
                        onEditMessage = onEditMessage,
                        onEditMessageBranch = onEditMessageBranch,
                    )

                    Role.ASSISTANT -> AssistantMessageRow(
                        message = message,
                        onContinue = onContinue,
                        isLast = message.id == last?.id,
                        streaming = streaming && message.id == last?.id,
                        onRegenerate = onRegenerate,
                        onSpeak = onSpeak,
                        playing = playingMessageId == message.id,
                        onSwitchVariant = onSwitchVariant,
                        onFeedback = onFeedback,
                        questionText = messages
                            .subList(0, index)
                            .lastOrNull { it.role == Role.USER }
                            ?.plainText
                            .orEmpty(),
                    )
                }
            }
        }

        // 不在底部时给一个回到底部的引导按钮
        androidx.compose.animation.AnimatedVisibility(
            visible = !autoFollow && messages.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = DsSize.gapL),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
        ) {
            Row(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(DsSize.radiusPill))
                    .background(DsTheme.colors.bgBase, RoundedCornerShape(DsSize.radiusPill))
                    .border(1.dp, DsTheme.colors.borderL2, RoundedCornerShape(DsSize.radiusPill))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        autoFollow = true
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.scrollToItem(messages.lastIndex)
                                listState.scrollBy(120_000f)
                            }
                        }
                    }
                    .padding(horizontal = DsSize.gapL, vertical = DsSize.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = DsIcons.ChevronDown,
                    contentDescription = null,
                    tint = DsTheme.colors.brandPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "回到最新" else "回到最新",
                    color = DsTheme.colors.labelSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * 用户消息（对照 UserMessage.tsx / .module.css）：
 * - 气泡右对齐，最大宽度 = 容器宽 - 88dp，圆角 22dp（右下6dp），内边距10/16dp，正文17sp/26sp。
 * - 附件排在气泡上方：图片 120dp（与文档混排时 64dp）圆角 16dp，非图片走文件卡片；
 *   间距 10dp、靠右对齐，下方留 8dp。
 * - 整条底部留白24dp；首条顶部留白24dp。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun UserMessageRow(
    message: Message,
    isFirst: Boolean,
    onEditMessage: (Message, String) -> Unit,
    onEditMessageBranch: (Message, String) -> Unit = { _, _ -> },
) {
    val colors = DsTheme.colors
    val hasDocument = message.attachments.any { !it.isImage }
    val imageSize = if (hasDocument) 64.dp else 120.dp

    // 编辑态：长按气泡进入，改完文字后「发送」= 删掉此问之后的消息并用新文字重新提问
    var editing by remember(message.id) { mutableStateOf(false) }
    // 长按弹出的操作菜单（对照官方：复制 / 修改 / 修改并创建分支）
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    // 本次编辑是否是「修改并创建分支」
    var branchMode by remember(message.id) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var draft by remember(message.id) { mutableStateOf(message.plainText) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            draft = message.plainText
            focusRequester.requestFocus()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = if (isFirst) 24.dp else 0.dp, bottom = 24.dp),
    ) {
        val bubbleMaxWidth = (maxWidth - 88.dp).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (message.attachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    message.attachments.forEach { attachment ->
                        if (attachment.isImage && attachment.localPath != null) {
                            ImageThumb(
                                uriString = attachment.localPath,
                                size = imageSize,
                                corner = 16.dp,
                            )
                        } else {
                            MessageFileCard(name = attachment.name, size = attachment.size)
                        }
                    }
                }
            }
            if (editing) {
                // 编辑态：圆角 22dp、selector 底、10/16dp 内边距的多行输入框 + 底部「取消 / 发送」
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(colors.selector)
                            .border(1.dp, colors.brandPrimary, RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            textStyle = TextStyle(
                                color = colors.labelPrimary,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            ),
                            cursorBrush = SolidColor(colors.brandPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions(),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditActionButton(
                            label = "取消",
                            primary = false,
                            enabled = true,
                            onClick = {
                                editing = false
                                branchMode = false
                            },
                        )
                        EditActionButton(
                            label = "发送",
                            primary = true,
                            enabled = draft.trim().isNotEmpty(),
                            onClick = {
                                val newText = draft.trim()
                                if (newText.isNotEmpty()) {
                                    if (branchMode) {
                                        onEditMessageBranch(message, newText)
                                    } else {
                                        onEditMessage(message, newText)
                                    }
                                    editing = false
                                    branchMode = false
                                }
                            },
                        )
                    }
                }
            } else if (message.plainText.isNotBlank()) {
                Box {
                    Box(
                        Modifier
                            .widthIn(max = bubbleMaxWidth)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true },
                            )
                            .background(colors.userBubble)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = message.plainText,
                            color = colors.userBubbleText,
                            fontSize = 17.sp,
                            lineHeight = 26.sp,
                            letterSpacing = 0.sp,
                        )
                    }
                    // 官方长按菜单：复制 / 修改 / 修改并创建分支
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("复制", color = colors.labelPrimary) },
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(message.plainText)
                                )
                                menuOpen = false
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("修改", color = colors.labelPrimary) },
                            onClick = {
                                draft = message.plainText
                                branchMode = false
                                editing = true
                                menuOpen = false
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("修改并创建分支", color = colors.labelPrimary) },
                            onClick = {
                                draft = message.plainText
                                branchMode = true
                                editing = true
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 编辑态操作按钮：胶囊形，主按钮 brand 填充、次按钮描边；禁用时降透明度。 */
@Composable
private fun EditActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(DsSize.radiusPill)
    val background = when {
        primary -> colors.brandPrimary
        pressed -> colors.interactiveHover
        else -> Color.Transparent
    }
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(background, shape)
            .border(1.dp, if (primary) Color.Transparent else colors.borderL2, shape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else colors.labelPrimary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AssistantMessageRow(
    message: Message,
    onContinue: () -> Unit = {},
    isLast: Boolean,
    streaming: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: (Message) -> Unit,
    playing: Boolean,
    onSwitchVariant: (messageId: String, targetNumber: Int) -> Unit,
    onFeedback: (messageId: String, value: Int) -> Unit = { _, _ -> },
    questionText: String = "",
) {
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    val shareScope = rememberCoroutineScope()
    val answerColor = if (DsTheme.colors.isDark) DsTheme.colors.labelPrimary else Color(0xFF191B23)
    // The native declaration is 0sp, but on this Android 10 renderer each body
    // codepoint advances exactly one physical pixel more (including mixed Latin/CJK).
    // Keep this measured renderer compatibility correction local to answer markdown;
    // never alter density, font size, thought labels, or insert forced line breaks.
    val answerTracking = with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toSp() }
    androidx.compose.material3.ProvideTextStyle(TextStyle(
        letterSpacing = answerTracking,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
        lineBreak = androidx.compose.ui.text.style.LineBreak(
            strategy = androidx.compose.ui.text.style.LineBreak.Strategy.HighQuality,
            strictness = androidx.compose.ui.text.style.LineBreak.Strictness.Strict,
            wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Default,
        ),
    )) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 6.dp, bottom = 16.dp),
    ) {
        val sources = remember(message.blocks) { collectSources(message.blocks) }
        var showSources by remember(message.id) { mutableStateOf(false) }
        var highlightSource by remember(message.id) { mutableStateOf<Int?>(null) }

        // 有序步骤序列 + 正文（最后一个工具块之后的文本）。
        val parts = remember(message.blocks) { buildReplyParts(message.blocks) }
        val hasTimeline = parts.steps.any { it is ReplyStep.Thinking || it is ReplyStep.Search }

        // 操作条显隐（官方 .actions 默认 opacity:0，仅在 hover/最后一条 .alwaysShow 显示）：
        // 移动端无 hover —— 最后一条始终显示；其它消息默认隐藏，点击正文切换。
        var actionsVisible by remember(message.id) { mutableStateOf(false) }
        val actionsShown = isLast || actionsVisible

        // 正文区：点击切换操作条显隐。用 pointerInput 的 tap 检测，内部链接/引用角标/代码复制
        // 会先消费点击事件，因此不会与它们冲突；文本选择由长按手势处理，也不受影响。
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (isLast) {
                        Modifier
                    } else {
                        Modifier.pointerInput(message.id) {
                            detectTapGestures { actionsVisible = !actionsVisible }
                        }
                    }
                ),
        ) {
            // 时间线保持在最上方
            if (hasTimeline) {
                androidx.compose.material3.ProvideTextStyle(TextStyle(letterSpacing = 0.sp)) {
                    ProcessTimeline(
                        blocks = message.blocks,
                        streaming = streaming,
                        onOpenSources = if (sources.isNotEmpty()) ({ showSources = true }) else null,
                        // 生成过程中先亮出助手标识，答完正文就不再占位
                        leadingAvatar = streaming && parts.answer.isBlank(),
                    )
                }
            }

            if (message.prefixText.isNotBlank()) {
                if (hasTimeline) {
                    Spacer(Modifier.height(DsSize.gapS))
                }
                MarkdownText(
                    markdown = message.prefixText,
                    fontSize = 17.sp,
                    lineHeight = 28.sp,
                    color = answerColor,
                    onLinkClick = null,
                )
                if (parts.answer.isNotBlank()) Spacer(Modifier.height(6.dp))
            }

            if (parts.answer.isNotBlank()) {
                if (hasTimeline) {
                    Spacer(Modifier.height(11.dp))
                }
                MarkdownText(
                    markdown = parts.answer,
                    // Paragraph engine 1.7 retains two physical pixels of leading
                    // compared with the device reference. Preserve line advances,
                    // removing only that measured leading from placement and height.
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height - 2).coerceAtLeast(0)) {
                            placeable.placeRelative(0, -2)
                        }
                    },
                    fontSize = 17.sp,
                    lineHeight = 28.sp,
                    color = answerColor,
                    onLinkClick = null,
                    onCitationClick = if (sources.isEmpty()) {
                        null
                    } else {
                        { index -> highlightSource = (index - 1).coerceAtLeast(0); showSources = true }
                    },
                )
            }

            if (streaming && message.blocks.isEmpty()) {
                DsLoadingDots()
            }

            if (streaming && message.blocks.isNotEmpty()) {
                Box(Modifier.padding(top = 6.dp)) {
                    DsLoadingDots()
                }
            }

            message.error?.let { error ->
                Spacer(Modifier.height(DsSize.gapS))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            DsTheme.colors.stateError.copy(alpha = 0.08f),
                            RoundedCornerShape(DsSize.radiusM),
                        )
                        .padding(DsSize.gapM),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = DsIcons.Error,
                        contentDescription = null,
                        tint = DsTheme.colors.stateError,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(DsSize.gapS))
                    Text(
                        text = error.message,
                        color = DsTheme.colors.stateError,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            if (message.status == MessageStatus.STOPPED) {
                Spacer(Modifier.height(DsSize.gapS))
                DsBadge("已停止")
            }
        }

        if (showSources) {
            SearchPanel(
                visible = showSources,
                sources = sources,
                onClose = {
                    showSources = false
                    highlightSource = null
                },
                highlightIndex = highlightSource,
            )
        }

        // 「已阅读 N 个网页」摘要行（官方 .summary）：正文之后、底部行之前。
        if (!streaming && sources.isNotEmpty()) {
            SourcesSummaryRow(
                sources = sources,
                onOpenSources = {
                    highlightSource = null
                    showSources = true
                },
            )
        }

        // 底部行（官方 .bottom）：版本切换 · 复制 · 朗读 · 重新生成 · 继续生成。
        if (!streaming) {
            AssistantBottomRow(
                variantNumber = message.variantNumber,
                variantTotal = message.variantTotal,
                onPrevious = { onSwitchVariant(message.id, message.variantNumber - 1) },
                onNext = { onSwitchVariant(message.id, message.variantNumber + 1) },
                copyText = message.plainText,
                playing = playing,
                actionsVisible = actionsShown,
                canContinue = isLast &&
                    message.status == MessageStatus.STOPPED &&
                    message.plainText.isNotBlank(),
                onSpeak = { onSpeak(message) },
                onRegenerate = onRegenerate,
                onContinue = onContinue,
                feedback = message.feedback,
                onFeedback = { value -> onFeedback(message.id, value) },
                onShareImage = {
                    shareScope.launch {
                        runCatching {
                            // 官方是重新排版成一张卡片，而不是截屏
                            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.shenyu.chat.ui.share.ShareCardRenderer.render(
                                    context = shareContext,
                                    question = questionText,
                                    answer = message.plainText,
                                )
                            }
                            com.shenyu.chat.ui.share.ShareImageStore.save(shareContext, bitmap).getOrThrow()
                        }.onSuccess {
                            android.widget.Toast.makeText(
                                shareContext,
                                "已保存到相册",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            android.widget.Toast.makeText(
                                shareContext,
                                "保存失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }
    }
}

/**
 * 「已阅读 N 个网页」摘要行（对照 AssistantMessage.module.css 的 .summary + SiteThumbnails）：
 * 左侧 3~5 个 14dp 站点小图标（间距 2dp），右侧 12sp/18sp labelTertiary 文案；
 * 整行可点，点击调用 onOpenSources 打开来源面板。padding top=8dp / bottom=4dp。
 */
@Composable
private fun SourcesSummaryRow(
    sources: List<SourceItem>,
    onOpenSources: () -> Unit,
) {
    val colors = DsTheme.colors
    val count = sources.size
    // 按 host 去重，优先用命中里的 siteIcon，最多取 5 个。
    val thumbs = remember(sources) {
        sources.mapNotNull { item ->
            val host = item.host.ifBlank { siteHostOf(item.url) }
            host.takeIf { it.isNotBlank() }?.let { it to item.siteIcon }
        }.distinctBy { it.first }.take(5)
    }
    Row(
        Modifier
            .padding(top = 16.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(DsSize.radiusPill))
            .background(colors.bgBase)
            .border(1.dp, colors.borderL2, RoundedCornerShape(DsSize.radiusPill))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenSources,
            )
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumbs.forEachIndexed { index, (host, icon) ->
            if (index > 0) Spacer(Modifier.width(2.dp))
            SiteIcon(
                host = host,
                size = 14.dp,
                iconUrl = icon.takeIf { it.isNotBlank() },
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count 个网页",
            color = colors.labelPrimary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * 助手消息底部行（对照 AssistantMessage.module.css 的 .bottom / .actions 与
 * BranchSwitcher / CopyButton / ReadAloudButton / IconButton）：
 * - 原生动作区相对正文左移8dp，20dp官方图标、40×48dp热区，重新生成靠右。
 * - 顺序：版本切换（如有）·复制·喜欢·不喜欢·朗读·分享；重新生成靠右。
 * - ②③④ 属于官方 .actions：默认隐藏，最后一条或点击正文后显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantBottomRow(
    variantNumber: Int,
    variantTotal: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    copyText: String,
    playing: Boolean,
    actionsVisible: Boolean,
    canContinue: Boolean,
    onSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    feedback: Int = 0,
    onFeedback: (Int) -> Unit = {},
    onShareImage: () -> Unit = {},
) {
    var dislikeSheet by remember { mutableStateOf(false) }
    if (dislikeSheet) {
        DislikeFeedbackDialog(onDismiss = { dislikeSheet = false })
    }
    Column(Modifier.fillMaxWidth()) {
        // Native footer: icons are 20dp in 40×48dp touch targets, with retry at the far end.
        // The content column starts at 24dp; a -8dp offset centers Copy at x36dp.
        Row(
            Modifier
                .layout { measurable, constraints ->
                    val extra = 16.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
                    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(0, 0) }
                }
                .fillMaxWidth().offset(x = (-8).dp).padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ① 版本切换（仅多版本时出现）
            if (variantTotal > 1) {
                BranchSwitcherRow(
                    variantNumber = variantNumber,
                    variantTotal = variantTotal,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
            }
            // ②③④ 官方 .actions 组：复制 / 朗读 / 重新生成，受显隐控制。
            AnimatedVisibility(
                visible = actionsVisible,
                modifier = Modifier.weight(1f),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    // Native footer glyph centers are 111px apart at 2.75 density;
                    // 40dp targets alone accumulate a 1px left drift per action.
                    horizontalArrangement = Arrangement.spacedBy(0.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CopyActionButton(text = copyText)
                    FeedbackActionButton(up = true, active = feedback == 1, onClick = { onFeedback(1) })
                    FeedbackActionButton(
                        up = false,
                        active = feedback == -1,
                        onClick = {
                            onFeedback(-1)
                            // 官方：点「不喜欢」后追问原因（message_press_dislike → 反馈页）
                            if (feedback != -1) dislikeSheet = true
                        },
                    )
                    // 官方顺序：复制 · 赞 · 踩 · 朗读 · 分享 · 重新生成
                    ReadAloudAction(playing = playing, onClick = onSpeak)
                    MessageIconButton(
                        icon = com.shenyu.chat.ui.base.OfficialIcons.Share,
                        contentDescription = "分享图片",
                        onClick = onShareImage,
                    )
                    Spacer(Modifier.weight(1f))
                    MessageIconButton(
                        icon = com.shenyu.chat.ui.base.OfficialIcons.Refresh,
                        contentDescription = "重新生成",
                        onClick = onRegenerate,
                        onLongClick = onContinue,
                    )
                }
            }
        }
        // 中断回复仍保留真实的继续生成入口；完整回复不多出官方没有的胶囊。
        if (canContinue) {
            ContinueGenerateButton(onClick = onContinue)
        }
    }
}

/** 「继续生成」按钮（官方 .continueButton：outlined / size=s）：描边胶囊，按下叠底色。 */
@Composable
private fun ContinueGenerateButton(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        if (pressed) colors.borderL2 else Color.Transparent,
        label = "continueBg",
    )
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(DsSize.radiusPill))
            .background(bg)
            .border(1.dp, colors.borderL2, RoundedCornerShape(DsSize.radiusPill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "继续生成",
            color = colors.labelSecondary,
            fontSize = 13.sp,
        )
    }
}

/** 复制按钮：成功后图标切对勾 + 显示“已复制”约 2 秒（对照 CopyButton.tsx）。 */
@Composable
private fun CopyActionButton(text: String) {
    // Native Copy: 40×48dp touch target, 20dp glyph; copied checkmark remains for 1 second.
    val colors = DsTheme.colors
    val idleTint = if (colors.isDark) colors.labelTertiary else Color(0xFF8E9094)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000L)
            copied = false
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        Modifier
            .width(40.dp).height(48.dp)
            .clip(RoundedCornerShape(DsSize.radiusPill))
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                if (copied) return@clickable
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) DsIcons.Check else com.shenyu.chat.ui.base.OfficialIcons.Copy,
            contentDescription = if (copied) "已复制" else "复制",
            tint = if (copied) colors.brandPrimary else idleTint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 原生40×48dp图标热区，20dp图标；按下叠hover底色，支持真实长按动作。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val idleTint = if (colors.isDark) colors.labelTertiary else Color(0xFF8E9094)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .width(40.dp).height(48.dp)
            .clip(CircleShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = idleTint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 喜欢 / 不喜欢按钮（官方 like、dislike）。
 * 选中后图标切成实心 + 品牌色，并带一次回弹；未选中是灰色描边。
 */
@Composable
private fun FeedbackActionButton(
    up: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val idleTint = if (colors.isDark) colors.labelTertiary else Color(0xFF8E9094)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "feedbackScale",
    )
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) colors.brandPrimary else idleTint,
        label = "feedbackTint",
    )
    val icon = when {
        up && active -> DsIcons.ThumbUpFill
        up -> DsIcons.ThumbUp
        active -> DsIcons.ThumbDownFill
        else -> DsIcons.ThumbDown
    }
    Box(
        Modifier
            .width(40.dp).height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (up) {
                if (active) "取消喜欢" else "喜欢"
            } else {
                if (active) "取消不喜欢" else "不喜欢"
            },
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 朗读按钮：未朗读显示喇叭；朗读中切换成跳动波形（对照 ReadAloudButton.tsx）。 */@Composable
private fun ReadAloudAction(playing: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val idleTint = if (colors.isDark) colors.labelTertiary else Color(0xFF8E9094)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .width(40.dp).height(48.dp)
            .clip(CircleShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            ReadingBars()
        } else {
            Icon(
                imageVector = DsIcons.Speaker,
                contentDescription = "朗读",
                tint = idleTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 版本切换器（对照 BranchSwitcher.tsx / .module.css）：
 * 一行 28dp、间距 2dp、左右各一个 14dp chevron（左箭头旋转 180°）、中间 “2 / 3” 15sp。
 * 只有存在多个版本（variantTotal > 1）时由调用方渲染；到头的一端禁用（labelDimmed，忽略点击）。
 */
@Composable
private fun BranchSwitcherRow(
    variantNumber: Int,
    variantTotal: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchArrowButton(
            enabled = variantNumber > 1,
            rotated = true,
            contentDescription = "上一版本",
            onClick = onPrevious,
        )
        Text(
            text = "$variantNumber / $variantTotal",
            color = colors.labelTertiary,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        BranchArrowButton(
            enabled = variantNumber < variantTotal,
            rotated = false,
            contentDescription = "下一版本",
            onClick = onNext,
        )
    }
}

@Composable
private fun BranchArrowButton(
    enabled: Boolean,
    rotated: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (enabled) colors.labelTertiary else colors.labelDimmed
    Box(
        Modifier
            .size(DsSize.controlXs)
            .clip(CircleShape)
            .background(if (pressed && enabled) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DsIcons.ChevronRight,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = if (rotated) 180f else 0f },
        )
    }
}

/** 回复视图里的一个步骤（对照官方 buildReplyView 的 Step）。 */
private sealed interface ReplyStep {
    /** 思考：相邻 thinking 合并为一步，多段 markdown；seconds 为各段用时之和。 */
    data class Thinking(
        val paragraphs: List<String>,
        val seconds: Int? = null,
        val done: Boolean = true,
    ) : ReplyStep

    /** 搜索 / 深度搜索 / 图片搜索 / 深度图片搜索 / 阅读网页。 */
    data class Search(
        val kind: String,
        val queries: List<String>,
        val status: SearchStatus,
        val hits: List<SearchHit>,
    ) : ReplyStep

    /** 工具调用之间的说明文字（preamble），不进正文。 */
    data class Preamble(val markdown: String) : ReplyStep
}

/** 拆出的步骤序列 + 正文（最后一个工具块之后的文本）。 */
private data class ReplyParts(val steps: List<ReplyStep>, val answer: String)

/**
 * 对照官方 buildReplyView 构建有序步骤序列：
 * - 遍历 blocks，相邻 thinking 合并为同一步骤（段间空行），出现任何非 thinking 块就断开；
 * - 每个 Search 单独一步，保持出现顺序；
 * - 工具块之间/之前的 Text 累积成 preamble 步骤；最后一个工具块之后仍累积未 flush 的 Text 才是正文。
 *
 * 注：官方还有 `text.isTemplate` 过滤，本项目 Block.Text 无该字段，无法跳过模板文字。
 */
private fun buildReplyParts(blocks: List<Block>): ReplyParts {
    val steps = ArrayList<ReplyStep>()
    val pending = StringBuilder()

    fun flushPreamble() {
        val md = pending.toString().trim()
        if (md.isNotEmpty()) steps += ReplyStep.Preamble(md)
        pending.setLength(0)
    }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is Block.Text -> {
                if (pending.isNotEmpty()) pending.append("\n\n")
                pending.append(block.text)
            }

            is Block.Thinking -> {
                if (block.text.isNotBlank()) {
                    flushPreamble()
                    // 相邻 thinking（紧挨着的上一块也是 thinking）合并成同一步骤。
                    val adjacent = index > 0 && blocks[index - 1] is Block.Thinking
                    val last = steps.lastOrNull()
                    if (adjacent && last is ReplyStep.Thinking) {
                        val prevSeconds = last.seconds
                        val curSeconds = block.seconds
                        steps[steps.lastIndex] = last.copy(
                            paragraphs = last.paragraphs + block.text,
                            seconds = if (prevSeconds != null && curSeconds != null) {
                                prevSeconds + curSeconds
                            } else {
                                null
                            },
                            done = last.done && block.done,
                        )
                    } else {
                        steps += ReplyStep.Thinking(
                            paragraphs = listOf(block.text),
                            seconds = block.seconds,
                            done = block.done,
                        )
                    }
                }
            }

            is Block.Search -> {
                flushPreamble()
                steps += ReplyStep.Search(
                    kind = block.kind,
                    queries = block.queries,
                    status = block.status,
                    hits = block.hits,
                )
            }
        }
    }

    val answer = pending.toString().trim()
    return ReplyParts(steps = steps, answer = answer)
}

/** 搜索种类对应的中文动词（read_link 用「阅读网页」）。 */
private fun searchVerb(kind: String): String = when (kind) {
    "read_link" -> "阅读网页"
    "deep_search" -> "深度搜索"
    "image_search" -> "图片搜索"
    "deep_image_search" -> "深度图片搜索"
    else -> "搜索"
}

/** 进行中标题文案，按种类区分。 */
private fun searchRunningTitle(kind: String): String = when {
    kind == "read_link" -> "正在阅读"
    kind.contains("image") -> "图像搜索中..."
    else -> "正在搜索"
}

@Composable
private fun ProcessTimeline(
    blocks: List<Block>,
    streaming: Boolean,
    onOpenSources: (() -> Unit)?,
    leadingAvatar: Boolean = false,
) {
    val parts = remember(blocks) { buildReplyParts(blocks) }
    val steps = parts.steps
    if (steps.isEmpty() || steps.none { it is ReplyStep.Thinking || it is ReplyStep.Search }) return

    val thoughtColor = if (DsTheme.colors.isDark) DsTheme.colors.labelTertiary else Color(0xFF7D7F85)
    val hasAnswer = parts.answer.isNotBlank()
    val last = steps.last()

    // 流式进行中的判断：最后一个步骤是（未完成的）思考 / 运行中的搜索。
    // 任意一个搜索步骤还在跑（并行搜索时最后一步可能已经完成）
    val runningSearch = steps.filterIsInstance<ReplyStep.Search>()
        .firstOrNull { it.status == SearchStatus.RUNNING }
    val showThinkingProgress = streaming && !hasAnswer && last is ReplyStep.Thinking
    val showSearchProgress = streaming && !hasAnswer && runningSearch != null
    val inProgress = streaming && (
        (last is ReplyStep.Thinking && !last.done) || runningSearch != null
        )

    // 用户手动点开/收起过就接管，不再自动收起。
    var userOverrode by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(inProgress) }
    LaunchedEffect(inProgress) {
        if (!userOverrode) expanded = inProgress
    }

    // 完成态统计：基于新 steps。
    val thinkingSteps = steps.filterIsInstance<ReplyStep.Thinking>()
    val searchSteps = steps.filterIsInstance<ReplyStep.Search>()
    val allHits = searchSteps.flatMap { it.hits }
    val pageCount = allHits.map { it.url.trim() }.filter { it.isNotBlank() }.distinct().size
    val totalSeconds: Int? = if (thinkingSteps.isEmpty()) {
        null
    } else {
        var total: Int? = 0
        for (step in thinkingSteps) {
            val s = step.seconds
            total = if (total != null && s != null) total + s else null
        }
        total?.let { maxOf(1, it) }
    }
    // favicon 行：按 host 去重取前 5，优先用命中里的 siteIcon。
    val favicons: List<Pair<String, String>> = remember(blocks) {
        allHits.mapNotNull { hit ->
            siteHostOf(hit.url).takeIf { it.isNotBlank() }?.let { it to hit.siteIcon }
        }.distinctBy { it.first }.take(5)
    }

    Column(Modifier.fillMaxWidth()) {
        // ---- 标题行（可点击展开/收起） ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    userOverrode = true
                    expanded = !expanded
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingAvatar) {
                AssistantAvatar()
                Spacer(Modifier.width(8.dp))
            }
            when {
                showThinkingProgress -> {
                    Text(
                        text = "正在思考",
                        color = thoughtColor,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        letterSpacing = 0.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    DsLoadingDots()
                }

                showSearchProgress -> {
                    Text(
                        text = searchRunningTitle(runningSearch?.kind ?: "search"),
                        color = thoughtColor,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        letterSpacing = 0.sp,
                    )
                }

                else -> {
                    // 官方 getAreaTitle：完成态只说「已思考（用时 N 秒）」（messageThinkDuration）
                    Text(
                        text = if (totalSeconds != null) "已思考（用时 $totalSeconds 秒）" else "已思考",
                        color = thoughtColor,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        letterSpacing = 0.sp,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            RotatingChevron(expanded = expanded, tint = thoughtColor, size = 20.dp, collapsedRight = true)
        }

        // ---- 展开区：按出现顺序的时间线 ----
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            TimelineSteps(steps = steps)
        }
    }
}

/** 助手标识：生成过程中出现在时间线标题左侧的小圆标（品牌色底 + 白色「深」）。 */
@Composable
private fun AssistantAvatar(size: Dp = 22.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(DsTheme.colors.brandPrimary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "深",
            color = Color.White,
            fontSize = (size.value * 0.55f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 时间线里的一行：单步，或合并后的同类搜索组。 */
private sealed interface TimelineItem {
    data class Single(val step: ReplyStep) : TimelineItem
    data class Searches(val group: SearchGroup) : TimelineItem
}

/** 合并后的搜索组：并行搜索的多个 query/命中合成一行展示。 */
private class SearchGroup(
    val kind: String,
    val steps: List<ReplyStep.Search>,
) {
    val hits get() = steps.flatMap { it.hits }.distinctBy { it.url }
    val queries get() = steps.flatMap { it.queries }.distinct()
    val running get() = steps.any { it.status == SearchStatus.RUNNING }
    val failed get() = steps.all { it.status == SearchStatus.FAILED }
    val readLink get() = kind == "read_link"
}

private fun stepCategory(step: ReplyStep): String = when (step) {
    is ReplyStep.Search -> if (step.kind == "read_link") "open" else "search"
    is ReplyStep.Thinking -> "think"
    is ReplyStep.Preamble -> "text"
}

/** 把连续的「搜索 / 打开网页」步骤各自并成一行；其它步骤原样保留。 */
private fun groupTimelineItems(steps: List<ReplyStep>): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(steps.size)
    var buffer = ArrayList<ReplyStep.Search>()
    var bufferKind = ""
    fun flush() {
        if (buffer.isEmpty()) return
        out += TimelineItem.Searches(SearchGroup(bufferKind, buffer.toList()))
        buffer = ArrayList()
        bufferKind = ""
    }
    steps.forEach { step ->
        val category = stepCategory(step)
        if (step is ReplyStep.Search && (category == "search" || category == "open")) {
            if (buffer.isNotEmpty() && bufferKind != category) flush()
            bufferKind = category
            buffer += step
        } else {
            flush()
            out += TimelineItem.Single(step)
        }
    }
    flush()
    return out
}

@Composable
private fun TimelineSteps(steps: List<ReplyStep>) {
    val lineColor = DsTheme.colors.labelTertiary.copy(alpha = 0.28f)
    val dotColor = DsTheme.colors.labelTertiary.copy(alpha = 0.55f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .drawBehind {
                val cx = 3.dp.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(
                    color = dotColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(cx, 6.dp.toPx()),
                )
            }
            .padding(start = 14.dp),
    ) {
        // 连续的同族搜索步骤合成一行（并行搜索不再刷出一长串行）
        val items = remember(steps) { groupTimelineItems(steps) }
        items.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(DsSize.gapS))
            when (item) {
                is TimelineItem.Single -> when (val step = item.step) {
                    is ReplyStep.Thinking -> TimelineThinking(step)
                    is ReplyStep.Search -> TimelineSearchGroup(SearchGroup(step.kind, listOf(step)))
                    is ReplyStep.Preamble -> TimelinePreamble(step)
                }
                is TimelineItem.Searches -> TimelineSearchGroup(item.group)
            }
        }
    }
}

/** 思考内容：合并后的多段 markdown 整体展示，最大高度 320dp 内部滚动。 */
@Composable
private fun TimelineThinking(step: ReplyStep.Thinking) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        MarkdownText(
            markdown = step.paragraphs.joinToString("\n\n"),
            color = DsTheme.colors.labelSecondary,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            onLinkClick = null,
        )
    }
}

/** 工具之间的说明文字：13sp、labelSecondary、默认 3 行折叠，点击展开/收起。 */
@Composable
private fun TimelinePreamble(step: ReplyStep.Preamble) {
    var expanded by remember(step) { mutableStateOf(false) }
    Text(
        text = step.markdown,
        color = DsTheme.colors.labelSecondary,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
    )
}

/**
 * 搜索/浏览步骤一行（合并组）：图标 + 文案；完成且有命中时可点击展开结果。
 * 文案对照官方 i18n：进行中是「搜索 <query>」（多 query 每 2 秒轮播）/
 * 「正在阅读」；完成是「已阅读 N 个网页」/「搜索到 N 个网页」/「浏览 N 个页面」。
 */
@Composable
private fun TimelineSearchGroup(group: SearchGroup) {
    val readLink = group.readLink
    val hits = group.hits
    val queries = group.queries
    val running = group.running
    val failed = group.failed
    var expanded by remember(group) { mutableStateOf(false) }

    // 多个并行 query 时每 2 秒轮播展示其中一个
    var tick by remember(group) { mutableIntStateOf(0) }
    LaunchedEffect(group, running, queries.size) {
        if (!running || queries.size <= 1) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2000)
            tick = (tick + 1) % queries.size
        }
    }

    val label = when {
        running -> when {
            readLink -> "正在阅读"
            group.kind.contains("image") -> "图像搜索中..."
            queries.isNotEmpty() -> "搜索 ${queries[tick % queries.size]}"
            else -> "正在搜索"
        }
        failed -> if (readLink) "浏览页面失败" else "联网搜索暂不可用"
        readLink -> if (hits.isEmpty()) "浏览页面失败" else "浏览 ${hits.size} 个页面"
        hits.isEmpty() -> "未搜索到相关内容"
        group.kind.startsWith("deep") -> "搜索到 ${hits.size} 个网页"
        else -> "已阅读 ${hits.size} 个网页"
    }

    val canExpand = !running && hits.isNotEmpty()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (canExpand) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = !expanded }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (readLink) DsIcons.Globe else DsIcons.Search,
                contentDescription = null,
                tint = DsTheme.colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = DsTheme.colors.labelTertiary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 官方 SearchRow：文案后面跟最多 3 个结果站点图标
            val thumbs = remember(hits) {
                hits.mapNotNull { hit ->
                    siteHostOf(hit.url).takeIf { it.isNotBlank() }?.let { it to hit.siteIcon }
                }.distinctBy { it.first }.take(3)
            }
            if (thumbs.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    thumbs.forEachIndexed { index, (host, icon) ->
                        if (index > 0) Spacer(Modifier.width(2.dp))
                        SiteIcon(host = host, size = 14.dp, iconUrl = icon.takeIf { it.isNotBlank() })
                    }
                }
            }
            if (canExpand) {
                Spacer(Modifier.width(4.dp))
                RotatingChevron(expanded = expanded, tint = DsTheme.colors.labelTertiary)
            }
        }
        if (canExpand && expanded) {
            Spacer(Modifier.height(4.dp))
            hits.take(6).forEach { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(DsTheme.colors.selector, RoundedCornerShape(DsSize.radiusM))
                        .padding(DsSize.gapM),
                ) {
                    Text(
                        text = hit.title.ifBlank { hit.url },
                        color = DsTheme.colors.labelPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W500,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hit.snippet.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = hit.snippet,
                            color = DsTheme.colors.labelTertiary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RotatingChevron(
    expanded: Boolean,
    tint: Color,
    size: Dp = 14.dp,
    collapsedRight: Boolean = false,
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsedRight) { if (expanded) 90f else 0f } else { if (expanded) 180f else 0f },
        label = "timelineChevronRotation",
    )
    Icon(
        imageVector = if (collapsedRight) com.shenyu.chat.ui.base.OfficialIcons.ChevronRight else DsIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
    )
}

/** 汇总一条回答里出现的所有来源（搜索命中 + 正文引用），按 url 去重。 */
private fun collectSources(blocks: List<Block>): List<SourceItem> {
    val seen = LinkedHashMap<String, SourceItem>()
    blocks.forEach { block ->
        when (block) {
            is Block.Search -> block.hits.forEach { hit ->
                val url = hit.url.trim()
                if (url.isNotEmpty() && !seen.containsKey(url)) {
                    seen[url] = SourceItem(
                        title = hit.title.ifBlank { url },
                        url = url,
                        snippet = hit.snippet,
                        date = hit.date,
                        host = hostOf(url),
                        siteName = hit.siteName,
                        siteIcon = hit.siteIcon,
                    )
                }
            }

            is Block.Text -> block.citations.forEach { citation ->
                val url = citation.url.trim()
                if (url.isNotEmpty() && !seen.containsKey(url)) {
                    seen[url] = SourceItem(
                        title = citation.title.ifBlank { url },
                        url = url,
                        host = hostOf(url),
                        index = citation.index,
                    )
                }
            }

            else -> Unit
        }
    }
    return seen.values.toList()
}

private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host?.removePrefix("www.") ?: ""
}.getOrDefault("")

/** 不喜欢原因（官方反馈页：没有帮助 / 虚假信息 / 有害、不安全 / 其他 + 文本框）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DislikeFeedbackDialog(onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val options = listOf("没有帮助", "虚假信息", "有害或不安全", "其他")
    var picked by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我们想知道你对此回答不满意的原因", color = colors.labelPrimary, fontSize = 16.sp) },
        text = {
            Column {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        val active = option in picked
                        Box(
                            Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(100))
                                .background(
                                    if (active) colors.brandPrimary.copy(alpha = 0.14f) else colors.bgLayer2
                                )
                                .border(
                                    1.dp,
                                    if (active) colors.brandPrimary else colors.borderL2,
                                    androidx.compose.foundation.shape.RoundedCornerShape(100),
                                )
                                .clickable {
                                    picked = if (active) picked - option else picked + option
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = option,
                                color = if (active) colors.brandText else colors.labelSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BasicText(
                    text = note,
                    onTextChange = { note = it },
                    placeholder = "你认为更好的回答是什么？",
                    colors = colors,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                android.widget.Toast.makeText(context, "反馈成功", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("提交", color = colors.brandText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.labelTertiary) }
        },
    )
}

/** 反馈输入框（简单多行）。 */
@Composable
private fun BasicText(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    colors: com.shenyu.chat.ui.theme.DsColors,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(colors.bgLayer2)
            .padding(12.dp),
    ) {
        if (text.isEmpty()) {
            Text(placeholder, color = colors.labelTertiary, fontSize = 13.sp)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = colors.labelPrimary,
                fontSize = 13.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brandPrimary),
        )
    }
}
