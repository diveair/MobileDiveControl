package com.mobiledivecontrol.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CameraFailureBannerPolicyTest {
    @Test
    fun `dedicated connection permission and seal statuses are not duplicated over camera`() {
        assertNull(cameraFailureBannerMessage("Housing disconnected — reconnecting"))
        assertNull(cameraFailureBannerMessage("Bluetooth Permission: Disabled"))
        assertNull(cameraFailureBannerMessage("Accessibility Permission: Disabled"))
        assertNull(
            cameraFailureBannerMessage(
                "Verified vacuum held across restart (-16.8 kPa). Trust restored at Conservative, 2516 min held.",
            ),
        )
    }

    @Test
    fun `real camera failures retain the amber banner`() {
        assertEquals(
            "8K camera session could not be opened.",
            cameraFailureBannerMessage("8K camera session could not be opened."),
        )
    }
}
