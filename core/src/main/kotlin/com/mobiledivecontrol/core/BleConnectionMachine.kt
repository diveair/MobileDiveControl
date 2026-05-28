package com.mobiledivecontrol.core

import java.time.Duration

sealed interface BleSignal {
    data object StartScan : BleSignal
    data object Connect : BleSignal
    data object DiscoverServices : BleSignal
    data object Subscribe : BleSignal
    data object Ready : BleSignal
    data object Disconnect : BleSignal

    /**
     * Housing powered off via hardware (OK long press per vendor spec §5.3 Table 7).
     * No BLE notification is sent — the housing firmware simply shuts down,
     * causing BLE disconnect. The app should transition to Idle (not Reconnecting)
     * because there is no device to reconnect to.
     */
    data object HousingPoweredOff : BleSignal

    data object Degrade : BleSignal
    data object Fail : BleSignal
    data object Reset : BleSignal
}

data class BleTransition(
    val state: BleConnectionState,
    val reconnectDelay: Duration? = null,
)

class BleConnectionMachine {
    fun transition(
        current: BleConnectionState,
        signal: BleSignal,
        reconnectAttempt: Int = 0,
    ): BleTransition = when (signal) {
        BleSignal.StartScan -> BleTransition(BleConnectionState.Scanning)
        BleSignal.Connect -> BleTransition(BleConnectionState.Connecting)
        BleSignal.DiscoverServices -> BleTransition(BleConnectionState.DiscoveringServices)
        BleSignal.Subscribe -> BleTransition(BleConnectionState.Subscribing)
        BleSignal.Ready -> BleTransition(BleConnectionState.Ready)
        BleSignal.Degrade -> BleTransition(BleConnectionState.Degraded)
        BleSignal.Disconnect -> {
            if (current == BleConnectionState.Idle) {
                BleTransition(BleConnectionState.Idle)
            } else {
                BleTransition(
                    state = BleConnectionState.Reconnecting,
                    reconnectDelay = reconnectDelay(reconnectAttempt),
                )
            }
        }
        // Housing powered off (OK long press) — go to Idle, not Reconnecting
        BleSignal.HousingPoweredOff -> BleTransition(BleConnectionState.Idle)
        BleSignal.Fail -> BleTransition(BleConnectionState.Failed)
        BleSignal.Reset -> BleTransition(BleConnectionState.Idle)
    }

    fun reconnectDelay(attempt: Int): Duration = when {
        attempt <= 1 -> Duration.ZERO
        attempt == 2 -> Duration.ofMillis(500)
        attempt == 3 -> Duration.ofSeconds(1)
        attempt == 4 -> Duration.ofSeconds(2)
        else -> Duration.ofSeconds(5)
    }
}
