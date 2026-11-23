package com.shenyu.chat.ui.base

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalDragOrCancellation
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Native-style, left-hand push drawer. The caller owns [open]; callbacks must update it.
 *
 * Both slots remain composed, including while the drawer is closed. Apply the modifier passed
 * to [drawerContent] to its root (for example `SiderPanel(modifier = it, ...)`). The drawer slot
 * is not placed while closed: its remembered state survives, but it cannot receive pointer or
 * accessibility events. This component adds no system-bar padding; each slot owns its insets.
 *
 * This BackHandler is registered before either slot, so a drawer child's enabled BackHandler
 * (for example "exit multi-select") gets first refusal. Do not wrap this in another drawer
 * animation, edge detector, scrim, or drawer-level BackHandler.
 */
@Composable
fun PushDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    drawerContent: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onOpenChange)
    val motion = remember { PushDrawerMotion(open, scope) }
    LaunchedEffect(open) { motion.follow(open) }

    // Declare before composing the slots, not after: child back handlers have priority.
    BackHandler(enabled = open || motion.progress > 0f || motion.holding) {
        motion.request(false)
        onChange(false)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val view = androidx.compose.ui.platform.LocalView.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val geometry = remember(view, configuration, density) {
            val insets = if (android.os.Build.VERSION.SDK_INT >= 31) view.rootWindowInsets else null
            fun radius(position: Int): Int = if (android.os.Build.VERSION.SDK_INT >= 31) {
                insets?.getRoundedCorner(position)?.radius ?: 0
            } else 0
            nativeDrawerGeometry(android.os.Build.VERSION.SDK_INT, density.density,
                radius(0), radius(1), radius(3), radius(2))
        }
        val drawerWidth = (maxWidth * 0.8f).coerceAtMost(326.dp)
        val drawerPx = with(density) { drawerWidth.toPx() }
        val edgePx = with(density) { 28.dp.toPx() }
        val flingPx = with(density) { 400.dp.toPx() }
        val background = DsTheme.colors.bgBase
        // An edge DOWN at progress=0 must not insert a scrim over the tapped control.
        val visible = open || motion.progress > 0f

        Box(
            Modifier.fillMaxSize()
                .background(background)
                .pointerInput(motion, drawerPx, edgePx, flingPx) {
                    if (drawerPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        // Admission is decided at DOWN, not when horizontal slop is crossed.
                        // Outside the edge, a closed drawer never competes with horizontal
                        // message/table/code scrolling, even if the finger later enters it.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val admitted = motion.progress > 0f || motion.externalOpen || down.position.x <= edgePx
                        if (admitted) {
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            motion.hold()
                            try {
                                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume()
                                    motion.drag(over / drawerPx)
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                                if (slop == null) {
                                    // Includes an ordinary tap. Never consume it here: the
                                    // scrim's click action and drawer children still work.
                                    motion.release(cancelled = true, velocity = 0f, flingThreshold = flingPx, onChange = { onChange(it) })
                                } else {
                                    var pointer = slop.id
                                    var cancelled = false
                                    while (true) {
                                        val change = awaitHorizontalDragOrCancellation(pointer)
                                        if (change == null) {
                                            cancelled = true
                                            break
                                        }
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        if (!change.pressed) break
                                        motion.drag((change.position.x - change.previousPosition.x) / drawerPx)
                                        change.consume()
                                        pointer = change.id
                                    }
                                    motion.release(
                                        cancelled = cancelled,
                                        velocity = tracker.calculateVelocity().x,
                                        flingThreshold = flingPx,
                                        onChange = { onChange(it) },
                                    )
                                }
                            } finally {
                                // Pointer-input cancellation (resize/disposal) must not leave
                                // the drawer stuck between endpoints. Normal release clears holding.
                                if (motion.holding) motion.cancelGesture()
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier.width(drawerWidth).fillMaxSize()
                    .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (visible) placeable.placeRelative(0, 0)
                        }
                    },
            ) {
                drawerContent(Modifier.fillMaxSize())
            }

            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = motion.progress
                    translationX = drawerPx * p
                    // Official ContentScrim scales only when the system reports screen
                    // corners (API31+). Huawei Android10 remains straight and unscaled.
                    scaleX = geometry.contentScale(p)
                    scaleY = geometry.contentScale(p)
                    shape = RoundedCornerShape(
                        topStart = geometry.leftRadiusDp.dp,
                        bottomStart = geometry.leftRadiusDp.dp,
                    )
                    clip = p > 0f
                    shadowElevation = 24.dp.toPx() * p
                }.background(background),
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .then(if (visible) Modifier.clearAndSetSemantics {} else Modifier),
                ) { content() }

                if (visible) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(background.copy(alpha = 0.65f * motion.progress))
                            .semantics { contentDescription = "收起会话列表" }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "收起会话列表",
                            ) {
                                motion.request(false)
                                onChange(false)
                            },
                    )
                }
            }
        }
    }
}

/**
 * The sole writer of progress. Drag deltas are synchronous (no launch/snapTo queue); an
 * endpoint animation is cancelled synchronously before a finger can write its first delta.
 * A generation token additionally prevents an obsolete animation from publishing a frame.
 */
private class PushDrawerMotion(initialOpen: Boolean, private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(if (initialOpen) 1f else 0f)
        private set
    var holding by mutableStateOf(false)
        private set
    var externalOpen: Boolean = initialOpen
        private set
    private var dragged = false
    private var animation: Job? = null
    private var generation = 0L
    private var target = initialOpen
    private var animatedTarget: Boolean? = null

    fun follow(open: Boolean) {
        externalOpen = open
        target = open
        if (!holding) settle(open)
    }

    fun request(open: Boolean) {
        target = open
        if (!holding) settle(open)
    }

    fun hold() {
        generation++
        animation?.cancel()
        animation = null
        holding = true
        dragged = false
        // Keep the exact current fraction when catching an animation halfway through.
    }

    fun drag(delta: Float) {
        dragged = true
        progress = (progress + delta).coerceIn(0f, 1f)
    }

    fun release(cancelled: Boolean, velocity: Float, flingThreshold: Float, onChange: (Boolean) -> Unit) {
        val endpoint = when {
            cancelled || !dragged -> target
            abs(velocity) >= flingThreshold -> velocity > 0f
            else -> progress >= 0.5f
        }
        holding = false
        dragged = false
        target = endpoint
        settle(endpoint)
        if (endpoint != externalOpen) onChange(endpoint)
    }

    fun cancelGesture() {
        holding = false
        dragged = false
        settle(target)
    }

    private fun settle(open: Boolean) {
        val endpoint = if (open) 1f else 0f
        if (animation?.isActive == true && animatedTarget == open) return
        generation++
        val token = generation
        animation?.cancel()
        target = open
        if (progress == endpoint) {
            animation = null
            return
        }
        animatedTarget = open
        animation = scope.launch {
            animate(
                initialValue = progress,
                targetValue = endpoint,
                // Same-device sampled curve is best approximated by ~150ms ease-out
                // (candidate fit, not a claim that the original implementation is known).
                animationSpec = tween(durationMillis = 150, easing = androidx.compose.animation.core.Easing { t ->
                    1f - (1f - t) * (1f - t) * (1f - t)
                }),
            ) { value, _ ->
                if (generation == token) progress = value
            }
        }
    }
}
