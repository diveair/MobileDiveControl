package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.math.roundToInt
import kotlin.test.*

/** Uses production packet parsing, ControlCore ticks, routing and reducers; never the alert simulator. */
class AirAlertEndToEndTest {
    private class Dive(settings: AirAlertSettings = defaultAirAlertSettings().copy(intervalSeconds = 30)) {
        var now = pressureMonotonicMs()
        private var buttonSerial = 0L
        val core = ControlCore(AppState(mode = AppMode.CameraLive,
            housing = HousingState(connected = true, inputEnabled = true),
            airAlerts = AirAlertState(settings = AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(settings)))),
            monotonicMs = { now })
        val air get() = core.state.airAlerts
        fun command(c: AirAlertCommand) { core.dispatch(DiveSettingsCommand.Alerts(c)) }
        fun sample(depth: Double) {
            val raw = ((STANDARD_SURFACE_PRESSURE_KPA + depth * FRESHWATER_KPA_PER_METER) * 100).roundToInt()
            core.handleNotificationPayload(HousingCharacteristic.WaterPressure.shortHex,
                ByteArray(4) { (raw ushr (8 * it)).toByte() }, Instant.ofEpochMilli(now), receivedAtMonotonicMs = now)
            core.tickDiveProfile(now)
        }
        fun start() { sample(20.0); assertNotNull(core.state.diveProfile.active); assertNull(air.simulation) }
        fun advance(seconds: Int, depth: Double = 20.0) { repeat(seconds * 2) { now += 500; sample(depth) } }
        fun key(raw: Int) { core.handleButtonPayload(byteArrayOf(raw.toByte()), receivedAt = Instant.ofEpochMilli(now + ++buttonSerial * 250)) }
        fun confirmStart() {
            core.forceMode(AppMode.DiveSettings)
            core.dispatch(DiveSettingsCommand.Select(DiveSettingsField.Alerts))
            core.dispatch(DiveSettingsCommand.Confirm)
            command(AirAlertCommand.SelectPage(AirPage.Gas))
            command(AirAlertCommand.SelectRow(airFields(air).indexOf(AirField.Start)))
            command(AirAlertCommand.Activate); command(AirAlertCommand.SavePressure)
            assertTrue(air.startArmed)
            command(AirAlertCommand.Back); command(AirAlertCommand.Back)
            core.forceMode(AppMode.CameraLive)
        }
    }

    @Test fun savedReminderFiresFromRealDepthAndTickPathAndHardwareOkDismisses() {
        val dive = Dive()
        dive.start(); dive.advance(29)
        assertNull(dive.air.prompt)
        dive.advance(1)
        assertEquals(1, dive.air.prompt!!.number)
        assertFalse(dive.air.prompt!!.confirmation)
        assertEquals(60000L, airNextAlertAtMs(dive.air))
        dive.key(0x50)
        assertNull(dive.air.prompt)
        dive.advance(30)
        assertEquals(2, dive.air.prompt!!.number)
    }

    @Test fun actualStartingPressureAndReportedReadingPopulateBottomTimeAndPrediction() {
        val dive = Dive(AirAlertSettings(enabled = true, intervalSeconds = 30, autoDismiss = false, hudMode = AirHudMode.BottomTime))
        dive.confirmStart(); dive.start()
        assertEquals(200.0, dive.air.observation!!.bar)
        assertNull(airCameraReadout(dive.air, dive.core.state.diveProfile, dive.now))
        dive.advance(30)
        assertTrue(dive.air.prompt!!.confirmation)
        dive.command(AirAlertCommand.SetPressure(196))
        assertNotNull(AirAlertEngine.readingEstimate(dive.air, dive.core.state.diveProfile, dive.now)!!.bottomMinutes)
        assertEquals(200.0, dive.air.observation!!.bar) // draft does not change the recorded gauge value
        dive.key(0x50)
        assertEquals(196.0, dive.air.observation!!.bar)
        assertNull(dive.air.prompt); assertNull(dive.air.entry)
        assertTrue(airCameraReadout(dive.air, dive.core.state.diveProfile, dive.now)!!.text.startsWith("EST. BOTTOM TIME"))
        dive.advance(30)
        assertEquals(192, airSuggestedPressure(dive.air, dive.core.state.diveProfile, dive.now))
        dive.key(0x70) // actual Z+ packet decreases the proposed BAR value by five
        assertEquals("187", dive.air.entry!!.text)
        dive.key(0x50)
        assertEquals(187.0, dive.air.observation!!.bar)
        assertEquals("More air used than estimated", dive.air.comparison)
    }

    @Test fun expiryAndHoldUseProductionTimeAndDoNotReplayAnAlert() {
        val auto = Dive(defaultAirAlertSettings().copy(intervalSeconds = 30, displaySeconds = 5))
        auto.start(); auto.advance(30); assertNotNull(auto.air.prompt)
        auto.advance(5); assertNull(auto.air.prompt)
        auto.advance(24); assertNull(auto.air.prompt)
        auto.advance(1); assertEquals(2, auto.air.prompt!!.number)
        val held = Dive(defaultAirAlertSettings().copy(intervalSeconds = 30, autoDismiss = false))
        held.start(); held.advance(100)
        assertEquals(1, held.air.prompt!!.number)
        held.key(0x50); held.advance(1)
        assertEquals(3, held.air.prompt!!.number) // one coalesced reminder, no backlog
    }

    @Test fun sequenceTextAndPerAlertPressureChoiceReachTheLivePrompt() {
        val dive = Dive(defaultAirAlertSettings().copy(schedule = AirSchedule.AtTimes, timesSeconds = listOf(60, 120),
            sequenceMessages = mapOf(60 to "CHECK YOUR BUDDY", 120 to "REPORT AIR"),
            sequenceActions = mapOf(120 to AirSequenceAction.Pressure), autoDismiss = false))
        dive.start(); dive.advance(60)
        assertEquals("CHECK YOUR BUDDY", dive.air.settings.messageFor(dive.air.prompt!!.number))
        assertFalse(dive.air.prompt!!.confirmation)
        dive.key(0x50); dive.advance(60)
        assertEquals("REPORT AIR", dive.air.settings.messageFor(dive.air.prompt!!.number))
        assertTrue(dive.air.prompt!!.confirmation)
        dive.command(AirAlertCommand.SetPressure(180)); dive.key(0x50)
        dive.advance(60); assertNull(dive.air.prompt); assertNull(airNextAlertAtMs(dive.air))
    }

    @Test fun onceAndEverySecondConfirmationFollowSavedConfiguration() {
        val once = Dive(defaultAirAlertSettings().copy(schedule = AirSchedule.Once, intervalSeconds = 30, displaySeconds = 5))
        once.start(); once.advance(30); assertNotNull(once.air.prompt)
        once.advance(90); assertNull(once.air.prompt); assertEquals(1, once.air.delivered)
        val cadence = Dive(AirAlertSettings(enabled = true, intervalSeconds = 30, confirmEvery = 2))
        cadence.start(); cadence.advance(30); assertFalse(cadence.air.prompt!!.confirmation)
        cadence.key(0x50); cadence.advance(30); assertTrue(cadence.air.prompt!!.confirmation)
        cadence.command(AirAlertCommand.SetPressure(180)); cadence.key(0x50)
        cadence.advance(30); assertFalse(cadence.air.prompt!!.confirmation)
    }

    @Test fun presentationBlockedBySafetyDefersRatherThanConsumesTheDeadline() {
        val dive = Dive()
        dive.start(); dive.core.forceMode(AppMode.Safety); dive.advance(65)
        assertNull(dive.air.prompt); assertEquals(0, dive.air.delivered)
        dive.core.forceMode(AppMode.CameraLive); dive.advance(1)
        assertEquals(2, dive.air.prompt!!.number)
        dive.key(0x50); dive.advance(1); assertNull(dive.air.prompt)
    }

    @Test fun receiverPacketsFeedPressureThenExpireWithoutInventingLiveData() {
        val mapping = AirPressureMapping(address = "AA:BB:CC:DD:EE:FF", service = "0000180f-0000-1000-8000-00805f9b34fb", characteristic = "00002a19-0000-1000-8000-00805f9b34fb", verified = true)
        val dive = Dive(defaultAirAlertSettings().copy(source = AirSource.Receiver, intervalSeconds = 30,
            mapping = mapping, hudMode = AirHudMode.LivePressure))
        dive.command(AirAlertCommand.ReceiverUpdate(AirReceiverState(connected = true, phase = AirReceiverPhase.Ready,
            characteristics = listOf(AirCharacteristic(mapping.service, mapping.characteristic, true, true)))))
        dive.start()
        fun packet(bar: Int) {
            val raw = bar * 10
            dive.command(AirAlertCommand.Packet(mapping.service, mapping.characteristic, listOf(raw and 255, raw ushr 8), dive.now))
        }
        packet(200); dive.advance(1)
        repeat(3) { packet(199 - it); dive.advance(10) }
        assertNotNull(dive.air.prompt)
        assertNotNull(AirAlertEngine.estimate(dive.air, dive.core.state.diveProfile, dive.now)!!.bottomMinutes)
        assertTrue(airCameraReadout(dive.air, dive.core.state.diveProfile, dive.now)!!.text.startsWith("LIVE ·"))
        dive.advance(16)
        assertNull(AirAlertEngine.liveBar(dive.air, dive.now))
        assertNull(AirAlertEngine.estimate(dive.air, dive.core.state.diveProfile, dive.now)!!.bottomMinutes)
        assertTrue(airCameraReadout(dive.air, dive.core.state.diveProfile, dive.now)!!.unavailable)
        dive.command(AirAlertCommand.ReceiverUpdate(AirReceiverState(phase = AirReceiverPhase.Failed)))
        assertNull(dive.air.receiver.liveBar)
    }

    @Test fun surfacingClearsPendingInputAndNextDiveStartsANewSchedule() {
        val dive = Dive(AirAlertSettings(enabled = true, intervalSeconds = 30))
        dive.start(); dive.advance(30); dive.key(0x70)
        assertNotNull(dive.air.entry)
        dive.advance(181, 0.0)
        assertNull(dive.core.state.diveProfile.active)
        assertNull(dive.air.prompt); assertNull(dive.air.entry); assertNull(dive.air.observation)
        assertNull(dive.air.gasWarning)
        dive.advance(1); assertEquals(0, dive.air.delivered)
        dive.advance(29); assertNull(dive.air.prompt)
        dive.advance(1); assertEquals(1, dive.air.prompt!!.number)
    }

    @Test fun offNeverProducesAirPromptsOrReadoutsDuringDive() {
        val dive = Dive(AirAlertSettings(enabled = false, intervalSeconds = 30))
        dive.start(); dive.advance(120)
        assertNull(dive.air.prompt); assertNull(dive.air.gasWarning)
        assertNull(airCameraReadout(dive.air, dive.core.state.diveProfile, dive.now))
    }
}
