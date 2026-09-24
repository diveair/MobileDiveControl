package com.mobiledivecontrol.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Versioned, bounded local checkpoint. Does not deserialize executable objects. */
object DiveProfileCodec {
    /** Fits 1,000 full 3,600-point logs, an active profile, and the tissue/oxygen checkpoint. */
    const val MAX_ENCODED_BYTES = 64_000_000

    fun encode(state: DiveProfileState): ByteArray = ByteArrayOutputStream().also { bytes ->
        require(state.logs.size <= DiveProfileTracker.MAX_LOGS)
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x44495645)
            out.writeInt(4)
            out.settings(state.settings)
            out.writeBoolean(state.active != null)
            state.active?.let { out.session(it) }
            out.writeInt(state.logs.size)
            state.logs.forEach { out.session(it) }
            out.decompression(state.decompression)
            out.writeInt(state.stopPresentation.ordinal)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): DiveProfileState {
        require(bytes.size <= MAX_ENCODED_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x44495645)
            val version = input.readInt().also { require(it in 1..4) }
            val settings = input.settings()
            val active = if (input.readBoolean()) input.session(version) else null
            val count = input.readInt().also { require(it in 0..DiveProfileTracker.MAX_LOGS) }
            val logs = List(count) { input.session(version) }
            val decompression = if (version >= 2) input.decompression() else DecompressionState()
            val presentation = if (version >= 3) DiveStopPresentation.entries[input.readInt()] else DiveStopPresentation.Expanded
            require(input.available() == 0)
            DiveProfileTracker.restored(DiveProfileState(settings = settings, active = active, logs = logs,
                decompression = decompression, stopPresentation = presentation))
        }
    }

    private fun DataOutputStream.settings(s: DiveSettings) {
        writeInt(s.gas.oxygenPercent); writeInt(s.gas.heliumPercent)
        writeInt(s.stopDepthMeters); writeInt(s.stopDurationSeconds)
        writeBoolean(false) // Reserved: the retired manual stop-requirement flag in versions 1–4.
    }
    private fun DataInputStream.settings(): DiveSettings {
        val settings = DiveSettings(DiveGas(readInt(), readInt()), readInt(), readInt())
        readBoolean() // Consume legacy manual flags without letting them override the recorded profile.
        return settings
    }

    private fun DataOutputStream.decompression(s: DecompressionState) {
        writeInt(s.history.ordinal)
        writeInt(s.tissues.size)
        s.tissues.forEach { writeDouble(it.nitrogenBar); writeDouble(it.heliumBar) }
        writeDouble(s.cnsPercent); writeBoolean(s.cnsOutsideTable)
        writeDouble(s.anchorMeters); writeLong(s.surfaceMs)
        writeLong(s.integratedEpochMs ?: -1L)
        writeDouble(s.depthMeters ?: -1.0)
        writeInt(s.otuMinutes.size)
        s.otuMinutes.forEach { writeLong(it.epochMinute); writeDouble(it.units) }
    }

    private fun DataInputStream.boundedDouble(max: Double): Double = readDouble().also {
        require(it.isFinite() && it in 0.0..max)
    }

    private fun DataInputStream.decompression(): DecompressionState {
        val history = DecoHistory.entries[readInt()]
        val count = readInt().also { require(it == 0 || it == 16) }
        require(history == DecoHistory.Uninitialized || count == 16)
        val tissues = List(count) { TissuePressure(boundedDouble(20.0), boundedDouble(20.0)) }
        val cns = boundedDouble(1_000_000.0)
        val outsideTable = readBoolean()
        val anchor = boundedDouble(200.0)
        val surfaceMs = readLong().also { require(it in 0..60_000) }
        val integrated = readLong().also { require(it in -1..253_402_300_799_000L) }.takeIf { it >= 0 }
        require(history == DecoHistory.Uninitialized || integrated != null)
        val depth = readDouble().also { require(it.isFinite() && (it == -1.0 || it in 0.0..100.0)) }.takeIf { it >= 0 }
        val bins = readInt().also { require(it in 0..1441) }
        var previous = -1L
        val otu = List(bins) {
            val minute = readLong().also { require(it in 0..4_223_371_679L && it > previous); previous = it }
            OtuMinute(minute, boundedDouble(1_000_000.0))
        }
        return DecompressionState(history = history, tissues = tissues, cnsPercent = cns,
            cnsOutsideTable = outsideTable, otuMinutes = otu, anchorMeters = anchor,
            surfaceMs = surfaceMs, integratedEpochMs = integrated, depthMeters = depth)
    }

    private fun DataOutputStream.session(s: DiveSession) {
        writeLong(s.startedAtEpochMs); settings(s.settings); writeLong(s.elapsedMs); writeDouble(s.maxDepthMeters)
        writeInt(s.phase.ordinal); writeLong(s.stopElapsedMs); writeBoolean(s.stopStarted)
        writeBoolean(s.profileIncomplete); writeInt(s.outcome?.ordinal ?: -1)
        writeInt(s.samples.size)
        s.samples.forEach { writeLong(it.elapsedMs); writeDouble(it.depthMeters); writeBoolean(it.gapBefore) }
        writeBoolean(s.limitReached)
        writeDouble(s.shallowestAfterStopMeters ?: -1000.0)
        writeInt(s.minimumNdlSeconds ?: -1)
    }

    private fun DataInputStream.session(version: Int): DiveSession {
        val start = readLong().also { require(it >= 0) }
        val settings = settings()
        val elapsed = readLong().also { require(it >= 0) }
        val max = readDouble().also { require(it.isFinite() && it in 0.0..100.0) }
        val phase = DiveStopPhase.entries[readInt()]
        val progress = readLong().also { require(it in 0..settings.stopDurationSeconds * 1000L) }
        val started = readBoolean()
        val incomplete = readBoolean()
        val result = readInt().let { if (it == -1) null else DiveLogOutcome.entries[it] }
        val count = readInt().also { require(it in 0..DiveProfileTracker.MAX_SAMPLES) }
        var previous = -1L
        val samples = List(count) {
            val time = readLong().also { require(it in 0..elapsed && it >= previous); previous = it }
            // The requested 0 m motion exercise can log signed depths above its surface target.
            val minimumDepth = if (settings.stopDepthMeters == 0) -1.5 else 0.0
            val depth = readDouble().also { require(it.isFinite() && it in minimumDepth..100.0) }
            DiveSample(time, depth, readBoolean())
        }
        val limitReached = version >= 3 && readBoolean()
        val shallowest = if (version >= 3) readDouble().also {
            require(it.isFinite() && (it == -1000.0 || it in (if (settings.stopDepthMeters == 0) -1.5 else 0.0)..100.0))
        }.takeUnless { it == -1000.0 } else samples.lastOrNull()?.depthMeters.takeIf { phase == DiveStopPhase.Complete }
        val minimumNdl = if (version >= 4) readInt().also { require(it in -1..Buhlmann.NDL_CAP_SECONDS) }.takeIf { it >= 0 } else null
        return DiveSession(start, settings, elapsed, max, samples, phase, progress, started, incomplete, result,
            limitReached = limitReached, shallowestAfterStopMeters = shallowest, minimumNdlSeconds = minimumNdl)
    }
}
