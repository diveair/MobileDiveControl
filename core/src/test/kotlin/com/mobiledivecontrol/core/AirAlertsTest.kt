package com.mobiledivecontrol.core

import kotlin.test.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirAlertsTest {
    private fun dive(id: Long = 1) = DiveProfileState(active = DiveSession(id, DiveSettings(), maxDepthMeters = 20.0),
        sensorAvailable = true, depthMeters = 20.0)
    private fun advance(air: AirAlertState, profile: DiveProfileState, end: Long): AirAlertState {
        var result = air
        var now = (air.lastTickMs ?: 0) + 500
        while (now <= end) { result = AirAlertEngine.tick(result, profile, now); now += 500 }
        return result
    }
    private fun start(settings: AirAlertSettings = AirAlertSettings(enabled = true), armed: Boolean = true) =
        AirAlertEngine.tick(AirAlertState(settings = settings, startArmed = armed), dive(), 0)
    private fun reduce(air: AirAlertState, command: AirAlertCommand, profile: DiveProfileState = dive()) =
        reduceAirAlerts(AppState(airAlerts = air, diveProfile = profile), command, air.lastTickMs ?: 0).state.airAlerts

    @Test fun `recurring once and custom schedules use dive time and deduplicate`() {
        assertEquals(0, AirAlertSettings().dueCount(299999))
        assertEquals(1, AirAlertSettings().dueCount(300000))
        assertEquals(3, AirAlertSettings().dueCount(900000))
        assertEquals(1, AirAlertSettings(schedule = AirSchedule.Once).dueCount(900000))
        assertEquals(2, AirAlertSettings(schedule = AirSchedule.AtTimes, timesSeconds = listOf(600, 300, 300, 900)).dueCount(600000))
    }
    @Test fun `confirmation can be every second or selected alerts`() {
        val every = AirAlertSettings(confirmEvery = 2)
        assertFalse(every.needsConfirmation(1)); assertTrue(every.needsConfirmation(2))
        val selected = every.copy(confirmation = AirConfirmation.Selected, confirmationNumbers = listOf(1, 4, 7))
        assertTrue(selected.needsConfirmation(4)); assertFalse(selected.needsConfirmation(2))
        assertFalse(selected.copy(confirmation = AirConfirmation.Never).needsConfirmation(4))
    }
    @Test fun `first consumption input after confirmed start produces normalized estimate with reserve`() {
        var air = advance(start(), dive(), 300000)
        air = AirAlertEngine.observe(air, 170.0)
        assertEquals(2.0, air.surfaceBarPerMinute!!, 0.0001) // 30 bar / (5 minutes * 3 ATA)
        val estimate = AirAlertEngine.estimate(air, dive(), 300000)!!
        assertEquals(170.0, estimate.pressureBar)
        assertTrue(estimate.ascentAndStopBar!! > 9.0)
        assertTrue(estimate.bottomMinutes!! in 16.0..18.0)
        air = advance(air, dive(), 360000)
        assertEquals(164.0, AirAlertEngine.estimate(air, dive(), 360000)!!.pressureBar, 0.001)
    }
    @Test fun `remaining time responds to current depth and available tank pressure`() {
        val air = AirAlertEngine.observe(advance(start(), dive(), 300000), 170.0)
        val shallow = AirAlertEngine.estimate(air, dive().copy(depthMeters = 10.0), 300000)!!
        val middle = AirAlertEngine.estimate(air, dive(), 300000)!!
        val deep = AirAlertEngine.estimate(air, dive().copy(depthMeters = 30.0), 300000)!!
        assertEquals(170.0, shallow.pressureBar)
        assertEquals(shallow.pressureBar, deep.pressureBar)
        assertTrue(shallow.bottomMinutes!! > middle.bottomMinutes!!)
        assertTrue(middle.bottomMinutes!! > deep.bottomMinutes!!)
        assertTrue(deep.ascentAndStopBar!! > shallow.ascentAndStopBar!!)
        // Isolate available pressure while retaining the same learned consumption rate.
        val lessAir = air.copy(observation = air.observation!!.copy(bar = 140.0))
        val lessTime = AirAlertEngine.estimate(lessAir, dive(), 300000)!!
        assertEquals(5.0, middle.bottomMinutes!! - lessTime.bottomMinutes!!, 0.0001)
    }
    @Test fun `changing depths between readings normalizes consumption and updates predicted pressure`() {
        val shallow = dive().copy(depthMeters = 10.0)
        val deep = dive().copy(depthMeters = 30.0)
        var air = AirAlertEngine.tick(AirAlertState(settings = AirAlertSettings(enabled = true), startArmed = true), shallow, 0)
        // Two minutes at 10m, one minute descending linearly to 30m, two minutes at 30m.
        air = advance(air, shallow, 120000)
        for (now in 120500L..180000L step 500) {
            val depth = 10.0 + 20.0 * (now - 120000) / 60000.0
            air = AirAlertEngine.tick(air, dive().copy(depthMeters = depth), now)
        }
        air = advance(air, deep, 300000)
        assertEquals(15.0, air.ambientMinutes, 0.0001) // 2*2 + 1*3 + 2*4 atmosphere-minutes.
        air = AirAlertEngine.observe(air, 170.0)
        assertEquals(2.0, air.surfaceBarPerMinute!!, 0.0001)
        // The next minute at 30m uses 8 BAR, rather than the 4 BAR/min at 10m.
        air = advance(air, deep, 360000)
        assertEquals(162.0, AirAlertEngine.estimate(air, deep, 360000)!!.pressureBar, 0.0001)
    }
    @Test fun `without confirmed start first measurement cannot invent consumption`() {
        var air = advance(start(armed = false), dive(), 300000)
        air = AirAlertEngine.observe(air, 170.0)
        assertNull(air.surfaceBarPerMinute)
        air = advance(air, dive(), 600000)
        air = AirAlertEngine.observe(air, 140.0)
        assertEquals(2.0, air.surfaceBarPerMinute!!, 0.001)
    }
    @Test fun `missed confirmation and auto dismissal do not create a pressure observation`() {
        var air = advance(start(), dive(), 300000)
        assertNotNull(air.prompt)
        assertEquals(200.0, air.observation!!.bar)
        air = advance(air, dive(), 316000)
        assertNull(air.prompt)
        assertEquals(200.0, air.observation!!.bar)
        assertNull(air.surfaceBarPerMinute)
    }
    @Test fun `manual dismissal and pressure editor suspend automatic hide`() {
        val persistent = start(AirAlertSettings(enabled = true, autoDismiss = false))
        assertNotNull(advance(persistent, dive(), 350000).prompt)
        var air = advance(start(), dive(), 300000)
        air = reduce(air, AirAlertCommand.PromptAction(1))
        assertNotNull(air.entry)
        air = advance(air, dive(), 350000)
        assertNotNull(air.entry); assertNotNull(air.prompt)
    }
    @Test fun `schedules do not replay backlog after a blocking menu`() {
        var air = advance(start().copy(menuOpen = true), dive(), 930000)
        assertNull(air.prompt)
        air = AirAlertEngine.tick(air.copy(menuOpen = false), dive(), 930500)
        assertEquals(3, air.prompt!!.number)
        air = reduce(air, AirAlertCommand.PromptAction(2))
        air = AirAlertEngine.tick(air, dive(), 931000)
        assertNull(air.prompt)
    }
    @Test fun `new dive resets rate sequence and consumes starting confirmation only once`() {
        var air = AirAlertEngine.observe(advance(start(), dive(), 300000), 170.0)
        air = AirAlertEngine.tick(air, dive(2), 301000)
        assertNull(air.surfaceBarPerMinute); assertNull(air.observation); assertEquals(0, air.delivered)
    }
    @Test fun `missing depth invalidates time estimate and requires a new interval`() {
        var air = AirAlertEngine.observe(advance(start(), dive(), 300000), 170.0)
        val lost = dive().copy(sensorAvailable = false, depthMeters = null)
        air = AirAlertEngine.tick(air, lost, 300500)
        assertNull(AirAlertEngine.estimate(air, lost, 300500)!!.bottomMinutes)
        air = advance(air, dive(), 310000)
        air = AirAlertEngine.observe(air, 169.0)
        assertNull(air.surfaceBarPerMinute)
        air = advance(air, dive(), 370000)
        air = AirAlertEngine.observe(air, 163.0)
        assertEquals(2.0, air.surfaceBarPerMinute!!, 0.001)
    }
    @Test fun `higher consumption corrects estimate and refill clears it`() {
        var air = AirAlertEngine.observe(advance(start(), dive(), 300000), 170.0)
        air = AirAlertEngine.observe(advance(air, dive(), 600000), 120.0)
        assertEquals("More air used than estimated", air.comparison)
        assertTrue(air.surfaceBarPerMinute!! > 3)
        air = AirAlertEngine.observe(advance(air, dive(), 610000), 210.0)
        assertNull(air.surfaceBarPerMinute)
    }
    @Test fun `zero consumption cannot generate infinite bottom time`() {
        val air = AirAlertEngine.observe(advance(start(), dive(), 300000), 200.0)
        assertNull(AirAlertEngine.estimate(air, dive(), 300000)!!.bottomMinutes)
    }
    @Test fun `gas time is suppressed when no-decompression limit has been reached`() {
        val air = AirAlertEngine.observe(advance(start(), dive(), 300000), 170.0)
        val deco = dive().let { it.copy(active = it.active!!.copy(limitReached = true)) }
        assertNull(AirAlertEngine.estimate(air, deco, 300000)!!.bottomMinutes)
    }
    @Test fun `keypad replaces suggested value and supports zero erase slider and cancel`() {
        var air = start().copy(entry = AirPressureEntry(AirEntryPurpose.Reading, "200"))
        air = reduce(air, AirAlertCommand.Key(0)); air = reduce(air, AirAlertCommand.Key(6)); air = reduce(air, AirAlertCommand.Key(9))
        assertEquals("170", air.entry!!.text)
        air = reduce(air, AirAlertCommand.Key(10)); assertEquals("17", air.entry!!.text)
        air = reduce(air, AirAlertCommand.NudgePressure(1)); assertEquals("22", air.entry!!.text)
        air = reduce(air, AirAlertCommand.CancelEntry); assertNull(air.entry)
        assertEquals(200.0, air.observation!!.bar)
    }
    @Test fun `invalid pressure stays in editor without changing estimate`() {
        val air = reduce(start().copy(entry = AirPressureEntry(AirEntryPurpose.Reading, "9999")), AirAlertCommand.SavePressure)
        assertNotNull(air.entry!!.error); assertEquals(200.0, air.observation!!.bar)
    }
    @Test fun `PSIG is consistently converted to bar`() {
        assertEquals(3000, AirPressureUnit.Psig.display(AirPressureUnit.Psig.toBar(3000)))
        var air = start(AirAlertSettings(unit = AirPressureUnit.Psig))
        air = air.copy(entry = AirPressureEntry(AirEntryPurpose.Reading, "2500"))
        air = reduce(air, AirAlertCommand.SavePressure)
        assertEquals(172.36893, air.observation!!.bar, 0.0001)
    }
    @Test fun `hardware shutter always captures and visible air input owns OK during a stop`() {
        val air = start().copy(prompt = AirPrompt(1, true, 0), entry = AirPressureEntry(AirEntryPurpose.Reading, "150"))
        val state = AppState(airAlerts = air, diveProfile = dive(), housing = HousingState(inputEnabled = true))
        val router = InputRouter()
        assertEquals(listOf(CameraCommand.CapturePhoto), router.route(state, HousingButtonEvent.Shutter).commands)
        val stopped = state.copy(diveProfile = dive().let { it.copy(active = it.active!!.copy(stopStarted = true)) })
        assertEquals(listOf(DiveSettingsCommand.Alerts(AirAlertCommand.SavePressure)), router.route(stopped, HousingButtonEvent.Ok).commands)
    }
    @Test fun `expired receiver pressure is never live and manual fallback starts a new rate interval`() {
        val mapping = AirPressureMapping(verified = true)
        var air = start(AirAlertSettings(source = AirSource.Receiver, mapping = mapping)).copy(
            receiver = AirReceiverState(connected = true, liveBar = 170.0, liveAtMs = 1000))
        assertEquals(170.0, AirAlertEngine.liveBar(air, 16000))
        assertNull(AirAlertEngine.liveBar(air, 16001))
        assertNull(AirAlertEngine.liveBar(air.copy(receiver = air.receiver.copy(connected = false)), 1001))
        air = AirAlertEngine.observe(air.copy(elapsedMs = 300000, ambientMinutes = 15.0), 170.0, receiver = true)
        air = AirAlertEngine.observe(air.copy(elapsedMs = 600000, ambientMinutes = 30.0), 140.0)
        assertNull(air.surfaceBarPerMinute)
    }
    @Test fun `decoders handle unsigned widths endian offset scale and bad packets`() {
        val base = AirPressureMapping()
        assertEquals(200.0, base.decode(listOf(0xd0, 0x07)))
        assertEquals(200.0, base.copy(littleEndian = false).decode(listOf(0x07, 0xd0)))
        assertEquals(200.0, base.copy(byteOffset = 1).decode(listOf(0xff, 0xd0, 0x07)))
        assertEquals(200.0, base.copy(format = PressureFormat.UInt24).decode(listOf(0xd0, 0x07, 0)))
        assertEquals(200.0, base.copy(format = PressureFormat.UInt32).decode(listOf(0xd0, 0x07, 0, 0)))
        assertEquals(200.0, base.copy(format = PressureFormat.UInt8, scale = 1.0).decode(listOf(200)))
        assertNull(base.decode(listOf(0xff)))
        assertNull(base.decode(listOf(-1, 0)))
        assertNull(base.copy(scale = Double.NaN).decode(listOf(1, 0)))
        assertNull(base.decode(listOf(255, 255)))
        val floatBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(200f).array().map { it.toInt() and 255 }
        assertEquals(200.0, base.copy(format = PressureFormat.Float32, scale = 1.0).decode(floatBytes))
    }
    @Test fun `unverified and wrong-characteristic bytes never become live pressure`() {
        var air = AirAlertState(settings = AirAlertSettings(mapping = AirPressureMapping(service = "service", characteristic = "pressure")),
            receiver = AirReceiverState(connected = true))
        air = reduce(air, AirAlertCommand.Packet("service", "battery", listOf(0xd0, 7), 100))
        assertTrue(air.receiver.payload.isEmpty())
        air = reduce(air, AirAlertCommand.Packet("service", "pressure", listOf(0xd0, 7), 100))
        assertNull(air.receiver.liveBar); assertEquals(listOf(0xd0, 7), air.receiver.payload)
    }
    @Test fun `two distinct gauge comparisons required and changing mapping invalidates verification`() {
        val surface = DiveProfileState()
        var air = AirAlertState(settings = AirAlertSettings(enabled = true, mapping = AirPressureMapping(service = "s", characteristic = "c")), lastTickMs = 1000,
            receiver = AirReceiverState(connected = true, packetAtMs = 1000, payload = listOf(0xd0, 7)),
            entry = AirPressureEntry(AirEntryPurpose.Calibration, "200"))
        air = reduce(air, AirAlertCommand.SavePressure, surface)
        assertFalse(air.settings.mapping.verified)
        air = air.copy(entry = AirPressureEntry(AirEntryPurpose.Calibration, "200"))
        air = reduce(air, AirAlertCommand.SavePressure, surface)
        assertNotNull(air.entry!!.error)
        air = air.copy(entry = AirPressureEntry(AirEntryPurpose.Calibration, "190"), receiver = air.receiver.copy(payload = listOf(0x6c, 7)))
        air = reduce(air, AirAlertCommand.SavePressure, surface)
        assertTrue(air.settings.mapping.verified)
        air = air.copy(menuOpen = true, page = AirPage.Mapping, selectedRow = airFields(air.copy(page = AirPage.Mapping)).indexOf(AirField.ByteOffset))
        air = reduce(air, AirAlertCommand.Adjust(1), surface)
        assertFalse(air.settings.mapping.verified); assertNull(air.receiver.liveBar)
    }
    @Test fun `settings roundtrip preserves configuration and rejects corrupt values`() {
        val settings = AirAlertSettings(enabled = true, schedule = AirSchedule.AtTimes, timesSeconds = listOf(60, 120),
            confirmation = AirConfirmation.Selected, confirmationNumbers = listOf(2, 5), unit = AirPressureUnit.Psig,
            autoDismiss = false, opacityPercent = 55, colour = AirColour.Purple)
        val encoded = AirAlertSettingsCodec.encode(settings)
        assertEquals(settings, AirAlertSettingsCodec.decode(encoded))
        assertFails { AirAlertSettingsCodec.decode(encoded.replace("opacity=55", "opacity=200")) }
        assertFails { AirAlertSettingsCodec.decode("garbage") }
    }
    @Test fun `tank calibration settings cannot be altered during a dive`() {
        val air = start().copy(menuOpen = true, page = AirPage.Gas, selectedRow = airFields(AirAlertState(page = AirPage.Gas)).indexOf(AirField.Start))
        assertNull(reduce(air, AirAlertCommand.Activate).entry)
    }
    @Test fun `surface preview expires but its pressure editor stays available`() {
        val surface = DiveProfileState()
        var air = AirAlertState(prompt = AirPrompt(1, true, 0, preview = true))
        air = AirAlertEngine.tick(air, surface, 1000)
        air = reduce(air, AirAlertCommand.PromptAction(1), surface)
        assertNotNull(air.entry)
        air = AirAlertEngine.tick(air, surface, 20000)
        assertNotNull(air.entry)
        air = reduce(air, AirAlertCommand.SavePressure, surface)
        assertNull(air.observation); assertNull(air.prompt)
        val expired = AirAlertEngine.tick(AirAlertState(prompt = AirPrompt(1, false, 0, preview = true)), surface, 16000)
        assertNull(expired.prompt)
    }
    @Test fun `receiver to manual transition discards learned receiver consumption`() {
        var air = start().copy(observation = AirObservation(180.0, 1000, 1.0, receiver = true),
            rateAnchor = AirObservation(180.0, 1000, 1.0, receiver = true), surfaceBarPerMinute = 2.0,
            elapsedMs = 300000, ambientMinutes = 15.0)
        air = AirAlertEngine.observe(air, 150.0)
        assertNull(air.surfaceBarPerMinute)
        air = AirAlertEngine.observe(air.copy(elapsedMs = 600000, ambientMinutes = 30.0), 120.0)
        assertEquals(2.0, air.surfaceBarPerMinute!!, 0.001)
    }
    @Test fun `housing failure blocks alert input and delays its dismissal`() {
        val air = start().copy(prompt = AirPrompt(1, true, 0))
        val state = AppState(airAlerts = air, safety = SafetyState(sealState = SealState.Failed))
        assertTrue(airAlertPresentationBlocked(state))
        assertNull(routeAirAlerts(state, HousingButtonEvent.Ok))
        val next = AirAlertEngine.tick(air, dive(), 20000, presentationBlocked = true)
        assertNotNull(next.prompt)
        assertEquals(20000L, next.prompt!!.shownAtMs)
    }

    @Test fun `a disconnected housing warning takes precedence during a dive`() {
        val state = AppState(diveProfile = dive(), airAlerts = start().copy(prompt = AirPrompt(1, true, 0)))
        assertTrue(airAlertPresentationBlocked(state))
        assertFalse(airAlertPresentationBlocked(state.copy(housing = HousingState(connected = true, inputEnabled = true))))
    }
    @Test fun `idle surface ticks do not recompose the camera twice a second`() {
        val air = AirAlertState()
        assertEquals(air, AirAlertEngine.tick(air, DiveProfileState(), 500))
    }
    @Test fun `a packet received after the last UI tick can be calibrated immediately`() {
        val air = AirAlertState(lastTickMs = 1000, receiver = AirReceiverState(connected = true,
            packetAtMs = 1250, payload = listOf(0xd0, 7)), entry = AirPressureEntry(AirEntryPurpose.Calibration, "200"))
        val result = reduceAirAlerts(AppState(airAlerts = air), AirAlertCommand.SavePressure, nowMs = 1251).state.airAlerts
        assertNull(result.entry)
        assertEquals(200.0, result.receiver.firstCalibrationBar)
    }

}
