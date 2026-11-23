package com.shenyu.chat.ui.base

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.R
import com.shenyu.chat.ui.theme.DsSize
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.delay

/**
 * 基础 UI 组件库（自绘，不依赖 material3 的 Button/Switch 默认皮肤）。
 *
 * 统一规则：
 * - 颜色全部走 DsTheme.colors，深浅色自适应；只有「品牌色上的前景色」用
 *   MaterialTheme.colorScheme.onPrimary，以及透明叠加用 Color.Transparent。
 * - 点击态统一用 interactionSource + collectIsPressedAsState，按下时叠 colors.interactiveHover。
 * - 可点区域不小于 40.dp；圆角、间距一律取 DsSize。
 */

private val RowShape = RoundedCornerShape(DsSize.radiusM)
private val CardShape = RoundedCornerShape(DsSize.radiusL)
private val PillShape = RoundedCornerShape(DsSize.radiusPill)

/** 主按钮：品牌色胶囊，加载中转圈点并禁用点击。 */
@Composable
fun DsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = DsTheme.colors
    val onBrand = MaterialTheme.colorScheme.onPrimary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = enabled && !loading

    // 品牌色上的按下态靠加深品牌色实现（叠 interactiveHover 在浅色主题下会发灰）
    val background = when {
        !clickable -> colors.brandPrimary.copy(alpha = 0.4f)
        pressed -> colors.brandPrimary.copy(alpha = 0.86f)
        else -> colors.brandPrimary
    }

    Box(
        modifier = modifier
            .heightIn(min = DsSize.controlXl)
            .clip(PillShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = clickable,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapXl, vertical = DsSize.gapM),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            DsLoadingDots(color = onBrand)
        } else {
            Text(
                text = text,
                color = onBrand,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 描边按钮：透明底 + 1dp 边框；danger 时用错误色描边与文字。 */
@Composable
fun DsOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val contentColor = when {
        !enabled -> colors.labelTertiary
        danger -> colors.stateError
        else -> colors.labelPrimary
    }
    val borderColor = when {
        !enabled -> colors.borderL1
        danger -> colors.stateError.copy(alpha = 0.6f)
        else -> colors.borderL3
    }
    val background = when {
        pressed && enabled -> colors.interactiveHover
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .heightIn(min = DsSize.controlL)
            .clip(PillShape)
            .background(background)
            .border(1.dp, borderColor, PillShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 幽灵图标按钮：无边框圆形，按下叠一层 hover 色。 */
@Composable
fun DsGhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DsTheme.colors.labelSecondary,
    size: Dp = 40.dp,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(size)
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
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.55f),
            tint = tint,
        )
    }
}

/** 侧栏 / 底栏用的图标按钮，可带一行小字标签，active 时用品牌色 + 高亮底。 */
@Composable
fun DsIconWithBadge(
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean,
    onClick: () -> Unit,
    label: String? = null,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val tint = if (active) colors.brandText else colors.labelSecondary
    val background = when {
        active -> colors.sidebarItemActive
        pressed -> colors.interactiveHover
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .clip(RowShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapXs, vertical = DsSize.gapXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(DsSize.controlL)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
                tint = tint,
            )
        }
        if (label != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * 官方 Android 原生提示：屏幕正中、16dp 圆角深色底、14sp/22sp，
 * 轻微缩放淡入，不拦截下方界面触摸；1.8 秒后自动消失。
 */
@Composable
fun DsToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var shown by remember { mutableStateOf<String?>(null) }
    val dismissState by rememberUpdatedState(onDismiss)

    LaunchedEffect(message) {
        if (message == null) {
            shown = null
            return@LaunchedEffect
        }
        shown = message
        delay(1800L)
        shown = null
        dismissState()
    }

    val visible = shown != null
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "dsToastAlpha",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val text = shown
        if (text != null) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = 0.96f + 0.04f * progress
                        scaleY = scaleX
                    }
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF333333))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    text = text,
                    // toast 底色两套主题都是深色，前景固定取 onPrimary（白）
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 「正在思考」三个点循环跳动。 */
@Composable
fun DsLoadingDots(
    color: Color = DsTheme.colors.labelTertiary,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "dsLoadingDots")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until 3) {
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 520,
                        delayMillis = index * 140,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dsLoadingDot$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 头像：品牌蓝底 + DeepSeek 官方鲸鱼标识。
 * 图形用官方发布的标准矢量路径（res/drawable/ic_ds_logo_white.xml），不再手绘。
 */
@Composable
fun DsAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.brandPrimary),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ds_logo_white),
            contentDescription = null,
            modifier = Modifier.size(size * 0.66f),
        )
    }
}

/** 小号描边标签：「深度思考」「已停止」等。 */
@Composable
fun DsBadge(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DsSize.radiusS))
            .border(1.dp, colors.borderL2, RoundedCornerShape(DsSize.radiusS))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = colors.labelSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** 1px 分隔线。 */
@Composable
fun DsDivider(
    modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.borderL1,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(color),
    )
}

/** 带开关的设置项。 */
@Composable
fun DsSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsSize.controlXl)
            .clip(RowShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.labelPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colors.labelTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.width(DsSize.gapM))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = colors.brandPrimary,
                checkedBorderColor = colors.brandPrimary,
                uncheckedThumbColor = colors.labelCaption,
                uncheckedTrackColor = colors.selector,
                uncheckedBorderColor = colors.borderL2,
            ),
        )
    }
}

/** 选择型设置项：左侧标题，右侧当前值 + 箭头。 */
@Composable
fun DsSelectRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsSize.controlXl)
            .clip(RowShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = colors.labelPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            color = colors.labelTertiary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DsSize.gapXs))
        Icon(
            imageVector = DsIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.labelTertiary,
        )
    }
}

/** 设置页列表项：可选图标 + 标题 + 右侧值 + 箭头；danger 用错误色。 */
@Composable
fun DsClickableRow(
    icon: ImageVector?,
    title: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val contentColor = if (danger) colors.stateError else colors.labelPrimary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsSize.controlXl)
            .clip(RowShape)
            .background(if (pressed) colors.interactiveHover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(DsSize.gapM))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                text = value,
                color = colors.labelTertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DsSize.gapXs))
        }
        Icon(
            imageVector = DsIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.labelTertiary,
        )
    }
}

/** 分组小标题（灰色小字）。 */
@Composable
fun DsSectionTitle(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Text(
        text = text,
        modifier = modifier.padding(
            start = DsSize.gapL,
            end = DsSize.gapL,
            top = DsSize.gapM,
            bottom = DsSize.gapS,
        ),
        color = colors.labelTertiary,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** 主题选择那样的选项卡：图标在上、文字在下，选中时品牌色描边 + 淡品牌底。 */
@Composable
fun DsOptionCard(
    icon: ImageVector,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val borderColor = if (selected) colors.brandPrimary else colors.borderL2
    val background = when {
        pressed -> colors.interactiveHover
        selected -> colors.brandPrimary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val contentColor = if (selected) colors.brandText else colors.labelSecondary

    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(CardShape)
            .background(background)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = DsSize.gapM, horizontal = DsSize.gapS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = contentColor,
        )
        Spacer(Modifier.height(DsSize.gapXs))
        Text(
            text = text,
            color = if (selected) colors.labelPrimary else colors.labelSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 胶囊标签页（通用设置 / 数据管理）。 */
@Composable
fun DsPillTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val background = when {
        selected -> colors.brandPrimary
        pressed -> colors.interactiveHover
        else -> colors.selector
    }
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else colors.labelSecondary

    Box(
        modifier = modifier
            .heightIn(min = DsSize.controlS)
            .clip(PillShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
