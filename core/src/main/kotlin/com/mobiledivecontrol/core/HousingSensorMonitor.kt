package com.mobiledivecontrol.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Notification-first telemetry with bounded reads when a sensor goes quiet. One instance per
 * connection, one outstanding read, no buttons/motor/valve reads, and no polling after cancellation.
 * All validation uses the same protocol parser as ControlCore; there is no alternate unit decoder.
 */
class HousingSensorMonitor(
    private val read: suspend (HousingCharacteristic) -> ByteArray?,
    private val deliver: (HousingCharacteristic, ByteArray, SensorPacketSource) -> Unit,
    private val nowMs: () -> Long = ::pressureMonotonicMs,
    private val parser: ProtocolParser = ProtocolParser(),
) {
    private data class Receipt(val atMs: Long, val sequence: Long)
    private val lock = Any()
    private val receipts = mutableMapOf<HousingCharacteristic, Receipt>()
    private val retryAt = mutableMapOf<HousingCharacteristic, Long>()
    private val failures = mutableMapOf<HousingCharacteristic, Int>()
    private var sequence = 0L
    private val readsEnabled = MutableStateFlow(true)
    private var readGeneration = 0L

    /** Suspend fallback reads during the pump workflow; notifications remain live. */
    fun setReadsEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (readsEnabled.value != enabled) {
                readGeneration++
                readsEnabled.value = enabled
            }
        }
    }

    /** Serializes notification delivery with read completion so an older read cannot win a race. */
    fun receive(characteristic: HousingCharacteristic, payload: ByteArray, source: SensorPacketSource) {
        synchronized(lock) {
            if (characteristic in intervals && valid(characteristic, payload)) {
                record(characteristic)
            }
            deliver(characteristic, payload, source)
        }
    }

    suspend fun run(available: Set<HousingCharacteristic>) {
        val sensors = intervals.keys.filter { it in available }
        if (sensors.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            for (sensor in sensors) {
                readsEnabled.first { it } // suspended while paused, with no timer wake-ups
                val before = synchronized(lock) {
                    if (!readsEnabled.value || dueIn(sensor) > 0L) null
                    else (receipts[sensor]?.sequence ?: -1L) to readGeneration
                } ?: continue
                val payload = try {
                    read(sensor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // Cancellation must not publish an old session's read after reconnection.
                if (!currentCoroutineContext().isActive) return
                synchronized(lock) {
                    // A valid notification arrived while the GATT read was in flight.
                    if ((receipts[sensor]?.sequence ?: -1L) != before.first ||
                        readGeneration != before.second || !readsEnabled.value) return@synchronized
                    if (payload != null && valid(sensor, payload)) {
                        record(sensor)
                    } else {
                        val count = ((failures[sensor] ?: 0) + 1).coerceAtMost(4)
                        failures[sensor] = count
                        retryAt[sensor] = nowMs() + (500L shl count) // 1, 2, 4, 8 seconds
                    }
                    // Rejected bytes still reach the diagnostics log, never the depth calculation.
                    if (payload != null) deliver(sensor, payload, SensorPacketSource.Read)
                }
            }
            val waitMs = synchronized(lock) { sensors.minOf { dueIn(it) }.coerceAtLeast(1L) }
            delay(waitMs)
        }
    }

    private fun record(sensor: HousingCharacteristic) {
        receipts[sensor] = Receipt(nowMs(), ++sequence)
        retryAt.remove(sensor)
        failures.remove(sensor)
    }

    private fun dueIn(sensor: HousingCharacteristic): Long {
        val now = nowMs()
        val afterReceipt = receipts[sensor]?.let { it.atMs + intervals.getValue(sensor) } ?: now
        return (maxOf(afterReceipt, retryAt[sensor] ?: now) - now).coerceAtLeast(0L)
    }

    private fun valid(sensor: HousingCharacteristic, payload: ByteArray): Boolean =
        parser.decodeNotification(sensor.shortHex, payload) is ParseResult.Success

    private companion object {
        val intervals = linkedMapOf(
            HousingCharacteristic.WaterPressure to 500L,
            HousingCharacteristic.BarometricPressure to 1_000L,
            HousingCharacteristic.WaterTemperature to 5_000L,
            // Firmware may omit the cap-change notification; refresh without busy polling.
            HousingCharacteristic.CoverState to 2_000L,
        )
    }
}
