package com.shenyu.chat.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDensityTest {
    @Test fun sameLogicalDpiCanHaveDifferentPhysicalCorrections() {
        assertEquals(2.75f, nativeUiDensity(480, 406.4f, 1f).density, 0f)
        assertEquals(3f, nativeUiDensity(480, 480f, 1f).density, 0f)
    }

    @Test fun standardSizeUsesStepsRatherThanMultiplication() {
        assertEquals(420f / 160f, nativeUiDensity(440, 400f, 1f).density, 0f)
        assertEquals(440f / 160f, nativeUiDensity(479, 400f, 1.1f).density, 0f)
        assertEquals(3f, nativeUiDensity(480, 400f, 1.1f).density, 0f)
    }

    @Test fun largeSystemFontsKeepAccessibilityScale() {
        assertEquals(1.2f, nativeUiDensity(480, 400f, 1.3f).fontScale, 0f)
        assertEquals(1.35f, nativeUiDensity(480, 400f, 1.6f).fontScale, 0f)
        assertEquals(3.25f, nativeUiDensity(480, 400f, 1.3f).density, 0f)
    }
}
