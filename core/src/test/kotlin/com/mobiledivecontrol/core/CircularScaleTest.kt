package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CircularScaleTest {
    private val reducer = ControlReducer()

    private fun nudge(settingId: String, value: String, step: Int): String {
        val camera = CameraCatalog.launchCameraState(
            activeMode = CameraModeId.Pro,
            settingValues = CameraCatalog.defaultSettingValues + (settingId to value),
        )
        return reducer.reduce(
            AppState(camera = camera),
            CameraCommand.NudgeSetting(settingId, step),
        ).state.camera.settingValues.getValue(settingId)
    }

    @Test
    fun `iso reaches the same Auto mode from both rails`() {
        assertEquals("Auto", nudge("pro.iso", "50", -1))
        assertEquals("Auto", nudge("pro.iso", "3200", +1))
        assertEquals("3200", nudge("pro.iso", "Auto", -1))
        assertEquals("50", nudge("pro.iso", "Auto", +1))
    }

    @Test
    fun `shutter reaches the same Auto mode from both rails`() {
        assertEquals("Auto", nudge("pro.shutter_speed", "1/24000", -1))
        assertEquals("Auto", nudge("pro.shutter_speed", "30\"", +1))
        assertEquals("30\"", nudge("pro.shutter_speed", "Auto", -1))
        assertEquals("1/24000", nudge("pro.shutter_speed", "Auto", +1))
    }

    @Test
    fun `EV wraps directly because its native dial has no Auto mode`() {
        assertEquals("+4.0", nudge("pro.exposure_value", "-4.0", -1))
        assertEquals("-4.0", nudge("pro.exposure_value", "+4.0", +1))
    }
}
