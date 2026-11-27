package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi

internal enum class CameraThermalLoad { Normal, Reduced, Cooling }

/** Roughly 15 display updates per second from 30/60 fps input, allowing timestamp jitter. */
internal fun skipWarmPreviewFrame(reduced: Boolean, previousNs: Long, nowNs: Long): Boolean =
    reduced && previousNs > 0 && nowNs > previousNs && nowNs - previousNs < 63_000_000L

internal class CameraThermalPolicy {
    var load = CameraThermalLoad.Normal
        private set
    private var recoveredSince: Long? = null

    fun update(status: Int, forecast: Float, now: Long): CameraThermalLoad {
        val requested = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE || forecast >= 1f -> CameraThermalLoad.Cooling
            status >= PowerManager.THERMAL_STATUS_LIGHT || forecast >= 0.85f -> CameraThermalLoad.Reduced
            else -> CameraThermalLoad.Normal
        }
        // Resume below moderate, after a stable interval. An unsupported forecast is NaN.
        if (requested >= load || (load == CameraThermalLoad.Cooling &&
                (status >= PowerManager.THERMAL_STATUS_MODERATE || forecast >= 0.95f))) {
            if (requested > load) load = requested
            recoveredSince = null
        } else {
            val since = recoveredSince ?: now.also { recoveredSince = it }
            if (now - since >= 30_000L) {
                load = requested
                recoveredSince = null
            }
        }
        return load
    }
}

/** One monitor per attached camera. All callbacks and the recovery clock run on the main looper. */
internal class CameraThermalGuard(private val context: Context, private val main: Handler, private val changed: () -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val policy = CameraThermalPolicy()
    private var forecast = Float.NaN
    private var lastForecastAt: Long? = null
    private var closed = false
    private var status = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else legacyBatteryStatus()
    @Volatile var load = policy.update(status, forecast, SystemClock.elapsedRealtime())
        private set
    private val unregister = if (Build.VERSION.SDK_INT >= 29) listen() else null
    private val sample = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= 30 && lastForecastAt?.let { now - it < 10_000L } != true) {
                lastForecastAt = now
                forecast = power.getThermalHeadroom(10)
            }
            if (Build.VERSION.SDK_INT < 29) status = legacyBatteryStatus()
            publish()
            main.postDelayed(this, 10_000L)
        }
    }
    init { main.post(sample) }

    fun setForeground(foreground: Boolean) {
        main.removeCallbacks(sample)
        if (foreground && !closed) main.post(sample)
    }

    @RequiresApi(29)
    private fun listen(): () -> Unit {
        val listener = PowerManager.OnThermalStatusChangedListener {
            if (!closed) {
                status = it
                publish()
            }
        }
        power.addThermalStatusListener(context.mainExecutor, listener)
        return { power.removeThermalStatusListener(listener) }
    }

    private fun legacyBatteryStatus(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return when {
            tenthsC >= 420 -> PowerManager.THERMAL_STATUS_SEVERE
            tenthsC >= 405 -> PowerManager.THERMAL_STATUS_MODERATE
            tenthsC >= 390 -> PowerManager.THERMAL_STATUS_LIGHT
            else -> PowerManager.THERMAL_STATUS_NONE
        }
    }

    private fun publish() {
        val next = policy.update(status, forecast, SystemClock.elapsedRealtime())
        if (next != load) {
            load = next
            changed()
        }
    }

    fun close() {
        closed = true
        main.removeCallbacks(sample)
        unregister?.invoke()
    }
}
