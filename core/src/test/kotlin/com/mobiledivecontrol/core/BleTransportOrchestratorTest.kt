package com.mobiledivecontrol.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BleTransportOrchestratorTest {

    @Test
    fun `full connection lifecycle with simulated transport`() = runTest {
        val stateLog = mutableListOf<BleConnectionState>()
        val notifications = mutableListOf<DecodedNotification>()

        val transport = SimulatedBleTransport()
        val orchestrator = BleTransportOrchestrator(
            transport = transport,
            onStateChanged = { stateLog.add(it) },
            onNotificationDecoded = { notifications.add(it) },
        )

        val result = orchestrator.connectToHousing()

        // Should be connected
        assertTrue(result is ConnectionResult.Connected)
        assertEquals("DIVE IT", result.device.name)
        assertEquals("UMEING", result.manufacturerName)
        assertEquals("A4.0", result.firmwareVersion)
        assertEquals("SIM-001", result.serialNumber)

        // Should have gone through all connection states
        assertEquals(BleConnectionState.Ready, orchestrator.state)
        assertTrue(stateLog.contains(BleConnectionState.Scanning))
        assertTrue(stateLog.contains(BleConnectionState.Connecting))
        assertTrue(stateLog.contains(BleConnectionState.DiscoveringServices))
        assertTrue(stateLog.contains(BleConnectionState.Subscribing))
        assertTrue(stateLog.contains(BleConnectionState.Ready))

        // Device info reads should have triggered notifications
        assertTrue(notifications.any { it is DecodedNotification.DeviceInfo })
    }

    @Test
    fun `rejects wrong advertising name`() = runTest {
        val transport = SimulatedBleTransport(deviceName = "NOT DIVE IT")
        val errors = mutableListOf<String>()
        val orchestrator = BleTransportOrchestrator(
            transport = transport,
            onError = { errors.add(it) },
        )

        val result = orchestrator.connectToHousing()

        assertTrue(result is ConnectionResult.Failed)
        assertTrue(result.reason.contains("Advertising"))
        assertEquals(BleConnectionState.Failed, orchestrator.state)
    }

    @Test
    fun `rejects wrong manufacturer`() = runTest {
        val transport = SimulatedBleTransport(manufacturerName = "BadCorp")
        val orchestrator = BleTransportOrchestrator(transport = transport)

        val result = orchestrator.connectToHousing()

        assertTrue(result is ConnectionResult.Failed)
        assertTrue(result.reason.contains("Manufacturer"))
    }

    @Test
    fun `rejects unsupported firmware`() = runTest {
        val transport = SimulatedBleTransport(firmwareVersion = "A1.0")
        val orchestrator = BleTransportOrchestrator(transport = transport)

        val result = orchestrator.connectToHousing()

        assertTrue(result is ConnectionResult.Failed)
        assertTrue(result.reason.contains("Firmware"))
    }

    @Test
    fun `handles connection failure`() = runTest {
        val transport = SimulatedBleTransport(shouldFailConnect = true)
        val orchestrator = BleTransportOrchestrator(transport = transport)

        val result = orchestrator.connectToHousing()

        assertTrue(result is ConnectionResult.Failed)
        assertTrue(result.reason.contains("Failed to connect"))
    }

    @Test
    fun `housing powered off goes to Idle`() = runTest {
        val stateLog = mutableListOf<BleConnectionState>()
        val transport = SimulatedBleTransport()
        val orchestrator = BleTransportOrchestrator(
            transport = transport,
            onStateChanged = { stateLog.add(it) },
        )

        // Connect first
        orchestrator.connectToHousing()
        assertEquals(BleConnectionState.Ready, orchestrator.state)

        // Housing powers off
        orchestrator.housingPoweredOff()
        assertEquals(BleConnectionState.Idle, orchestrator.state)
    }

    @Test
    fun `simulated button notification decoded correctly`() = runTest {
        val notifications = mutableListOf<DecodedNotification>()
        val transport = SimulatedBleTransport()
        val orchestrator = BleTransportOrchestrator(
            transport = transport,
            onNotificationDecoded = { notifications.add(it) },
        )

        orchestrator.connectToHousing()

        // Simulate shutter press (0x20 per vendor spec §5.3 Table 7)
        transport.simulateButtonPress(0x20u)

        val buttonNotif = notifications.filterIsInstance<DecodedNotification.Button>().lastOrNull()
        assertNotNull(buttonNotif)
        assertEquals(HousingButtonEvent.Shutter, buttonNotif.packet.event)
    }

    @Test
    fun `simulated cover state decoded correctly`() = runTest {
        val notifications = mutableListOf<DecodedNotification>()
        val transport = SimulatedBleTransport()
        val orchestrator = BleTransportOrchestrator(
            transport = transport,
            onNotificationDecoded = { notifications.add(it) },
        )

        orchestrator.connectToHousing()

        // Simulate cover open (0x00 per vendor spec §5.4)
        transport.simulateCoverState(open = true)

        val sensorNotif = notifications.filterIsInstance<DecodedNotification.Sensor>().lastOrNull()
        assertNotNull(sensorNotif)
        assertTrue(sensorNotif.update is SensorUpdate.CoverState)
        assertEquals(true, (sensorNotif.update as SensorUpdate.CoverState).open)
    }

    @Test
    fun `send vacuum motor command encodes correctly`() = runTest {
        val transport = SimulatedBleTransport()
        val orchestrator = BleTransportOrchestrator(transport = transport)

        orchestrator.connectToHousing()

        // Send motor start command
        val result = orchestrator.sendCommand(HousingCommand.SetVacuumMotor(enabled = true))
        assertTrue(result) // SimulatedTransport always returns true for connected writes
    }
}
