package com.mobiledivecontrol.ui.components

import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DepthGaugeColorTest {
    @Test
    fun `surface pressure differences below zero clamp to zero depth`() {
        assertEquals(0.0, depthMetersFromPressure(101.2))
    }

    @Test
    fun `water pressure converts to depth from the DiveIT surface reference`() {
        assertEquals(2.0, depthMetersFromPressure(120.945)!!, 0.0001)
    }

    @Test
    fun `heading shares arrow success colour when synchronized`() {
        assertEquals(
            DiveColors.Success,
            headingReadoutColor(formatHeading(356.0), headingTargetSynchronized = true),
        )
    }

    @Test
    fun `heading uses navigation colour outside synchronization tolerance`() {
        assertEquals(
            DiveColors.HeadingViolet,
            headingReadoutColor(formatHeading(356.0), headingTargetSynchronized = false),
        )
    }

    @Test
    fun `missing heading stays muted even with stale synchronization state`() {
        assertEquals(
            DiveColors.TextMuted,
            headingReadoutColor(null, headingTargetSynchronized = true),
        )
    }

    @Test fun `bearing label retains wrap and cardinal boundaries`() {
        assertEquals("000° N", formatHeading(-0.4))
        assertEquals("000° N", formatHeading(359.6))
        assertEquals("022° N", formatHeading(22.49))
        assertEquals("023° NE", formatHeading(22.5))
    }
}
