package com.mobiledivecontrol.core

import kotlin.test.*

class AirSliderRangeTest {
    private val settings = AirAlertSettings(enabled = true, sliderMinBar = 50.0, sliderMaxBar = 180.0)
    private val profile = DiveProfileState(sensorAvailable = true, depthMeters = 20.0,
        active = DiveSession(1, DiveSettings(), maxDepthMeters = 20.0))
    private fun reading(s: AirAlertSettings = settings) = AppState(diveProfile = profile, airAlerts = AirAlertState(
        settings = s, sessionId = 1, elapsedMs = 300000, ambientMinutes = 15.0,
        observation = AirObservation(200.0, 0, 0.0), rateAnchor = AirObservation(200.0, 0, 0.0),
        prompt = AirPrompt(1, true, 0)))
    private fun command(state: AppState, cmd: AirAlertCommand) = reduceAirAlerts(state, cmd, 300000).state
    private fun field(state: AppState, field: AirField) = command(command(state,
        AirAlertCommand.SelectRow(airFields(state.airAlerts).indexOf(field))), AirAlertCommand.Activate)

    @Test fun `slider limits save from tank settings and reject reversed or collapsed ranges`() {
        var state = AppState(mode = AppMode.DiveSettings, airAlerts = AirAlertState(settings = settings, menuOpen = true, page = AirPage.Gas))
        state = field(state, AirField.SliderMinimum)
        state = command(state, AirAlertCommand.SetPressure(25))
        state = command(state, AirAlertCommand.SavePressure)
        assertEquals(25.0, state.airAlerts.settings.sliderMinBar)
        state = field(state, AirField.SliderMaximum)
        state = command(state, AirAlertCommand.SetPressure(240))
        state = command(state, AirAlertCommand.SavePressure)
        assertEquals(240.0, state.airAlerts.settings.sliderMaxBar)
        state = field(state, AirField.SliderMinimum)
        state = command(state, AirAlertCommand.SetPressure(240))
        state = command(state, AirAlertCommand.SavePressure)
        assertNotNull(state.airAlerts.entry!!.error)
        assertEquals(25.0, state.airAlerts.settings.sliderMinBar)
        state = command(state, AirAlertCommand.CancelEntry)
        state = field(state, AirField.SliderMaximum)
        state = command(state, AirAlertCommand.SetPressure(10))
        state = command(state, AirAlertCommand.SavePressure)
        assertNotNull(state.airAlerts.entry!!.error)
        assertEquals(240.0, state.airAlerts.settings.sliderMaxBar)
    }
    @Test fun `touch and fine or coarse housing adjustments stop at configured limits`() {
        var state = command(reading(), AirAlertCommand.SetPressure(10))
        assertEquals("50", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(-1))
        assertEquals("50", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(1, fine = true))
        assertEquals("51", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.SetPressure(999))
        assertEquals("180", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(1, fine = true))
        assertEquals("180", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(-1))
        assertEquals("175", state.airAlerts.entry!!.text)
    }
    @Test fun `predicted slider position is bounded while the independent estimate stays truthful`() {
        val state = reading()
        assertEquals(180, airSuggestedPressure(state.airAlerts, profile, 300000))
        assertEquals(200.0, AirAlertEngine.estimate(state.airAlerts, profile, 300000)!!.pressureBar)
        val shown = airSuggestedEntry(state.airAlerts, profile, 300000)
        assertTrue(shown.edited) // The UI must identify the bounded value as TANK PRESSURE.
        assertEquals("180", shown.text)
        val preview = AirAlertEngine.readingEstimate(state.airAlerts.copy(entry = shown), profile, 300000)
        val accepted = command(state, AirAlertCommand.PromptAction(0))
        assertEquals(preview, AirAlertEngine.estimate(accepted.airAlerts, profile, 300000))
        assertEquals(180.0, accepted.airAlerts.observation!!.bar)
        val low = state.airAlerts.copy(observation = AirObservation(30.0, 300000, 15.0))
        assertEquals(50, airSuggestedPressure(low, profile, 300000))
        assertTrue(airSuggestedEntry(low, profile, 300000).edited)
        assertFalse(airSuggestedEntry(state.airAlerts.copy(settings = AirAlertSettings()), profile, 300000).edited)
        assertEquals(30.0, AirAlertEngine.estimate(low, profile, 300000)!!.pressureBar)
    }
    @Test fun `PSIG limits convert exactly and unit switching retains physical pressures`() {
        val unit = AirPressureUnit.Psig
        val s = settings.copy(unit = unit, sliderMinBar = unit.toBar(500), sliderMaxBar = unit.toBar(3000))
        assertEquals(500..3000, s.pressureRange(AirEntryPurpose.Reading))
        var state = command(reading(s), AirAlertCommand.SetPressure(5000))
        assertEquals("3000", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(-1, fine = true))
        assertEquals("2990", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.NudgePressure(-1))
        assertEquals("2985", state.airAlerts.entry!!.text)
        state = command(state, AirAlertCommand.SetPressure(0))
        assertEquals("500", state.airAlerts.entry!!.text)
        val menu = AppState(airAlerts = AirAlertState(settings = s, menuOpen = true, page = AirPage.Gas))
        val switched = field(menu, AirField.Unit).airAlerts.settings
        assertEquals(AirPressureUnit.Bar, switched.unit)
        assertEquals(s.sliderMinBar, switched.sliderMinBar)
        assertEquals(s.sliderMaxBar, switched.sliderMaxBar)
        assertEquals(34..207, switched.pressureRange(AirEntryPurpose.Reading))
    }
    @Test fun `starting pressure and reserve editors keep full range and direct gauge entry is not clipped`() {
        for (purpose in listOf(AirEntryPurpose.Start, AirEntryPurpose.TestStart, AirEntryPurpose.Reserve, AirEntryPurpose.SliderMinimum, AirEntryPurpose.SliderMaximum)) {
            assertEquals(0..350, settings.pressureRange(purpose))
        }
        val state = reading().let { it.copy(airAlerts = it.airAlerts.copy(
            entry = AirPressureEntry(AirEntryPurpose.Reading, "40", keypad = true, edited = true))) }
        val saved = command(state, AirAlertCommand.SavePressure)
        assertEquals(40.0, saved.airAlerts.observation!!.bar)
        assertNull(saved.airAlerts.entry)
    }
    @Test fun `range persists and legacy preferences migrate with the original full slider range`() {
        assertEquals(settings, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(settings)))
        val custom = settings.copy(message = "MY AIR CHECK", unit = AirPressureUnit.Psig)
        val old = AirAlertSettingsCodec.encode(custom).replace("version=6", "version=2")
            .lineSequence().filterNot { it.startsWith("sliderMin=") || it.startsWith("sliderMax=") }.joinToString("\n")
        val migrated = AirAlertSettingsCodec.decode(old)
        assertEquals(custom.copy(sliderMinBar = 0.0, sliderMaxBar = 350.0), migrated)
        for (invalid in listOf(settings.copy(sliderMinBar = 180.0), settings.copy(sliderMaxBar = 40.0),
            settings.copy(sliderMaxBar = Double.NaN), settings.copy(sliderMinBar = -1.0))) {
            assertFailsWith<IllegalArgumentException> { AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(invalid)) }
        }
    }
    @Test fun `test and production use identical saved slider limits`() {
        val test = AirSimulationEngine.tick(AirSimulationEngine.start(settings, DiveSettings(), 0), 10000)
        assertEquals(settings.pressureRange(AirEntryPurpose.Reading), test.air.settings.pressureRange(AirEntryPurpose.Reading))
        assertEquals(180, airSuggestedPressure(test.air, test.profile, 10000))
    }
}
