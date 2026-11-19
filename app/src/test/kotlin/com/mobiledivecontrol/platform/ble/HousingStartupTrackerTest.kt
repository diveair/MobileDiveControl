package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.BleSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HousingStartupTrackerTest {
    private val hour = HousingStartupTracker.ONE_HOUR_MS
    private var now = 1_000_000_000L
    private var saved = HousingStartupHistory()
    private var writes = 0
    private fun tracker() = HousingStartupTracker(saved, { saved = it; writes++ }, { now })

    @Test
    fun `first connection leaves initial onboarding in charge`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
        assertEquals(now, saved.lastSeenAtMs)
    }

    @Test
    fun `power on after more than one hour requests startup once`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        link.onSignal(BleSignal.HousingPoweredOff)
        now += hour + 1
        link.onSignal(BleSignal.Ready)
        assertTrue(link.consumeStartupRequest())
        assertFalse(link.consumeStartupRequest())
        link.onSignal(BleSignal.Ready) // A subscriber snapshot is not another power-on.
        assertFalse(link.consumeStartupRequest())
    }

    @Test
    fun `exactly one hour and shorter power cycles skip startup`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        for (gap in listOf(500L, 30 * 60_000L, hour)) {
            link.onSignal(BleSignal.HousingPoweredOff)
            now += gap
            link.onSignal(BleSignal.Ready)
            assertFalse(link.consumeStartupRequest())
        }
    }

    @Test
    fun `hours continuously connected and a short dropout do not trigger startup`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        repeat(180) { now += 60_000; link.onLivePacket() }
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
        link.onSignal(BleSignal.Disconnect)
        now += 10_000
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
    }

    @Test
    fun `off timer survives app restart and failed reconnect attempts`() {
        val oldLink = tracker()
        oldLink.onSignal(BleSignal.Ready)
        oldLink.onSignal(BleSignal.HousingPoweredOff)
        val offTime = now
        now += hour / 2
        val newLink = tracker()
        newLink.onSignal(BleSignal.StartScan)
        newLink.onSignal(BleSignal.Fail)
        newLink.onSignal(BleSignal.Disconnect)
        assertEquals(offTime, saved.disconnectedAtMs)
        assertFalse(newLink.consumeStartupRequest())
        now += hour / 2 + 1
        newLink.onSignal(BleSignal.Ready)
        assertTrue(newLink.consumeStartupRequest())
    }

    @Test
    fun `last live contact is fallback when process ended before disconnect`() {
        val oldLink = tracker()
        oldLink.onSignal(BleSignal.Ready)
        now += hour * 2
        oldLink.onLivePacket()
        now += 5_000
        val quickRestart = tracker()
        quickRestart.onSignal(BleSignal.Ready)
        assertFalse(quickRestart.consumeStartupRequest())
        now += hour + 1
        val laterRestart = tracker()
        laterRestart.onLivePacket() // Priming reads precede Ready and cannot erase history.
        laterRestart.onSignal(BleSignal.Ready)
        assertTrue(laterRestart.consumeStartupRequest())
    }

    @Test
    fun `new off period is measured from disconnect not last animation`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        link.onSignal(BleSignal.HousingPoweredOff)
        now += hour + 1
        link.onSignal(BleSignal.Ready)
        assertTrue(link.consumeStartupRequest())
        now += hour * 4
        link.onSignal(BleSignal.HousingPoweredOff)
        now += 1_000
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
    }

    @Test
    fun `disconnection cancels unclaimed startup for late subscribers`() {
        saved = HousingStartupHistory(lastSeenAtMs = now - hour - 1)
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        link.onSignal(BleSignal.HousingPoweredOff)
        assertFalse(link.consumeStartupRequest())
        now += 1_000
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
    }

    @Test
    fun `live telemetry checkpoints are throttled and cannot request startup`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        repeat(299) { now += 200; link.onLivePacket() }
        assertEquals(1, writes)
        now += 200
        link.onLivePacket()
        assertEquals(2, writes)
        assertFalse(link.consumeStartupRequest())
    }

    @Test
    fun `clock going backwards does not create a long off interval`() {
        val link = tracker()
        link.onSignal(BleSignal.Ready)
        link.onSignal(BleSignal.HousingPoweredOff)
        now -= hour * 2
        link.onSignal(BleSignal.Ready)
        assertFalse(link.consumeStartupRequest())
        assertEquals(now, saved.lastSeenAtMs)
    }
}
