package com.mobiledivecontrol.ui.components

import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.theme.DiveColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HousingLinkBannerPolicyTest {
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

        assertEquals("HOUSING UNAVAILABLE", alert.headline)
        assertEquals(DiveColors.Critical, alert.background)
    }
}
