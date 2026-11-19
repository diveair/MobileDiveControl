package com.mobiledivecontrol.core

import kotlin.test.*

class AirDisplayControlsTest {
    private fun state() = AppState(mode = AppMode.DiveSettings, housing = HousingState(inputEnabled = true),
        airAlerts = AirAlertState(menuOpen = true, page = AirPage.Gas, settings = AirAlertSettings(enabled = true)))
    private fun command(s: AppState, c: AirAlertCommand) = reduceAirAlerts(s, c, 0).state
    private fun field(s: AppState, f: AirField) = command(command(s, AirAlertCommand.SelectRow(airFields(s.airAlerts).indexOf(f))), AirAlertCommand.Activate)
    private fun key(s: AppState, event: HousingButtonEvent): AppState {
        val commands = routeAirAlerts(s, event)!!.commands
        return commands.fold(s) { current, c -> reduceDiveSettings(current, c as DiveSettingsCommand).state }
    }

    @Test fun `camera readout cycles through all six options in the requested order`() {
        var s = field(state().let { it.copy(airAlerts = it.airAlerts.copy(settings = it.airAlerts.settings.copy(hudMode = AirHudMode.Off))) }, AirField.Hud)
        for (mode in AirHudMode.entries) {
            assertEquals(mode, s.airAlerts.settings.hudMode)
            s = key(s, HousingButtonEvent.Right)
        }
        assertEquals(AirHudMode.BottomTime, s.airAlerts.settings.hudMode)
    }
    @Test fun `repeated OK on Back exits every alert level and then returns to the camera`() {
        var s = state().let { it.copy(airAlerts = it.airAlerts.copy(page = AirPage.Times,
            backStack = listOf(AirMenuLocation(AirPage.Home, 1), AirMenuLocation(AirPage.Schedule, 1)))) }
        s = field(s, AirField.Back)
        assertEquals(AirPage.Schedule, s.airAlerts.page)
        assertEquals(AirField.Back, airFields(s.airAlerts)[s.airAlerts.selectedRow])
        s = key(s, HousingButtonEvent.Ok)
        assertEquals(AirPage.Home, s.airAlerts.page)
        assertEquals(AirField.Back, airFields(s.airAlerts)[s.airAlerts.selectedRow])
        s = key(s, HousingButtonEvent.Ok)
        assertFalse(s.airAlerts.menuOpen)
        assertEquals(DiveSettingsField.Back, s.diveProfile.selectedField)
        s = reduceDiveSettings(s, DiveSettingsCommand.Confirm).state
        assertEquals(AppMode.CameraLive, s.mode)
    }
}
