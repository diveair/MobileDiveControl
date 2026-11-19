package com.mobiledivecontrol.core

import kotlin.test.*

class AirReceiverPickerTest {
    private val a = AirDevice("AA:BB:CC:DD:EE:01", "Receiver")
    private val b = AirDevice("AA:BB:CC:DD:EE:02", "Receiver")
    private fun initial() = AppState(mode = AppMode.DiveSettings, airAlerts = AirAlertState(menuOpen = true,
        settings = defaultAirAlertSettings().copy(source = AirSource.Receiver),
        receiver = AirReceiverState(devices = listOf(a, b))))
    private fun send(s: AppState, c: AirAlertCommand) = reduceAirAlerts(s, c, 1000).state
    private fun update(s: AppState, r: AirReceiverState) = send(s, AirAlertCommand.ReceiverUpdate(r))
    private fun key(s: AppState, event: HousingButtonEvent): AppState =
        routeAirAlerts(s, event)!!.commands.fold(s) { state, command -> send(state, (command as DiveSettingsCommand.Alerts).command) }

    @Test fun everyDeviceHasItsOwnHousingRowAndOkConnectsTheHighlightedAddress() {
        var s = initial()
        assertEquals(listOf(AirField.Mode, AirField.Device, AirField.Device, AirField.Scan, AirField.Back), airFields(s.airAlerts))
        s = key(key(s, HousingButtonEvent.Down), HousingButtonEvent.Down)
        assertEquals(b, airDeviceAtRow(s.airAlerts))
        assertNull(s.airAlerts.receiverRequest)
        s = key(s, HousingButtonEvent.Ok)
        assertEquals(b.address, s.airAlerts.settings.mapping.address)
        assertEquals(AirReceiverAction.Connect, s.airAlerts.receiverRequest!!.action)
        assertEquals(AirReceiverPhase.Connecting, s.airAlerts.receiver.phase)
        assertFalse(AirField.Mapping in airFields(s.airAlerts))
    }

    @Test fun touchConnectsByAddressRegardlessOfFocusOrNewDiscoveryOrder() {
        val oldMapping = AirPressureMapping(address = a.address, deviceName = a.name,
            service = "old", characteristic = "pressure", verified = true)
        var s = initial().let { it.copy(airAlerts = it.airAlerts.copy(settings = it.airAlerts.settings.copy(mapping = oldMapping))) }
        s = update(s, s.airAlerts.receiver.copy(devices = listOf(b, a)))
        s = send(s, AirAlertCommand.ConnectReceiver(b.address))
        assertEquals(b.address, s.airAlerts.settings.mapping.address)
        assertEquals(b, airDeviceAtRow(s.airAlerts))
        assertFalse(s.airAlerts.settings.mapping.verified)
        assertEquals("", s.airAlerts.settings.mapping.characteristic)
        assertEquals(AirReceiverAction.Connect, s.airAlerts.receiverRequest!!.action)
    }

    @Test fun sameReceiverRetainsVerifiedMapping() {
        val mapping = AirPressureMapping(address = b.address, service = "saved", characteristic = "pressure", verified = true)
        val s = initial().let { it.copy(airAlerts = it.airAlerts.copy(settings = it.airAlerts.settings.copy(mapping = mapping))) }
        val result = send(s, AirAlertCommand.ConnectReceiver(b.address)).airAlerts
        assertTrue(result.settings.mapping.verified)
        assertEquals("pressure", result.settings.mapping.characteristic)
    }

    @Test fun discoveriesKeepDeviceAndBackFocusStable() {
        val devices = (3..40).map { AirDevice("AA:BB:CC:DD:EE:%02X".format(it), "Device $it") }
        var s = send(initial(), AirAlertCommand.SelectRow(2))
        s = update(s, s.airAlerts.receiver.copy(devices = listOf(a) + devices + b))
        assertEquals(b, airDeviceAtRow(s.airAlerts))
        assertEquals(40, airFields(s.airAlerts).count { it == AirField.Device })
        s = send(s, AirAlertCommand.SelectRow(airFields(s.airAlerts).lastIndex))
        s = update(s, s.airAlerts.receiver.copy(devices = listOf(a, b)))
        assertEquals(AirField.Back, airFields(s.airAlerts)[s.airAlerts.selectedRow])
        assertFalse(key(s, HousingButtonEvent.Ok).airAlerts.menuOpen)
    }

    @Test fun repeatedDiscoveryAndConnectCommandsDoNotCreateDuplicateRequests() {
        val scanning = send(initial(), AirAlertCommand.DiscoverReceivers)
        assertEquals(scanning, send(scanning, AirAlertCommand.DiscoverReceivers))
        val connecting = send(scanning, AirAlertCommand.ConnectReceiver(b.address))
        assertEquals(connecting, send(connecting, AirAlertCommand.ConnectReceiver(a.address)))
        assertEquals(connecting, send(connecting, AirAlertCommand.DiscoverReceivers))
    }

    @Test fun unknownOrVanishedDeviceCannotConnectToSavedReceiverInstead() {
        val s = initial()
        assertEquals(s, send(s, AirAlertCommand.ConnectReceiver("missing")))
        val cleared = update(send(s, AirAlertCommand.SelectRow(1)), s.airAlerts.receiver.copy(devices = emptyList()))
        assertEquals(AirField.Scan, airFields(cleared.airAlerts)[cleared.airAlerts.selectedRow])
        assertEquals(cleared, send(cleared, AirAlertCommand.ConnectReceiver(a.address)))
    }

    @Test fun failureStaysVisibleUntilExplicitRetry() {
        val failed = update(initial(), initial().airAlerts.receiver.copy(phase = AirReceiverPhase.Failed, status = "Connection timed out"))
        assertEquals(failed, send(failed, AirAlertCommand.DiscoverReceivers))
        val retry = key(send(failed, AirAlertCommand.SelectRow(airFields(failed.airAlerts).indexOf(AirField.Scan))), HousingButtonEvent.Ok)
        assertEquals(AirReceiverPhase.Scanning, retry.airAlerts.receiver.phase)
        assertEquals(AirReceiverAction.Scan, retry.airAlerts.receiverRequest!!.action)
    }

    @Test fun successfulSelectionOpensDataAndBackReturnsThroughExistingNavigation() {
        var s = send(initial(), AirAlertCommand.ConnectReceiver(b.address))
        s = update(s, s.airAlerts.receiver.copy(connected = true, phase = AirReceiverPhase.Ready,
            characteristics = listOf(AirCharacteristic("service", "pressure", true, true))))
        assertEquals(AirPage.Receiver, s.airAlerts.page)
        assertTrue(AirField.Mapping in airFields(s.airAlerts))
        assertEquals(AirReceiverAction.Watch, s.airAlerts.receiverRequest!!.action)
        s = send(s, AirAlertCommand.Back)
        assertEquals(AirPage.Home, s.airAlerts.page)
        assertEquals(AirField.Back, airFields(s.airAlerts)[s.airAlerts.selectedRow])
    }

    @Test fun closedMenuOtherModesAndActiveDiveDoNotStartDiscoveryOrConnect() {
        val base = initial()
        val blocked = listOf(
            base.copy(airAlerts = base.airAlerts.copy(menuOpen = false)),
            base.copy(airAlerts = base.airAlerts.copy(settings = defaultAirAlertSettings())),
            base.copy(diveProfile = DiveProfileState(active = DiveSession(1, DiveSettings())))
        )
        blocked.forEach { s ->
            assertNull(send(s, AirAlertCommand.DiscoverReceivers).airAlerts.receiverRequest)
            assertNull(send(s, AirAlertCommand.ConnectReceiver(a.address)).airAlerts.receiverRequest)
        }
    }
}
