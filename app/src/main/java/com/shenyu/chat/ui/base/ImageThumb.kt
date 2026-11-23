package com.shenyu.chat.ui.base

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 本地图片缩略图。
 *
 * 直接从 content:// 解码并降采样，不引入外部图片库：先读尺寸算 inSampleSize，
 * 再按需解码到目标像素，避免把整张大图读进内存。
 */
@Composable
fun rememberLocalImage(uriString: String?, targetPx: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString, targetPx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uriString, targetPx) {
        if (uriString.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeSampled(context, uriString, targetPx)
        }
    }
    return bitmap
}

private fun decodeSampled(context: Context, uriString: String, targetPx: Int): ImageBitmap? {
    return runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest > 0 && longest / sample > targetPx.coerceAtLeast(1) && sample < 32) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }?.asImageBitmap()
    }.getOrNull()
}

/** 固定尺寸的图片缩略图；解码失败时退回附件图标，不会让布局塌掉。点开是全屏大图。 */
@Composable
fun ImageThumb(
    uriString: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    zoomable: Boolean = true,
) {
    val targetPx = with(LocalDensity.current) { (size * 2).roundToPx() }
    val bitmap = rememberLocalImage(uriString, targetPx)
    var previewOpen by remember(uriString) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(DsTheme.colors.selector)
            .then(if (zoomable) Modifier.clickable { previewOpen = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = DsIcons.Attachment,
                contentDescription = null,
                tint = DsTheme.colors.labelTertiary,
                modifier = Modifier.size(size * 0.34f),
            )
        }
    }

    if (previewOpen) {
        ImagePreviewDialog(uriString = uriString, onDismiss = { previewOpen = false })
    }
}

/** 全屏大图：黑底、双指缩放、点击空白关闭（官方点附件图片就是进大图）。 */
@Composable
fun ImagePreviewDialog(
    uriString: String,
    onDismiss: () -> Unit,
) {
    val bitmap = rememberLocalImage(uriString, 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(
                            state = rememberTransformableState { zoom, pan, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                offset = if (scale <= 1f) {
                                    Offset.Zero
                                } else {
                                    offset + pan
                                }
                            },
                        ),
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = DsIcons.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }
        }
    }
}
