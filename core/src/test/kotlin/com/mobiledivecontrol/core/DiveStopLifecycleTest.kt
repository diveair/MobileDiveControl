package com.mobiledivecontrol.core

import kotlin.test.*

class DiveStopLifecycleTest {
    private var now = 0L
    private var profile = DiveProfileState()
    private fun at(depth: Double) {
        now += 1000
        profile = DiveProfileTracker.sample(profile, depth, now, 1_800_000_000_000L + now)
    }
    private fun requiredStop() { at(2.0); at(30.0); at(6.0); at(5.0) }

    @Test fun `ordinary shallow dive also triggers the recommended stop but surface readings do not`() {
        repeat(10) { at(0.0) }
        assertNull(profile.active)
        listOf(2.0, 12.0, 8.0, 5.0, 4.0).forEach(::at)
        assertTrue(profile.stopActive)
        assertFalse(profile.active!!.requiredByKnownProfile)
        assertEquals(1, profile.alertSequence)
        at(0.0)
        assertEquals(DiveLogOutcome.IncompleteStop, profile.logs.single().outcome)
        repeat(100) { at(0.0) }
        assertNull(profile.active)
        assertEquals(1, profile.alertSequence)
    }

    @Test fun `dive that never went deeper than the target does not trigger on descent or ascent`() {
        listOf(0.0, 2.0, 4.0, 5.0, 4.0, 2.0, 0.0).forEach(::at)
        assertNull(profile.active)
        assertEquals(0, profile.alertSequence)
        assertEquals(DiveLogOutcome.NoStopRecorded, profile.logs.single().outcome)
    }

    @Test fun `ascent from just beyond a custom target triggers once at the target`() {
        profile = DiveProfileState(settings = DiveSettings(stopDepthMeters = 4))
        listOf(2.0, 4.0, 4.1).forEach(::at)
        assertFalse(profile.stopActive)
        at(4.0)
        assertTrue(profile.stopActive)
        assertEquals(1, profile.alertSequence)
        repeat(10) { at(4.0) }
        assertEquals(1, profile.alertSequence)
    }

    @Test fun `required stop waits for an observed ascent to the configured trigger depth`() {
        listOf(2.0, 5.0, 30.0, 6.0, 5.1).forEach(::at)
        assertFalse(profile.stopActive)
        at(5.0)
        assertTrue(profile.stopActive)
        assertEquals(1, profile.alertSequence)
        repeat(20) { at(5.0) }
        val progress = profile.active!!.stopElapsedMs
        at(15.0); at(5.0)
        assertEquals(progress, profile.active!!.stopElapsedMs)
        assertEquals(1, profile.alertSequence)
    }

    @Test fun `completion remains completed until a fresh descent then ascent rearms a required stop`() {
        requiredStop()
        repeat(180) { at(5.0) }
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        repeat(20) { at(5.25); at(5.0); at(4.8) }
        assertEquals(2, profile.alertSequence)
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        at(5.3)
        assertEquals(DiveStopPhase.Armed, profile.active!!.phase)
        assertFalse(profile.stopActive)
        at(5.2)
        assertFalse(profile.stopActive)
        at(5.0)
        assertEquals(3, profile.alertSequence)
        assertEquals(DiveStopAlert.Started, profile.lastAlert)
        assertEquals(180, profile.active!!.remainingSeconds)
    }

    @Test fun `surfacing clears completion and next dive does not inherit its requirement`() {
        requiredStop(); repeat(180) { at(5.0) }; at(0.0)
        assertNull(profile.active)
        assertFalse(profile.stopActive)
        assertEquals(DiveLogOutcome.CompletedStop, profile.logs.single().outcome)
        at(2.0); at(8.0); at(5.0)
        assertTrue(profile.stopActive)
        assertFalse(profile.active!!.requiredByKnownProfile)
        assertEquals(180, profile.active!!.remainingSeconds)
        assertEquals(3, profile.alertSequence)
    }

    @Test fun `completing at the deep timing boundary cannot rearm on ascent or small depth noise`() {
        requiredStop(); repeat(180) { at(6.0) }
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        listOf(6.02, 5.98, 6.01, 5.9, 5.5, 5.0, 4.5).forEach(::at)
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        assertEquals(2, profile.alertSequence)
        at(5.3); at(5.0)
        assertEquals(3, profile.alertSequence)
    }

    @Test fun `computed NDL requirement survives ascent and persistence without treating unknown as zero`() {
        at(2.0); at(20.0)
        assertFalse(profile.active!!.requiredByKnownProfile)
        profile = profile.copy(decompression = DecompressionState(history = DecoHistory.Tracking,
            tissues = Buhlmann.equilibrium(), integratedEpochMs = 1_800_000_000_000L,
            readings = DecoReadings(0, 0.0, 0.0, false, 0.0)))
        at(20.0)
        assertTrue(profile.active!!.limitReached)
        profile = profile.copy(decompression = DecompressionTracker.unavailable(profile.decompression))
        at(6.0); at(5.0)
        assertTrue(profile.stopActive)
        assertTrue(DiveProfileCodec.decode(DiveProfileCodec.encode(profile)).active!!.limitReached)
    }

    @Test fun `completed dismissed checkpoint cannot restart its timer at the same depth`() {
        requiredStop(); repeat(180) { at(5.0) }
        profile = DiveProfileCodec.decode(DiveProfileCodec.encode(profile.copy(stopPresentation = DiveStopPresentation.Dismissed)))
        at(5.0); at(5.0)
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        assertEquals(DiveStopPresentation.Dismissed, profile.stopPresentation)
        assertNull(profile.lastAlert)
    }

    @Test fun `normal startup retires the old surface exercise without losing logs`() {
        val exercise = DiveProfileTracker.sample(DiveProfileState(settings = DiveSettings(stopDepthMeters = 0)),
            0.0, 1_000, 1_000, triggerAtSurface = true)
        val core = ControlCore(initialState = AppState(diveProfile = exercise))
        assertNull(core.state.diveProfile.active)
        assertEquals(5, core.state.diveProfile.settings.stopDepthMeters)
        assertEquals(1, core.state.diveProfile.logs.size)
        core.updateSensor(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA))
        assertNull(core.state.diveProfile.active)
    }
}
