package com.mobiledivecontrol.core

import kotlin.math.*

enum class DecoHistory { Uninitialized, Tracking, SurfaceIntervalUnconfirmed, Incomplete }
data class OtuMinute(val epochMinute: Long, val units: Double)
data class DecoReadings(val ndlSeconds: Int, val ceilingMeters: Double, val cnsPercent: Double,
    val cnsOutsideTable: Boolean, val otu24Hours: Double)

/** Physiology persists independently of saved dive logs and their retention limit. */
data class DecompressionState(
    val history: DecoHistory = DecoHistory.Uninitialized,
    val tissues: List<TissuePressure> = emptyList(),
    val cnsPercent: Double = 0.0,
    val cnsOutsideTable: Boolean = false,
    val otuMinutes: List<OtuMinute> = emptyList(),
    val anchorMeters: Double = 0.0,
    val surfaceMs: Long = 0,
    /** Time through which tissues/exposure were actually integrated; never advanced across gaps. */
    val integratedEpochMs: Long? = null,
    val sampleEpochMs: Long? = null,
    val sampleMonotonicMs: Long? = null,
    val depthMeters: Double? = null,
    val pressureBar: Double? = null,
    val ppO2Ata: Double? = null,
    val readings: DecoReadings? = null,
)

object DecompressionTracker {
    private const val SURFACE_METERS = .5
    private const val SURFACE_CONFIRM_MS = 60_000L
    private const val DAY_MS = 86_400_000L

    fun unavailable(s: DecompressionState): DecompressionState = s.copy(
        history = if (s.history == DecoHistory.Tracking) {
            if (s.surfaceMs >= SURFACE_CONFIRM_MS && (s.depthMeters ?: 100.0) <= SURFACE_METERS)
                DecoHistory.SurfaceIntervalUnconfirmed else DecoHistory.Incomplete
        } else s.history,
        readings = null, ppO2Ata = null, sampleMonotonicMs = null,
    )

    fun restored(s: DecompressionState) = unavailable(s)

    /** Explicit user declaration: no diving in the preceding 48 hours, with fresh surface pressure. */
    fun initialize(s: DecompressionState, gas: DiveGas): DecompressionState {
        if (s.sampleMonotonicMs == null || s.sampleEpochMs == null || (s.depthMeters ?: 100.0) > SURFACE_METERS) return s
        if (s.history == DecoHistory.Tracking) return s
        return calculate(s.copy(history = DecoHistory.Tracking, tissues = Buhlmann.equilibrium(),
            cnsPercent = 0.0, cnsOutsideTable = false, otuMinutes = emptyList(), anchorMeters = 0.0,
            surfaceMs = SURFACE_CONFIRM_MS, integratedEpochMs = s.sampleEpochMs), gas)
    }

    /** Offline air off-gassing is credited only after the diver confirms no unlogged immersion. */
    fun confirmSurfaceInterval(s: DecompressionState, gas: DiveGas): DecompressionState {
        if (s.history != DecoHistory.SurfaceIntervalUnconfirmed || s.sampleMonotonicMs == null ||
            (s.depthMeters ?: 100.0) > SURFACE_METERS || s.tissues.size != 16) return s
        val end = s.sampleEpochMs ?: return s
        val start = s.integratedEpochMs ?: return s
        if (end < start) return s.copy(history = DecoHistory.Incomplete)
        val minutes = (end-start)/60_000.0
        return calculate(s.copy(history = DecoHistory.Tracking,
            tissues = Buhlmann.load(s.tissues, Buhlmann.SURFACE_BAR, Buhlmann.SURFACE_BAR, minutes, DiveGas()),
            cnsPercent = OxygenExposure.surfaceCns(s.cnsPercent, minutes),
            cnsOutsideTable = s.cnsOutsideTable,
            otuMinutes = recentOtu(s.otuMinutes, end), anchorMeters = 0.0,
            surfaceMs = SURFACE_CONFIRM_MS, integratedEpochMs = end), gas)
    }

    fun sample(s: DecompressionState, depth: Double, absoluteBar: Double, nowMs: Long, epochMs: Long,
        gas: DiveGas): DecompressionState {
        if (!depth.isFinite() || depth !in 0.0..100.0 || !absoluteBar.isFinite() || absoluteBar !in .3..10.0)
            return unavailable(s)
        if (s.sampleMonotonicMs != null && nowMs <= s.sampleMonotonicMs) return s
        val dt = s.sampleMonotonicMs?.let { nowMs-it }
        val continuous = dt != null && dt in 1 until PRESSURE_STALE_MS
        val clockContinuous = (dt == null || s.sampleEpochMs == null || abs((epochMs-s.sampleEpochMs)-dt) <= 2_000) &&
            (s.integratedEpochMs == null || epochMs >= s.integratedEpochMs)
        var next = if (s.history == DecoHistory.Tracking && (!continuous || !clockContinuous)) unavailable(s) else s
        if (!clockContinuous && next.history != DecoHistory.Uninitialized) next = next.copy(history = DecoHistory.Incomplete)
        if (next.history == DecoHistory.SurfaceIntervalUnconfirmed && depth > SURFACE_METERS)
            next = next.copy(history = DecoHistory.Incomplete)
        next = next.copy(sampleMonotonicMs = nowMs, sampleEpochMs = epochMs,
            depthMeters = depth, pressureBar = absoluteBar, ppO2Ata = OxygenExposure.ppO2(absoluteBar, gas), readings = null)
        if (next.history != DecoHistory.Tracking) return next
        val duration = dt!! / 60_000.0
        val atSurface = depth <= SURFACE_METERS && (s.depthMeters ?: 100.0) <= SURFACE_METERS
        val surfaceMs = if (atSurface) (s.surfaceMs + dt).coerceAtMost(SURFACE_CONFIRM_MS) else 0L
        // Do not silently change a selected oxygen-rich gas at shallow depth.
        val surfaceAir = atSurface && s.surfaceMs >= SURFACE_CONFIRM_MS && gas == DiveGas()
        val breathing = gas
        val startBar = s.pressureBar ?: absoluteBar
        val p0 = OxygenExposure.ppO2(startBar, breathing)
        val p1 = OxygenExposure.ppO2(absoluteBar, breathing)
        val cns = if (surfaceAir) OxygenExposure.surfaceCns(s.cnsPercent, duration)
            else s.cnsPercent + OxygenExposure.cnsDose(p0, p1, duration)
        val otu = addOtu(s.otuMinutes, epochMs-dt, epochMs, p0, p1)
        return calculate(next.copy(
            tissues = Buhlmann.load(s.tissues, startBar, absoluteBar, duration, breathing),
            cnsPercent = cns, cnsOutsideTable = s.cnsOutsideTable || max(p0,p1) > 1.6,
            otuMinutes = otu, surfaceMs = surfaceMs, integratedEpochMs = epochMs,
            anchorMeters = if (surfaceMs >= SURFACE_CONFIRM_MS) 0.0 else s.anchorMeters,
        ), gas)
    }

    private fun calculate(s: DecompressionState, gas: DiveGas): DecompressionState {
        if (s.history != DecoHistory.Tracking || s.tissues.size != 16) return s.copy(readings = null)
        val ndl = Buhlmann.ndlSeconds(s.tissues, s.depthMeters ?: 0.0, gas)
        val anchor = if (ndl == 0) max(s.anchorMeters,
            ceil(Buhlmann.depth(Buhlmann.ceilingPressure(s.tissues, Buhlmann.GF_LOW))/3)*3) else s.anchorMeters
        return s.copy(anchorMeters = anchor, readings = DecoReadings(ndl,
            Buhlmann.ceilingMeters(s.tissues, anchor), s.cnsPercent, s.cnsOutsideTable, s.otuMinutes.sumOf { it.units }))
    }

    private fun recentOtu(list: List<OtuMinute>, epochMs: Long) =
        list.filter { (it.epochMinute+1)*60_000 > epochMs-DAY_MS }

    /** One-minute bins, retaining the entire boundary minute so expiry never undercounts OTU. */
    private fun addOtu(list: List<OtuMinute>, start: Long, end: Long, p0: Double, p1: Double): List<OtuMinute> {
        val bins = recentOtu(list, end).associate { it.epochMinute to it.units }.toMutableMap()
        var at = start
        while (at < end) {
            val minute = at / 60_000
            val until = min(end, (minute+1)*60_000)
            fun p(t: Long) = p0 + (p1-p0)*(t-start).toDouble()/(end-start)
            val units = OxygenExposure.otuDose(p(at), p(until), (until-at)/60_000.0)
            if (units > 0) bins[minute] = (bins[minute] ?: 0.0) + units
            at = until
        }
        return bins.entries.sortedBy { it.key }.map { OtuMinute(it.key, it.value) }
    }
}
