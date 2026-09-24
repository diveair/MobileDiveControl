package com.mobiledivecontrol.platform.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GattQueueSensorIsolationTest {
    @Test
    fun `air pressure callback cannot complete an external pressure read`() = runTest {
        val queue = GattQueue(backgroundScope)
        val waterUuid = "00001625-1212-efde-1523-785feabcd123"
        val airUuid = "00001627-1212-efde-1523-785feabcd123"
        val pending = async { queue.submit(GattQueue.Kind.Read, waterUuid, "water") { true } }
        runCurrent()
        queue.complete(GattQueue.Kind.Read, airUuid, 0, byteArrayOf(0x7F, 0x8A.toByte(), 1, 0))
        runCurrent()
        assertFalse(pending.isCompleted)
        val water = byteArrayOf(0x83.toByte(), 0x27, 0, 0)
        queue.complete(GattQueue.Kind.Read, waterUuid.uppercase(), 0, water)
        runCurrent()
        assertTrue(pending.await().isSuccess)
        assertArrayEquals(water, pending.await().value)
        queue.close()
    }

    @Test
    fun `cover read cannot consume a late pressure callback`() = runTest {
        val queue = GattQueue(backgroundScope)
        val pending = async { queue.submit(GattQueue.Kind.Read, "1628", "cover") { true } }
        runCurrent()
        queue.complete(GattQueue.Kind.Read, "1625", 0, byteArrayOf(0x83.toByte(), 0x27, 0, 0))
        queue.complete(GattQueue.Kind.Write, "1628", 0, null)
        runCurrent()
        assertFalse(pending.isCompleted)
        queue.complete(GattQueue.Kind.Read, "1628", 0, byteArrayOf(1))
        runCurrent()
        assertArrayEquals(byteArrayOf(1), pending.await().value)
        queue.close()
    }
}
