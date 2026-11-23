package com.shenyu.chat.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyu.chat.ui.base.SiteIcon
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 来源卡片左侧的站点图标。
 *
 * 对齐官方 `SiteLogo.module.css`：18×18、`border-radius: 50%`、
 * 底色取 `--dsw-alias-markdown-citation`（这里用最接近的 markdownInlineCode）。
 * 内部复用现有 [SiteIcon]（磁盘缓存 + 首字母降级），不改其签名。
 */
@Composable
fun SiteLogo(
    host: String,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(DsTheme.colors.markdownInlineCode),
        contentAlignment = Alignment.Center,
    ) {
        SiteIcon(host = host, size = size - 2.dp, iconUrl = iconUrl)
    }
}
