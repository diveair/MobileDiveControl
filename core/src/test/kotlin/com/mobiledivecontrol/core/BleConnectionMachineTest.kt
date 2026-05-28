package com.mobiledivecontrol.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class BleConnectionMachineTest {
    private val machine = BleConnectionMachine()

    @Test
    fun `disconnect enters reconnecting with first retry immediate`() {
        val transition = machine.transition(
            current = BleConnectionState.Ready,
            signal = BleSignal.Disconnect,
            reconnectAttempt = 1,
        )

        assertEquals(BleConnectionState.Reconnecting, transition.state)
        assertEquals(Duration.ZERO, transition.reconnectDelay)
    }

    @Test
    fun `reconnect backoff is capped at five seconds`() {
        assertEquals(Duration.ofMillis(500), machine.reconnectDelay(2))
        assertEquals(Duration.ofSeconds(1), machine.reconnectDelay(3))
        assertEquals(Duration.ofSeconds(2), machine.reconnectDelay(4))
        assertEquals(Duration.ofSeconds(5), machine.reconnectDelay(9))
    }
}
