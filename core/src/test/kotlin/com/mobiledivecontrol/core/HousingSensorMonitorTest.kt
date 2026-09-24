package com.mobiledivecontrol.core

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HousingSensorMonitorTest {
    private val water = HousingCharacteristic.WaterPressure
    private val air = HousingCharacteristic.BarometricPressure
    private fun bytes(raw: Int) = ByteArray(4) { (raw ushr (8 * it)).toByte() }

    @Test
    fun `quiet external sensor is read twice a second and each change reaches depth immediately`() = runTest {
        val core = ControlCore(AppState(safety = SafetyState(surfaceAmbientKpa = 101.15)))
        val reads = mutableListOf<Pair<HousingCharacteristic, Long>>()
        var waterRaw = 10115
        val monitor = HousingSensorMonitor(
            read = { sensor ->
                reads += sensor to testScheduler.currentTime
                bytes(if (sensor == water) waterRaw else 81000)
            },
            deliver = { sensor, payload, source ->
                core.handleNotificationPayload(sensor.shortHex, payload, Instant.EPOCH, source, testScheduler.currentTime)
            },
            nowMs = { testScheduler.currentTime },
        )
        monitor.receive(water, bytes(waterRaw), SensorPacketSource.Read)
        monitor.receive(air, bytes(81000), SensorPacketSource.Read)
        val job = launch { monitor.run(setOf(water, air)) }
        runCurrent()
        assertTrue(reads.isEmpty())
        waterRaw = 10623 // +4.905 kPa over the fixed reference: 0.5 m.
        advanceTimeBy(500); runCurrent()
        assertEquals(0.5, pressureDepthMeters(core.state.safety.waterPressureKpa)!!, 0.001)
        assertEquals(81.0, core.state.safety.barometricPressureKpa)
        assertEquals(101.15, core.state.safety.surfaceAmbientKpa)
        assertEquals(SensorPacketSource.Read, core.state.waterPressureTelemetry?.source)
        waterRaw = 11114
        advanceTimeBy(500); runCurrent()
        assertEquals(1.0, pressureDepthMeters(core.state.safety.waterPressureKpa)!!, 0.001)
        assertEquals(listOf(water to 500L, water to 1000L, air to 1000L), reads)
        job.cancelAndJoin()
        advanceTimeBy(5000); runCurrent()
        assertEquals(3, reads.size)
    }

    @Test
    fun `healthy notifications suppress redundant reads and do not wait for polling`() = runTest {
        var readCount = 0
        val delivered = mutableListOf<ByteArray>()
        val monitor = HousingSensorMonitor(
            read = { readCount++; bytes(10115) },
            deliver = { _, payload, _ -> delivered += payload },
            nowMs = { testScheduler.currentTime },
        )
        monitor.receive(water, bytes(10115), SensorPacketSource.Notification)
        val job = launch { monitor.run(setOf(water)) }
        repeat(20) {
            advanceTimeBy(200)
            monitor.receive(water, bytes(10115 + it), SensorPacketSource.Notification)
            assertEquals(it + 2, delivered.size)
            runCurrent()
        }
        assertEquals(0, readCount)
        job.cancelAndJoin()
    }

    @Test
    fun `missing sensors buttons and actuators are never polled`() = runTest {
        val reads = mutableListOf<HousingCharacteristic>()
        val monitor = HousingSensorMonitor(
            read = { reads += it; bytes(81000) },
            deliver = { _, _, _ -> },
            nowMs = { testScheduler.currentTime },
        )
        val job = launch { monitor.run(setOf(air, HousingCharacteristic.ButtonEvents, HousingCharacteristic.VacuumMotor)) }
        advanceTimeBy(2100); runCurrent()
        assertEquals(listOf(air, air, air), reads)
        job.cancelAndJoin()
    }

    @Test
    fun `failed reads back off without making data fresh`() = runTest {
        val readTimes = mutableListOf<Long>()
        var delivered = 0
        val monitor = HousingSensorMonitor(
            read = { readTimes += testScheduler.currentTime; null },
            deliver = { _, _, _ -> delivered++ },
            nowMs = { testScheduler.currentTime },
        )
        val job = launch { monitor.run(setOf(water)) }
        advanceTimeBy(14999); runCurrent()
        assertEquals(listOf(0L, 1000L, 3000L, 7000L), readTimes)
        assertEquals(0, delivered)
        job.cancelAndJoin()
    }

    @Test
    fun `malformed notifications cannot suppress recovery reads`() = runTest {
        var readCount = 0
        val monitor = HousingSensorMonitor(
            read = { readCount++; bytes(10606) },
            deliver = { _, _, _ -> },
            nowMs = { testScheduler.currentTime },
        )
        monitor.receive(water, bytes(10115), SensorPacketSource.Notification)
        val job = launch { monitor.run(setOf(water)) }
        repeat(6) {
            advanceTimeBy(100)
            monitor.receive(water, byteArrayOf(0), SensorPacketSource.Notification)
            runCurrent()
        }
        assertEquals(1, readCount)
        job.cancelAndJoin()
    }

    @Test
    fun `notification arriving during a read cannot be overwritten by that older read`() = runTest {
        val pendingRead = CompletableDeferred<ByteArray?>()
        val values = mutableListOf<String>()
        val monitor = HousingSensorMonitor(
            read = { pendingRead.await() },
            deliver = { _, payload, _ -> values += payload.toHexString() },
            nowMs = { testScheduler.currentTime },
        )
        val job = launch { monitor.run(setOf(water)) }
        runCurrent()
        monitor.receive(water, bytes(10606), SensorPacketSource.Notification)
        pendingRead.complete(bytes(10115))
        runCurrent()
        assertEquals(listOf(bytes(10606).toHexString()), values)
        job.cancelAndJoin()
    }

    @Test
    fun `missed cap close notification is recovered by a read without inferring from vacuum`() = runTest {
        val cap = HousingCharacteristic.CoverState
        val core = ControlCore(AppState(safety = SafetyState(barometricPressureKpa = 78.6)))
        var capByte: Byte = 0
        val reads = mutableListOf<Long>()
        val monitor = HousingSensorMonitor(
            read = { reads += testScheduler.currentTime; byteArrayOf(capByte) },
            deliver = { sensor, payload, source ->
                core.handleNotificationPayload(sensor.shortHex, payload, Instant.EPOCH, source, testScheduler.currentTime)
            },
            nowMs = { testScheduler.currentTime },
        )
        monitor.receive(cap, byteArrayOf(0), SensorPacketSource.Read)
        val job = launch { monitor.run(setOf(cap)) }
        advanceTimeBy(2000); runCurrent()
        assertEquals(true, core.state.safety.coverOpen) // vacuum never overrides the raw switch
        capByte = 1 // physical change, but firmware sends no notification
        advanceTimeBy(2000); runCurrent()
        assertEquals(false, core.state.safety.coverOpen)
        assertEquals(listOf(2000L, 4000L), reads)
        job.cancelAndJoin()
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, reads.size)
    }


    @Test
    fun `pump workflow pauses all fallback reads while notifications remain immediate`() = runTest {
        val reads = mutableListOf<HousingCharacteristic>()
        val delivered = mutableListOf<SensorPacketSource>()
        val monitor = HousingSensorMonitor(
            read = { reads += it; bytes(10115) },
            deliver = { _, _, source -> delivered += source },
            nowMs = { testScheduler.currentTime },
        )
        monitor.setReadsEnabled(false)
        val job = launch { monitor.run(setOf(water, air, HousingCharacteristic.CoverState)) }
        advanceTimeBy(10_000); runCurrent()
        assertTrue(reads.isEmpty())
        monitor.receive(air, bytes(99000), SensorPacketSource.Notification)
        assertEquals(listOf(SensorPacketSource.Notification), delivered)
        monitor.setReadsEnabled(true)
        runCurrent()
        assertEquals(listOf(water, HousingCharacteristic.CoverState), reads)
        job.cancelAndJoin()
    }

    @Test
    fun `a read begun before pump startup cannot deliver an old sample into the pump workflow`() = runTest {
        val pendingRead = CompletableDeferred<ByteArray?>()
        val delivered = mutableListOf<String>()
        val monitor = HousingSensorMonitor(
            read = { pendingRead.await() },
            deliver = { _, payload, _ -> delivered += payload.toHexString() },
            nowMs = { testScheduler.currentTime },
        )
        val job = launch { monitor.run(setOf(water)) }
        runCurrent()
        monitor.setReadsEnabled(false)
        pendingRead.complete(bytes(10115))
        runCurrent()
        assertTrue(delivered.isEmpty())
        monitor.receive(water, bytes(10606), SensorPacketSource.Notification)
        assertEquals(listOf(bytes(10606).toHexString()), delivered)
        job.cancelAndJoin()
    }

}
