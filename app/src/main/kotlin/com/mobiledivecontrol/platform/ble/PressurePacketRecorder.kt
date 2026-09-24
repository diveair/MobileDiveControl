package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.DecodedNotification
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.ParseResult
import com.mobiledivecontrol.core.ProtocolParser
import com.mobiledivecontrol.core.STANDARD_SURFACE_PRESSURE_KPA
import com.mobiledivecontrol.core.pressureDepthMeters
import com.mobiledivecontrol.core.SensorUpdate
import com.mobiledivecontrol.core.jsonObject
import com.mobiledivecontrol.core.toSpacedHexString
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout

/** Local engineering capture: never blocks Bluetooth, polls sensors, or changes application state. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PressurePacketRecorder(
    private val directory: File,
    scope: CoroutineScope,
    private val maxFileBytes: Long = 4L * 1024 * 1024,
    private val onFailure: (Exception) -> Unit = {},
) {
    private data class Entry(
        val sequence: Long, val event: HousingLinkEvent?, val epochMs: Long,
        val captureId: String, val captureActive: Boolean? = null, val surfaceBaselineKpa: Double? = null,
    )
    private val pending = Channel<Entry>(256)
    private val dropped = AtomicLong()
    private val sequence = AtomicLong()
    private val recordingId = UUID.randomUUID().toString()
    private val parser = ProtocolParser()
    private val enqueueLock = Any()
    @Volatile private var enabled = false
    private var captureId = ""

    fun setEnabled(active: Boolean, surfaceBaselineKpa: Double? = null) {
        if (enabled == active) return
        synchronized(enqueueLock) {
            if (enabled == active) return
            enabled = active
            if (active) captureId = UUID.randomUUID().toString()
            enqueue(Entry(sequence.incrementAndGet(), null, System.currentTimeMillis(),
                captureId, active, surfaceBaselineKpa))
        }
    }

    private fun enqueue(entry: Entry) {
        if (pending.trySend(entry).isFailure) dropped.incrementAndGet()
    }

    private val writerJob = scope.launch {
        try {
            RotatingLog(directory, maxFileBytes).use { output ->
                output.line(jsonObject("type" to "recording_started", "recordingId" to recordingId,
                    "epochMs" to System.currentTimeMillis(), "maxFileBytes" to maxFileBytes))
                output.flush()
                for (first in pending) {
                    output.line(format(first))
                    // One flush per second while receiving; no periodic wake-ups when idle.
                    val flushAtNanos = System.nanoTime() + 1_000_000_000L
                    var batchSize = 1
                    while (batchSize < 128) {
                        val remainingMs = ((flushAtNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                        // select avoids cancelling a receive at the flush deadline, which could
                        // otherwise consume a packet without ever writing its evidence.
                        val next = select<Entry?> {
                            pending.onReceiveCatching { it.getOrNull() }
                            onTimeout(remainingMs) { null }
                        } ?: break
                        output.line(format(next))
                        batchSize++
                    }
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) output.line(jsonObject("type" to "dropped_samples",
                        "recordingId" to recordingId, "epochMs" to System.currentTimeMillis(), "count" to lost))
                    output.flush()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Storage failure must never take down the radio or depth pipeline.
            onFailure(error)
        } finally {
            pending.close()
        }
    }

    fun record(event: HousingLinkEvent) {
        if (!enabled) return
        if (event is HousingLinkEvent.Warning) return
        if (event is HousingLinkEvent.Notification &&
            HousingCharacteristic.from(event.characteristicShortHex) !in sensors) return
        synchronized(enqueueLock) {
            if (!enabled) return
            // Android callbacks can reuse byte arrays. Preserve the exact received payload.
            val stable = if (event is HousingLinkEvent.Notification) event.copy(payload = event.payload.copyOf()) else event
            enqueue(Entry(sequence.incrementAndGet(), stable, System.currentTimeMillis(), captureId))
        }
    }

    /** Engineering tests can drain the recorder; the installed recorder is process-scoped. */
    suspend fun close() {
        pending.close()
        writerJob.join()
    }

    private fun format(entry: Entry): String = when (val event = entry.event) {
        null -> jsonObject("type" to if (entry.captureActive == true) "capture_started" else "capture_stopped",
            "recordingId" to recordingId, "captureId" to entry.captureId, "sequence" to entry.sequence,
            "epochMs" to entry.epochMs, "surfaceBaselineKpa" to entry.surfaceBaselineKpa)
        is HousingLinkEvent.Notification -> {
            val sensor = HousingCharacteristic.from(event.characteristicShortHex)!!
            val decoded = parser.decodeNotification(sensor.shortHex, event.payload)
            val update = ((decoded as? ParseResult.Success)?.value as? DecodedNotification.Sensor)?.update
            val pressure = when (update) {
                is SensorUpdate.WaterPressure -> update.kpa
                is SensorUpdate.BarometricPressure -> update.kpa
                else -> null
            }
            val depth = (update as? SensorUpdate.WaterPressure)?.let { pressureDepthMeters(it.kpa) }
            val raw24 = if (sensor == HousingCharacteristic.WaterPressure && event.payload.size >= 3) {
                (event.payload[0].toInt() and 255) or
                    ((event.payload[1].toInt() and 255) shl 8) or
                    ((event.payload[2].toInt() and 255) shl 16)
            } else null
            val raw = if (event.payload.size == 4) event.payload.withIndex().fold(0L) { value, (i, byte) ->
                value or ((byte.toLong() and 0xffL) shl (i * 8))
            } else null
            jsonObject("type" to "sensor_packet", "recordingId" to recordingId,
                "captureId" to entry.captureId, "sequence" to entry.sequence, "epochMs" to event.receivedAtEpochMs,
                "monotonicMs" to event.receivedAtMonotonicMs, "characteristic" to sensor.shortHex,
                "sensor" to sensor.name, "source" to event.source.name,
                "rawHex" to event.payload.toSpacedHexString(), "bytes" to event.payload.map { it.toInt() and 255 },
                "rawUnsignedLE32" to raw, "rawPressureLE24" to raw24, "pressureKpa" to pressure,
                "depthReferenceKpa" to if (sensor == HousingCharacteristic.WaterPressure) STANDARD_SURFACE_PRESSURE_KPA else null,
                "calculatedDepthMeters" to depth,
                "depthDisplay" to depth?.let { String.format(java.util.Locale.ROOT, "%.1f m", it) },
                "temperatureC" to (update as? SensorUpdate.WaterTemperature)?.celsius,
                "coverOpen" to (update as? SensorUpdate.CoverState)?.open,
                "valid" to (decoded is ParseResult.Success),
                "error" to (decoded as? ParseResult.Failure)?.error?.code)
        }
        is HousingLinkEvent.Ble -> jsonObject("type" to "connection", "recordingId" to recordingId,
            "captureId" to entry.captureId, "sequence" to entry.sequence, "epochMs" to entry.epochMs, "signal" to event.signal.toString())
        is HousingLinkEvent.Identity -> jsonObject("type" to "identity", "recordingId" to recordingId,
            "captureId" to entry.captureId, "sequence" to entry.sequence, "epochMs" to entry.epochMs, "firmware" to event.firmware)
        is HousingLinkEvent.Warning -> error("Warnings are not recorded")
    }

    private class RotatingLog(directory: File, private val maxBytes: Long) : AutoCloseable {
        private val current = File(directory, "pressure-current.jsonl")
        private val previous = File(directory, "pressure-previous.jsonl")
        private var output: BufferedOutputStream
        private var size: Long
        init {
            require(maxBytes > 0)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create pressure log directory" }
            size = current.length()
            output = current.outputStreamAppend()
        }
        fun line(line: String) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (size > 0 && size + bytes.size > maxBytes) {
                output.close()
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
                output = current.outputStreamAppend()
                size = 0
            }
            output.write(bytes)
            size += bytes.size
        }
        fun flush() = output.flush()
        override fun close() = output.close()
        private fun File.outputStreamAppend() = BufferedOutputStream(java.io.FileOutputStream(this, true), 32 * 1024)
    }

    private companion object {
        val sensors = setOf(HousingCharacteristic.WaterPressure, HousingCharacteristic.BarometricPressure,
            HousingCharacteristic.WaterTemperature, HousingCharacteristic.CoverState)
    }
}
