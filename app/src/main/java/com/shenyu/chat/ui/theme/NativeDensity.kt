package com.shenyu.chat.ui.theme

import androidx.compose.ui.unit.Density

/**
 * Native 2.8 UI scale: discrete logical-DPI steps plus a high-physical-DPI correction.
 * Do not hardcode a screenshot's px/dp ratio: e.g. the same 480 logical DPI is rendered
 * at 440 DPI on a ~400 physical-DPI phone, but at 480 on a >460 physical-DPI phone.
 * Our custom message font preference remains separate from this system-size adaptation.
 */
internal fun nativeUiDensity(logicalDpi: Int, physicalDpi: Float, systemFontScale: Float): Density {
    val steps = intArrayOf(120, 140, 160, 180, 200, 213, 220, 240, 260, 280, 300, 320, 340, 360, 400, 420, 440, 480, 520, 560, 600, 640)
    val found = steps.binarySearch(logicalDpi)
    val index = (if (found >= 0) found else -found - 2).coerceIn(3, steps.lastIndex)
    val (offset, fontScale) = when {
        systemFontScale <= 0.8f -> -3 to 1f
        systemFontScale <= 0.9f -> -2 to 1f
        systemFontScale <= 1.01f -> -1 to 1f
        systemFontScale <= 1.1f -> 0 to 1f
        systemFontScale <= 1.2f -> 1 to 1f
        systemFontScale <= 1.4f -> 1 to 1.2f
        else -> 1 to 1.35f
    }
    val correction = if (physicalDpi > 460f) 1 else 0
    val targetIndex = index + offset + correction
    val dpi = if (targetIndex > steps.lastIndex) steps.last() + offset * 40 else steps[targetIndex.coerceAtLeast(0)]
    return Density(dpi / 160f, fontScale)
}
