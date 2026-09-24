package com.mobiledivecontrol.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mobiledivecontrol.core.PRESSURE_STALE_MS
import com.mobiledivecontrol.core.PressureTelemetry
import com.mobiledivecontrol.core.SensorPacketSource
import com.mobiledivecontrol.core.pressureMonotonicMs
import kotlinx.coroutines.delay

/** One expiry timer per accepted packet, no periodic UI refresh or smoothing of pressure changes. */
@Composable
fun rememberLivePressure(kpa: Double?, telemetry: PressureTelemetry?, connected: Boolean): Double? {
    val available = connected || telemetry?.source == SensorPacketSource.Simulation
    var fresh by remember(telemetry, available) {
        mutableStateOf(available && telemetry?.isFresh(pressureMonotonicMs()) == true)
    }
    LaunchedEffect(telemetry, available) {
        if (available && telemetry != null && fresh) {
            delay((PRESSURE_STALE_MS - telemetry.ageMs(pressureMonotonicMs())).coerceAtLeast(1L))
            fresh = false
        }
    }
    return kpa.takeIf { fresh }
}
