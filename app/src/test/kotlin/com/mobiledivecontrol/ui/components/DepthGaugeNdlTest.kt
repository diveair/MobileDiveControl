package com.mobiledivecontrol.ui.components

import com.mobiledivecontrol.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DepthGaugeNdlTest {
    @Test fun `NDL uses fresh tracked physiology and suppresses stale or unknown limits`() {
        val profile = DiveProfileState(sensorAvailable = true,
            decompression = DecompressionState(history = DecoHistory.Tracking,
                readings = DecoReadings(521, 0.0, 0.0, false, 0.0)))
        assertEquals(521, liveHudNdlSeconds(profile))
        assertNull(liveHudNdlSeconds(profile.copy(sensorAvailable = false)))
        assertNull(liveHudNdlSeconds(profile.copy(decompression = profile.decompression.copy(readings = null))))
        DecoHistory.entries.filter { it != DecoHistory.Tracking }.forEach { history ->
            assertNull(liveHudNdlSeconds(profile.copy(decompression = profile.decompression.copy(history = history))))
        }
    }

    @Test fun `NDL pulses only below one metre with a valid depth and limit`() {
        for (depth in listOf(null, -1.0, 0.0, 0.99, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(shouldPulseHudNdl(depth, Buhlmann.NDL_CAP_SECONDS))
        }
        assertTrue(shouldPulseHudNdl(1.01, Buhlmann.NDL_CAP_SECONDS))
        assertTrue(shouldPulseHudNdl(20.0, 0))
        assertFalse(shouldPulseHudNdl(20.0, null))
        assertFalse(shouldPulseHudNdl(20.0, -1))
    }

    @Test fun `NDL distinguishes the time cap zero seconds and unavailable values without rounding up`() {
        assertEquals("—", hudNdlTime(null))
        assertEquals("—", hudNdlTime(-1))
        assertEquals("0s", hudNdlTime(0))
        assertEquals("59s", hudNdlTime(59))
        assertEquals("1min", hudNdlTime(60))
        assertEquals("1min", hudNdlTime(119))
        assertEquals("98min", hudNdlTime(Buhlmann.NDL_CAP_SECONDS - 1))
        assertEquals("∞min", hudNdlTime(Buhlmann.NDL_CAP_SECONDS))
    }
}
