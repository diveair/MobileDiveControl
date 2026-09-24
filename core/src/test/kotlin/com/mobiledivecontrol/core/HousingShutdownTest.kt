package com.mobiledivecontrol.core

import kotlin.test.*

class HousingShutdownTest {
    private val machine = SafetyStateMachine()
    private val reducer = ControlReducer()
    private val motorOn = PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(true))

    @Test fun `opening a verified surface housing latches shutdown before the pressure packet arrives`() {
        val sealed = SafetyState(sealState = SealState.Passed, coverOpen = false,
            sealConfidence = SealConfidence.ManufacturerMinimum, waterPressureKpa = 101.325)
        val opened = machine.apply(sealed, SafetySignal.CoverStateChanged(true)).state
        assertTrue(opened.vacuumReleasedPrompt)
        assertTrue(machine.apply(opened, SafetySignal.StartVacuumCheckRequested).effects.isEmpty())
        val closed = machine.apply(opened, SafetySignal.CoverStateChanged(false)).state
        assertTrue(closed.vacuumReleasedPrompt)
        assertTrue(machine.apply(closed, SafetySignal.StartVacuumCheckRequested).effects.isEmpty())
    }

    @Test fun `a held OK after venting cannot pump or operate camera controls`() {
        var state = AppState(housing = HousingState(connected = true, inputEnabled = true),
            safety = SafetyState(vacuumReleasedPrompt = true, coverOpen = true))
        val router = InputRouter()
        val initialCamera = state.camera
        repeat(100) {
            val result = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok), it)
            assertTrue(result.effects.isEmpty())
            state = result.state
        }
        assertEquals(initialCamera, state.camera)
        assertTrue(state.safety.vacuumReleasedPrompt)
        val bypass = reducer.reduce(state, SafetyCommand.StartVacuumCheck)
        assertFalse(motorOn in bypass.effects)
        val reset = reducer.reduce(state, SafetyCommand.ResetSealState)
        assertTrue(reset.state.safety.vacuumReleasedPrompt)
    }

    @Test fun `disconnect then a ready connection clears the choice and awaits fresh cover telemetry`() {
        val state = AppState(housing = HousingState(connected = true, inputEnabled = true),
            safety = SafetyState(vacuumReleasedPrompt = true, coverOpen = true))
        assertTrue(reducer.updateBleState(state, BleConnectionState.Ready).state.safety.vacuumReleasedPrompt)
        val disconnected = reducer.updateBleState(state, BleConnectionState.Reconnecting).state
        assertTrue(disconnected.safety.vacuumReleaseDisconnectObserved)
        assertTrue(disconnected.safety.vacuumReleasedPrompt)
        val ready = reducer.updateBleState(disconnected, BleConnectionState.Ready).state
        assertFalse(ready.safety.vacuumReleasedPrompt)
        assertNull(ready.safety.coverOpen) // await a fresh cover packet
        val fresh = machine.apply(ready.safety, SafetySignal.CoverStateChanged(true)).state
        assertTrue(motorOn in machine.apply(fresh, SafetySignal.StartVacuumCheckRequested).effects)
    }

    @Test fun `vacuum-like samples while venting cannot clear the shutdown latch`() {
        var state = SafetyState(vacuumReleasedPrompt = true, surfaceAmbientKpa = 101.325, waterPressureKpa = 101.325)
        repeat(20) { state = machine.apply(state, SafetySignal.BarometricPressureSample(85.0, it * 500L)).state }
        assertTrue(state.vacuumReleasedPrompt)
        assertNotEquals(SealState.Passed, state.sealState)
    }

    @Test fun `right only selects restart and OK starts the managed pump exactly once`() {
        var state = AppState(housing = HousingState(connected = true, inputEnabled = true),
            safety = SafetyState(vacuumReleasedPrompt = true, coverOpen = true, waterPressureKpa = 101.325))
        val camera = state.camera
        val router = InputRouter()
        val selected = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Right))
        assertTrue(selected.effects.isEmpty())
        state = selected.state
        assertEquals(VacuumReleaseChoice.RestartPump, state.safety.vacuumReleaseChoice)
        assertEquals(camera, state.camera)
        val started = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok))
        assertEquals(1, started.effects.count { it == motorOn })
        assertEquals(SealState.Vacuuming, started.state.safety.sealState)
        assertNotNull(started.state.safety.motorStartedAtEpochMs)
        assertFalse(started.state.safety.vacuumReleasedPrompt)
        assertEquals(VacuumReleaseChoice.ShutDown, started.state.safety.vacuumReleaseChoice)
        val replay = reducer.reduce(started.state, SafetyCommand.ConfirmVacuumReleaseChoice)
        assertFalse(motorOn in replay.effects)
    }

    @Test fun `left returns to shutdown and repeated OK never pumps`() {
        var state = AppState(housing = HousingState(connected = true, inputEnabled = true),
            safety = SafetyState(vacuumReleasedPrompt = true, coverOpen = true))
        val router = InputRouter()
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Right)).state
        state = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Left)).state
        assertEquals(VacuumReleaseChoice.ShutDown, state.safety.vacuumReleaseChoice)
        repeat(100) {
            val result = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok), it)
            assertTrue(result.effects.isEmpty())
            state = result.state
        }
        assertTrue(state.safety.vacuumReleasedPrompt)
    }

    @Test fun `restart choice cannot bypass closed cover disconnected link or underwater pressure`() {
        val base = AppState(housing = HousingState(connected = true, inputEnabled = true),
            safety = SafetyState(vacuumReleasedPrompt = true, coverOpen = true, waterPressureKpa = 101.325,
                vacuumReleaseChoice = VacuumReleaseChoice.RestartPump))
        listOf(base.copy(safety = base.safety.copy(coverOpen = false)),
            base.copy(safety = base.safety.copy(coverOpen = null)),
            base.copy(housing = base.housing.copy(connected = false)),
            base.copy(safety = base.safety.copy(waterPressureKpa = 200.0))).forEach { state ->
            val result = reducer.reduce(state, SafetyCommand.ConfirmVacuumReleaseChoice)
            assertTrue(result.state.safety.vacuumReleasedPrompt)
            assertFalse(motorOn in result.effects)
        }
        val lost = reducer.updateBleState(base, BleConnectionState.Reconnecting).state
        assertEquals(VacuumReleaseChoice.ShutDown, lost.safety.vacuumReleaseChoice)
    }

    @Test fun `each new vent defaults to shutdown and selection outside the banner is ignored`() {
        val sealed = SafetyState(sealState = SealState.Passed, coverOpen = false,
            sealConfidence = SealConfidence.ManufacturerMinimum, waterPressureKpa = 101.325,
            vacuumReleaseChoice = VacuumReleaseChoice.RestartPump)
        val vented = machine.apply(sealed, SafetySignal.CoverStateChanged(true)).state
        assertTrue(vented.vacuumReleasedPrompt)
        assertEquals(VacuumReleaseChoice.ShutDown, vented.vacuumReleaseChoice)
        val state = AppState()
        assertEquals(state, reducer.reduce(state,
            SafetyCommand.SelectVacuumReleaseChoice(VacuumReleaseChoice.RestartPump)).state)
    }
}
