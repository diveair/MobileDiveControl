package com.mobiledivecontrol.core

import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PressureReadingTest {
    private fun bytes(raw: Long) = ByteArray(4) { (raw ushr (8 * it)).toByte() }

    @Test
    fun `water and vacuum pressure use their distinct vendor units and never substitute`() {
        val core = ControlCore(AppState(safety = SafetyState(surfaceAmbientKpa = 101.15)))
        core.handleNotificationPayload("1625", bytes(10623))
        core.handleNotificationPayload("1627", bytes(81000))
        assertEquals(106.23, core.state.safety.waterPressureKpa)
        assertEquals(81.0, core.state.safety.barometricPressureKpa)
        assertEquals(0.5, pressureDepthMeters(core.state.safety.waterPressureKpa)!!, 0.001)
        core.handleNotificationPayload("1627", bytes(75000))
        assertEquals(106.23, core.state.safety.waterPressureKpa)
        assertEquals(101.15, core.state.safety.surfaceAmbientKpa)
    }

    @Test
    fun `range and malformed payload errors neither overwrite pressure nor refresh its age`() {
        val core = ControlCore()
        core.handleNotificationPayload("1625", bytes(10115), receivedAtMonotonicMs = 0)
        val good = core.state.waterPressureTelemetry
        for (invalid in listOf(bytes(0), bytes(100001), bytes(0xFFFFFFFF), byteArrayOf(1, 2), ByteArray(5))) {
            core.handleNotificationPayload("1625", invalid, receivedAtMonotonicMs = 2000)
            assertEquals(good, core.state.waterPressureTelemetry)
            assertEquals(101.15, core.state.safety.waterPressureKpa)
        }
        assertFalse(good!!.isFresh(3000))
        val parser = ProtocolParser()
        for (raw in listOf(0L, 29999, 120001, 0xFFFFFFFF)) {
            assertIs<ParseResult.Failure>(parser.decodeBarometricPressure(bytes(raw)))
        }
        assertIs<ParseResult.Success<SensorUpdate.WaterPressure>>(parser.decodeWaterPressure(bytes(100000)))
    }

    @Test
    fun `equal packets still confirm liveness but a wall clock change cannot revive stale data`() {
        val core = ControlCore()
        core.handleNotificationPayload("1625", bytes(10115), Instant.EPOCH, receivedAtMonotonicMs = 0)
        core.handleNotificationPayload("1625", bytes(10115), Instant.EPOCH.minusSeconds(3600), receivedAtMonotonicMs = 500)
        val telemetry = core.state.waterPressureTelemetry!!
        assertEquals(2L, telemetry.packetCount)
        assertTrue(telemetry.isFresh(3499))
        assertFalse(telemetry.isFresh(3500))
        assertFalse(telemetry.isFresh(499))
    }

    @Test
    fun `invalid numeric values are never displayed as depth and valid changes are not smoothed`() {
        for (water in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 1000.01)) {
            assertNull(pressureDepthMeters(water))
        }
        assertEquals(0.0, pressureDepthMeters(101.14))
        assertEquals(0.5, pressureDepthMeters(106.23)!!, 1e-10)
        assertEquals(1.0, pressureDepthMeters(111.135)!!, 1e-10)
        // A reconnect underwater is not automatically re-zeroed to the new pressure.
        assertTrue(pressureDepthMeters(106.23)!! > 0.49)
    }

    @Test
    fun `every tenth of a metre survives BLE decoding and display rounding`() {
        val core = ControlCore(AppState(safety = SafetyState(surfaceAmbientKpa = 101.15)))
        for (tenths in 0..10) {
            val raw = kotlin.math.round((STANDARD_SURFACE_PRESSURE_KPA + tenths * 0.1 * 9.81) * 100).toLong()
            core.handleNotificationPayload("1625", bytes(raw))
            val depth = pressureDepthMeters(core.state.safety.waterPressureKpa)!!
            assertEquals(tenths * 0.1, depth, 0.0006)
            assertEquals(
                String.format(java.util.Locale.US, "%.1f", tenths * 0.1),
                String.format(java.util.Locale.US, "%.1f", depth),
            )
        }
    }

    @Test
    fun `reconnection never revives the prior sessions packet`() {
        val core = ControlCore(AppState(
            bleConnectionState = BleConnectionState.Ready,
            housing = HousingState(connected = true),
            safety = SafetyState(surfaceAmbientKpa = 101.15),
        ))
        core.handleNotificationPayload("1625", bytes(10623))
        assertTrue(core.state.waterPressureTelemetry != null)
        core.advanceBle(BleSignal.Disconnect)
        assertNull(core.state.waterPressureTelemetry)
        core.advanceBle(BleSignal.Ready)
        assertNull(core.state.waterPressureTelemetry)
        core.handleNotificationPayload("1625", bytes(11114))
        assertEquals(1.0, pressureDepthMeters(core.state.safety.waterPressureKpa)!!, 0.001)
    }

    @Test
    fun `Diveit first three bytes are unsigned little endian with the fourth ignored`() {
        val parser = ProtocolParser()
        for (raw in listOf(10115L, 10623L, 14057L, 65535L, 98559L, 100000L)) {
            val expected = raw / 100.0
            for (payload in listOf(bytes(raw).copyOf(3), bytes(raw), bytes(raw).also { it[3] = 0xff.toByte() })) {
                val decoded = assertIs<ParseResult.Success<SensorUpdate.WaterPressure>>(parser.decodeWaterPressure(payload))
                assertEquals(expected, decoded.value.kpa)
            }
        }
    }

    @Test
    fun `captured internal pressure cannot shift depth and four metre packets are retained`() {
        val core = ControlCore(AppState(safety = SafetyState(surfaceAmbientKpa = 105.5)))
        core.handleNotificationPayload("1625", bytes(14057))
        core.handleNotificationPayload("1627", bytes(84000))
        assertEquals(4.0, pressureDepthMeters(core.state.safety.waterPressureKpa)!!, 0.001)
        assertEquals(105.5, core.state.safety.surfaceAmbientKpa)
        assertEquals(84.0, core.state.safety.barometricPressureKpa)
    }

    @Test
    fun `exports preserve exact pressure bytes sample origin and receipt time`() {
        val core = ControlCore()
        core.handleNotificationPayload("1625", bytes(10623), Instant.ofEpochMilli(1234), SensorPacketSource.Read)
        val exported = core.exportDiagnostics()
        assertTrue(exported.getValue("pressure-sensors.json").contains("7F 29 00 00"))
        assertTrue(exported.getValue("pressure-sensors.json").contains("Read"))
        assertTrue(exported.getValue("raw-packets.jsonl").contains("7F290000"))
    }
}
