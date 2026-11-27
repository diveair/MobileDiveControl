package com.mobiledivecontrol.depthlab

import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabDepthRouterTest {
    private val router = LabDepthRouter()
    private fun water(raw: Int, time: Long, source: SensorPacketSource) =
        HousingLinkEvent.Notification(HousingCharacteristic.WaterPressure.shortHex,
            byteArrayOf(raw.toByte(), (raw ushr 8).toByte(), (raw ushr 16).toByte()), source, 0, time)

    @Test fun `USB overrides only displayed water while the real housing keeps sending data and buttons`() {
        val raw = water(10133, 100, SensorPacketSource.Read)
        assertSame(raw, router.route(LabInput.Physical(raw)))
        val simulated = water(16509, 200, SensorPacketSource.Simulation)
        assertSame(simulated, router.route(LabInput.Laptop(simulated)))
        for (source in listOf(SensorPacketSource.Read, SensorPacketSource.Notification)) {
            val newer = raw.copy(source = source, receivedAtMonotonicMs = 300)
            assertNull(router.route(LabInput.Physical(newer)))
            assertNotNull(router.physicalDepth(301))
            assertTrue(router.physicalDepth(301)!! < 0.01)
        }
        val button = HousingLinkEvent.Notification(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x50))
        val barometer = HousingLinkEvent.Notification(HousingCharacteristic.BarometricPressure.shortHex, byteArrayOf(0,0,0,0))
        for (live in listOf(button, barometer, HousingLinkEvent.Ble(BleSignal.Ready))) {
            assertSame(live, router.route(LabInput.Physical(live)))
        }
        assertNull(router.route(LabInput.MissingDepth))
        assertNull(router.route(LabInput.Physical(raw)))
        assertNull(router.route(LabInput.HousingDepth))
        assertSame(raw, router.route(LabInput.Physical(raw)))
        assertEquals("Housing", router.source)
        assertNull(router.physicalDepth(10_000))
    }

    @Test fun `closing USB never generates a BLE disconnect and losing BLE never creates simulated readiness`() {
        router.route(LabInput.Laptop(water(16509, 100, SensorPacketSource.Simulation)))
        router.route(LabInput.Physical(water(10133, 100, SensorPacketSource.Notification)))
        val disconnect = HousingLinkEvent.Ble(BleSignal.Disconnect)
        assertSame(disconnect, router.route(LabInput.Physical(disconnect)))
        assertNull(router.physicalDepth(101))
        assertEquals("Laptop", router.source)
        assertNull(router.route(LabInput.HousingDepth))
    }
}
