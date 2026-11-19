package com.mobiledivecontrol.core

import kotlin.test.*

class AirReadingEstimateTest {
    private val profile = DiveProfileState(sensorAvailable = true, depthMeters = 20.0,
        active = DiveSession(1, DiveSettings(), maxDepthMeters = 20.0))
    private val baseline = AirObservation(200.0, 0, 0.0)
    private fun first() = AirAlertState(settings = AirAlertSettings(enabled = true), sessionId = 1,
        elapsedMs = 300000, ambientMinutes = 15.0, observation = baseline, rateAnchor = baseline,
        prompt = AirPrompt(1, true, 0))
    private fun edit(air: AirAlertState, value: Int) = reduceAirAlerts(AppState(airAlerts = air, diveProfile = profile),
        AirAlertCommand.SetPressure(value), 300000).state.airAlerts
    private fun draft(air: AirAlertState, dive: DiveProfileState = profile) = AirAlertEngine.readingEstimate(air, dive, 300000)

    @Test fun `first pressure drop calculates live before OK and matches the saved estimate`() {
        val air = first()
        assertNull(draft(air)!!.bottomMinutes)
        val edited = edit(air, 170)
        val estimate = draft(edited)!!
        assertEquals(170.0, estimate.pressureBar)
        assertTrue(estimate.bottomMinutes!! in 16.0..18.0)
        assertTrue(draft(edit(air, 160))!!.bottomMinutes!! < estimate.bottomMinutes!!)
        assertEquals(baseline, edited.observation)
        assertNull(edited.surfaceBarPerMinute)
        val saved = reduceAirAlerts(AppState(airAlerts = edited, diveProfile = profile), AirAlertCommand.SavePressure, 300000).state.airAlerts
        assertNull(saved.prompt)
        assertNull(saved.entry)
        assertEquals(estimate, AirAlertEngine.estimate(saved, profile, 300000))
    }
    @Test fun `correction changes bottom time but leaves expected pressure and previous rate untouched`() {
        val air = AirAlertEngine.observe(first(), 170.0).copy(elapsedMs = 600000, ambientMinutes = 30.0,
            prompt = AirPrompt(2, true, 0))
        val edited = edit(air, 130)
        val original = AirAlertEngine.estimate(air, profile, 300000)!!
        assertEquals(140.0, original.pressureBar)
        assertEquals(original, AirAlertEngine.estimate(edited, profile, 300000))
        assertEquals(130.0, draft(edited)!!.pressureBar)
        assertTrue(draft(edited)!!.bottomMinutes!! < original.bottomMinutes!!)
        assertEquals(2.0, edited.surfaceBarPerMinute)
        val cancelled = reduceAirAlerts(AppState(airAlerts = edited, diveProfile = profile), AirAlertCommand.CancelEntry, 300000).state.airAlerts
        assertEquals(air.observation, cancelled.observation)
        assertEquals(air.rateAnchor, cancelled.rateAnchor)
        assertEquals(air.surfaceBarPerMinute, cancelled.surfaceBarPerMinute)
        assertEquals(original, draft(cancelled))
    }
    @Test fun `PSIG draft uses the same canonical pressure as saving`() {
        val unit = AirPressureUnit.Psig
        val initial = AirObservation(unit.toBar(3000), 0, 0.0)
        val air = first().copy(settings = AirAlertSettings(enabled = true, unit = unit), observation = initial, rateAnchor = initial)
        val edited = edit(air, 2500)
        assertEquals(unit.toBar(2500), draft(edited)!!.pressureBar)
        val saved = reduceAirAlerts(AppState(airAlerts = edited, diveProfile = profile), AirAlertCommand.SavePressure, 300000).state.airAlerts
        assertEquals(draft(edited), AirAlertEngine.estimate(saved, profile, 300000))
    }
    @Test fun `unusable readings and missing consumption data cannot invent bottom time`() {
        assertNull(draft(edit(first(), 200))!!.bottomMinutes)
        assertNull(draft(edit(first(), 210))!!.bottomMinutes)
        assertNull(draft(edit(first().copy(observation = null, rateAnchor = null), 170))!!.bottomMinutes)
        assertNull(draft(edit(first().copy(depthGap = true), 170))!!.bottomMinutes)
        val edited = edit(first(), 170)
        assertNull(draft(edited, profile.copy(sensorAvailable = false))!!.bottomMinutes)
        assertNull(draft(edited, profile.copy(stopDecompressionPaused = true))!!.bottomMinutes)
        assertNull(draft(edited, profile.copy(active = profile.active!!.copy(limitReached = true)))!!.bottomMinutes)
        assertNull(draft(edited.copy(entry = edited.entry!!.copy(text = ""))))
        assertNull(draft(edited.copy(entry = edited.entry!!.copy(text = "351"))))
    }
    @Test fun `fresh receiver cannot override the pressure being previewed or create a cross source rate`() {
        val air = first().copy(settings = AirAlertSettings(enabled = true, source = AirSource.Receiver, mapping = AirPressureMapping(verified = true)),
            receiver = AirReceiverState(connected = true, liveBar = 180.0, liveAtMs = 300000))
        assertEquals(180.0, AirAlertEngine.estimate(air, profile, 300000)!!.pressureBar)
        val edited = edit(air, 170)
        assertEquals(170.0, draft(edited)!!.pressureBar)
        assertNotNull(draft(edited)!!.bottomMinutes)
        assertEquals(180.0, AirAlertEngine.estimate(edited, profile, 300000)!!.pressureBar)
        val receiverHistory = air.copy(observation = baseline.copy(receiver = true), rateAnchor = baseline.copy(receiver = true), surfaceBarPerMinute = 2.0)
        assertNull(draft(edit(receiverHistory, 170))!!.bottomMinutes)
    }
}
