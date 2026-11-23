@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.shenyu.chat.ui.sider

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.chat.SessionHeaderUi
import com.shenyu.chat.ui.base.DsDivider
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.theme.DsTheme
import java.util.Calendar

/**
 * Official-style sidebar: full-text search, pinned/time groups, and a device-local user menu.
 * The surrounding App owns the pushed main page, not a dimmed modal-drawer scrim.
 * Long press exposes pin/rename/delete/multi-select; the first group also has a manager entry.
 */
@Composable
fun SiderPanel(
    sessions: List<SessionHeaderUi>,
    activeId: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCall: () -> Unit = {},
    onClose: () -> Unit = {},
    onSetPinned: (List<String>, Boolean) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    bodyMatchIds: Set<String> = emptySet(),
    onSearchBody: (String) -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<SessionHeaderUi?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionHeaderUi?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // 标题命中 ∪ 正文命中（官方「搜索对话内容」是全文搜索）
    val visible = remember(sessions, query, bodyMatchIds) {
        if (query.isBlank()) sessions
        else {
            val q = query.trim()
            sessions.filter { it.title.contains(q, ignoreCase = true) || it.id in bodyMatchIds }
        }
    }
    val groups = remember(visible) { buildGroups(visible) }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selected }
    val allSelectedPinned = selected.isNotEmpty() && selected.all { id ->
        sessions.firstOrNull { it.id == id }?.pinned == true
    }

    fun enterSelection(id: String) {
        multiSelect = true
        selected = selected + id
    }

    fun toggleSelection(id: String) {
        selected = if (id in selected) selected - id else selected + id
        // 原生允许暂时清空选择；用顶部关闭按钮或返回退出，避免最后一圈引发布局闪回。
    }

    fun toggleSelectAll() {
        selected = if (allSelected) selected - visibleIds.toSet() else selected + visibleIds
    }

    fun exitSelection() {
        multiSelect = false
        selected = emptySet()
    }

    // 真机原生参考按2.75px/dp布局；沿用Lead第3轮的系统栏后12dp留白。
    val searchTopGap = 12.dp
    var accountMenuOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(multiSelect) {
        if (multiSelect) {
            focusManager.clearFocus()
            keyboard?.hide()
            accountMenuOpen = false
        }
    }
    val muted = if (DsTheme.colors.isDark) DsTheme.colors.labelTertiary else Color(0xFF999A9C)
    val searchFill = if (DsTheme.colors.isDark) DsTheme.colors.selector else Color(0xFFF5F5F5)

    BackHandler(enabled = multiSelect) { exitSelection() }

    Column(
        modifier
            .background(DsTheme.colors.bgBase)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Spacer(Modifier.height(searchTopGap))
        if (multiSelect) {
            // 与搜索完全同高：列表的 y 坐标在进入/退出多选时保持不变。
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .padding(start = 22.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选择 ${selected.size} 个对话",
                    modifier = Modifier.weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClickLabel = if (allSelected) "取消全选对话" else "全选对话",
                            onLongClick = { toggleSelectAll() },
                        )
                        .semantics {
                            contentDescription = "已选择 ${selected.size} 个对话，长按${if (allSelected) "取消全选" else "全选"}"
                        },
                    color = DsTheme.colors.labelPrimary,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.size(36.dp)
                        .shadow(10.dp, CircleShape, clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.07f),
                            spotColor = Color.Black.copy(alpha = 0.07f))
                        .clip(CircleShape)
                        .background(DsTheme.colors.bgBase)
                        .border(0.5.dp, DsTheme.colors.borderL2, CircleShape)
                        .combinedClickable(onClick = { exitSelection() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DsIcons.Close, "退出多选", tint = DsTheme.colors.labelSecondary, modifier = Modifier.size(20.dp))
                }
            }
        } else Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(searchFill)
                .combinedClickable(onClick = { searchFocus.requestFocus(); keyboard?.show() })
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DsIcons.Search,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearchBody(it)
                },
                modifier = Modifier.weight(1f).focusRequester(searchFocus)
                    .semantics { contentDescription = "搜索对话内容" },
                textStyle = TextStyle(color = DsTheme.colors.labelPrimary, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(DsTheme.colors.brandPrimary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("搜索对话内容...", color = muted, fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .combinedClickable(onClick = {
                            query = ""
                            onSearchBody("")
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DsIcons.Close, "清空搜索", tint = muted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (query.isBlank()) "暂无对话" else "暂未找到结果",
                        color = DsTheme.colors.labelSecondary,
                        fontSize = 14.sp,
                    )
                    if (query.isBlank()) {
                        Text(
                            "你与深语的对话\n将展示在这里",
                            color = DsTheme.colors.labelTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                groups.forEachIndexed { groupIndex, (label, list) ->
                    item(key = "header-$label") {
                        Row(
                            Modifier.fillMaxWidth().height(29.dp)
                                .padding(start = 22.dp, end = 15.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                modifier = Modifier.weight(1f),
                                color = muted,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                            // Official management entry lives in the pinned header, never on each row.
                            // If there are no pins, keep it on the first group so management is reachable.
                            if (groupIndex == 0 && !multiSelect) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .combinedClickable(onClick = { multiSelect = true })
                                        .semantics { contentDescription = "管理对话，进入多选" },
                                    contentAlignment = Alignment.Center,
                                ) { SessionManagementIcon(muted) }
                            }
                        }
                        Spacer(Modifier.height(1.dp))
                    }
                    items(list, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            active = session.id == activeId,
                            selectionMode = multiSelect,
                            selected = session.id in selected,
                            onClick = {
                                if (multiSelect) toggleSelection(session.id) else onSelect(session.id)
                            },
                            onLongClick = { toggleSelection(session.id) },
                            onMore = { enterSelection(session.id) },
                            onTogglePin = { onSetPinned(listOf(session.id), !session.pinned) },
                            onRename = { renameTarget = session },
                            onDelete = { deleteTarget = session },
                        )
                    }
                    if (label == "置顶") {
                        item(key = "pinned-divider") {
                            // Same-device rule: x61..803, y568..571. Preserve the
                            // combined block height while correcting thickness/extent.
                            Spacer(Modifier.height(17.09.dp))
                            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp)
                                .height(1.dp).background(
                                    if (DsTheme.colors.isDark) DsTheme.colors.borderL2 else Color(0xFFEDEDED),
                                ))
                            Spacer(Modifier.height(18.18.dp))
                        }
                    } else {
                        item(key = "group-gap-$label") { Spacer(Modifier.height(1.dp)) }
                    }
                }
            }
        }

        if (multiSelect) {
            BatchActionBar(
                allPinned = allSelectedPinned,
                enabled = selected.isNotEmpty(),
                onTogglePin = {
                    onSetPinned(selected.toList(), !allSelectedPinned)
                    exitSelection()
                },
                onDelete = { confirmBatchDelete = true },
            )
        } else {
        // Local-only identity: no invented DeepSeek account, photo or account name.
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(58.dp)
                    .padding(start = 16.dp, end = 15.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { accountMenuOpen = true }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocalUserAvatar()
                Spacer(Modifier.width(10.dp))
                Text(
                    "深语用户",
                    modifier = Modifier.weight(1f),
                    color = DsTheme.colors.labelPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 1,
                )
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .combinedClickable(onClick = { accountMenuOpen = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DsIcons.MoreHorizontal, "用户菜单", tint = muted, modifier = Modifier.size(24.dp))
                }
            }
            AccountMenu(
                expanded = accountMenuOpen,
                onDismissRequest = { accountMenuOpen = false },
                onOpenSettings = onOpenSettings,
                onOpenCall = onOpenCall,
                onNewChat = onNewChat,
            )
        }
        Spacer(Modifier.height(2.dp))
        }
    }

    renameTarget?.let { target ->
        NativeRenameDialog(
            initialTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                onRename(target.id, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        NativeConfirmDialog(
            onDismiss = { deleteTarget = null },
            title = "删除后，该对话将不可恢复",
            message = "由该对话生成的分享链接也将失效",
            confirmText = "删除该对话",
            onConfirm = {
                onDelete(target.id)
                deleteTarget = null
            },
        )
    }

    if (confirmBatchDelete) {
        NativeConfirmDialog(
            onDismiss = { confirmBatchDelete = false },
            title = "删除选择的 ${selected.size} 个对话？",
            message = "删除后，对话将不可恢复，由该对话生成的分享链接也将失效。",
            onConfirm = {
                val ids = selected.toList()
                confirmBatchDelete = false
                onDeleteMany(ids)
                exitSelection()
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionHeaderUi,
    active: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = when {
            selectionMode -> Color.Transparent
            menuOpen -> if (DsTheme.colors.isDark) DsTheme.colors.selector else Color(0xFFF5F5F5)
            active -> DsTheme.colors.sidebarItemActive
            else -> Color.Transparent
        },
        label = "session-row-bg",
    )
    Box {
        Row(
            Modifier
                .padding(start = 10.dp, end = 10.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (selectionMode) onLongClick() else menuOpen = true },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectCircle(selected = selected)
                    // 圈从 x22dp 起，20dp 圈 + 10dp 空隙，正文固定从 x52dp 起。
                    Spacer(Modifier.width(10.dp))
                }
            }
            Text(
                text = session.title,
                modifier = Modifier.weight(1f),
                color = if (active && !selectionMode) DsTheme.colors.brandPrimary else DsTheme.colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = if (active && !selectionMode) FontWeight.Medium else FontWeight.Normal,
            )
            if (active && !selectionMode) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .combinedClickable(onClick = { menuOpen = true })
                        .semantics { contentDescription = "对话选项" },
                    contentAlignment = Alignment.Center,
                ) { Icon(DsIcons.MoreHorizontal, null, tint = DsTheme.colors.labelCaption, modifier = Modifier.size(20.dp)) }
            }
        }
        if (menuOpen) {
            val density = LocalDensity.current
            val rightInset = with(density) { 10.dp.roundToPx() }
            val gap = with(density) { 7.dp.roundToPx() }
            val shadowMargin = with(density) { 16.dp.roundToPx() }
            val position = remember(rightInset, gap, shadowMargin) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        // 容器末端 x0.8W，菜单右侧再内收10dp；208dp宽对应参考x264..836。
                        val right = anchorBounds.right - rightInset
                        val x = (right - popupContentSize.width + shadowMargin).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val below = anchorBounds.bottom + gap - shadowMargin
                        val y = if (below + popupContentSize.height <= windowSize.height) below
                            else (anchorBounds.top - gap - popupContentSize.height + shadowMargin).coerceAtLeast(0)
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                // A Popup owns a separate Android root and otherwise resets LocalDensity
                // to the system 480dpi instead of the native UI's 440dpi policy.
                androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
                // Popup's own window clips draw-outside shadows. Reserve a transparent
                // margin and subtract it in the position provider, not from the surface.
                Box(Modifier.padding(16.dp)) {
                Column(
                    Modifier.width(208.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp), clip = false)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DsTheme.colors.bgBase),
                ) {
                    SessionMenuAction("重命名", com.shenyu.chat.ui.base.OfficialIcons.Edit) {
                        menuOpen = false; onRename()
                    }
                    DsDivider(color = DsTheme.colors.borderL2)
                    SessionMenuAction(if (session.pinned) "取消置顶" else "置顶",
                        if (session.pinned) com.shenyu.chat.ui.base.OfficialIcons.Unpin else com.shenyu.chat.ui.base.OfficialIcons.Pin) {
                        menuOpen = false; onTogglePin()
                    }
                    DsDivider(color = DsTheme.colors.borderL2)
                    SessionMenuAction("多选", com.shenyu.chat.ui.base.OfficialIcons.Select, customSelectionIcon = true) {
                        menuOpen = false; onMore()
                    }
                    DsDivider(color = DsTheme.colors.borderL2)
                    SessionMenuAction("删除", com.shenyu.chat.ui.base.OfficialIcons.Delete, DsTheme.colors.stateError) {
                        menuOpen = false; onDelete()
                    }
                }
                }
                }
            }
        }
    }
}

@Composable
private fun SessionMenuAction(
    title: String,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = DsTheme.colors.labelPrimary,
    customSelectionIcon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (customSelectionIcon) SessionManagementIcon(tint)
        else Icon(image, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = tint, fontSize = 17.sp, lineHeight = 26.sp)
    }
}

/** Two outlined selectors and short rules, matching the official pinned-list manager. */
@Composable
private fun SessionManagementIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val radius = 2.15.dp.toPx()
        listOf(6.dp.toPx(), 14.dp.toPx()).forEach { y ->
            drawCircle(tint, radius, Offset(4.dp.toPx(), y), style = Stroke(stroke))
            drawLine(tint, Offset(8.5.dp.toPx(), y), Offset(15.5.dp.toPx(), y), stroke)
        }
    }
}

/** A neutral device-local default avatar, deliberately not a real person's photo. */
@Composable
private fun LocalUserAvatar() {
    val fill = if (DsTheme.colors.isDark) DsTheme.colors.bgLayer3 else Color(0xFFF0F1F3)
    val foreground = DsTheme.colors.labelSecondary
    Canvas(Modifier.size(32.dp).clip(CircleShape).background(fill)) {
        drawCircle(foreground, 4.5.dp.toPx(), Offset(size.width / 2, 11.dp.toPx()))
        drawOval(
            foreground,
            topLeft = Offset(7.dp.toPx(), 18.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(18.dp.toPx(), 16.dp.toPx()),
        )
    }
}

/** 圆形勾选圈：选中为品牌蓝实心 + 白色对勾，未选为灰色描边圆。 */
@Composable
private fun SelectCircle(selected: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (selected) DsTheme.colors.brandPrimary else Color.Transparent,
        label = "circle-fill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) DsTheme.colors.brandPrimary else if (DsTheme.colors.isDark) DsTheme.colors.labelTertiary else Color(0xFFD5D5D5),
        label = "circle-stroke",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "circle-check",
    )
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DsIcons.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(16.dp)
                .alpha(checkAlpha),
        )
    }
}

/** 原生批量操作条：替换用户 footer；固定高度、等宽操作、顶边及中央细分隔线。 */
@Composable
private fun BatchActionBar(
    allPinned: Boolean,
    enabled: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(62.dp)) {
        DsDivider(color = DsTheme.colors.borderL2)
        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchAction(
                title = if (allPinned) "取消置顶" else "置顶",
                image = if (allPinned) com.shenyu.chat.ui.base.OfficialIcons.Unpin else com.shenyu.chat.ui.base.OfficialIcons.Pin,
                tint = DsTheme.colors.labelPrimary,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onTogglePin,
            )
            Box(Modifier.width(0.5.dp).height(24.dp).background(DsTheme.colors.borderL2))
            BatchAction(
                title = "删除",
                image = com.shenyu.chat.ui.base.OfficialIcons.Delete,
                tint = DsTheme.colors.stateError,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun BatchAction(
    title: String,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(61.5.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .combinedClickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(image, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = tint, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    }
}

/** 置顶组排在最前，其余按「今天 / 昨天 / 7 天内 / 30 天内 / 更早」分组；组内按更新时间降序。 */
private fun buildGroups(visible: List<SessionHeaderUi>): List<Pair<String, List<SessionHeaderUi>>> {
    val pinned = visible.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val rest = visible.filterNot { it.pinned }
    val result = mutableListOf<Pair<String, List<SessionHeaderUi>>>()
    if (pinned.isNotEmpty()) result.add("置顶" to pinned)
    result.addAll(groupByTime(rest))
    return result
}

/** 按「今天 / 昨天 / 7 天内 / 30 天内 / 更早」分组，组内按更新时间降序。 */
private fun groupByTime(sessions: List<SessionHeaderUi>): List<Pair<String, List<SessionHeaderUi>>> {
    val today = startOfDay(System.currentTimeMillis())
    val day = 24 * 60 * 60 * 1000L
    val buckets = linkedMapOf<String, MutableList<SessionHeaderUi>>()
    sessions.forEach { session ->
        val label = when {
            session.updatedAt >= today -> "今天"
            session.updatedAt >= today - day -> "昨天"
            session.updatedAt >= today - 7 * day -> "7 天内"
            session.updatedAt >= today - 30 * day -> "30 天内"
            else -> "更早"
        }
        buckets.getOrPut(label) { mutableListOf() }.add(session)
    }
    return buckets.map { (label, list) -> label to list.sortedByDescending { it.updatedAt } }
}

private fun startOfDay(time: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = time
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
