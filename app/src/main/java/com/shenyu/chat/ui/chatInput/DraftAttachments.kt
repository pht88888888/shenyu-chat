package com.shenyu.chat.ui.chatInput

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.data.Attachment
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.base.ImageThumb
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 输入区已选附件草稿卡（对齐官方 DraftAttachments.tsx / FileCard）：
 * - 图片：64dp 圆角缩略图 + 右上角圆形删除按钮。
 * - 其它：文件类型图标 + 文件名 + 「扩展名 大小」横向卡片 + 右侧删除按钮。
 * 整块可横向滚动。
 */
@Composable
fun DraftAttachments(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        attachments.forEach { attachment ->
            if (attachment.isImage && attachment.localPath != null) {
                ImageAttachmentCard(attachment) { onRemove(attachment) }
            } else {
                FileAttachmentCard(attachment) { onRemove(attachment) }
            }
        }
    }
}

/** 图片缩略图卡：64dp、圆角 16dp、1dp 描边、右上角删除。 */
@Composable
private fun ImageAttachmentCard(item: Attachment, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        ImageThumb(
            uriString = item.localPath.orEmpty(),
            size = 64.dp,
            corner = 16.dp,
        )
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, DsTheme.colors.borderL2, RoundedCornerShape(16.dp)),
        )
        RemoveButton(
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            onClick = onRemove,
        )
    }
}

/** 文档文件卡：240dp 宽、最小 64dp 高、圆角 16dp。 */
@Composable
private fun FileAttachmentCard(item: Attachment, onRemove: () -> Unit) {
    Box(Modifier.width(240.dp).heightIn(min = 64.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DsTheme.colors.inputMajor)
                .border(1.dp, DsTheme.colors.borderL2, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = DsIcons.Attachment,
                    contentDescription = null,
                    tint = DsTheme.colors.labelTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = DsTheme.colors.labelPrimary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileSubCaption(item),
                    color = DsTheme.colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RemoveButton(
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            onClick = onRemove,
        )
    }
}

/** 18dp 圆形删除按钮：按下时缩小并变暗，给出明确反馈。 */
@Composable
private fun RemoveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "removeScale",
    )
    val base = DsTheme.colors.labelSecondary
    Box(
        modifier
            .size(18.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (pressed) base.copy(alpha = 0.72f) else base)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DsIcons.Close,
            contentDescription = "移除附件",
            tint = Color.White,
            modifier = Modifier.size(10.dp),
        )
    }
}

private fun fileSubCaption(item: Attachment): String {
    val ext = item.name.substringAfterLast('.', "").uppercase()
    val size = formatSize(item.size)
    return listOf(ext, size).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = 1024.0
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / kb)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (kb * kb))
        else -> String.format("%.1f GB", bytes / (kb * kb * kb))
    }
}
