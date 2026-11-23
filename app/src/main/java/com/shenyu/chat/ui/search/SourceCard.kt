package com.shenyu.chat.ui.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 来源条目（把 `SearchHit` 与 `Citation` 归一后的展示模型）。
 *
 * 字段映射（官方 `Source` → 本地）：
 *  - `title`       → `SearchHit.title` / `Citation.title`
 *  - `url`         → `SearchHit.url` / `Citation.url`
 *  - `snippet`     → `SearchHit.snippet`（`Citation` 无摘要，留空）
 *  - `publishedAt` → `SearchHit.date`（`Citation` 无日期，留空）
 *  - `siteName`    → 由 `url` 解析出的域名（[com.shenyu.chat.ui.base.siteHostOf]）
 *  - `siteIcon`    → 由域名交给 [SiteLogo] 拉 favicon
 *  - `index`       → `Citation.index`（角标序号，供正文角标跳转用）
 */
data class SourceItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val date: String = "",
    val host: String = "",
    val index: Int = 0,
    val siteName: String = "",
    val siteIcon: String = "",
)

/**
 * 来源卡片，1:1 复刻官方 `SourceCard`：
 *  - 卡片内边距 12dp、圆角 12dp（来自 `SearchPanel.module.css` 的 `.item`）；
 *  - meta 行：18dp 站点图标 + 域名（12sp/500）+ 1×12 分隔线 + 日期（12sp/400），间距 6dp；
 *  - 标题：14sp/22sp 500，最多 2 行；距 meta 6dp；
 *  - 摘要：13sp/20sp，次要色，最多 2 行；距标题 6dp；
 *  - 整卡可点击：用系统浏览器 (`ACTION_VIEW`) 打开，失败弹 Toast；按压叠 active 色。
 */
@Composable
fun SourceCard(item: SourceItem, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val context = LocalContext.current
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val highlightColor = colors.brandPrimary.copy(alpha = 0.08f)
    val background = when {
        pressed -> colors.interactiveActive
        highlighted -> highlightColor
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { openInBrowser(context, item.url) }
            .padding(12.dp),
    ) {
        // ---- meta 行：图标 · 域名 · 日期 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            SiteLogo(
                host = item.host,
                size = 18.dp,
                iconUrl = item.siteIcon.takeIf { it.isNotBlank() },
            )
            val siteName = item.siteName.ifBlank { item.host }
            val date = formatDate(item.date)
            if (siteName.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = siteName,
                    color = colors.labelSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (siteName.isNotBlank() && date.isNotBlank()) {
                Box1x12(Modifier.padding(horizontal = 6.dp))
            }
            if (date.isNotBlank()) {
                Text(
                    text = date,
                    color = colors.labelSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // ---- 标题（最多 2 行）----
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title.ifBlank { item.url },
            color = colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // ---- 摘要（可选，最多 2 行）----
        if (item.snippet.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.snippet,
                color = colors.labelSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** meta 行里 1dp 宽、12dp 高的竖分隔线。 */
@Composable
private fun Box1x12(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(width = 1.dp, height = 12.dp)
            .background(DsTheme.colors.borderL3),
    )
}

/** 用系统浏览器打开链接；无可用浏览器或解析失败时 Toast 兜底。 */
private fun openInBrowser(context: Context, url: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "链接为空，无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    val result = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    if (result.isFailure) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

/** 官方把日期格式化成 YYYY/MM/DD：`yyyy-MM-dd` 直接替换分隔符，其余原样保留。 */
private fun formatDate(raw: String): String {
    val v = raw.trim()
    if (v.isEmpty()) return ""
    return if (Regex("""\d{4}-\d{2}-\d{2}""").matches(v)) v.replace('-', '/') else v
}
