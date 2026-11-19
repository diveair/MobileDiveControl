package com.mobiledivecontrol.platform.ble

import android.content.Context
import com.mobiledivecontrol.core.BleSignal

internal data class HousingStartupHistory(
    val lastSeenAtMs: Long? = null,
    val disconnectedAtMs: Long? = null,
)

/**
 * The process-wide link owns this timer, so activity recreation cannot look like a power-on.
 * A received disconnect gives the best off-time. If Android killed the process first, the last
 * live contact is the fallback: the housing protocol does not expose an uptime/power-on counter.
 */
internal class HousingStartupTracker(
    private var history: HousingStartupHistory,
    private val save: (HousingStartupHistory) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var connected = false
    private var startupPending = false

    @Synchronized
    fun onSignal(signal: BleSignal) {
        when (signal) {
            BleSignal.Ready -> if (!connected) {
                val now = nowMs()
                val lastContact = history.disconnectedAtMs ?: history.lastSeenAtMs
                // No history belongs to first-install onboarding, not a second power-on intro.
                startupPending = lastContact != null && now - lastContact > ONE_HOUR_MS
                connected = true
                persist(HousingStartupHistory(lastSeenAtMs = now))
            }
            BleSignal.Disconnect, BleSignal.HousingPoweredOff, BleSignal.Reset,
            BleSignal.Fail, BleSignal.StartScan -> if (connected) {
                connected = false
                startupPending = false
                persist(history.copy(disconnectedAtMs = nowMs()))
            }
            else -> Unit
        }
    }

    @Synchronized
    fun onLivePacket() {
        if (!connected) return // Initial GATT reads must not erase the previous off-time.
        val now = nowMs()
        val previous = history.lastSeenAtMs
        if (previous == null || now < previous || now - previous >= CHECKPOINT_INTERVAL_MS) {
            persist(HousingStartupHistory(lastSeenAtMs = now))
        }
    }

    /** Shared by a live Ready event and its subscriber snapshot; either may claim it, once. */
    @Synchronized
    fun consumeStartupRequest(): Boolean {
        val show = startupPending && connected
        startupPending = false
        return show
    }

    private fun persist(value: HousingStartupHistory) {
        history = value
        save(value)
    }

    companion object {
        const val ONE_HOUR_MS = 60 * 60 * 1_000L
        private const val CHECKPOINT_INTERVAL_MS = 60 * 1_000L

        fun create(context: Context): HousingStartupTracker {
            val preferences = context.applicationContext.getSharedPreferences("housing_startup", Context.MODE_PRIVATE)
            fun readTime(key: String) = if (preferences.contains(key)) preferences.getLong(key, 0L) else null
            return HousingStartupTracker(
                history = HousingStartupHistory(readTime("last_seen_ms"), readTime("disconnected_ms")),
                save = { value ->
                    val editor = preferences.edit()
                    value.lastSeenAtMs?.let { editor.putLong("last_seen_ms", it) } ?: editor.remove("last_seen_ms")
                    value.disconnectedAtMs?.let { editor.putLong("disconnected_ms", it) } ?: editor.remove("disconnected_ms")
                    editor.apply()
                },
            )
        }
    }
}
