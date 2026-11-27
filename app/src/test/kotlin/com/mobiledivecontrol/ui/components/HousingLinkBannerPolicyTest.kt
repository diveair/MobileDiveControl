package com.mobiledivecontrol.ui.components

import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.ControlCore
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.PressureTelemetry
import com.mobiledivecontrol.core.SensorPacketSource
import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HousingLinkBannerPolicyTest {
    @Test
    fun `one blue reconnect sequence leads to a single 1500 ms green confirmation`() {
        for (stage in listOf(BleConnectionState.Connecting, BleConnectionState.DiscoveringServices, BleConnectionState.Subscribing)) {
            val alert = requireNotNull(alertFor(stage, reconnectingSession = true))
            assertEquals("RECONNECTING TO HOUSING", alert.headline)
            assertEquals(DiveColors.OceanBlue, alert.background)
        }
        var now = 10_000L
        val core = ControlCore(monotonicMs = { now })
        core.advanceBle(BleSignal.Ready)
        assertEquals(Banner.B12, core.state.safety.sealMonitor.banner)
        assertEquals(LinkNotice.Connected, timedLinkNotice(core.state, now)?.notice)
        val green = requireNotNull(alertFor(BleConnectionState.Ready, showConnected = true))
        assertEquals("CONNECTED TO HOUSING", green.headline)
        assertEquals(DiveColors.Success, green.background)
        now += 1499
        core.advanceBle(BleSignal.Ready)
        assertEquals(LinkNotice.Connected, timedLinkNotice(core.state, now)?.notice)
        assertNull(timedLinkNotice(core.state, ++now))
        core.advanceBle(BleSignal.Disconnect)
        assertNull(core.state.housing.connectedAtMs)
        core.advanceBle(BleSignal.Ready)
        assertEquals(LinkNotice.Connected, timedLinkNotice(core.state, now)?.notice)
        val failure = core.state.safety.sealMonitor.copy(banner = Banner.B07)
        assertNull(timedLinkNotice(core.state.copy(safety = core.state.safety.copy(sealMonitor = failure)), now))
    }

    @Test
    fun `simultaneous telemetry loss does not flash depth caution before a disconnect`() {
        val sample = PressureTelemetry(null, 0L, 10_000L, 3L, SensorPacketSource.Notification)
        val base = AppState(bleConnectionState = BleConnectionState.Ready, waterPressureTelemetry = sample,
            barometricPressureTelemetry = sample)
        val state = base.copy(safety = base.safety.copy(sealMonitor = base.safety.sealMonitor.copy(banner = Banner.B12)))
        assertEquals(LinkNotice.AwaitingDisconnect, timedLinkNotice(state, 13_500)?.notice)
        assertNull(timedLinkNotice(state.copy(bleConnectionState = BleConnectionState.Reconnecting), 14_500))
        assertEquals("HOUSING NOT CONNECTED", alertFor(BleConnectionState.Reconnecting)?.headline)
        assertNull(timedLinkNotice(state, 15_500)) // both streams remain silent on a still-connected link
        assertNull(timedLinkNotice(state.copy(barometricPressureTelemetry = sample.copy(receivedAtMonotonicMs = 13_400)), 13_500))
        assertNull(timedLinkNotice(state.copy(safety = state.safety.copy(
            sealMonitor = state.safety.sealMonitor.copy(invalidWater = true))), 13_500))
    }

    @Test
    fun `generic startup failure remains an orange not-connected caution`() {
        val alert = requireNotNull(
            alertFor(BleConnectionState.Failed, housingBatteryPercent = null),
        )

        assertEquals("HOUSING NOT CONNECTED", alert.headline)
        assertEquals(DiveColors.Warning, alert.background)
    }

    @Test
    fun `red unavailable warning requires a measured empty housing battery`() {
        val alert = requireNotNull(
            alertFor(BleConnectionState.Failed, housingBatteryPercent = 0),
        )

        assertEquals("HOUSING BATTERY EMPTY", alert.headline)
        assertEquals("Charge the housing, then hold SHUTTER to turn it on", alert.action)
        assertEquals(DiveColors.Critical, alert.background)
    }
}
