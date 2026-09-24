package com.mobiledivecontrol.core

import kotlin.test.*

class DiveLogDeletionTest {
    private val first = DiveSession(1000, DiveSettings(), elapsedMs = 300000, maxDepthMeters = 12.0)
    private val second = DiveSession(2000, DiveSettings(), elapsedMs = 600000, maxDepthMeters = 20.0)
    private val router = InputRouter()
    private val reducer = ControlReducer()
    private fun state(profile: DiveProfileState) = AppState(mode = AppMode.DiveSettings,
        housing = HousingState(connected = true, inputEnabled = true), diveProfile = profile)
    private fun press(state: AppState, button: HousingButtonEvent) = reducer.applyRouteDecision(state, router.route(state, button)).state

    private fun openDelete(state: AppState) = press(press(state, HousingButtonEvent.Ok), HousingButtonEvent.Ok)

    @Test fun `left and right browse while OK opens the selected log menu and delete confirmation defaults to cancel`() {
        var state = state(DiveProfileState(logs = listOf(first, second)))
        state = press(state, HousingButtonEvent.Right)
        assertEquals(1, state.diveProfile.logIndex)
        state = press(state, HousingButtonEvent.Left)
        assertEquals(0, state.diveProfile.logIndex)
        state = press(state, HousingButtonEvent.Ok)
        assertEquals(first, state.diveProfile.logMenuSession)
        assertNull(state.diveProfile.pendingLogDeletion)
        state = press(state, HousingButtonEvent.Ok)
        assertEquals(first, state.diveProfile.pendingLogDeletion)
        assertFalse(state.diveProfile.deleteLogSelected)
        state = press(state, HousingButtonEvent.Ok)
        assertNull(state.diveProfile.pendingLogDeletion)
        assertEquals(first, state.diveProfile.logMenuSession)
        assertEquals(listOf(first, second), state.diveProfile.logs)
        state = press(state, HousingButtonEvent.Down)
        state = press(state, HousingButtonEvent.Ok)
        assertNull(state.diveProfile.logMenuSession)
        assertEquals(AppMode.DiveSettings, state.mode)
        state = press(state, HousingButtonEvent.Down)
        assertEquals(DiveSettingsField.Gas, state.diveProfile.selectedField)
    }

    @Test fun `right then OK deletes only the selected saved log and persists the result`() {
        val profile = DiveProfileState(logs = listOf(first, second), logIndex = 1,
            decompression = DecompressionState(tissues = Buhlmann.equilibrium()))
        var state = openDelete(state(profile))
        state = press(state, HousingButtonEvent.Right)
        assertEquals(listOf(first, second), state.diveProfile.logs)
        state = press(state, HousingButtonEvent.Ok)
        assertEquals(listOf(first), state.diveProfile.logs)
        assertEquals(0, state.diveProfile.logIndex)
        assertEquals(DiveSettingsField.Log, state.diveProfile.selectedField)
        assertNull(state.diveProfile.logMenuSession)
        assertEquals(profile.decompression, state.diveProfile.decompression)
        assertEquals(listOf(first), DiveProfileCodec.decode(DiveProfileCodec.encode(state.diveProfile)).logs)
    }

    @Test fun `active dive cannot be deleted but a saved dive can while monitoring continues`() {
        val active = DiveSession(3000, DiveSettings(), maxDepthMeters = 18.0)
        var state = state(DiveProfileState(active = active, logs = listOf(first)))
        state = press(state, HousingButtonEvent.Ok)
        assertNull(state.diveProfile.pendingLogDeletion)
        assertNull(state.diveProfile.logMenuSession)
        state = state.copy(diveProfile = state.diveProfile.copy(logIndex = 1))
        state = openDelete(state)
        state = reducer.reduce(state, DiveSettingsCommand.ConfirmLogDeletion).state
        assertEquals(active, state.diveProfile.active)
        assertTrue(state.diveProfile.logs.isEmpty())
        assertEquals(0, state.diveProfile.logIndex)
    }

    @Test fun `confirmation keeps its exact target when a new log shifts the list`() {
        var state = press(state(DiveProfileState(logs = listOf(first, second))), HousingButtonEvent.Ok)
        val newer = DiveSession(3000, DiveSettings())
        state = state.copy(diveProfile = state.diveProfile.copy(logs = listOf(newer, first, second)))
        state = press(state, HousingButtonEvent.Ok)
        state = reducer.reduce(state, DiveSettingsCommand.ConfirmLogDeletion).state
        assertEquals(listOf(newer, second), state.diveProfile.logs)
    }

    @Test fun `last log deletion leaves an empty list and a stale confirm does nothing`() {
        var state = openDelete(state(DiveProfileState(logs = listOf(first))))
        state = reducer.reduce(state, DiveSettingsCommand.ConfirmLogDeletion).state
        assertTrue(state.diveProfile.logs.isEmpty())
        assertEquals(0, state.diveProfile.logIndex)
        assertEquals(state, reducer.reduce(state, DiveSettingsCommand.ConfirmLogDeletion).state)
        state = press(state, HousingButtonEvent.Ok)
        assertNull(state.diveProfile.logMenuSession)
        assertNull(state.diveProfile.pendingLogDeletion)
    }
}
