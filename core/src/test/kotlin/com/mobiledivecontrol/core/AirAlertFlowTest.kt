package com.mobiledivecontrol.core

import kotlin.test.*

class AirAlertFlowTest {
    private fun state(debug: Boolean = false) = AppState(mode = AppMode.DiveSettings,
        airAlerts = AirAlertState(menuOpen = true, debugControls = debug))
    private fun command(state: AppState, command: AirAlertCommand, now: Long = 1000) = reduceAirAlerts(state, command, now).state
    private fun field(state: AppState, field: AirField, command: AirAlertCommand = AirAlertCommand.Activate): AppState {
        val index = airFields(state.airAlerts).indexOf(field)
        assertTrue(index >= 0, "$field is reachable on ${state.airAlerts.page}")
        return command(command(state, AirAlertCommand.SelectRow(index)), command)
    }
    private fun key(state: AppState, key: HousingButtonEvent): AppState {
        var result = state
        val route = routeAirAlerts(state, key) ?: error("Key not routed: $key")
        route.commands.forEach { result = command(result, (it as DiveSettingsCommand.Alerts).command) }
        return result
    }

    @Test fun `default reminder needs one press to enable pressure tracking without unrelated controls`() {
        var s = state()
        assertEquals(AirMode.Reminder, airMode(s.airAlerts.settings))
        assertEquals(listOf(AirField.Mode, AirField.Timing, AirField.Style, AirField.Back), airFields(s.airAlerts))
        s = key(s, HousingButtonEvent.Right)
        assertEquals(AirMode.Manual, airMode(s.airAlerts.settings))
        assertEquals(AirConfirmation.Every, s.airAlerts.settings.confirmation)
        assertEquals(1, s.airAlerts.settings.confirmEvery)
        assertTrue(AirField.Tank in airFields(s.airAlerts))
        assertFalse(AirField.ReceiverSetup in airFields(s.airAlerts))
    }
    @Test fun `confirmation cadence changes in place and custom list is one level deep`() {
        var s = field(state(), AirField.Mode)
        s = field(s, AirField.Confirmation, AirAlertCommand.Adjust(1))
        assertEquals(2, s.airAlerts.settings.confirmEvery)
        s = field(s, AirField.Confirmation)
        assertEquals(AirPage.Confirmations, s.airAlerts.page)
        assertEquals(AirConfirmation.Selected, s.airAlerts.settings.confirmation)
        s = field(s, AirField.Back)
        assertEquals(AirPage.Home, s.airAlerts.page)
        assertEquals(AirField.Back, airFields(s.airAlerts)[s.airAlerts.selectedRow])
    }
    @Test fun `specific times hide interval and repeated schedule hides custom editor`() {
        var s = field(state(), AirField.Timing)
        assertEquals(listOf(AirField.Schedule, AirField.Interval, AirField.Back), airFields(s.airAlerts))
        s = field(field(s, AirField.Schedule), AirField.Schedule)
        assertEquals(AirSchedule.AtTimes, s.airAlerts.settings.schedule)
        assertEquals(listOf(AirField.Schedule, AirField.Times, AirField.Back), airFields(s.airAlerts))
        s = field(s, AirField.Times)
        val before = s.airAlerts.settings.timesSeconds
        s = field(s, AirField.AddTime)
        assertNotNull(s.airAlerts.entry)
        s = command(s, AirAlertCommand.CancelEntry)
        assertEquals(before, s.airAlerts.settings.timesSeconds)
        s = field(s, AirField.AddTime)
        s = command(command(command(s, AirAlertCommand.Key(1)), AirAlertCommand.Key(4)), AirAlertCommand.SavePressure)
        assertEquals(before + 1500, s.airAlerts.settings.timesSeconds)
        s = field(s, AirField.Back)
        assertEquals(AirPage.Schedule, s.airAlerts.page)
        s = field(s, AirField.Back)
        assertEquals(AirPage.Home, s.airAlerts.page)
    }
    @Test fun `time editor accepts full interval range and never records pressure`() {
        var s = field(field(state(), AirField.Timing), AirField.Interval)
        s = s.copy(airAlerts = s.airAlerts.copy(entry = s.airAlerts.entry!!.copy(text = "21600")))
        s = key(s, HousingButtonEvent.Shutter)
        assertEquals(21600, s.airAlerts.settings.intervalSeconds)
        assertNull(s.airAlerts.observation)
    }
    @Test fun `duplicate sequence times are rejected without losing alerts`() {
        var s = state().copy(airAlerts = AirAlertState(menuOpen = true, page = AirPage.Times))
        s = field(s, AirField.AddTime)
        s = command(command(s, AirAlertCommand.Key(4)), AirAlertCommand.SavePressure)
        assertEquals(listOf(300, 600, 900), s.airAlerts.settings.timesSeconds)
        assertNotNull(s.airAlerts.entry!!.error)
    }
    @Test fun `saving starting pressure also arms the baseline with no extra confirmation row`() {
        var s = field(state(), AirField.Mode)
        s = field(field(s, AirField.Tank), AirField.Start)
        s = key(s, HousingButtonEvent.Shutter)
        assertTrue(s.airAlerts.startArmed)
        assertEquals(200.0, s.airAlerts.settings.startBar)
        assertNull(s.airAlerts.entry)
        s = AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(s.airAlerts.settings)).let { s.copy(airAlerts = AirAlertState(settings = it)) }
        assertFalse(s.airAlerts.startArmed)
    }
    @Test fun `visibility has one control for duration and hold until dismissed`() {
        var s = field(state(), AirField.Style)
        assertEquals(6, airFields(s.airAlerts).size)
        s = field(s, AirField.Duration)
        assertFalse(s.airAlerts.settings.autoDismiss)
        s = field(s, AirField.Duration, AirAlertCommand.Adjust(1))
        assertTrue(s.airAlerts.settings.autoDismiss)
        assertEquals(20, s.airAlerts.settings.displaySeconds)
    }
    @Test fun `notification test controls are absent even in debug fixtures`() {
        assertEquals(airFields(state().airAlerts), airFields(state(debug = true).airAlerts))
        assertTrue(AirField.entries.none { "test" in it.label.lowercase() })
        assertFalse(AirAlertState().debugControls)
    }
    @Test fun `zoom from a pressure alert opens adjusts and shutter saves in two actions`() {
        var s = state().let { it.copy(housing = HousingState(inputEnabled = true),
            diveProfile = DiveProfileState(active = DiveSession(1, DiveSettings()), depthMeters = 20.0, sensorAvailable = true),
            airAlerts = it.airAlerts.copy(settings = AirAlertSettings(enabled = true), sessionId = 1, elapsedMs = 300000,
                ambientMinutes = 15.0, observation = AirObservation(170.0, 300000, 15.0), rateAnchor = AirObservation(200.0, 0, 0.0),
                surfaceBarPerMinute = 2.0, prompt = AirPrompt(1, true, 1000))) }
        s = key(s, HousingButtonEvent.ZoomIn)
        assertEquals("165", s.airAlerts.entry!!.text)
        s = key(s, HousingButtonEvent.Shutter)
        assertNull(s.airAlerts.entry)
        assertEquals(165.0, s.airAlerts.observation!!.bar)
        assertNull(s.airAlerts.prompt)
        assertNotNull(AirAlertEngine.estimate(s.airAlerts, s.diveProfile, 1000)!!.bottomMinutes)
    }
    @Test fun `tank settings cannot change in dive but alerts can be turned off`() {
        val dive = DiveProfileState(active = DiveSession(1, DiveSettings()))
        var s = state().copy(diveProfile = dive, airAlerts = AirAlertState(menuOpen = true, settings = AirAlertSettings(enabled = true)))
        s = field(field(s, AirField.Tank), AirField.Start)
        assertNull(s.airAlerts.entry)
        s = field(s, AirField.Back)
        s = field(s, AirField.Mode)
        assertFalse(s.airAlerts.settings.enabled)
    }
    @Test fun `all supported modes and pages fit at most eight nonduplicated rows`() {
        AirPage.entries.forEach { page ->
            listOf(AirSource.Manual, AirSource.Receiver).forEach { source ->
                val air = AirAlertState(page = page, debugControls = true, settings = AirAlertSettings(enabled = true, source = source),
                    receiver = AirReceiverState(connected = true, devices = listOf(AirDevice("a", "receiver")), characteristics = listOf(AirCharacteristic("s", "c", true, true))))
                val fields = airFields(air)
                assertTrue(fields.size <= 8, "$page has ${fields.size} rows")
                assertEquals(fields.distinct(), fields)
                assertEquals(AirField.Back, fields.last())
            }
        }
    }
    @Test fun `one time tracking asks once without irrelevant cadence controls`() {
        val settings = AirAlertSettings(enabled = true, schedule = AirSchedule.Once, confirmEvery = 2)
        assertTrue(settings.needsConfirmation(1))
        assertFalse(AirField.Confirmation in airFields(AirAlertState(settings = settings)))
        assertFalse(settings.copy(confirmation = AirConfirmation.Never).needsConfirmation(1))
        assertTrue(AirField.Confirmation in airFields(AirAlertState(settings = settings.copy(source = AirSource.Receiver), receiver = AirReceiverState(connected = true))))
    }
}
