package com.mobiledivecontrol.core

import kotlin.test.*

class DiveStopControlsTest {
    private val reducer = ControlReducer()
    private val router = InputRouter()
    private fun AppState.sample(depth: Double): AppState {
        val time = (diveProfile.lastSampleMs ?: 0) + 1_000
        return copy(diveProfile = DiveProfileTracker.sample(diveProfile, depth, time, time))
    }
    private fun qualifyingDive() = AppState(housing = HousingState(inputEnabled = true)).sample(20.0)
    private fun started() = qualifyingDive().sample(5.0)
    private fun AppState.heldAtTarget() = (1..5).fold(this) { s, _ -> s.sample(5.0) }
    private fun ok(state: AppState) = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok)).state

    @Test fun `deep boundary pauses on steady or descent and counts on a measured ascent`() {
        val holding = started().heldAtTarget()
        var state = holding.sample(6.4)
        assertEquals(DiveStopPhase.Holding, state.diveProfile.active!!.phase)
        state = state.sample(6.5)
        val held = state.diveProfile.active!!.stopElapsedMs
        repeat(6) { state = state.sample(6.5) }
        assertEquals(StopMotion.Steady, stopMotion(state.diveProfile.verticalMetersPerMinute))
        assertEquals(DiveStopPhase.TooDeep, state.diveProfile.active!!.phase)
        assertEquals(held, state.diveProfile.active!!.stopElapsedMs)
        repeat(6) { state = state.sample(6.9) }
        state = state.sample(6.5)
        assertEquals(DiveStopPhase.Holding, state.diveProfile.active!!.phase)
        assertEquals(held, state.diveProfile.active!!.stopElapsedMs)
        state = state.sample(6.4)
        assertEquals(held + 1_000, state.diveProfile.active!!.stopElapsedMs)
    }

    @Test fun `a steady seven metre reading can open the single confirmation from expanded view`() {
        val held = started().heldAtTarget()
        val lost = held.copy(diveProfile = DiveProfileTracker.unavailable(held.diveProfile))
        val fresh = lost.sample(7.0)
        assertFalse(fresh.diveProfile.continuationPending)
        val pending = fresh.sample(7.0)
        assertTrue(pending.diveProfile.continuationPending)
        assertFalse(pending.diveProfile.stopExpanded)
        assertFalse(ok(pending).diveProfile.stopBannerVisible)
        assertTrue(ok(pending).sample(5.5).diveProfile.stopExpanded)
    }

    @Test fun `continuation starts at seven metres and keeps one confirmation per excursion`() {
        val minimized = ok(started().heldAtTarget())
        assertFalse(minimized.sample(6.5).diveProfile.continuationPending)
        assertFalse(minimized.sample(6.99).diveProfile.continuationPending)
        var state = minimized.sample(6.5).sample(7.0)
        val before = state.diveProfile.active!!
        assertTrue(state.diveProfile.continuationPending)
        repeat(200) { state = state.sample(7.1) }
        assertTrue(state.diveProfile.continuationPending)
        assertEquals(before.stopElapsedMs, state.diveProfile.active!!.stopElapsedMs)
        val beforeOk = state
        assertEquals(listOf(DiveSettingsCommand.ConfirmContinuationReserve), router.route(state, HousingButtonEvent.Ok).commands)
        state = ok(state)
        assertEquals(beforeOk.camera, state.camera)
        assertEquals(beforeOk.airAlerts, state.airAlerts)
        assertEquals(beforeOk.diveProfile.decompression, state.diveProfile.decompression)
        val confirmed = state.diveProfile.active!!
        assertEquals(beforeOk.diveProfile.active!!.copy(continuation = DiveContinuation.ConfirmedByDiver,
            diverReserveConfirmations = 1, lastReserveConfirmationElapsedMs = confirmed.elapsedMs), confirmed)
        for (depth in listOf(6.4, 6.7, 6.2, 11.0)) {
            state = state.sample(depth)
            assertFalse(state.diveProfile.continuationPending)
        }
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(state.diveProfile)).active!!
        assertEquals(1, restored.diverReserveConfirmations)
        assertEquals(confirmed.lastReserveConfirmationElapsedMs, restored.lastReserveConfirmationElapsedMs)
        assertEquals(DiveContinuation.ConfirmedByDiver, restored.continuation)
        state = state.heldAtTarget().sample(7.0)
        assertTrue(state.diveProfile.continuationPending)
        assertEquals(2, ok(state).diveProfile.active!!.diverReserveConfirmations)
        assertEquals(before.startedAtEpochMs, state.diveProfile.active!!.startedAtEpochMs)
        assertTrue(state.diveProfile.logs.isEmpty())
    }

    @Test fun `pressure replay keeps tissues NDL gas estimate and dive log continuous across acknowledgment`() {
        val gas = DiveGas()
        val surface = DecompressionTracker.sample(DecompressionState(), 0.0, STANDARD_SURFACE_PRESSURE_KPA / 100.0, 0, 0, gas)
        var now = 0L
        val initial = AppState(housing = HousingState(inputEnabled = true), diveProfile = DiveProfileState(
            decompression = DecompressionTracker.initialize(surface, gas)))
        val seed = ControlCore(initialState = initial, monotonicMs = { now })
        fun pressure(core: ControlCore, depth: Double) {
            core.updateSensor(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA + depth * FRESHWATER_KPA_PER_METER),
                receivedAt = java.time.Instant.ofEpochMilli(now))
            core.tickDiveProfile(now)
        }
        for (depth in listOf(2.0, 12.0, 10.0, 4.0, 5.0) + List(5) { 5.0 }) {
            now += 1_000
            pressure(seed, depth)
        }
        val minimized = ok(seed.state)
        val observed = AirAlertEngine.observe(minimized.airAlerts, 180.0).copy(surfaceBarPerMinute = 1.5)
        val confirmed = ControlCore(initialState = minimized.copy(airAlerts = observed), monotonicMs = { now })
        val waiting = ControlCore(initialState = confirmed.state, monotonicMs = { now })
        for (depth in listOf(6.5, 7.0, 8.0, 10.0, 11.0)) {
            now += 1_000
            pressure(confirmed, depth)
            pressure(waiting, depth)
            if (depth == 7.0) {
                assertTrue(confirmed.state.diveProfile.continuationPending)
                confirmed.handleButtonPayload(byteArrayOf(0x50), receivedAt = java.time.Instant.ofEpochMilli(now))
            }
        }
        val actual = confirmed.state
        val reference = waiting.state
        assertTrue(reference.diveProfile.continuationPending)
        assertEquals(1, actual.diveProfile.active!!.diverReserveConfirmations)
        assertEquals(reference.diveProfile.decompression, actual.diveProfile.decompression)
        assertNotNull(actual.diveProfile.decompression.readings?.ndlSeconds)
        assertNotEquals(minimized.diveProfile.decompression.tissues, actual.diveProfile.decompression.tissues)
        assertEquals(reference.airAlerts, actual.airAlerts)
        val estimate = assertNotNull(AirAlertEngine.estimate(actual.airAlerts, actual.diveProfile, now))
        assertTrue(estimate.projected && estimate.pressureBar < 180.0)
        assertEquals(reference.diveProfile.active!!.samples, actual.diveProfile.active!!.samples)
        assertEquals(minimized.diveProfile.active!!.startedAtEpochMs, actual.diveProfile.active!!.startedAtEpochMs)
        assertEquals(minimized.diveProfile.active!!.elapsedMs + 5_000, actual.diveProfile.active!!.elapsedMs)
        assertEquals(minimized.diveProfile.active!!.stopElapsedMs, actual.diveProfile.active!!.stopElapsedMs)
        assertTrue(actual.diveProfile.logs.isEmpty())
    }

    @Test fun `an initial descent and stale or decompression data cannot manufacture a reserve confirmation`() {
        assertFalse(qualifyingDive().sample(22.0).diveProfile.continuationPending)
        val entered = ok(started().heldAtTarget()).sample(7.0)
        val lost = entered.copy(diveProfile = DiveProfileTracker.unavailable(entered.diveProfile))
        assertFalse(lost.diveProfile.continuationPending)
        assertTrue(lost.sample(7.0).diveProfile.continuationPending)
        val deco = entered.diveProfile.copy(decompression = DecompressionState(history = DecoHistory.Tracking,
            readings = DecoReadings(0, 2.0, 0.0, false, 0.0)))
        assertFalse(deco.continuationPending)
        assertEquals(0, ok(entered.copy(diveProfile = deco)).diveProfile.active!!.diverReserveConfirmations)
        assertFalse(entered.sample(5.9).diveProfile.continuationPending)
    }

    @Test fun `a held reserve confirmation cannot open a menu or change a camera setting`() {
        val state = ok(started().heldAtTarget()).sample(7.0)
        val core = ControlCore(initialState = state)
        val start = java.time.Instant.ofEpochMilli(50_000)
        core.handleButtonPayload(byteArrayOf(0x50), receivedAt = start)
        val camera = core.state.camera
        assertEquals(1, core.state.diveProfile.active!!.diverReserveConfirmations)
        for (offset in 50L..750L step 50L) {
            core.handleButtonPayload(byteArrayOf(0x50), receivedAt = start.plusMillis(offset))
            assertEquals(camera, core.state.camera)
        }
        core.handleButtonPayload(byteArrayOf(0x50), receivedAt = start.plusMillis(1_000))
        assertNotEquals(camera.focusedZone, core.state.camera.focusedZone)
    }

    @Test fun `higher priority alarms retain OK while a continuation confirmation is pending`() {
        val pending = ok(started().heldAtTarget()).sample(7.0)
        val criticalGas = pending.copy(airAlerts = AirAlertState(gasWarning = AirGasWarning(AirGasLevel.Critical, nextAtMs = 0)))
        assertFalse(criticalGas.continuationOwnsOk())
        assertIs<DiveSettingsCommand.Alerts>(router.route(criticalGas, HousingButtonEvent.Ok).commands.single())
        assertTrue(ok(criticalGas).diveProfile.continuationPending)
        val seal = pending.copy(safety = SafetyState(sealMonitor = SealMonitor(banner = HousingSealTransitions.Banner.B07)))
        assertFalse(seal.continuationOwnsOk())
        assertEquals(seal, reduceDiveSettings(seal, DiveSettingsCommand.ConfirmContinuationReserve).state)
        val afterPress = ok(seal)
        assertTrue(afterPress.diveProfile.continuationPending)
        assertEquals(seal.camera, afterPress.camera)
    }

    @Test fun `OK minimizes the active stop without resetting it and cannot activate the covered seal prompt`() {
        val state = started().copy(safety = SafetyState(coverOpen = true))
        val minimized = ok(state)
        assertEquals(DiveStopPresentation.Minimized, minimized.diveProfile.stopPresentation)
        assertEquals(state.diveProfile.active, minimized.diveProfile.active)
        assertEquals(state.safety, minimized.safety)
        assertFalse(minimized.diveProfile.stopExpanded)
        assertTrue(minimized.diveProfile.stopActive)
    }

    @Test fun `active-only entry follows Track Heading without changing capture mode indices`() {
        val active = CameraCatalog.primaryRailWithStop(true)
        val inactive = CameraCatalog.primaryRailWithStop(false)
        assertEquals(CameraRailAction.ShutOff, active[0].value.action)
        assertEquals(CameraRailAction.TrackHeading, active[1].value.action)
        assertEquals(CameraRailAction.SafetyStop, active[2].value.action)
        assertEquals(inactive, active.filter { it.value.action != CameraRailAction.SafetyStop })
        assertEquals(CameraCatalog.SAFETY_STOP_RAIL_INDEX, CameraCatalog.movePrimaryRail(0, 1, true))
        assertEquals(1, CameraCatalog.movePrimaryRail(0, 1, false))
        assertEquals(CameraCatalog.SHUT_OFF_RAIL_INDEX, CameraCatalog.movePrimaryRail(0, -1, true))
    }

    @Test fun `housing navigation restores the same minimized stop and leaves recording intact`() {
        var state = ok(started()).copy(camera = CameraState(recording = true, focusedZone = CameraUiZone.ModeRail, highlightedPrimaryIndex = 0))
        val session = state.diveProfile.active
        state = reducer.reduce(state, CameraCommand.NavigateDown).state
        assertEquals(CameraCatalog.SAFETY_STOP_RAIL_INDEX, state.camera.highlightedPrimaryIndex)
        state = ok(state)
        assertEquals(DiveStopPresentation.Expanded, state.diveProfile.stopPresentation)
        assertEquals(session, state.diveProfile.active)
        assertTrue(state.camera.recording)
        assertNotEquals(CameraUiZone.ModeRail, state.camera.focusedZone)
    }

    @Test fun `completion expands a minimized timer then OK closes it and removes menu action`() {
        var state = ok(started())
        repeat(180) { state = state.sample(5.0) }
        assertEquals(DiveStopPhase.Complete, state.diveProfile.active!!.phase)
        assertTrue(state.diveProfile.stopExpanded)
        assertFalse(state.diveProfile.stopActive)
        val closed = ok(state)
        assertEquals(DiveStopPresentation.Dismissed, closed.diveProfile.stopPresentation)
        assertEquals(state.diveProfile.active, closed.diveProfile.active)
        assertEquals(closed, reducer.reduce(closed, CameraCommand.ActivateModeRailEntry(CameraCatalog.SAFETY_STOP_RAIL_INDEX)).state)
    }

    @Test fun `watching and armed dives cannot expose the stop menu action`() {
        assertFalse(DiveProfileState().stopActive)
        val watching = DiveProfileState(active = DiveSession(0, DiveSettings()))
        assertFalse(watching.stopActive)
        assertFalse(watching.copy(active = watching.active!!.copy(phase = DiveStopPhase.Armed)).stopActive)
    }

    @Test fun `the approach leaves the display and housing controls available until the timer starts`() {
        val deep = qualifyingDive()
        assertFalse(deep.diveProfile.stopBannerVisible)
        assertFalse(DiveSettingsCommand.AcknowledgeStop in router.route(deep, HousingButtonEvent.Ok).commands)
        val approaching = deep.sample(7.0)
        assertFalse(approaching.diveProfile.stopBannerVisible)
        assertFalse(DiveSettingsCommand.AcknowledgeStop in router.route(approaching, HousingButtonEvent.Ok).commands)
        assertFalse(DiveProfileTracker.unavailable(approaching.diveProfile).stopBannerVisible)
        assertFalse(approaching.sample(6.0).diveProfile.stopBannerVisible)
        val holding = approaching.sample(5.0)
        assertTrue(holding.diveProfile.stopExpanded)
        assertEquals(DiveStopAlert.Started, holding.diveProfile.lastAlert)
        assertEquals(DiveStopPhase.Holding, holding.diveProfile.active!!.phase)
    }

    @Test fun `dismissal from a previous dive does not suppress the next timer`() {
        val previous = AppState(diveProfile = DiveProfileState(stopPresentation = DiveStopPresentation.Dismissed))
        assertTrue(previous.sample(20.0).sample(7.0).sample(5.0).diveProfile.stopExpanded)
    }

    @Test fun `holding housing OK to minimize a stop cannot confirm a camera action underneath`() {
        val core = ControlCore(initialState = started())
        fun press(time: Long) = core.handleButtonPayload(byteArrayOf(0x50), receivedAt = java.time.Instant.ofEpochMilli(time))
        val minimized = press(3_000)
        assertEquals(DiveStopPresentation.Minimized, minimized.state.diveProfile.stopPresentation)
        repeat(10) { i ->
            val held = press(3_050L + i * 50L)
            assertEquals(minimized.state.camera, held.state.camera)
            assertTrue(held.effects.isEmpty())
        }
    }
}
