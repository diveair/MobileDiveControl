package com.mobiledivecontrol.core

import kotlin.test.*

class AirAscentTest {
    private fun moving(rate: Double, seconds: Int = 5): DiveProfileState {
        var dive = DiveProfileState(active = DiveSession(1, DiveSettings(), maxDepthMeters = 20.0))
        for (second in 0..seconds)
            dive = DiveProfileTracker.sample(dive, 20.0 - rate * second / 60.0,
                1_000L + second * 1_000, 1)
        return dive
    }
    @Test fun measuredAscentUsesActualDepthSamples() {
        val dive = moving(3.0)
        assertEquals(3.0, measuredAirAscentMetersPerMinute(dive, 6_000)!!, 1e-8)
        assertEquals(3.0, airAscentBudgetMetersPerMinute(dive, 6_000), 1e-8)
        assertEquals(1.5, airAscentBudgetMetersPerMinute(moving(1.5), 6_000), 1e-8)
    }
    @Test fun fallbackHandlesStartupHoldingDescentNoiseAndLostData() {
        val dive = moving(3.0)
        for (profile in listOf(DiveProfileState(), moving(3.0, 2), moving(0.0), moving(-3.0), moving(0.2),
            dive.copy(sensorAvailable = false), dive.copy(verticalMetersPerMinute = Double.NaN),
            DiveProfileTracker.sample(dive, 19.0, 11_000, 1))) {
            assertNull(measuredAirAscentMetersPerMinute(profile, profile.lastSampleMs ?: 6_000))
            assertEquals(6.0, airAscentBudgetMetersPerMinute(profile, profile.lastSampleMs ?: 6_000))
        }
        assertNull(measuredAirAscentMetersPerMinute(dive, 9_000))
        assertNull(measuredAirAscentMetersPerMinute(dive, 5_999))
    }
    @Test fun fastAscentCannotInflateBottomTimeAndSlowAscentReservesMoreAir() {
        val fast = moving(18.0)
        assertEquals(18.0, measuredAirAscentMetersPerMinute(fast, 6_000)!!, 1e-8)
        assertEquals(6.0, airAscentBudgetMetersPerMinute(fast, 6_000))
        val slow = moving(3.0)
        val air = AirAlertState(settings = AirAlertSettings(enabled = true), sessionId = 1,
            elapsedMs = 300_000, ambientMinutes = 15.0, observation = AirObservation(170.0, 300_000, 15.0),
            surfaceBarPerMinute = 2.0)
        val measured = AirAlertEngine.estimate(air, slow, 6_000)!!
        val fallback = AirAlertEngine.estimate(air, slow.copy(verticalMetersPerMinute = null), 6_000)!!
        assertTrue(measured.ascentAndStopBar!! > fallback.ascentAndStopBar!!)
        assertTrue(measured.bottomMinutes!! < fallback.bottomMinutes!!)
        val quickAtSameDepth = AirAlertEngine.estimate(air, slow.copy(verticalMetersPerMinute = -18.0), 6_000)!!
        assertEquals(fallback, quickAtSameDepth)
    }
    @Test fun legacyManualRatesAreIgnoredAndNoAscentControlRemains() {
        val settings = AirAlertSettings(enabled = true, startBar = 220.0, reserveBar = 40.0)
        val saved = AirAlertSettingsCodec.encode(settings)
        assertFalse(saved.lineSequence().any { it.startsWith("ascent=") })
        for (rate in listOf(3, 8, 9)) {
            val old = saved.replace("version=6", "version=3") + "\nascent=$rate\n"
            assertEquals(settings, AirAlertSettingsCodec.decode(old))
        }
        val fields = airFields(AirAlertState(settings = settings, page = AirPage.Gas))
        assertEquals(listOf(AirField.Unit, AirField.Start, AirField.SliderMinimum, AirField.SliderMaximum,
            AirField.Reserve, AirField.Hud, AirField.Back), fields)
    }
}
