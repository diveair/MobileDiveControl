package com.mobiledivecontrol.ui.dive

import androidx.compose.ui.graphics.Color
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiveStopColorTest {
    private fun badge(depth: Double?, rate: Double?, phase: DiveStopPhase = DiveStopPhase.Holding,
                      returning: Boolean = false): StopBadge {
        val dive = DiveSession(0, DiveSettings(), phase = phase, stopStarted = true)
        val profile = DiveProfileState(active = dive, depthMeters = depth, sensorAvailable = depth != null,
            verticalMetersPerMinute = rate, returningFromDeep = returning)
        return stopBadge(profile, dive)
    }

    @Test fun `motion inside the normal band is quiet except on an ascent returning from deeper water`() {
        for (depth in listOf(4.5, 4.9, 5.0, 5.1, 5.5)) for (rate in listOf(null, -0.6, 0.0, 0.6)) {
            assertNull(badge(depth, rate).cue)
        }
        assertEquals(StopCue.Ascending, badge(5.1, -0.6, returning = true).cue)
        assertNull(badge(5.1, 0.0, returning = true).cue)
        assertNull(badge(5.0, -0.6, returning = true).cue)
    }

    @Test fun `deeper guidance uses ten color steps and direction has its own color`() {
        assertEquals(11, (55..65).map { badge(it / 10.0, -1.0).color }.distinct().size)
        assertEquals(DiveColors.Success, badge(6.4, -0.6).triangleColor)
        assertEquals(DiveColors.Warning, badge(5.6, 0.6).triangleColor)
        assertEquals(StopCue.Ascend, badge(6.1, 0.0).cue)
        assertNull(badge(6.1, null).cue)
        assertEquals("MEASURING MOTION", badge(6.1, null).status)
        assertEquals("TOO DEEP", badge(6.5, 0.0, DiveStopPhase.TooDeep).status)
    }

    @Test fun `shallow ascent stays red and recovery fades its flash independently of the row`() {
        fun shallow(d: Double, r: Double) = badge(d, r, DiveStopPhase.TooShallow)
        assertEquals(StopNotice.ShallowAscent, shallow(3.5, -0.6).notice)
        assertEquals(DiveColors.Critical, shallow(3.5, -0.6).color)
        assertEquals(DiveColors.Warning, shallow(3.5, -0.6).triangleColor)
        assertEquals(1f, shallow(3.5, 0.0).flash)
        assertTrue(shallow(3.8, 1.0).flash in 0.1f..0.9f)
        assertEquals(0f, shallow(4.25, 1.0).flash)
        assertEquals(Color.Yellow, shallow(4.25, 1.0).color)
        assertEquals(StopCue.Descending, shallow(4.9, 1.0).cue)
    }

    @Test fun `decompression outranks lost depth and completion and rounds upward in displayed units`() {
        val dive = DiveSession(0, DiveSettings(), phase = DiveStopPhase.Complete, stopStarted = true)
        val state = DiveProfileState(active = dive, decompression = DecompressionState(history = DecoHistory.Tracking,
            readings = DecoReadings(0, 2.01, 0.0, false, 0.0)))
        assertEquals(StopNotice.Ceiling, stopBadge(state, dive).notice)
        assertEquals(StopNotice.Ceiling, stopBadge(DiveProfileTracker.unavailable(state), dive).notice)
        assertEquals("2.1 m", stopCeilingLabel(2.01, true))
        assertEquals("6.6 ft", stopCeilingLabel(2.01, false))
    }
}
