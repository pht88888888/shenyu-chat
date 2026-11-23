package com.shenyu.chat.ui.chatInput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shenyu.chat.ui.voice.HoldToTalkSession
import com.shenyu.chat.ui.voice.VoiceCapture
import com.shenyu.chat.ui.voice.VoiceInputPhase
import com.shenyu.chat.ui.voice.VoiceInputUiState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shenyu.chat.data.Attachment
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.theme.DsTheme
import com.shenyu.chat.ui.voice.VoiceRecorder
import java.io.File
import kotlinx.coroutines.delay

/**
 * 底部输入区：多行输入框 + 附件、深度思考、智能搜索、语音、发送。
 * 视觉按官方 ChatInput.module.css 1:1 复刻；生成中发送键变为停止键。
 */
@Composable
fun ChatInput(
    streaming: Boolean,
    hasMessages: Boolean = false,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    onToggleThinking: (Boolean) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onSend: (String, List<Attachment>) -> Unit,
    onStop: () -> Unit,
    onTranscribe: (ByteArray, (Result<String>) -> Unit) -> Unit,
    draftText: String = "",
    onDraftChange: (String) -> Unit = {},
    onOpenCall: () -> Unit = {},
    modifier: Modifier = Modifier,
    voiceContextKey: String? = null,
    onVoiceStateChange: (VoiceInputUiState) -> Unit = {},
    voiceCapture: VoiceCapture? = null,
) {
    // 草稿文本受控于外部（按 sessionId 存在内存里），切换会话再切回来文字仍在。
    val text = draftText
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val context = LocalContext.current

    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !streaming

    // 选附件：走系统文件选择器，只记 Uri 与元信息，真正上传在发送时进行
    // 选择失败的提示（官方是 Toast；这里就地显示在附件区上方，3 秒自动消失）
    var pickError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pickError) {
        if (pickError != null) {
            kotlinx.coroutines.delay(3000)
            pickError = null
        }
    }

    // 官方约束：单文件 ≤64MB（图片与普通文档按 user_data 记 32MB），最多 50 个
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val limit = 64L * 1024 * 1024
        val next = attachments.toMutableList()
        var error: String? = null
        uris.forEach { uri ->
            val meta = readFileMeta(context, uri)
            val size = meta.third
            if (size > limit) {
                error = "单个文件大小超过限制（64MB），请调整后重试"
                return@forEach
            }
            if (next.size >= 50) {
                error = "上传文件数达到上限，每个对话可上传 50 个文件"
                return@forEach
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            next += Attachment(
                name = meta.first,
                mime = meta.second,
                size = size,
                localPath = uri.toString(),
            )
        }
        attachments = next
        pickError = error
    }

    // 附件面板：相册 / 拍照 / 文件（对照官方 upload_panel_album、camera、file）
    var attachMenuOpen by remember { mutableStateOf(false) }
    var galleryAccessRevision by remember { mutableStateOf(0) }
    var galleryPermissionRequested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        galleryAccessRevision++
    }
    fun requestGalleryPreviewAccess() {
        if (galleryPermissionRequested) return
        val permissions = when {
            android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        galleryPermissionRequested = true
        if (permissions.none { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            galleryPermission.launch(permissions)
        }
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(enabled = attachMenuOpen) { attachMenuOpen = false }
    LaunchedEffect(voiceContextKey) { attachMenuOpen = false }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> addUris(uris) }

    val albumLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris: List<Uri> -> addUris(uris) }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) addUris(listOf(uri))
        cameraUri = null
    }

    fun launchCamera() {
        val uri = runCatching { createCameraUri(context) }.getOrNull()
        if (uri == null) {
            pickError = "相机不可用，请检查系统权限"
            return
        }
        cameraUri = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { pickError = "相机不可用，请检查系统权限" }
    }

    // A press owns a complete record → transcribe → send transaction.
    val recorder = remember(voiceCapture) { voiceCapture ?: VoiceRecorder() }
    val voiceSession = remember(recorder, voiceContextKey) { HoldToTalkSession(recorder) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestSend by rememberUpdatedState(onSend)
    val latestDraftChange by rememberUpdatedState(onDraftChange)
    val latestDraft by rememberUpdatedState(text)
    val latestAttachments by rememberUpdatedState(attachments)
    val latestStreaming by rememberUpdatedState(streaming)
    val latestTranscribe by rememberUpdatedState(onTranscribe)
    val latestVoiceStateChange by rememberUpdatedState(onVoiceStateChange)
    val recording = voiceSession.phase == VoiceInputPhase.Recording
    val transcribing = voiceSession.phase == VoiceInputPhase.Transcribing
    val voiceHint = voiceSession.hint
    var amplitude by remember { mutableStateOf(0f) }
    var voiceMode by remember { mutableStateOf(false) }
    var voicePressed by remember { mutableStateOf(false) }
    val voiceSurface by animateColorAsState(
        targetValue = when {
            voicePressed -> DsTheme.colors.interactiveActive
            transcribing -> DsTheme.colors.brandPrimary.copy(alpha = 0.04f)
            else -> Color.Transparent
        },
        animationSpec = tween(120), label = "voicePressSurface",
    )

    fun finishRecording() {
        val draft = latestDraft
        val files = latestAttachments
        voiceSession.release(
            transcribe = { pcm, callback -> latestTranscribe(pcm, callback) },
            send = { spoken ->
                val message = listOf(draft.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" ")
                if (latestStreaming) {
                    // If generation started elsewhere during ASR, retain visible text.
                    latestDraftChange(message)
                    voiceMode = false
                    voiceSession.showHint("请等待当前回答结束后发送")
                } else {
                    latestSend(message, files)
                    latestDraftChange("")
                    attachments = emptyList()
                }
            },
        )
    }

    val voiceUiState = VoiceInputUiState(voiceSession.phase, voiceSession.cancelRequested, amplitude)
    SideEffect { onVoiceStateChange(voiceUiState) }
    DisposableEffect(voiceSession, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voiceSession.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voiceSession.cancel()
            latestVoiceStateChange(VoiceInputUiState())
        }
    }
    BackHandler(enabled = recording || transcribing) { voiceSession.cancel() }
    LaunchedEffect(voiceSession, recording) {
        while (voiceSession.phase == VoiceInputPhase.Recording) {
            amplitude = recorder.amplitude
            if (recorder.elapsedMs() >= VoiceRecorder.MAX_MS) finishRecording()
            delay(50)
        }
        amplitude = 0f
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The permission dialog has ended the original press. Never start a stranded mic.
        voiceSession.showHint(if (granted) "已允许麦克风，请重新按住说话" else "没有麦克风权限，无法语音输入")
    }
    val cancelDistance = with(LocalDensity.current) { 64.dp.toPx() }

    fun submit() {
        if (!canSend) return
        onSend(text, attachments)
        onDraftChange("")
        attachments = emptyList()
    }

    val colors = DsTheme.colors
    val cardShape = RoundedCornerShape(28.dp)
    // 同机 1080×2400 / 2.75 px/dp 校准；底部 27dp 是导航安全区以外的留白。
    // IME 仍交由输入态处理，语音态不额外加键盘高度。
    Column(
        modifier.fillMaxWidth()
            .alpha(if (recording || transcribing) 0f else 1f)
            .then(if (voiceMode) Modifier else Modifier.imePadding())
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (hasMessages) 1.818.dp else 25.818.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 两层低不透明度阴影：近处的轮廓 + 下方大范围柔光，深色不出现白色光晕。
                .shadow(
                    elevation = 12.dp,
                    shape = cardShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.24f else 0.22f),
                    spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.32f else 0.30f),
                )
                .shadow(
                    elevation = 2.dp,
                    shape = cardShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.02f),
                    spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.10f else 0.025f),
                )
                .clip(cardShape)
                .background(colors.inputMajor)
                .border(1.dp, if (colors.isDark) colors.borderL1 else Color(0xFFF0F0F0), cardShape)
                // Match the native outer stroke without shifting any inner controls:
                // one dp extra surface on both sides and below the existing contents.
                .padding(start = 1.dp, end = 1.dp, bottom = 1.dp),
        ) {
            // 附件草稿卡（横向滚动）
            DraftAttachments(
                attachments = attachments,
                onRemove = { attachments = attachments - it },
            )

            pickError?.let { message ->
                Text(
                    text = message,
                    color = DsTheme.colors.stateError,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
            }

            if (voiceMode) {
                // 语音模式：中央「按住说话」大按钮，录音时上方显示 Spectrum 波形
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(voiceSurface)
                            .alpha(if (transcribing) 0.65f else 1f)
                            .pointerInput(voiceMode, transcribing, voiceSession, streaming) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (voiceSession.phase != VoiceInputPhase.Idle || streaming) return@awaitEachGesture
                                    down.consume()
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        return@awaitEachGesture
                                    }
                                    if (!voiceSession.start(scope)) return@awaitEachGesture
                                    voicePressed = true
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pointer = event.changes.firstOrNull { it.id == down.id }
                                            if (pointer == null || pointer.isConsumed) {
                                                voiceSession.cancel()
                                                break
                                            }
                                            voiceSession.updateCancel(pointer.position.y - down.position.y < -cancelDistance)
                                            pointer.consume()
                                            if (!pointer.pressed) {
                                                finishRecording()
                                                break
                                            }
                                        }
                                    } finally {
                                        voicePressed = false
                                        if (voiceSession.phase == VoiceInputPhase.Recording) voiceSession.cancel()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                transcribing -> "正在识别…"
                                else -> "按住说话"
                            },
                            color = if (recording) colors.brandText else if (colors.isDark) colors.labelPrimary else Color(0xFF191B23),
                            fontSize = 17.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(0, 4) },
                        )
                    }
                }
                // 官方语音态：「按住说话」下方有一条极细横线贯穿卡片
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(0, -1) }
                        .height(1.dp)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.031f to (if (colors.isDark) colors.borderL1 else Color(0xFFF0F0F0)),
                            0.969f to (if (colors.isDark) colors.borderL1 else Color(0xFFF0F0F0)),
                            1f to Color.Transparent,
                        )),
                )
            } else {
                // 首行高 51dp，与语音区 50dp + 1dp 分隔线等高，切换时卡片不跳动。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 51.dp, max = 336.dp)
                        .padding(start = 16.36.dp, end = 12.dp, top = 11.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { onDraftChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = DsTheme.colors.labelPrimary,
                            fontSize = 17.sp,
                            lineHeight = 26.sp,
                            letterSpacing = 0.sp,
                        ),
                        cursorBrush = SolidColor(DsTheme.colors.brandText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "发消息或按住说话",
                                    color = if (colors.isDark) colors.labelCaption else Color(0xFFB9BABB),
                                    fontSize = 17.sp,
                                    lineHeight = 26.sp,
                                    letterSpacing = 0.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
            }

            if (voiceHint != null) {
                Text(
                    text = voiceHint.orEmpty(),
                    color = DsTheme.colors.stateError,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 4.dp),
                )
            }

            // 胶囊按原生内容宽度排布；给两个 40dp 触区优先留足宽度。
            // 窄屏只约束 chip 最大宽度，不挤掉右侧按钮；功能行保持同机实测基线。
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 11.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
            ) {
                val chipMaxWidth = ((maxWidth - 84.dp - 8.dp - 8.dp) / 2).coerceAtLeast(0.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(
                            icon = DsIcons.DeepThink,
                            label = "深度思考",
                            active = thinkingEnabled,
                            modifier = Modifier.widthIn(max = chipMaxWidth),
                            onClick = { onToggleThinking(!thinkingEnabled) },
                        )
                        ToggleChip(
                            icon = InputSearchGlobe,
                            label = "智能搜索",
                            active = searchEnabled,
                            modifier = Modifier.widthIn(max = chipMaxWidth),
                            onClick = { onToggleSearch(!searchEnabled) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(1, 0) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 附件 ⊕：弹出「相册 / 拍照 / 文件」面板（官方是圆圈加号）
                        RoundIconButton(
                            icon = DsIcons.Add,
                            rotation = if (attachMenuOpen) 45f else 0f,
                            contentDescription = if (attachMenuOpen) "关闭附件面板" else "上传文件",
                            iconSize = 26.dp,
                            onClick = {
                                if (!attachMenuOpen) {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    requestGalleryPreviewAccess()
                                }
                                attachMenuOpen = !attachMenuOpen
                            },
                        )
                        // 语音 / 键盘切换：有文字时这个位置让给发送按钮
                        if (!(canSend || streaming)) VoiceModeButton(
                            voiceMode = voiceMode,
                            recording = recording,
                            onClick = {
                                if (recording || transcribing) voiceSession.cancel()
                                voiceMode = !voiceMode
                            },
                        )
                        // 官方：同一个位置，没有文字时是语音按钮，有文字时换成蓝色发送箭头
                        if (canSend || streaming) {
                            SendOrStopButton(
                                streaming = streaming,
                                enabled = canSend || streaming,
                                onClick = { if (streaming) onStop() else submit() },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = attachMenuOpen,
            enter = expandVertically(tween(220)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            AttachmentPanel(
                onCamera = { launchCamera() },
                onGallery = {
                    albumLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onFile = { pickLauncher.launch(arrayOf("*/*")) },
                onPickImage = { uri ->
                    if (attachments.any { it.localPath == uri.toString() }) {
                        attachments = attachments.filterNot { it.localPath == uri.toString() }
                    } else addUris(listOf(uri))
                },
                selectedUris = attachments.mapNotNull { it.localPath }.toSet(),
                refreshKey = galleryAccessRevision,
            )
        }
        // 官方实测：首页（键盘态、语音态都一样）输入框下方是空白，只有对话页底部才有这行
        if (hasMessages && !attachMenuOpen) {
            Text(
                text = "回答由 AI 生成，仅供参考",
                color = if (colors.isDark) colors.labelCaption else Color(0xFFDDDDDD),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, -9) },
            )
        }
    }
}

/** 发送 / 停止：34dp 圆形主色按钮，margin-left 6dp，禁用时降透明度。 */
@Composable
private fun SendOrStopButton(
    streaming: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(DsTheme.colors.brandPrimary)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) DsIcons.Stop else DsIcons.Send,
            contentDescription = if (streaming) "停止生成" else "发送",
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 语音 / 键盘切换按钮：34dp 圆形；录音时显示品牌色核心 + 呼吸光圈。 */
@Composable
private fun VoiceModeButton(
    voiceMode: Boolean,
    recording: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            val transition = rememberInfiniteTransition(label = "voiceHalo")
            val haloScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "halo",
            )
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer { scaleX = haloScale; scaleY = haloScale }
                    .clip(CircleShape)
                    .background(DsTheme.colors.brandPrimary.copy(alpha = 0.2f)),
            )
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(DsTheme.colors.brandPrimary.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DsIcons.Mic,
                    contentDescription = "结束录音",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Icon(
                imageVector = if (voiceMode) DsIcons.Keyboard else DsIcons.Voice,
                contentDescription = if (voiceMode) "切回键盘" else "语音输入",
                tint = DsTheme.colors.labelPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** 34dp 圆形图标按钮（附件用），图标 16dp。 */
@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    val angle by androidx.compose.animation.core.animateFloatAsState(rotation, tween(180), label = "attachmentIcon")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DsTheme.colors.labelPrimary,
            modifier = Modifier.size(iconSize).graphicsLayer { rotationZ = angle },
        )
    }
}

/** 原生 ic_globe_language_outline_16；不能用 20 视口资源扩大替代，否则占位和文字间距都不一致。 */
private val InputSearchGlobe: ImageVector by lazy {
    ImageVector.Builder(
        name = "InputSearchGlobe16", defaultWidth = 16.dp, defaultHeight = 16.dp,
        viewportWidth = 16f, viewportHeight = 16f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M8.001,0.451C12.17,0.451 15.55,3.831 15.55,8C15.55,12.169 12.17,15.549 8.001,15.549C3.832,15.549 0.451,12.17 0.451,8C0.451,3.831 3.832,0.451 8.001,0.451ZM6.268,8.701C6.324,10.249 6.565,11.613 6.917,12.61C7.12,13.185 7.347,13.6 7.565,13.858C7.776,14.105 7.922,14.144 7.992,14.147C7.995,14.147 7.997,14.148 8,14.148C8.067,14.148 8.216,14.116 8.436,13.858C8.654,13.6 8.881,13.185 9.084,12.61C9.436,11.613 9.677,10.249 9.733,8.701H6.268ZM11.134,8.701C11.077,10.375 10.818,11.905 10.404,13.077C10.318,13.321 10.223,13.554 10.119,13.774C12.261,12.988 13.843,11.044 14.108,8.701H11.134ZM1.893,8.701C2.158,11.043 3.74,12.986 5.881,13.773C5.777,13.554 5.683,13.32 5.597,13.077C5.183,11.905 4.924,10.375 4.867,8.701H1.893ZM8,1.851C7.933,1.851 7.784,1.885 7.565,2.142C7.347,2.4 7.12,2.815 6.917,3.39C6.565,4.387 6.324,5.752 6.268,7.3H9.733C9.677,5.752 9.436,4.387 9.084,3.39C8.881,2.815 8.654,2.4 8.436,2.142C8.216,1.884 8.067,1.851 8,1.851ZM10.119,2.225C10.223,2.445 10.318,2.679 10.404,2.923C10.818,4.096 11.078,5.625 11.135,7.3H14.108C13.843,4.957 12.261,3.011 10.119,2.225ZM5.881,2.226C3.739,3.013 2.158,4.957 1.893,7.3H4.867C4.924,5.625 5.183,4.096 5.597,2.923C5.683,2.679 5.777,2.446 5.881,2.226Z").toNodes(),
            fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd,
        )
    }.build()
}

/** 原生 ToolButton：32dp 高，12dp 内边距 + 16dp 图标 + 4dp 间距 + 14sp 文字，宽度由内容决定。 */
@Composable
private fun ToggleChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val fill by animateColorAsState(
        targetValue = if (active) {
            if (colors.isDark) colors.brandPrimary.copy(alpha = 0.14f) else Color(0xFFEDF3FE)
        } else Color.Transparent,
        animationSpec = tween(160),
        label = "chipFill",
    )
    val border by animateColorAsState(
        targetValue = if (active) {
            if (colors.isDark) colors.brandText.copy(alpha = 0.30f) else Color(0xFFD3E2FF)
        } else if (colors.isDark) colors.borderL2 else Color(0xFFEDEDED),
        animationSpec = tween(160),
        label = "chipBorder",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) colors.brandText else colors.labelPrimary,
        animationSpec = tween(160),
        label = "chipForeground",
    )
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = foreground,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 读取选中文件的显示名、MIME 与大小。 */
private fun readFileMeta(context: android.content.Context, uri: Uri): Triple<String, String, Long> {
    var name = uri.lastPathSegment ?: "附件"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return Triple(name, mime, size)
}

/** 拍照输出到 cache 目录，交给 FileProvider 授权给相机应用。 */
private fun createCameraUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
