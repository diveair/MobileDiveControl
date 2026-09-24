package com.mobiledivecontrol.ui.dive

import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiveStopColorTest {
    private fun profile(depth: Double, rate: Double) = DiveProfileState(sensorAvailable = true,
        depthMeters = depth, verticalMetersPerMinute = rate,
        active = DiveSession(0, DiveSettings(), phase = DiveStopPhase.Holding, stopStarted = true))

    @Test fun `shallow ascent is red recovery descent is yellow and target is green`() {
        assertEquals(DiveColors.Critical, safetyStopAccent(profile(4.5, -6.0)))
        assertEquals(DiveColors.Critical, safetyStopAccent(profile(4.5, 0.0)))
        assertEquals(DiveColors.Warning, safetyStopAccent(profile(4.5, 6.0)))
        assertEquals(DiveColors.Success, safetyStopAccent(profile(4.75, 6.0)))
        assertEquals(DiveColors.Success, safetyStopAccent(profile(5.25, 6.0)))
        assertEquals(DiveColors.Warning, safetyStopAccent(profile(5.3, 6.0)))
    }

    @Test fun `a decompression ceiling overrides green target and completion colors`() {
        val atTarget = profile(5.0, 0.0).copy(decompression = DecompressionState(history = DecoHistory.Tracking,
            readings = DecoReadings(0, 2.0, 0.0, false, 0.0)))
        assertEquals(DiveColors.Critical, safetyStopAccent(atTarget))
        assertEquals(DiveColors.Critical, safetyStopAccent(atTarget.copy(
            active = atTarget.active!!.copy(phase = DiveStopPhase.Complete))))
        assertEquals(DiveColors.Critical, safetyStopAccent(profile(5.0, 0.0).copy(
            stopDecompressionPaused = true, decompression = DecompressionState(history = DecoHistory.Incomplete))))
    }
}
