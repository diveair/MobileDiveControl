package com.mobiledivecontrol.core

import kotlin.test.*

class AirMinimalSetupTest {
    private val connected = AirReceiverState(connected = true, phase = AirReceiverPhase.Ready,
        characteristics = listOf(AirCharacteristic("service", "pressure", true, true), AirCharacteristic("service", "other", true, true)))
    private fun run(air: AirAlertState, command: AirAlertCommand) = reduceAirAlerts(AppState(airAlerts = air), command, 1000).state.airAlerts
    private fun field(air: AirAlertState, field: AirField) = run(air.copy(selectedRow = airFields(air).indexOf(field).also { assertTrue(it >= 0) }), AirAlertCommand.Activate)

    @Test fun defaultIsReminderAndStoredOffIsPreserved() {
        assertEquals(AirMode.Reminder, airMode(AirAlertState().settings))
        val off = defaultAirAlertSettings().copy(enabled = false)
        assertEquals(off, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(off)))
        assertEquals(listOf(AirField.Mode, AirField.Back), airFields(AirAlertState(settings = off, debugControls = true)))
    }
    @Test fun offClearsAllAirWarningsEvenWhenPresentationIsBlocked() {
        val air = AirAlertState(settings = AirAlertSettings(enabled = false), sessionId = 1,
            prompt = AirPrompt(1, false, 0), gasWarning = AirGasWarning(AirGasLevel.Critical, nextAtMs = 0),
            tankCheckDueMs = 0, buddyConfirmation = true)
        val dive = DiveProfileState(active = DiveSession(1, DiveSettings(), elapsedMs = 600000), sensorAvailable = true, depthMeters = 20.0)
        val result = AirAlertEngine.tick(air, dive, 1000, presentationBlocked = true)
        assertNull(result.prompt); assertNull(result.gasWarning); assertNull(result.tankCheckDueMs)
        assertFalse(result.buddyConfirmation)
    }
    @Test fun nextAlertUsesDiveScheduleAndSkipsExpiredDeadlines() {
        val air = AirAlertState(elapsedMs = 300000, delivered = 1, prompt = AirPrompt(1, false, 0))
        assertEquals(600000L, airNextAlertAtMs(air))
        assertEquals(1200000L, airNextAlertAtMs(air.copy(elapsedMs = 930000)))
        assertEquals("5:00", airDiveClock(300000)); assertEquals("65:09", airDiveClock(3909000))
        assertNull(airNextAlertAtMs(air.copy(settings = air.settings.copy(schedule = AirSchedule.Once))))
        val sequence = air.copy(settings = air.settings.copy(schedule = AirSchedule.AtTimes, timesSeconds = listOf(900, 300, 300, 600)))
        assertEquals(600000L, airNextAlertAtMs(sequence))
        assertNull(airNextAlertAtMs(sequence.copy(elapsedMs = 900000, delivered = 3)))
    }
    @Test fun unconnectedReceiverDiscoversDevicesWithoutExtraRegistrationOrSavedAutoConnect() {
        val air = AirAlertState(menuOpen = true, debugControls = true, settings = AirAlertSettings(enabled = true, source = AirSource.Receiver))
        assertEquals(listOf(AirField.Mode, AirField.Scan, AirField.Back), airFields(air))
        val opened = run(air, AirAlertCommand.DiscoverReceivers)
        assertEquals(AirPage.Home, opened.page)
        assertEquals(AirReceiverPhase.Scanning, opened.receiver.phase)
        assertEquals(AirReceiverAction.Scan, opened.receiverRequest!!.action)
        val saved = air.copy(settings = air.settings.copy(mapping = AirPressureMapping(address = "AA:BB:CC:DD:EE:FF")))
        assertEquals(AirReceiverAction.Scan, run(saved, AirAlertCommand.DiscoverReceivers).receiverRequest!!.action)
        assertFalse(AirField.Mapping in airFields(opened))
        assertTrue(AirField.Timing in airFields(air.copy(receiver = connected)))
    }
    @Test fun lostConnectionClosesMappingAndShowsFailure() {
        val air = AirAlertState(menuOpen = true, page = AirPage.Mapping, settings = AirAlertSettings(enabled = true, source = AirSource.Receiver), receiver = connected)
        val failed = run(air, AirAlertCommand.ReceiverUpdate(AirReceiverState(phase = AirReceiverPhase.Failed, status = "Connection failed")))
        assertEquals(AirPage.Receiver, failed.page)
        assertEquals("Connection failed", failed.receiver.status)
        assertFalse(AirField.Mapping in airFields(failed)); assertNull(failed.receiver.liveBar)
        val timing = run(air.copy(page = AirPage.Schedule), AirAlertCommand.ReceiverUpdate(AirReceiverState()))
        assertEquals(AirPage.Receiver, timing.page)
    }
    @Test fun successfulRegistrationStartsReadingSelectedSignal() {
        val air = AirAlertState(menuOpen = true, page = AirPage.Receiver, settings = AirAlertSettings(enabled = true, source = AirSource.Receiver))
        val result = run(air, AirAlertCommand.ReceiverUpdate(connected))
        assertEquals("pressure", result.settings.mapping.characteristic)
        assertEquals(AirReceiverAction.Watch, result.receiverRequest!!.action)
        assertFalse(result.settings.mapping.verified)
        assertTrue(AirField.Values in airFields(result))
    }
    @Test fun labelledValuesDecodeIndependentlyAndExpire() {
        val pressure = AirPressureMapping(address = "AA:BB:CC:DD:EE:FF", service = "service", characteristic = "pressure", verified = true)
        val other = AirReceiverValue("Temperature", "C", pressure.copy(characteristic = "other", format = PressureFormat.UInt8, scale = 1.0, offset = -50.0, verified = false))
        var air = AirAlertState(settings = AirAlertSettings(enabled = true, source = AirSource.Receiver, mapping = pressure, receiverValues = listOf(other)), receiver = connected)
        air = run(air, AirAlertCommand.Packet("service", "pressure", listOf(208, 7), 1000))
        assertEquals(200.0, air.receiver.liveBar)
        air = run(air, AirAlertCommand.Packet("service", "other", listOf(42), 1000))
        assertEquals(-8.0, airReceiverValueNumber(air, other, 1000))
        assertEquals(200.0, air.receiver.liveBar)
        assertNull(airReceiverValueNumber(air, other, 17000))
        assertNull(airReceiverValueNumber(air.copy(receiver = AirReceiverState()), other, 1000))
        assertNull(airReceiverValueNumber(air, other.copy(mapping = other.mapping.copy(address = "different")), 1000))
    }
    @Test fun labelsMappingsAndUnitsRoundTripWithoutPressureVerification() {
        val value = AirReceiverValue("Temperature", "C", AirPressureMapping(address = "AA:BB:CC:DD:EE:FF", deviceName = "Receiver", unit = AirPressureUnit.Psig, service = "service", characteristic = "other", scale = 0.5, offset = -40.0))
        val settings = defaultAirAlertSettings().copy(receiverValues = listOf(value))
        assertEquals(settings, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(settings)))
        val old = AirAlertSettingsCodec.encode(settings).replace("version=6", "version=5")
        assertEquals(settings.copy(receiverValues = emptyList()), AirAlertSettingsCodec.decode(old))
        var air = AirAlertState(settings = settings, menuOpen = true, page = AirPage.Values, receiver = connected)
        air = field(air, AirField.ValueLabel)
        air = run(run(air, AirAlertCommand.EditText("Receiver battery")), AirAlertCommand.SaveText)
        assertEquals("Receiver battery", air.settings.receiverValues.single().label)
        assertEquals(settings.mapping, air.settings.mapping)
        air = field(air, AirField.ValueMapping)
        air = field(air, AirField.ByteOffset)
        assertEquals(1, air.settings.receiverValues.single().mapping.byteOffset)
        assertEquals(settings.mapping, air.settings.mapping)
    }
}
