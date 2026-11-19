package com.mobiledivecontrol.core

import kotlin.test.*

class AirSimulationTest {
    private fun begin(settings: AirAlertSettings = AirAlertSettings(), now: Long = 0): AppState {
        var state = AppState(mode = AppMode.DiveSettings, airAlerts = AirAlertState(settings = settings, debugControls = true, menuOpen = true,
            entry = AirPressureEntry(AirEntryPurpose.TestStart, settings.unit.display(settings.startBar).toString())))
        state = reduceAirAlerts(state, AirAlertCommand.SavePressure, now).state
        assertNotNull(state.airAlerts.simulation)
        return state
    }
    private fun tick(s: AppState, now: Long) = s.copy(airAlerts = AirAlertEngine.tick(s.airAlerts, s.diveProfile, now))
    private fun cmd(s: AppState, command: AirAlertCommand, now: Long) = reduceAirAlerts(s, command, now).state
    private fun input(s: AppState, key: HousingButtonEvent, now: Long): AppState {
        var state = s
        routeAirAlerts(state, key)!!.commands.forEach { state = cmd(state, (it as DiveSettingsCommand.Alerts).command, now) }
        return state
    }

    @Test fun `ten real seconds advance one interval and request pressure without touching live state`() {
        var s = begin()
        val liveBefore = s.airAlerts.copy(simulation = null, lastTickMs = null)
        s = tick(s, 9500)
        assertNull(s.airAlerts.simulation!!.air.prompt)
        assertEquals(285000L, s.airAlerts.simulation!!.air.elapsedMs)
        s = tick(s, 10000)
        val test = s.airAlerts.simulation!!
        assertEquals(300000L, test.air.elapsedMs)
        assertEquals(15.0, test.air.ambientMinutes)
        assertTrue(test.air.prompt!!.confirmation)
        assertEquals(liveBefore, s.airAlerts.copy(simulation = null, lastTickMs = null))
        assertNull(s.diveProfile.active)
        assertTrue(s.diveProfile.logs.isEmpty())
    }
    @Test fun `reported drop teaches expected pressure and the second correction updates consumption`() {
        var s = tick(begin(), 10000)
        s = cmd(s, AirAlertCommand.SetPressure(170), 10000)
        s = input(s, HousingButtonEvent.Ok, 10000)
        assertEquals(2.0, s.airAlerts.simulation!!.air.surfaceBarPerMinute!!, 0.0001)
        assertNull(s.airAlerts.simulation!!.air.prompt)
        assertNull(s.airAlerts.simulation!!.air.entry)
        assertNotNull(AirAlertEngine.estimate(s.airAlerts.simulation!!.air, s.airAlerts.simulation!!.profile, 10000)!!.bottomMinutes)
        s = tick(s, 20000)
        val second = s.airAlerts.simulation!!
        assertEquals(2, second.air.prompt!!.number)
        assertEquals(140, airSuggestedPressure(second.air, second.profile, 20000))
        s = cmd(s, AirAlertCommand.SetPressure(130), 20000)
        s = input(s, HousingButtonEvent.Ok, 20000)
        assertEquals("More air used than estimated", s.airAlerts.simulation!!.air.comparison)
        assertEquals(40.0 / 15.0, s.airAlerts.simulation!!.air.surfaceBarPerMinute!!, 0.0001)
        assertNull(s.airAlerts.observation)
        assertNull(s.airAlerts.surfaceBarPerMinute)
    }
    @Test fun `clock pauses while input is adjusted and resumes at a full ten seconds`() {
        var s = tick(begin(), 10000)
        s = input(s, HousingButtonEvent.ZoomIn, 10001)
        s = tick(s, 99000)
        assertEquals(300000L, s.airAlerts.simulation!!.air.elapsedMs)
        assertNotNull(s.airAlerts.simulation!!.air.entry)
        s = input(s, HousingButtonEvent.Ok, 99000)
        s = tick(s, 108999)
        assertNull(s.airAlerts.simulation!!.air.prompt)
        s = tick(s, 109000)
        assertNotNull(s.airAlerts.simulation!!.air.prompt)
    }
    @Test fun `unchanged first pressure cannot manufacture an expected rate`() {
        var s = tick(begin(), 10000)
        s = input(s, HousingButtonEvent.Ok, 10000)
        assertNull(s.airAlerts.simulation!!.air.surfaceBarPerMinute)
        assertNull(AirAlertEngine.estimate(s.airAlerts.simulation!!.air, s.airAlerts.simulation!!.profile, 10000)!!.bottomMinutes)
        s = tick(s, 20000)
        s = cmd(s, AirAlertCommand.SetPressure(190), 20000)
        s = input(s, HousingButtonEvent.Ok, 20000)
        assertNotNull(s.airAlerts.simulation!!.air.surfaceBarPerMinute)
    }
    @Test fun `left and right fine adjust and zoom coarse adjust the same slider and OK saves`() {
        var s = tick(begin(), 10000)
        s = input(s, HousingButtonEvent.Left, 10000)
        assertEquals("199", s.airAlerts.simulation!!.air.entry!!.text)
        assertTrue(s.airAlerts.simulation!!.air.entry!!.edited)
        assertEquals(200.0, s.airAlerts.simulation!!.air.observation!!.bar)
        s = input(s, HousingButtonEvent.ZoomIn, 10000)
        assertEquals("194", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.Right, 10000)
        assertEquals("195", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.ZoomOut, 10000)
        assertEquals("200", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.ZoomIn, 10000)
        assertEquals("195", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.Ok, 10000)
        assertEquals(195.0, s.airAlerts.simulation!!.air.observation!!.bar)
    }
    @Test fun `PSIG slider has correct steps and learns consumption in canonical bar`() {
        var s = tick(begin(AirAlertSettings(unit = AirPressureUnit.Psig, startBar = AirPressureUnit.Psig.toBar(3000))), 10000)
        s = input(s, HousingButtonEvent.Left, 10000)
        assertEquals("2990", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.ZoomIn, 10000)
        assertEquals("2985", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.ZoomOut, 10000)
        assertEquals("2990", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.ZoomIn, 10000)
        assertEquals("2985", s.airAlerts.simulation!!.air.entry!!.text)
        s = input(s, HousingButtonEvent.Ok, 10000)
        assertEquals(AirPressureUnit.Psig.toBar(2985), s.airAlerts.simulation!!.air.observation!!.bar)
    }
    @Test fun `default starting pressure persists but test readings and arming never do`() {
        val original = AirAlertSettings(source = AirSource.Receiver, enabled = false)
        var s = begin(original)
        s = cmd(s, AirAlertCommand.StopTest, 100)
        s = s.copy(airAlerts = s.airAlerts.copy(entry = AirPressureEntry(AirEntryPurpose.TestStart, "210")))
        s = cmd(s, AirAlertCommand.SavePressure, 1000)
        assertEquals(210.0, s.airAlerts.settings.startBar)
        assertEquals(AirSource.Receiver, s.airAlerts.settings.source)
        assertFalse(s.airAlerts.settings.enabled)
        assertFalse(s.airAlerts.startArmed)
        assertEquals(210.0, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(s.airAlerts.settings)).startBar)
        s = input(s, HousingButtonEvent.Down, 2000)
        assertNull(s.airAlerts.simulation)
        assertNull(s.airAlerts.observation)
    }
    @Test fun `real dive start and release mode terminate simulation`() {
        val s = begin()
        val live = s.copy(diveProfile = DiveProfileState(active = DiveSession(123, DiveSettings()), depthMeters = 20.0, sensorAvailable = true))
        assertNull(tick(live, 1000).airAlerts.simulation)
        val release = s.copy(airAlerts = s.airAlerts.copy(debugControls = false))
        assertNull(tick(release, 1000).airAlerts.simulation)
    }
    @Test fun `slider offers keypad and returns without losing its value`() {
        var s = tick(begin(), 10000)
        s = input(s, HousingButtonEvent.Up, 10000)
        assertTrue(s.airAlerts.simulation!!.air.entry!!.keypad)
        assertFalse(s.airAlerts.simulation!!.air.entry!!.edited)
        s = cmd(s, AirAlertCommand.Key(0), 10000)
        s = cmd(s, AirAlertCommand.Key(6), 10000)
        s = cmd(s, AirAlertCommand.Key(9), 10000)
        s = cmd(s, AirAlertCommand.UseKeypad(false), 10000)
        assertEquals("170", s.airAlerts.simulation!!.air.entry!!.text)
        assertTrue(s.airAlerts.simulation!!.air.entry!!.edited)
        assertFalse(s.airAlerts.simulation!!.air.entry!!.keypad)
        s = input(s, HousingButtonEvent.Ok, 10000)
        assertEquals(170.0, s.airAlerts.simulation!!.air.observation!!.bar)
    }

    @Test fun `test respects reminder mode and every second confirmation`() {
        val reminder = tick(begin(AirAlertSettings(confirmation = AirConfirmation.Never)), 10000).airAlerts.simulation!!
        assertFalse(reminder.air.prompt!!.confirmation)
        var s = tick(begin(AirAlertSettings(confirmEvery = 2)), 10000)
        assertFalse(s.airAlerts.simulation!!.air.prompt!!.confirmation)
        s = input(s, HousingButtonEvent.Ok, 10000)
        s = tick(s, 20000)
        assertTrue(s.airAlerts.simulation!!.air.prompt!!.confirmation)
        assertEquals(2, s.airAlerts.simulation!!.air.prompt!!.number)
    }
    @Test fun `once and sequence stop at configured final alert and use each time and action`() {
        var once = tick(begin(AirAlertSettings(schedule = AirSchedule.Once)), 10000)
        once = input(once, HousingButtonEvent.Down, 10000)
        once = tick(once, 20000)
        assertTrue(once.airAlerts.simulation!!.complete)
        assertNull(once.airAlerts.simulation!!.air.prompt)
        assertEquals(300000L, once.airAlerts.simulation!!.air.elapsedMs)
        val settings = AirAlertSettings(schedule = AirSchedule.AtTimes, timesSeconds = listOf(120, 600),
            sequenceMessages = mapOf(120 to "CHECK BUDDY", 600 to "CHECK AIR"),
            sequenceActions = mapOf(120 to AirSequenceAction.Reminder, 600 to AirSequenceAction.Pressure))
        var s = tick(begin(settings), 10000)
        assertEquals(120000L, s.airAlerts.simulation!!.air.elapsedMs)
        assertEquals("CHECK BUDDY", s.airAlerts.simulation!!.air.settings.messageFor(1))
        assertFalse(s.airAlerts.simulation!!.air.prompt!!.confirmation)
        s = input(s, HousingButtonEvent.Ok, 10000)
        s = tick(s, 15000)
        assertEquals(360000L, s.airAlerts.simulation!!.air.elapsedMs)
        s = tick(s, 20000)
        assertEquals(600000L, s.airAlerts.simulation!!.air.elapsedMs)
        assertEquals("CHECK AIR", s.airAlerts.simulation!!.air.settings.messageFor(2))
        assertTrue(s.airAlerts.simulation!!.air.prompt!!.confirmation)
        s = input(s, HousingButtonEvent.Down, 20000)
        s = tick(s, 30000)
        assertTrue(s.airAlerts.simulation!!.complete)
        assertNull(s.airAlerts.simulation!!.air.prompt)
    }
    @Test fun `test shares real skip and keypad back controls and only ends between alerts`() {
        var s = tick(begin(), 10000)
        val baseline = s.airAlerts.simulation!!.air.observation
        s = input(s, HousingButtonEvent.Up, 10000)
        s = input(s, HousingButtonEvent.BackOrSafety, 10000)
        assertFalse(s.airAlerts.simulation!!.air.entry!!.keypad)
        s = cmd(s, AirAlertCommand.SetPressure(160), 10000)
        s = input(s, HousingButtonEvent.Down, 10000)
        assertNotNull(s.airAlerts.simulation)
        assertNull(s.airAlerts.simulation!!.air.entry)
        assertNull(s.airAlerts.simulation!!.air.prompt)
        assertEquals(baseline, s.airAlerts.simulation!!.air.observation)
        s = input(s, HousingButtonEvent.Down, 10000)
        assertNull(s.airAlerts.simulation)
    }
    @Test fun `test retains appearance and receiver configuration without inventing a connection`() {
        val settings = AirAlertSettings(source = AirSource.Receiver, confirmation = AirConfirmation.Never,
            colour = AirColour.Red, opacityPercent = 45, displaySeconds = 5, message = "AIR STATUS", flash = false)
        var s = tick(begin(settings), 10000)
        assertEquals(settings.copy(enabled = true), s.airAlerts.simulation!!.air.settings)
        assertFalse(s.airAlerts.simulation!!.air.receiver.connected)
        assertNull(AirAlertEngine.liveBar(s.airAlerts.simulation!!.air, 10000))
        s = tick(s, 15000)
        assertNull(s.airAlerts.simulation!!.air.prompt)
        assertEquals(200.0, s.airAlerts.simulation!!.air.observation!!.bar)
    }
}
