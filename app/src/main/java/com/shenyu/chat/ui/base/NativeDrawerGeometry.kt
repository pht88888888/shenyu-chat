package com.shenyu.chat.ui.base

/** Matches the native WindowCornerRadii policy, not a handset-name heuristic. */
internal data class NativeDrawerGeometry(val leftRadiusDp: Float, val scalesContent: Boolean) {
    fun contentScale(progress: Float): Float = if (scalesContent) 1f - 0.05f * progress.coerceIn(0f, 1f) else 1f
}

internal fun nativeDrawerGeometry(
    apiLevel: Int,
    density: Float,
    topLeftPx: Int,
    topRightPx: Int,
    bottomLeftPx: Int,
    bottomRightPx: Int,
): NativeDrawerGeometry {
    if (apiLevel < 31) return NativeDrawerGeometry(0f, false)
    val radii = listOf(topLeftPx, topRightPx, bottomLeftPx, bottomRightPx)
    return NativeDrawerGeometry(
        leftRadiusDp = (topLeftPx.coerceAtLeast(0) / density.coerceAtLeast(0.01f)).coerceAtMost(36f),
        scalesContent = radii.any { it > 0 },
    )
}
