package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusValueRegressionTest {
    @Test fun `manual focus preserves continuous ramp values in every focus mode`() {
        for (mode in listOf(CameraModeId.Photo, CameraModeId.Pro, CameraModeId.ExpertRaw,
            CameraModeId.ProVideo, CameraModeId.PortraitVideo, CameraModeId.Hyperlapse)) {
            val spec = CameraCatalog.settingsFor(mode, GalaxyDeviceVariant.entries.first())
                .first { it.id.endsWith(".manual_focus") }
            for (value in listOf("0.1234", "0.0", "1.0", "0.9951")) {
                val camera = CameraState(activeMode = mode, settingValues = CameraCatalog.defaultSettingValues +
                    (spec.id to value) + (spec.id.substringBeforeLast('.') + ".lens" to "1x"))
                assertEquals(value, CameraCatalog.currentValue(camera, spec), "$mode $value")
            }
        }
    }
    @Test fun `fixed hyperlapse lens never becomes manual focus`() {
        val spec = CameraCatalog.settingsFor(CameraModeId.Hyperlapse, GalaxyDeviceVariant.entries.first())
            .first { it.id.endsWith(".manual_focus") }
        val camera = CameraState(settingValues = CameraCatalog.defaultSettingValues +
            ("hyperlapse.lens" to "0.6x") + (spec.id to "0.1234"))
        assertEquals("Fixed", CameraCatalog.currentValue(camera, spec))
    }
}
