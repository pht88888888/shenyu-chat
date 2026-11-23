package com.shenyu.chat.ui.base

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 胶囊选择器，1:1 复刻官方 `components/base/Select.tsx`：
 *  - 触发器高 36dp、圆角 18dp、左内边距 14dp、右内边距 42dp（箭头贴右 14dp），14sp/22sp；
 *  - 打开时正文色变 `labelTertiary`；
 *  - 菜单：padding 4dp、圆角 12dp、最宽 280dp、最高 320dp 内滚，`bgLayer3` 底 + 1dp 描边 + 12dp 投影；
 *  - 选项高 ≥40dp、左右内边距 10/4dp、圆角 10dp、14sp，选中项右侧打勾。
 */

/** 锚定在触发器右下方的定位器：优先向下展开，空间不足时翻到上方。 */
private class AnchoredEndPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height || above < 0) {
            below.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        } else {
            above
        }
        return IntOffset(x, y)
    }
}

@Composable
fun DsSelect(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 90.dp,
) {
    val colors = DsTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second
    val arrowRotation by animateFloatAsState(if (open) 180f else 0f, label = "selectArrow")
    val density = LocalDensity.current
    val gapPx = with(density) { 4.dp.roundToPx() }

    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .widthIn(min = minWidth)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.selector)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                ) { open = !open }
                .padding(start = 14.dp, end = 42.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current ?: placeholder,
                color = when {
                    !enabled -> colors.labelCaption
                    open -> colors.labelTertiary
                    current != null -> colors.labelPrimary
                    else -> colors.labelTertiary
                },
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = DsIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .size(14.dp)
                .rotate(arrowRotation),
            tint = colors.labelSecondary,
        )

        if (open) {
            Popup(
                popupPositionProvider = remember(gapPx) {
                    AnchoredEndPositionProvider(gapPx = gapPx)
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .widthIn(min = 124.dp, max = 280.dp)
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bgLayer3)
                        .border(1.dp, colors.borderL2, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    options.forEach { (key, label) ->
                        val selected = key == value
                        val rowInteraction = remember { MutableInteractionSource() }
                        val isPressed by rowInteraction.collectIsPressedAsState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isPressed) colors.interactiveActive else Color.Transparent
                                )
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = null,
                                ) {
                                    open = false
                                    onSelect(key)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                color = colors.labelPrimary,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = DsIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.brandPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
