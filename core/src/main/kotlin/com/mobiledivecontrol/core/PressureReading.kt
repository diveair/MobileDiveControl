package com.mobiledivecontrol.core

/** BLE receive time is monotonic so changing the phone clock cannot revive a stale depth. */
fun pressureMonotonicMs(): Long = System.nanoTime() / 1_000_000L

enum class SensorPacketSource { Notification, Read, Simulation }

/** Last accepted packet, separate from safety state so unchanged heartbeats do not drive the camera. */
data class PressureTelemetry(
    val rawHex: String?,
    val receivedAtEpochMs: Long,
    val receivedAtMonotonicMs: Long,
    val packetCount: Long,
    val source: SensorPacketSource,
) {
    fun ageMs(nowMs: Long): Long = (nowMs - receivedAtMonotonicMs).coerceAtLeast(0L)

    fun isFresh(nowMs: Long): Boolean = nowMs - receivedAtMonotonicMs in 0 until PRESSURE_STALE_MS
}

const val PRESSURE_STALE_MS = 3_000L
const val STANDARD_SURFACE_PRESSURE_KPA = 101.325
const val FRESHWATER_KPA_PER_METER = 9.81

/** HP5834-10BA range; zero is not a usable absolute pressure in an operating dive housing. */
fun validWaterPressure(kpa: Double): Boolean = kpa.isFinite() && kpa > 0.0 && kpa <= 1000.0

/** HP203N specifies 300-1200 mbar. It measures the air inside the housing, never dive depth. */
fun validBarometricPressure(kpa: Double): Boolean = kpa.isFinite() && kpa in 30.0..120.0

/** No deadband, quantization or averaging: every accepted pressure change changes depth. */
fun pressureDepthMeters(waterKpa: Double?): Double? {
    val water = waterKpa?.takeIf(::validWaterPressure) ?: return null
    // Diveit's DiveIT BLE path constructs a default, sea-level environment. The captured
    // internal pressure belongs to vacuum monitoring and must never shift the depth zero.
    return ((water - STANDARD_SURFACE_PRESSURE_KPA) / FRESHWATER_KPA_PER_METER)
        .coerceIn(0.0, 300.0)
}
