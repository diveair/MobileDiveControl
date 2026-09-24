package com.mobiledivecontrol.core

import kotlin.test.*

class DiveStopControlsTest {
    private val reducer = ControlReducer()
    private val router = InputRouter()
    private fun started(): AppState = AppState(housing = HousingState(inputEnabled = true),
        diveProfile = DiveProfileTracker.sample(DiveProfileState(), 0.0, 1_000, 1_000, true))
    private fun ok(state: AppState) = reducer.applyRouteDecision(state, router.route(state, HousingButtonEvent.Ok)).state

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
        assertEquals(CameraRailAction.TrackHeading, active[0].value.action)
        assertEquals(CameraRailAction.SafetyStop, active[1].value.action)
        assertEquals(inactive, active.filter { it.value.action != CameraRailAction.SafetyStop })
        assertEquals(CameraCatalog.SAFETY_STOP_RAIL_INDEX, CameraCatalog.movePrimaryRail(0, 1, true))
        assertEquals(1, CameraCatalog.movePrimaryRail(0, 1, false))
        assertEquals(CameraCatalog.primaryRailEntries.lastIndex, CameraCatalog.movePrimaryRail(0, -1, true))
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
        repeat(180) { i -> state = state.copy(diveProfile = DiveProfileTracker.sample(state.diveProfile,
            0.0, (i + 2) * 1000L, (i + 2) * 1000L, true)) }
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
}
