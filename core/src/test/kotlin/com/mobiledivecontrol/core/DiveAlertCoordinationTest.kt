package com.mobiledivecontrol.core

import kotlin.test.*

class DiveAlertCoordinationTest {
    private fun state() = AppState(permissions = PermissionsState(camera = true, microphone = true), housing = HousingState(inputEnabled = true, connected = true),
        camera = CameraState(activeMode = CameraModeId.ProVideo),
        diveProfile = DiveProfileState(sensorAvailable = true, depthMeters = 4.0, lastSampleMs = 60_000,
            active = DiveSession(1000, DiveSettings(stopDepthMeters = 4), elapsedMs = 60_000,
                maxDepthMeters = 20.0, phase = DiveStopPhase.Holding, stopStarted = true, stopElapsedMs = 30_000)),
        airAlerts = AirAlertState(settings = defaultAirAlertSettings().copy(intervalSeconds = 30, autoDismiss = false),
            sessionId = 1000, elapsedMs = 60_000, lastTickMs = 60_000))

    @Test fun `air deadline can fire while stop is expanded and its OK dismisses only air`() {
        var state = state()
        state = state.copy(airAlerts = AirAlertEngine.tick(state.airAlerts, state.diveProfile, 60_000,
            airAlertPresentationBlocked(state)))
        assertNotNull(state.airAlerts.prompt)
        assertTrue(airAlertVisible(state))
        val result = ControlReducer().applyRouteDecision(state, InputRouter().route(state, HousingButtonEvent.Ok)).state
        assertNull(result.airAlerts.prompt)
        assertEquals(state.diveProfile, result.diveProfile)
    }

    @Test fun `shutter records while pressure entry and stop timer remain untouched`() {
        val state = state().copy(airAlerts = state().airAlerts.copy(entry = AirPressureEntry(AirEntryPurpose.Reading, "180")))
        val decision = InputRouter().route(state, HousingButtonEvent.Shutter)
        assertEquals(listOf(CameraCommand.ToggleVideoRecording), decision.commands)
        val result = ControlReducer().applyRouteDecision(state, decision).state
        assertTrue(result.camera.recording)
        assertEquals(state.airAlerts, result.airAlerts)
        assertEquals(state.diveProfile, result.diveProfile)
    }

    @Test fun `surface waiting period suppresses reminders immediately without deleting the profile`() {
        val state = state()
        val dive = state.diveProfile.copy(depthMeters = 0.4)
        val air = AirAlertEngine.tick(state.airAlerts.copy(prompt = AirPrompt(1, false, 59_000)), dive, 60_000)
        assertNotNull(dive.active)
        assertNull(air.prompt)
    }
}
