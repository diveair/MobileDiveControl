package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.test.*

class DiveProfileTest {
    @Test fun `quarter metre target window includes its boundaries and distinguishes shallow from deep`() {
        assertEquals(StopDepthZone.OnTarget, stopDepthZone(4.75, 5.0))
        assertEquals(StopDepthZone.OnTarget, stopDepthZone(5.25, 5.0))
        assertEquals(StopDepthZone.TooShallow, stopDepthZone(4.749, 5.0))
        assertEquals(StopDepthZone.TooDeep, stopDepthZone(5.251, 5.0))
        assertEquals(StopDepthZone.Unknown, stopDepthZone(null, 5.0))
        assertEquals(StopDepthZone.OnTarget, stopDepthZone(0.0, 0.0))
    }
    @Test fun `requested live surface trigger uses actual samples and the same three minute timer`() {
        var now = 1_000L
        val core = ControlCore(monotonicMs = { now }, triggerSafetyStopAtSurface = true)
        fun freshSurface() {
            core.updateSensor(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA))
        }
        freshSurface()
        assertEquals(DiveStopPhase.Holding, core.state.diveProfile.active!!.phase)
        assertEquals(0, core.state.diveProfile.active!!.settings.stopDepthMeters)
        assertEquals(DiveStopAlert.Started, core.state.diveProfile.lastAlert)
        repeat(179) { now += 1000; freshSurface() }
        assertEquals(1, core.state.diveProfile.active!!.remainingSeconds)
        now += 1000; freshSurface()
        assertEquals(DiveStopPhase.Complete, core.state.diveProfile.active!!.phase)
        assertEquals(DiveStopAlert.Completed, core.state.diveProfile.lastAlert)
        repeat(60) { now += 1000; freshSurface() }
        assertNotNull(core.state.diveProfile.active) // completion remains visible on the surface
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(core.state.diveProfile))
        assertEquals(0, restored.active!!.settings.stopDepthMeters)
        now += 3000
        assertFalse(core.tickDiveProfile().state.diveProfile.sensorAvailable)
    }

    private class Dive(settings: DiveSettings = DiveSettings()) {
        var state = DiveProfileState(settings = settings)
        var now = 0L
        fun at(depth: Double, after: Long = 1000): DiveProfileState {
            now += after
            state = DiveProfileTracker.sample(state, depth, now, 1_700_000_000_000 + now)
            return state
        }
        fun ascend() { at(2.0); at(5.0); at(12.0); at(30.0); at(8.0); at(6.0); at(5.0) }
    }

    @Test fun `descent through stop depth never starts or credits a stop`() {
        val d = Dive()
        listOf(2.0, 4.0, 5.0, 6.0, 12.0, 30.0).forEach { d.at(it) }
        assertEquals(DiveStopPhase.Armed, d.state.active!!.phase)
        assertEquals(0, d.state.active!!.stopElapsedMs)
        assertEquals(0, d.state.alertSequence)
    }

    @Test fun `ascent triggers once and completion needs all 180 fresh seconds`() {
        val d = Dive(); d.ascend()
        assertEquals(DiveStopAlert.Started, d.state.lastAlert)
        assertEquals(180, d.state.active!!.remainingSeconds)
        repeat(179) { d.at(5.0) }
        assertEquals(1, d.state.active!!.remainingSeconds)
        assertEquals(DiveStopPhase.Holding, d.state.active!!.phase)
        d.at(5.0)
        assertEquals(DiveStopPhase.Complete, d.state.active!!.phase)
        assertEquals(DiveStopAlert.Completed, d.state.lastAlert)
        val sequence = d.state.alertSequence
        repeat(30) { d.at(5.0) }
        assertEquals(sequence, d.state.alertSequence)
    }

    @Test fun `both depth boundaries count but excursions pause and resume without crediting the crossing`() {
        val d = Dive(); d.ascend(); d.at(4.0); d.at(6.0)
        assertEquals(2000, d.state.active!!.stopElapsedMs)
        d.at(3.9)
        assertEquals(DiveStopPhase.TooShallow, d.state.active!!.phase)
        repeat(5) { d.at(3.9) }
        d.at(4.0)
        assertEquals(2000, d.state.active!!.stopElapsedMs)
        d.at(5.0)
        d.at(6.1)
        assertEquals(DiveStopPhase.TooDeep, d.state.active!!.phase)
        d.at(5.0)
        assertEquals(3000, d.state.active!!.stopElapsedMs)
        assertEquals(1, d.state.alertSequence)
    }

    @Test fun `missing disconnected duplicate invalid and delayed data never earn stop time`() {
        val d = Dive(); d.ascend(); d.at(5.0)
        val progress = d.state.active!!.stopElapsedMs
        d.state = DiveProfileTracker.tick(d.state, d.now + 3000, true)
        assertFalse(d.state.sensorAvailable)
        assertNull(d.state.verticalMetersPerMinute)
        d.at(5.0, 60000)
        assertEquals(progress, d.state.active!!.stopElapsedMs)
        d.at(5.0, 3000) // exact freshness boundary is excluded
        assertEquals(progress, d.state.active!!.stopElapsedMs)
        val accepted = d.state
        d.state = DiveProfileTracker.sample(d.state, 5.0, d.now, 0)
        assertEquals(accepted, d.state)
        d.state = DiveProfileTracker.sample(d.state, 5.0, d.now - 1, 0)
        assertEquals(accepted, d.state)
        d.state = DiveProfileTracker.sample(d.state, Double.NaN, d.now + 1, 0)
        assertFalse(d.state.sensorAvailable)
        d.at(5.0)
        assertEquals(progress, d.state.active!!.stopElapsedMs)
        d.state = DiveProfileTracker.tick(d.state, d.now, false)
        d.at(5.0)
        assertEquals(progress, d.state.active!!.stopElapsedMs)
        assertTrue(d.state.active!!.profileIncomplete)
    }

    @Test fun `tick cannot finish a stop without a new reading`() {
        val d = Dive(); d.ascend()
        d.state = DiveProfileTracker.tick(d.state, d.now + 180000, true)
        assertEquals(0, d.state.active!!.stopElapsedMs)
        assertFalse(d.state.sensorAvailable)
    }

    @Test fun `a missed band still prompts but never awards time`() {
        val d = Dive(); d.at(2.0); d.at(30.0); d.at(2.5)
        assertEquals(DiveStopPhase.TooShallow, d.state.active!!.phase)
        assertEquals(DiveStopAlert.Started, d.state.lastAlert)
        assertEquals(0, d.state.active!!.stopElapsedMs)
    }

    @Test fun `gas does not invent deco math and custom duration and target are respected`() {
        val d = Dive(DiveSettings(DiveGas(32), stopDepthMeters = 4, stopDurationSeconds = 300))
        d.ascend()
        assertEquals(DiveStopPhase.Armed, d.state.active!!.phase)
        d.at(4.0)
        repeat(299) { d.at(4.0) }
        assertEquals(1, d.state.active!!.remainingSeconds)
        d.at(4.0)
        assertEquals(DiveStopPhase.Complete, d.state.active!!.phase)
        assertEquals("Nitrox 32", d.state.active!!.settings.gas.label)
    }

    @Test fun `known deep requirements come from recorded maximum depth`() {
        val d = Dive(); d.at(29.9)
        assertFalse(d.state.active!!.requiredByKnownProfile)
        d.at(30.0)
        assertTrue(d.state.active!!.requiredByKnownProfile)
    }

    @Test fun `a renewed deep excursion rearms even a completed stop`() {
        val d = Dive(); d.ascend(); repeat(180) { d.at(5.0) }
        d.at(12.0)
        assertEquals(DiveStopPhase.Armed, d.state.active!!.phase)
        assertEquals(0, d.state.active!!.stopElapsedMs)
        d.at(5.0)
        assertEquals(DiveStopAlert.Started, d.state.lastAlert)
        assertEquals(3, d.state.alertSequence)
    }

    @Test fun `a fresh zero depth ends dive and next dive has its own maximum`() {
        val d = Dive(); d.ascend(); d.at(0.4)
        d.at(0.4, 60000)
        assertNotNull(d.state.active)
        repeat(60) { d.at(0.4) }
        assertNotNull(d.state.active)
        d.at(0.0)
        assertNull(d.state.active)
        assertEquals(DiveLogOutcome.IncompleteStop, d.state.logs.single().outcome)
        assertEquals(30.0, d.state.logs.single().maxDepthMeters)
        d.at(2.0)
        assertEquals(2.0, d.state.active!!.maxDepthMeters)
        assertEquals(180, d.state.active!!.remainingSeconds)
    }

    @Test fun `movement tells rising from sinking and clears on sensor loss`() {
        val d = Dive(); d.at(8.0); d.at(7.5)
        assertTrue(DiveProfileTracker.movementLabel(d.state).contains("RISING"))
        repeat(6) { d.at(8.0 + it * 0.2) }
        assertTrue(DiveProfileTracker.movementLabel(d.state).contains("SINKING"))
        d.state = DiveProfileTracker.unavailable(d.state)
        assertEquals("DEPTH UNAVAILABLE", DiveProfileTracker.movementLabel(d.state))
    }

    @Test fun `restart preserves a completed stop instead of triggering it again`() {
        val d = Dive(DiveSettings(DiveGas(21, 35))); d.ascend(); repeat(180) { d.at(5.0) }
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(d.state))
        assertEquals(d.state.settings, restored.settings)
        assertEquals(d.state.active!!.samples, restored.active!!.samples)
        assertFalse(restored.sensorAvailable)
        assertNull(restored.lastSampleMs)
        assertEquals(180000, restored.active!!.stopElapsedMs)
        assertEquals(DiveStopPhase.Complete, restored.active!!.phase)
        assertTrue(restored.active!!.profileIncomplete)
        repeat(61) { d.at(0.0) }
        assertEquals(d.state.logs, DiveProfileCodec.decode(DiveProfileCodec.encode(d.state)).logs)
    }

    @Test fun `corrupt checkpoint and impossible settings are rejected`() {
        assertFails { DiveProfileCodec.decode(byteArrayOf(1, 2, 3)) }
        assertFails { DiveGas(80, 30) }
        assertFails { DiveSettings(stopDurationSeconds = 10) }
        assertFails { DiveSettings(stopDepthMeters = 1) }
    }

    @Test fun `settings work with housing input and lock during a dive even when sensor is lost`() {
        val router = InputRouter()
        val reducer = ControlReducer()
        var state = AppState(mode = AppMode.DiveSettings, housing = HousingState(inputEnabled = true),
            diveProfile = DiveProfileState(selectedField = DiveSettingsField.Gas))
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok)).state
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Right)).state
        assertEquals(DiveGas(32), state.diveProfile.settings.gas)
        state = reducer.reduce(state, DiveSettingsCommand.CloseGasMenu).state
        val d = Dive(state.diveProfile.settings); d.ascend()
        state = state.copy(diveProfile = DiveProfileTracker.unavailable(d.state))
        val before = state.diveProfile.settings
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Right)).state
        assertEquals(before, state.diveProfile.settings)
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.BackOrSafety)).state
        assertEquals(AppMode.CameraLive, state.mode)
    }

    @Test fun `core receives identical pressure heartbeats but rejects malformed intervals and expiry`() {
        var now = 1000L
        val core = ControlCore(monotonicMs = { now })
        fun at(depth: Double) {
            now += 1000
            core.updateSensor(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA + depth * FRESHWATER_KPA_PER_METER))
        }
        at(2.0); at(30.0); at(5.0); at(5.0)
        val progress = core.state.diveProfile.active!!.stopElapsedMs
        now += 200
        val bad = core.handleNotificationPayload("1625", byteArrayOf(1), Instant.EPOCH)
        assertFalse(bad.state.diveProfile.sensorAvailable)
        at(5.0)
        assertEquals(progress, core.state.diveProfile.active!!.stopElapsedMs)
        now += 3000
        assertFalse(core.tickDiveProfile().state.diveProfile.sensorAvailable)
    }
}
