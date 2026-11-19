package com.mobiledivecontrol.core

import kotlin.test.*

class AirCameraReadoutTest {
    private val dive = DiveProfileState(sensorAvailable = true, depthMeters = 20.0,
        active = DiveSession(1, DiveSettings(), elapsedMs = 420_000))
    private val manual = AirObservation(170.0, 300_000, 15.0)
    private val air = AirAlertState(settings = AirAlertSettings(enabled = true),
        sessionId = 1, elapsedMs = 420_000, ambientMinutes = 21.0,
        observation = manual, lastManualObservation = manual, surfaceBarPerMinute = 2.0)
    private fun read(mode: AirHudMode, state: AirAlertState = air, now: Long = 1_000) =
        airCameraReadout(state.copy(settings = state.settings.copy(hudMode = mode)), dive, now)

    @Test fun `last pressure reports the saved reading and its age instead of predicted pressure`() {
        assertEquals("LAST TANK · 170 BAR · 2 MIN AGO", read(AirHudMode.LastPressure)!!.text)
        assertEquals("LAST TANK · 2466 PSIG · 2 MIN AGO",
            read(AirHudMode.LastPressure, air.copy(settings = air.settings.copy(unit = AirPressureUnit.Psig)))!!.text)
        assertEquals("LAST TANK · 170 BAR · <1 MIN AGO",
            read(AirHudMode.LastPressure, air.copy(elapsedMs = 310_000))!!.text)
    }
    @Test fun `bottom time uses the gas estimate and remains unavailable without consumption data`() {
        val estimate = AirAlertEngine.estimate(air, dive, 1_000)!!.bottomMinutes!!.toInt()
        assertEquals("EST. BOTTOM TIME · $estimate MIN", read(AirHudMode.BottomTime)!!.text)
        assertNull(read(AirHudMode.BottomTime, air.copy(surfaceBarPerMinute = null)))
        assertNull(read(AirHudMode.BottomTime, air.copy(depthGap = true)))
        assertEquals("EST. BOTTOM TIME · 0 MIN", read(AirHudMode.BottomTime,
            air.copy(observation = manual.copy(bar = 0.0)))!!.text)
        assertNull(read(AirHudMode.Off))
        assertNull(airCameraReadout(
            air.copy(settings = air.settings.copy(hudMode = AirHudMode.BottomTime)), DiveProfileState(), 1_000))
    }
    @Test fun `bottom time stays hidden until available including its pulse phase`() {
        val surface = DiveProfileState()
        val ready = AirAlertState(settings = AirAlertSettings(enabled = true, hudMode = AirHudMode.TimeAndLast))
        assertNull(airCameraReadout(ready, surface, 3_999))
        assertEquals("LAST TANK · NO READING", airCameraReadout(ready, surface, 4_000)!!.text)
        assertNull(airCameraReadout(ready.copy(settings = ready.settings.copy(hudMode = AirHudMode.Off)), surface, 0))
        assertNull(airCameraReadout(ready.copy(settings = ready.settings.copy(enabled = false)), surface, 0))
        assertNull(airCameraReadout(ready.copy(settings = ready.settings.copy(confirmation = AirConfirmation.Never)), surface, 0))
    }

    @Test fun `pulse modes alternate on four second boundaries`() {
        val first = air.copy(elapsedMs = 432_000)
        val second = air.copy(elapsedMs = 436_000)
        assertEquals(read(AirHudMode.BottomTime, first, 3_999), read(AirHudMode.TimeAndLast, first, 3_999))
        assertEquals(read(AirHudMode.LastPressure, second, 4_000), read(AirHudMode.TimeAndLast, second, 4_000))
        assertEquals(read(AirHudMode.BottomTime, first, 3_999), read(AirHudMode.TimeAndLive, first, 3_999))
        assertEquals(read(AirHudMode.LivePressure, second, 4_000), read(AirHudMode.TimeAndLive, second, 4_000))
    }
    @Test fun `live readout requires a verified connected fresh receiver and never substitutes manual pressure`() {
        val receiver = air.copy(settings = air.settings.copy(source = AirSource.Receiver,
            mapping = AirPressureMapping(verified = true)),
            receiver = AirReceiverState(connected = true, liveBar = 150.0, liveAtMs = 1_000))
        assertEquals(AirCameraReadout("LIVE · 150 BAR"), read(AirHudMode.LivePressure, receiver, 16_000))
        assertTrue(read(AirHudMode.LivePressure, receiver, 16_001)!!.unavailable)
        assertTrue(read(AirHudMode.LivePressure, receiver.copy(receiver = receiver.receiver.copy(connected = false)))!!.unavailable)
        assertTrue(read(AirHudMode.LivePressure, receiver.copy(settings = receiver.settings.copy(mapping = AirPressureMapping())))!!.unavailable)
        assertTrue(read(AirHudMode.LivePressure)!!.unavailable)
    }
    @Test fun `receiver updates retain the last manually confirmed reading until the dive ends`() {
        val received = AirAlertEngine.observe(air, 150.0, receiver = true)
        assertEquals(manual, received.lastManualObservation)
        assertEquals("LAST TANK · 170 BAR · 2 MIN AGO", read(AirHudMode.LastPressure, received)!!.text)
        val entered = AirAlertEngine.observe(received, 145.0)
        assertEquals(145.0, entered.lastManualObservation!!.bar)
        val ended = AirAlertEngine.tick(entered, DiveProfileState(), 2_000)
        assertNull(ended.lastManualObservation)
        assertNull(ended.observation)
    }
    @Test fun `six readout modes persist and old visible or hidden settings migrate`() {
        for (mode in AirHudMode.entries) {
            val settings = air.settings.copy(hudMode = mode)
            assertEquals(settings, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(settings)))
        }
        for (version in 1..3) {
            for (mode in listOf(AirHudMode.TimeAndLast, AirHudMode.Off)) {
                val settings = air.settings.copy(hudMode = mode)
                val old = AirAlertSettingsCodec.encode(settings).replace("version=6", "version=$version")
                    .lineSequence().filterNot { it.startsWith("hudMode=") }.joinToString("\n")
                assertEquals(mode, AirAlertSettingsCodec.decode(old).hudMode)
            }
        }
    }
}
