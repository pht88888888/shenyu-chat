package com.shenyu.chat.ui.chatInput

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.base.ImageThumb
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Inline attachment tray. The caller owns expansion/IME animations and picker launchers.
 * Does not request gallery permission: the fallback always opens the system picker.
 * Selection belongs to the caller, so already attached images stay marked after reopening.
 */
@Composable
fun AttachmentPanel(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onPickImage: (Uri) -> Unit,
    selectedUris: Set<String>,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        // Permissions can change while a system picker/settings screen is foreground.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val recent by produceState<RecentAttachmentImages>(RecentAttachmentImages.Loading, context, refresh, refreshKey) {
        value = RecentAttachmentImages.Loading
        value = withContext(Dispatchers.IO) { loadRecentAttachmentImages(context) }
    }
    AttachmentPanelContent(recent, onCamera, onGallery, onFile, onPickImage, selectedUris, modifier)
}

internal sealed interface RecentAttachmentImages {
    data object Loading : RecentAttachmentImages
    data object PermissionRequired : RecentAttachmentImages
    data object Unavailable : RecentAttachmentImages
    data class Ready(val uris: List<Uri>) : RecentAttachmentImages
}

/** Resolver/decoding work never runs during composition or on the UI thread. */
private suspend fun loadRecentAttachmentImages(context: Context): RecentAttachmentImages {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val canRead = when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (!canRead) return RecentAttachmentImages.PermissionRequired
    return try {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = if (Build.VERSION.SDK_INT >= 26) {
            val arguments = Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 12)
                if (Build.VERSION.SDK_INT >= 29) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.IS_PENDING}=0")
                }
            }
            context.contentResolver.query(collection, projection, arguments, null)
        } else {
            context.contentResolver.query(collection, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
        }
        if (cursor == null) return RecentAttachmentImages.Unavailable
        val images = cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                // Providers may ignore QUERY_ARG_LIMIT; still never consume over 12 rows.
                while (size < 12 && it.moveToNext()) {
                    coroutineContext.ensureActive()
                    add(ContentUris.withAppendedId(collection, it.getLong(id)))
                }
            }
        }
        RecentAttachmentImages.Ready(images)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        // A grant can be revoked between checking it and opening the cursor.
        RecentAttachmentImages.PermissionRequired
    } catch (_: Exception) {
        RecentAttachmentImages.Unavailable
    }
}

/** State-only renderer also allows deterministic callback/selection tests without a resolver. */
@Composable
internal fun AttachmentPanelContent(
    recent: RecentAttachmentImages,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onPickImage: (Uri) -> Unit,
    selectedUris: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        when {
            recent is RecentAttachmentImages.Ready && recent.uris.isNotEmpty() -> {
                LazyRow(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(recent.uris, key = { _, uri -> uri.toString() }) { index, uri ->
                        RecentImageTile(uri, index + 1, uri.toString() in selectedUris) { onPickImage(uri) }
                    }
                }
            }
            recent is RecentAttachmentImages.Loading -> {
                Row(
                    Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp))
                        .background(DsTheme.colors.selector),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = DsTheme.colors.brandText, strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("正在加载最近照片…", color = DsTheme.colors.labelSecondary, fontSize = 14.sp)
                }
            }
            else -> GalleryFallback(
                subtitle = when (recent) {
                    RecentAttachmentImages.PermissionRequired -> "从系统相册中选择要发送的照片"
                    RecentAttachmentImages.Unavailable -> "最近照片暂不可用，可从相册选择"
                    else -> "还没有最近照片，可从相册选择"
                },
                onGallery = onGallery,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachmentAction("拍照", DsIcons.Camera, onCamera, Modifier.weight(1f))
            AttachmentAction("相册", DsIcons.Photo, onGallery, Modifier.weight(1f))
            AttachmentAction("文件", DsIcons.Folder, onFile, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GalleryFallback(subtitle: String, onGallery: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) DsTheme.colors.interactiveActive else DsTheme.colors.selector,
        label = "galleryFallbackPress",
    )
    Row(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).background(fill)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onGallery)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DsIcons.Photo, null, Modifier.size(28.dp), tint = DsTheme.colors.labelSecondary)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("选择照片", color = DsTheme.colors.labelPrimary, fontSize = 16.sp)
            Text(subtitle, color = DsTheme.colors.labelTertiary, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(DsIcons.ChevronRight, null, Modifier.size(20.dp), tint = DsTheme.colors.labelTertiary)
    }
}

@Composable
private fun AttachmentAction(label: String, image: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) DsTheme.colors.interactiveActive else DsTheme.colors.selector,
        label = "attachmentActionPress",
    )
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "attachmentActionScale")
    Column(
        modifier.height(97.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp)).background(fill)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(image, null, Modifier.size(24.dp), tint = DsTheme.colors.labelPrimary)
        Spacer(Modifier.height(10.dp))
        Text(label, color = DsTheme.colors.labelPrimary, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun RecentImageTile(uri: Uri, number: Int, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember(uri) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, label = "recentImagePress")
    val selectionFill by animateColorAsState(
        if (selected) DsTheme.colors.brandPrimary else Color.Black.copy(alpha = .22f),
        label = "recentImageSelection",
    )
    Box(
        Modifier.size(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(2.dp, DsTheme.colors.brandPrimary, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Checkbox,
                onClickLabel = if (selected) "照片已添加" else "添加最近照片 $number",
                onClick = { if (!selected) onClick() })
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription = if (selected) "已选择" else "未选择"
                contentDescription = "最近照片 $number"
            },
    ) {
        // Existing loader downsamples/decodes on Dispatchers.IO, never in composition.
        ImageThumb(uri.toString(), 96.dp, modifier = Modifier.clearAndSetSemantics {},
            corner = 16.dp, zoomable = false)
        Box(
            Modifier.align(Alignment.TopEnd).padding(7.dp).size(23.dp).clip(CircleShape)
                .background(selectionFill).border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(DsIcons.Check, null, Modifier.size(16.dp), tint = Color.White)
        }
        if (pressed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)))
    }
}
