package com.mobiledivecontrol.core

import kotlin.test.*

class SurfaceMotionTest {
    @Test fun `surface exercise crosses red ascent threshold without changing the target or timer implementation`() {
        var now = 1_000L
        val core = ControlCore(monotonicMs = { now }, triggerSafetyStopAtSurface = true)
        core.updateSurfaceMotionDepth(0.0)
        repeat(8) { now += 250; core.updateSurfaceMotionDepth(0.0) }
        val earned = core.state.diveProfile.active!!.stopElapsedMs
        repeat(32) { i -> now += 250; core.updateSurfaceMotionDepth(-(i+1)*.025) }
        val profile = core.state.diveProfile
        assertTrue(profile.sensorAvailable)
        assertEquals(0, profile.active!!.settings.stopDepthMeters)
        assertEquals(DiveStopPhase.TooShallow, profile.active!!.phase)
        assertEquals(StopDepthZone.TooShallow, stopDepthZone(profile.depthMeters, 0.0))
        assertTrue(profile.verticalMetersPerMinute!! < -.6)
        assertEquals(-.8, profile.depthMeters!!, 1e-10)
        assertEquals(earned, profile.active!!.stopElapsedMs)
        assertNull(profile.decompression.readings)
        assertEquals(0.0, pressureDepthMeters(core.state.safety.waterPressureKpa))

        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(profile))
        assertTrue(restored.active!!.samples.any { it.depthMeters < 0 })
        assertEquals(0, restored.active!!.settings.stopDepthMeters)
    }

    @Test fun `negative depth is never accepted through the ordinary tracker or production motion entry`() {
        assertFailsWith<IllegalArgumentException> { ControlCore().updateSurfaceMotionDepth(-.8) }
        assertFalse(DiveProfileTracker.sample(DiveProfileState(), -.8, 1000, 1000, true).sensorAvailable)
        assertFalse(DiveProfileTracker.sample(DiveProfileState(), -.8, 1000, 1000,
            triggerAtSurface = false, surfaceMotionExercise = true).sensorAvailable)
        val core = ControlCore(triggerSafetyStopAtSurface = true)
        core.updateSensor(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA - .8 * FRESHWATER_KPA_PER_METER))
        assertEquals(0.0, core.state.diveProfile.depthMeters)
    }
}
