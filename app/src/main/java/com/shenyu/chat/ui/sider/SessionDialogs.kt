package com.shenyu.chat.ui.sider

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

private val NativeDialogText = Color(0xFF0F0F0F)
private val NativeDialogMuted = Color(0xFF81858C)
private val NativeDialogBlue = Color(0xFF426EFE)
private val NativeDialogDivider = Color(0xFFD4D4D4)

/**
 * Device reference: official-rename-r2, density 2.75, root 798×481px.
 * This deliberately does not use Material AlertDialog: its padding, tonal surface,
 * minimum width and button layout differ from the native DeepSeek dialog.
 */
@Composable
fun NativeRenameDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialTitle) {
        mutableStateOf(TextFieldValue(initialTitle, TextRange(initialTitle.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val dismiss by rememberUpdatedState(onDismiss)
    val confirm by rememberUpdatedState(onConfirm)
    val trimmed = value.text.trim()
    val canConfirm = trimmed.isNotEmpty()

    NativeSessionDialog(
        onDismiss = { dismiss() },
        showKeyboard = true,
    ) {
        // Use this window's keyboard controller, not the Activity's window token.
        val keyboard = LocalSoftwareKeyboardController.current
        fun submit() {
            val title = value.text.trim()
            if (title.isNotEmpty()) {
                keyboard?.hide()
                confirm(title)
            }
        }
        Column {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "重命名对话",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = NativeDialogText,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F5F5))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = NativeDialogText,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NativeDialogBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
            Spacer(Modifier.height(20.dp))
            NativeDialogButtons(
                onDismiss = {
                    keyboard?.hide()
                    dismiss()
                },
                onConfirm = { submit() },
                confirmText = "确认",
                confirmColor = NativeDialogBlue,
                confirmEnabled = canConfirm,
            )
        }
        LaunchedEffect(focusRequester) {
            // Wait for the separate Dialog composition/window to attach before requesting IME.
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

/** A matching two-action confirmation surface for deleting one or several sessions. */
@Composable
fun NativeConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "删除",
    destructive: Boolean = true,
) {
    NativeSessionDialog(onDismiss = onDismiss) {
        Column {
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = NativeDialogText,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    color = Color(0xFF61666B),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            NativeDialogButtons(
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                confirmText = confirmText,
                confirmColor = if (destructive) Color(0xFFEC1313) else NativeDialogBlue,
            )
        }
    }
}

@Composable
private fun NativeSessionDialog(
    onDismiss: () -> Unit,
    showKeyboard: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Dialog creates a new Android root. Keep the caller's native-app density (2.75 on
    // the QA device), rather than silently returning to the system density of 3.0.
    val outerDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        CompositionLocalProvider(LocalDensity provides outerDensity) {
            val view = LocalView.current
            DisposableEffect(view, showKeyboard) {
                val window = (view.parent as? DialogWindowProvider)?.window
                val oldSoftInputMode = window?.attributes?.softInputMode
                val oldDimAmount = window?.attributes?.dimAmount
                val oldGravity = window?.attributes?.gravity
                window?.apply {
                    // Let WindowManager center in the resized area above the IME. Do not
                    // add imePadding here: it would count the keyboard a second time.
                    setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            (if (showKeyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED),
                    )
                    setGravity(Gravity.CENTER)
                    setDimAmount(0.6f)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                onDispose {
                    if (oldSoftInputMode != null) window?.setSoftInputMode(oldSoftInputMode)
                    if (oldDimAmount != null) window?.setDimAmount(oldDimAmount)
                    if (oldGravity != null) window?.setGravity(oldGravity)
                }
            }
            Box(
                Modifier.width(290.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun NativeDialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    confirmColor: Color,
    confirmEnabled: Boolean = true,
) {
    // Physical one-pixel rules, independent of the overridden app density.
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column {
        Spacer(Modifier.fillMaxWidth().height(hairline).background(NativeDialogDivider))
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            NativeDialogButton(
                text = "取消",
                color = NativeDialogMuted,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            Spacer(Modifier.width(hairline).height(48.dp).background(NativeDialogDivider))
            NativeDialogButton(
                text = confirmText,
                color = confirmColor,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f),
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun NativeDialogButton(
    text: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(48.dp).clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) color else color.copy(alpha = 0.35f),
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
