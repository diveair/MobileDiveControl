package com.mobiledivecontrol.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone battery level, for the HUD's second battery readout.
 *
 * The diver seals the phone in the housing and cannot look at the Android status bar afterwards,
 * so the phone's own charge has to be on the dive HUD. Getting it wrong is expensive in both
 * directions: a stale number lets someone start a dive on a phone that dies at depth, and a
 * polling loop to keep it fresh burns power and heat on a device that is already thermally
 * marginal with the camera running for a whole dive.
 *
 * [Intent.ACTION_BATTERY_CHANGED] solves both. It is a sticky broadcast, so
 * [ContextCompat.registerReceiver] returns the last known value immediately — the first read costs
 * nothing and happens before the first frame. After that the system pushes an update only when the
 * level actually moves. No polling, no `WorkManager`, no alarms, no wakelocks.
 */
class PhoneBatteryMonitor(private val context: Context) {

    private val _percent = MutableStateFlow<Int?>(null)

    /**
     * 0–100, or null while the level is genuinely unknown.
     *
     * Null is not zero. A HUD that renders an unread battery as a critical red 0% teaches the
     * diver to ignore a red battery, and then the real one goes unnoticed.
     */
    val percent: StateFlow<Int?> = _percent.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /**
     * Starts listening. Safe to call repeatedly; the first sticky value lands synchronously.
     */
    fun start() {
        if (receiver != null) return

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { publish(it) }
            }
        }
        receiver = batteryReceiver

        // NOT_EXPORTED because nothing outside the app may spoof a battery level into the HUD.
        // ACTION_BATTERY_CHANGED is a protected system broadcast, so this is belt and braces, but
        // targetSdk 35 makes the flag mandatory for anything that is not.
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Could not register battery receiver; phone battery stays unknown", error)
            receiver = null
            null
        }

        // registerReceiver returns the sticky intent for ACTION_BATTERY_CHANGED, which is why this
        // reports a level on the very first composition rather than at the next system broadcast.
        sticky?.let { publish(it) }
    }

    /** Releases the receiver. Called from the view model's `onCleared`. */
    fun stop() {
        val current = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(current) }
            .onFailure { error -> Log.w(TAG, "Battery receiver already unregistered", error) }
    }

    /**
     * Converts the raw level/scale pair to a percentage.
     *
     * Scale is not always 100 — some OEM builds report 1000 — so the division is not optional.
     * A malformed extra leaves the previous value alone rather than publishing a wrong number.
     */
    private fun publish(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return

        _percent.value = (level * 100 / scale).coerceIn(0, 100)
    }

    private companion object {
        const val TAG = "PhoneBatteryMonitor"
    }
}
