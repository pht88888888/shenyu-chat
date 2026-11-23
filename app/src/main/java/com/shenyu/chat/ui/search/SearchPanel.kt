package com.shenyu.chat.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shenyu.chat.ui.base.DsDivider
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.delay

private const val ENTER_MS = 280
private const val EXIT_MS = 260

/**
 * 从底部滑出的「来源」面板。
 *
 * 用 [Dialog]（`usePlatformDefaultWidth = false`）承载全屏遮罩，这样无论从消息列表哪个位置
 * 唤起都能覆盖整屏，不必改动上层布局。行为：
 *  - 背景遮罩淡入、点击关闭；
 *  - 面板从底部滑入 / 滑出（280ms / 260ms）；
 *  - 顶部圆角 16dp、最大高度约屏幕 75%、内容 [LazyColumn] 可滚动；
 *  - 标题区「来源 · N 个网页 + 关闭按钮」，标题栏可上下拖动收起。
 */
@Composable
fun SearchPanel(
    visible: Boolean,
    sources: List<SourceItem>,
    onClose: () -> Unit,
    /** 从正文角标点进来时定位到第几条（0 起） */
    highlightIndex: Int? = null,
) {
    // 独立的窗台状态：退出动画期间 Dialog 仍需存在，动画结束后再移除。
    var windowShown by remember { mutableStateOf(visible) }
    var animateIn by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            windowShown = true
            // 先以 animateIn=false 组合一帧，再翻成 true，才能触发进入动画。
            animateIn = false
            withFrameNanos { }
            animateIn = true
        } else {
            animateIn = false
            delay(EXIT_MS.toLong())
            windowShown = false
        }
    }

    if (!windowShown) return

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            // ---- 遮罩 ----
            AnimatedVisibility(
                visible = animateIn,
                enter = fadeIn(tween(EXIT_MS)),
                exit = fadeOut(tween(EXIT_MS)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClose() },
                )
            }
            // ---- 面板 ----
            AnimatedVisibility(
                visible = animateIn,
                enter = slideInVertically(tween(ENTER_MS)) { it } + fadeIn(tween(ENTER_MS)),
                exit = slideOutVertically(tween(EXIT_MS)) { it } + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PanelContent(
                    sources = sources,
                    onClose = onClose,
                    visible = visible,
                    highlightIndex = highlightIndex,
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    sources: List<SourceItem>,
    onClose: () -> Unit,
    visible: Boolean = true,
    highlightIndex: Int? = null,
) {
    val colors = DsTheme.colors
    // 拖动收起：translationY（px）；超过阈值收起，否则回弹。
    var dragOffset by remember { mutableStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .graphicsLayer { translationY = dragOffset }
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(colors.bgBase),
    ) {
        // ---- 标题区（可拖动收起）----
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .pointerInput(Unit) {
                    val threshold = 80.dp.toPx()
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffset > threshold) onClose() else dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    ) { _, dy ->
                        dragOffset = (dragOffset + dy).coerceAtLeast(0f)
                    }
                }
                .padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "来源",
                color = colors.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${sources.size} 个网页",
                color = colors.labelTertiary,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            CloseButton(onClose)
        }
        DsDivider(color = colors.borderL2)

        // ---- 列表 ----
        val listState = rememberLazyListState()
        LaunchedEffect(visible, highlightIndex, sources.size) {
            if (visible && highlightIndex != null && highlightIndex in sources.indices) {
                listState.animateScrollToItem(highlightIndex)
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(sources, key = { _, item -> item.url }) { index, item ->
                SourceCard(item = item, highlighted = index == highlightIndex)
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DsIcons.Close,
            contentDescription = "关闭",
            tint = colors.labelTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
