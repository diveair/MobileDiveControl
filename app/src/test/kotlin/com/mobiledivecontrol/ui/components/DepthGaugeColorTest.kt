package com.mobiledivecontrol.ui.components

import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DepthGaugeColorTest {
    @Test
    fun `surface pressure differences below zero clamp to zero depth`() {
        assertEquals(0.0, depthMetersFromPressure(101.2, 101.6))
    }

    @Test
    fun `water pressure converts to depth from captured surface baseline`() {
        assertEquals(2.0, depthMetersFromPressure(121.22, 101.6)!!, 0.0001)
    }

    @Test
    fun `heading shares arrow success colour when synchronized`() {
        assertEquals(
            DiveColors.Success,
            headingReadoutColor(headingDegrees = 356.0, headingTargetSynchronized = true),
        )
    }

    @Test
    fun `heading uses navigation colour outside synchronization tolerance`() {
        assertEquals(
            DiveColors.HeadingViolet,
            headingReadoutColor(headingDegrees = 356.0, headingTargetSynchronized = false),
        )
    }

    @Test
    fun `missing heading stays muted even with stale synchronization state`() {
        assertEquals(
            DiveColors.TextMuted,
            headingReadoutColor(headingDegrees = null, headingTargetSynchronized = true),
        )
    }
}
