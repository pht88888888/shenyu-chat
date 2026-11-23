package com.shenyu.chat.ui

import com.shenyu.chat.ui.base.nativeDrawerGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDrawerGeometryTest {
    @Test fun android10DoesNotInventScreenCorners() {
        val geometry = nativeDrawerGeometry(29, 2.75f, 100, 100, 100, 100)
        assertEquals(0f, geometry.leftRadiusDp, 0f)
        assertFalse(geometry.scalesContent)
        assertEquals(1f, geometry.contentScale(1f), 0f)
    }
    @Test fun android12WithNoReportedCornersStaysStraight() {
        val geometry = nativeDrawerGeometry(31, 3f, 0, 0, 0, 0)
        assertEquals(0f, geometry.leftRadiusDp, 0f)
        assertFalse(geometry.scalesContent)
    }
    @Test fun reportedRadiusIsConvertedAndCappedAndScaleFollowsProgress() {
        val geometry = nativeDrawerGeometry(35, 3f, 150, 150, 150, 150)
        assertEquals(36f, geometry.leftRadiusDp, 0f)
        assertTrue(geometry.scalesContent)
        assertEquals(1f, geometry.contentScale(0f), 0f)
        assertEquals(.975f, geometry.contentScale(.5f), .0001f)
        assertEquals(.95f, geometry.contentScale(1f), .0001f)
        assertEquals(20f, nativeDrawerGeometry(31, 3f, 60, 0, 0, 0).leftRadiusDp, 0f)
    }
    @Test fun otherCornerStillEnablesScaleButDoesNotInventLeftRadius() {
        val geometry = nativeDrawerGeometry(31, 3f, 0, 80, 0, 0)
        assertEquals(0f, geometry.leftRadiusDp, 0f)
        assertTrue(geometry.scalesContent)
    }
}
