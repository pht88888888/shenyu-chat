package com.shenyu.chat.ui.sider

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.shenyu.chat.ui.base.DsDivider
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.theme.DsTheme

/** Shares the bottom identity row's anchor for both avatar and overflow entry points. */
@Composable
internal fun AccountMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCall: () -> Unit,
    onNewChat: () -> Unit,
) {
    // Capture before entering the Popup: its Android root otherwise resets our density.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val root = LocalView.current.rootView
    val colors = DsTheme.colors
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val transition = updateTransition(visibility, label = "account-menu")
    val opacity by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 130 else 90) }, label = "opacity",
    ) { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 150 else 90) }, label = "scale",
    ) { if (it) 1f else 0.96f }

    if (visibility.currentState || visibility.targetState) {
        val margin = with(density) { 16.dp.roundToPx() }
        val inset = with(density) { 16.dp.roundToPx() }
        val gap = with(density) { 7.dp.roundToPx() }
        val position = remember(margin, inset, gap) { AccountMenuPositionProvider(margin, inset, gap) }
        val availableWidth = with(density) {
            ((root.width.takeIf { it > 0 } ?: (configuration.screenWidthDp * density.density).toInt()) - 2 * margin)
                .coerceAtLeast(1).toDp()
        }
        val availableHeight = with(density) {
            ((root.height.takeIf { it > 0 } ?: (configuration.screenHeightDp * density.density).toInt()) - 2 * margin)
                .coerceAtLeast(1).toDp()
        }
        Popup(
            popupPositionProvider = position,
            onDismissRequest = { if (expanded) onDismissRequest() },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = true,
            ),
        ) {
            CompositionLocalProvider(LocalDensity provides density) {
                // Keep shadows inside the transparent Popup window instead of clipping them.
                Box(Modifier.padding(16.dp)) {
                    Column(
                        Modifier.width(minOf(208.dp, availableWidth))
                            .graphicsLayer {
                                alpha = opacity
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 1f)
                            }
                            .shadow(12.dp, RoundedCornerShape(16.dp), clip = false)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.bgBase)
                            .heightIn(max = availableHeight)
                            .verticalScroll(rememberScrollState())
                            .semantics { contentDescription = "用户菜单卡片" },
                    ) {
                        AccountMenuAction("设置", DsIcons.Settings, expanded) {
                            onDismissRequest()
                            onOpenSettings()
                        }
                        DsDivider(color = colors.borderL2)
                        AccountMenuAction("语音通话", DsIcons.Phone, expanded) {
                            onDismissRequest()
                            onOpenCall()
                        }
                        DsDivider(color = colors.borderL2)
                        AccountMenuAction("新建对话", DsIcons.NewChat, expanded) {
                            onDismissRequest()
                            onNewChat()
                        }
                    }
                }
            }
        }
    }
}

/** Upward expansion, clamped using the complete window size including shadow margins. */
internal class AccountMenuPositionProvider(
    private val shadowMargin: Int,
    private val horizontalInset: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + horizontalInset - shadowMargin
        } else {
            anchorBounds.right - horizontalInset - popupContentSize.width + shadowMargin
        }
        val y = anchorBounds.top - gap - popupContentSize.height + shadowMargin
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

@Composable
private fun AccountMenuAction(title: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = DsTheme.colors
    val background by animateColorAsState(
        targetValue = if (pressed) colors.selector else Color.Transparent,
        animationSpec = tween(70), label = "account-menu-press",
    )
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.labelPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = colors.labelPrimary, fontSize = 17.sp, lineHeight = 26.sp)
    }
}
