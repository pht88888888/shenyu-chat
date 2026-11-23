package com.shenyu.chat.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 正文 [citation:N] 的数字角标外观（对照 CitationLink.tsx 的 .ds-markdown-cite）。
 *
 * 官方：高 14、左右 padding 4、最小宽 14、圆角 6 6 6 0、浅灰底（markdown-placeholder）、
 * 0.5px 内描边、字号 9/14、等宽、上标对齐、文字色 label-tertiary。
 *
 * 这里只提供「视觉」组件；Markdown.kt 里 `[citation:N]` 的解析与排版由 Markdown 维护方
 * 负责，不要在本文件之外改 Markdown.kt。若要让正文用上这套样式，请 Markdown 维护方在
 * 渲染角标处换用本组件。
 */
@Composable
fun CitationBadge(
    index: Int,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    // markdown-placeholder：浅色主题 neutral-bluish-60，深色主题 neutral-bluish-850
    val background = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFF5F6F7)
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 0.dp)
    Box(
        modifier = modifier
            .height(14.dp)
            .defaultMinSize(minWidth = 14.dp)
            .background(background, shape)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            color = colors.labelTertiary,
            fontSize = 9.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
