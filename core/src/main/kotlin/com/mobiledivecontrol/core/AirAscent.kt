package com.mobiledivecontrol.core

/** Conservative gas-budget assumption, not a prescribed ascent speed or decompression rule. */
const val AIR_ASCENT_FALLBACK_METERS_PER_MINUTE = 6.0

/** Reuse the depth tracker’s rolling measurement; exclude startup, stale samples and holding-depth noise. */
fun measuredAirAscentMetersPerMinute(dive: DiveProfileState, nowMs: Long): Double? {
    val sampledAt = dive.lastSampleMs ?: return null
    if (dive.active == null || !dive.sensorAvailable || nowMs - sampledAt !in 0 until PRESSURE_STALE_MS) return null
    val first = dive.rateWindow.firstOrNull() ?: return null
    if (sampledAt - first.elapsedMs < 3_000 || dive.rateWindow.last().elapsedMs != sampledAt) return null
    val vertical = dive.verticalMetersPerMinute?.takeIf { it.isFinite() } ?: return null
    return (-vertical).takeIf { it >= 0.6 }
}

/** A measured slower ascent reserves more air. Faster-than-fallback movement cannot add bottom time relative to the fallback. */
fun airAscentBudgetMetersPerMinute(dive: DiveProfileState, nowMs: Long): Double =
    minOf(AIR_ASCENT_FALLBACK_METERS_PER_MINUTE,
        measuredAirAscentMetersPerMinute(dive, nowMs) ?: AIR_ASCENT_FALLBACK_METERS_PER_MINUTE)
