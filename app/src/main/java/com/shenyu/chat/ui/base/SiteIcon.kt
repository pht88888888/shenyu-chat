package com.shenyu.chat.ui.base

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 网站图标（favicon）。
 *
 * 取 `host` 的域名后用 Google 的公开 favicon 服务拉取 64px 图标：
 *  - 先读磁盘缓存 `cacheDir/favicons/<sanitized-host>.png`，命中直接解码；
 *  - 未命中走网络（连接 / 读取各 5s 超时），成功后顺带写回磁盘缓存（写失败忽略）；
 *  - 任何失败 / 超时都降级成「首字母圆形色块」，绝不抛异常、绝不阻塞主线程。
 */

private val faviconClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}

/** 6 个柔和的品牌降级底色，用 host.hashCode() 稳定映射其中一个。 */
private val fallbackPalette = listOf(
    Color(0xFFE8836F), // 珊瑚红
    Color(0xFF6E8FE8), // 静谧蓝
    Color(0xFF5FB88A), // 青草绿
    Color(0xFFE0A93E), // 暖金
    Color(0xFF9B7FD4), // 淡紫
    Color(0xFF57AEC0), // 湖蓝
)

/**
 * 从任意「host 或完整 url 字符串」里取出去掉 `www.` 的小写域名。
 * 空串 / 无法解析时返回空串，全程 runCatching，不抛异常。
 */
fun siteHostOf(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val host = runCatching {
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        java.net.URI(withScheme).host
    }.getOrNull()
        ?: runCatching { android.net.Uri.parse(trimmed).host }.getOrNull()
        ?: trimmed
    return host
        .substringAfterLast("@")
        .substringBefore(":")
        .removePrefix("www.")
        .lowercase()
        .trim()
}

private fun faviconCacheFile(context: Context, host: String): File {
    val dir = File(context.cacheDir, "favicons")
    val safe = host.replace(Regex("[^a-z0-9.\\-]"), "_").ifBlank { "unknown" }
    return File(dir, "$safe.png")
}

/** 磁盘缓存命中返回解码结果，否则联网拉取并落盘。全部 runCatching。 */
private suspend fun loadFavicon(
    context: Context,
    host: String,
    iconUrl: String? = null,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (host.isBlank() && iconUrl.isNullOrBlank()) return@runCatching null

            val key = (iconUrl?.takeIf { it.isNotBlank() }?.let { siteHostOf(it) } ?: host)
                .ifBlank { "unknown" }
            val file = faviconCacheFile(context, key)
            if (file.exists() && file.length() > 0L) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return@runCatching it.asImageBitmap() }
            }

            // 服务端直接给了站点图标地址就优先用它，没有才回落到 Google 的公开服务
            val url = iconUrl?.takeIf { it.isNotBlank() }
                ?: "https://www.google.com/s2/favicons?sz=64&domain=$host"
            val request = Request.Builder().url(url).build()
            faviconClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                if (bytes.isEmpty()) return@runCatching null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runCatching null
                runCatching {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                bitmap.asImageBitmap()
            }
        }.getOrNull()
    }

/**
 * 网站小图标。加载成功显示圆角 3dp 的 favicon；失败 / 超时 / 空 host 显示首字母圆形色块。
 */
@Composable
fun SiteIcon(
    host: String,
    size: Dp,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
) {
    val context = LocalContext.current
    val domain = remember(host) { siteHostOf(host) }
    var bitmap by remember(domain, iconUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(domain, iconUrl) {
        bitmap = null
        if (domain.isNotBlank() || !iconUrl.isNullOrBlank()) {
            bitmap = loadFavicon(context, domain, iconUrl)
        }
    }

    val current = bitmap
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            LetterBadge(domain = domain, size = size)
        }
    }
}

@Composable
private fun LetterBadge(domain: String, size: Dp) {
    val paletteIndex = ((domain.hashCode() % fallbackPalette.size) + fallbackPalette.size) % fallbackPalette.size
    val letter = domain.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackPalette[paletteIndex]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontSize = (size.value * 0.55f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
