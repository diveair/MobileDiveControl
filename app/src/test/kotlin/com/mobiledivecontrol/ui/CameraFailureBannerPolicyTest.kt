package com.mobiledivecontrol.ui

import com.mobiledivecontrol.core.HousingSealTransitions.Banner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CameraFailureBannerPolicyTest {
    @Test fun `a failed recheck owns its verdict and the ambient pressure diagnostic stays out of the camera HUD`() {
        val pressure = "Pressure back to ambient after 277s: cover open or seal lost."
        assertNull(cameraFailureBannerMessage(pressure, Banner.B17, pressure))
        assertNull(cameraFailureBannerMessage(pressure, Banner.B15))
        val decay = "Vacuum decayed 5.1 kPa since the hold began: slow leak detected."
        assertNull(cameraFailureBannerMessage(decay, Banner.B17, decay))
        val camera = "8K camera session could not be opened."
        assertEquals(camera, cameraFailureBannerMessage(camera, Banner.B17, decay))
    }
    @Test fun `both vacuum choices temporarily own the banner area`() {
        val message = "8K camera session could not be opened."
        assertNull(cameraFailureBannerMessage(message, Banner.ConfirmRelease))
        assertNull(cameraFailureBannerMessage(message, Banner.Release))
        assertEquals(message, cameraFailureBannerMessage(message, Banner.B00))
    }
    @Test
    fun `dedicated connection permission and seal statuses are not duplicated over camera`() {
        assertNull(cameraFailureBannerMessage("Housing disconnected — reconnecting"))
        assertNull(cameraFailureBannerMessage("Pressure unstable after 4423s: leak detected."))
        assertNull(
            cameraFailureBannerMessage(
                "GATT discovery: 7 services, 16 housing characteristics resolved.",
            ),
        )
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
