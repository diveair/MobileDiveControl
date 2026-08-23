package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The white-balance housing control is one continuous ring with two explicit auto modes. */
class WbAutoGateTest {

    private val settingId = "pro.white_balance"

    private inner class Rig(startValue: String) {
        var clock = 100_000L
        private val reducer = ControlReducer(nowMs = { clock })
        var state: AppState

        init {
            val cursor = CameraCatalog.settingsBarItems(
                CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, false,
            ).indexOfFirst { it is BottomBarItem.Setting && it.spec.id == settingId }
            check(cursor >= 0) { "no bottom-bar item for $settingId" }
            val base = AppState(
                camera = CameraCatalog.launchCameraState(
                    activeMode = CameraModeId.Pro,
                    settingValues = CameraCatalog.defaultSettingValues + (settingId to startValue),
                    sliderSensitivities = CameraCatalog.defaultSliderSensitivities,
                    focusCurveModes = CameraCatalog.defaultFocusCurveModes,
                    detectedLenses = emptyList(),
                ),
            )
            state = base.copy(
                camera = base.camera.copy(
                    focusedZone = CameraUiZone.SettingsPanel,
                    settingsEditing = true,
                    sliderEditTarget = SliderEditTarget.Value,
                    settingsCursor = cursor,
                    lastFocusInputAtMs = clock,
                ),
            )
        }

        fun press(command: CameraCommand): String {
            // A deliberate one-rung press; the topology must not depend on pause timing.
            clock += ControlReducer.FOCUS_AF_PAUSE_MS + 50
            state = reducer.reduce(state, command, repeatCount = 0).state
            return state.camera.settingValues.getValue(settingId)
        }

        fun nudge(step: Int): String {
            state = reducer.reduce(state, CameraCommand.NudgeSetting(settingId, step)).state
            return state.camera.settingValues.getValue(settingId)
        }
    }

    @Test
    fun `cold end crosses continuous then shutter then warm end`() {
        val rig = Rig("10000K")
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, rig.press(CameraCommand.NavigateRight))
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, rig.press(CameraCommand.NavigateRight))
        assertEquals("2300K", rig.press(CameraCommand.NavigateRight))
    }

    @Test
    fun `warm end crosses shutter then continuous then cold end`() {
        val rig = Rig("2300K")
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, rig.press(CameraCommand.NavigateLeft))
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, rig.press(CameraCommand.NavigateLeft))
        assertEquals("10000K", rig.press(CameraCommand.NavigateLeft))
    }

    @Test
    fun `ramp nudges use the identical circular topology`() {
        val rig = Rig("10000K")
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, rig.nudge(+1))
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, rig.nudge(+1))
        assertEquals("2300K", rig.nudge(+1))
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, rig.nudge(-1))
    }
}
