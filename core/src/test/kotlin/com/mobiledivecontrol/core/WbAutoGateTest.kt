package com.mobiledivecontrol.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * White balance's AUTO GATE — the same barrier focus has at its AF rail, on BOTH ends of the
 * kelvin dial.
 *
 * Auto sits past 2300K on the warm end and past 10000K on the cold end, but it is a mode
 * change, not the next value along, so a running turn must never fall into it by momentum:
 * the wheel STOPS at the rail, and only a further press after resting
 * [ControlReducer.FOCUS_AF_PAUSE_MS] there crosses. Leaving Auto is symmetric — a press in
 * EITHER direction converts to manual at the kelvin the meter is currently reporting, and the
 * next detent steps one rung from there; [ControlReducer.AF_EXIT_GUARD_MS] of quiet is
 * required first so one sustained gesture cannot cross in and straight back out.
 */
class WbAutoGateTest {

    private val settingId = "pro.white_balance"

    /** A reducer with a hand-cranked clock, parked on the WB value editor. */
    private inner class Rig(startValue: String, meteredKelvin: Int? = null) {
        var clock = 100_000L
        val reducer = ControlReducer(nowMs = { clock })
        val ladder = CameraCatalog.whiteBalanceLadder
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
                    meteredExposure = MeteredExposure(wbKelvin = meteredKelvin),
                    // Ground the gap clock so the FIRST press's cadence is what the test says
                    // it is, not "forever since app launch".
                    lastFocusInputAtMs = clock,
                ),
            )
        }

        /** One press after [gapMs] of quiet; returns the reduction, keeps the state. */
        fun press(command: CameraCommand, gapMs: Long): Reduction {
            clock += gapMs
            val out = reducer.reduce(state, command, repeatCount = 0)
            state = out.state
            return out
        }

        fun value(): String = state.camera.settingValues[settingId]!!
    }

    /** Steady turning: below the pause threshold, above the click-blend, one rung per detent. */
    private val turningGapMs = ControlReducer.FOCUS_AF_PAUSE_MS - 200
    private val restedGapMs = ControlReducer.FOCUS_AF_PAUSE_MS + 300

    @Test
    fun `a running turn stops at 2300K and never falls into Auto`() {
        val rig = Rig(startValue = rigStart(3))
        rig.press(CameraCommand.NavigateLeft, turningGapMs)
        rig.press(CameraCommand.NavigateLeft, turningGapMs)
        assertEquals("2300K", rig.value(), "two detents from index 3 must land on the rail")
        repeat(4) {
            rig.press(CameraCommand.NavigateLeft, turningGapMs)
            assertEquals("2300K", rig.value(), "a running turn must stop at the warm rail")
        }
    }

    @Test
    fun `a rested press at 2300K crosses into Auto`() {
        val rig = Rig(startValue = "2300K")
        rig.press(CameraCommand.NavigateLeft, restedGapMs)
        // The camera hears this the way it hears every WB detent: settingValues observation.
        assertEquals("Auto", rig.value(), "rest at the rail, then one deliberate press, crosses")
    }

    @Test
    fun `the cold rail gates identically at 10000K`() {
        val rig = Rig(startValue = "10000K")
        repeat(3) {
            rig.press(CameraCommand.NavigateRight, turningGapMs)
            assertEquals("10000K", rig.value(), "a running turn must stop at the cold rail")
        }
        rig.press(CameraCommand.NavigateRight, restedGapMs)
        assertEquals("Auto", rig.value())
    }

    @Test
    fun `one sustained gesture cannot cross into Auto and straight back out`() {
        val rig = Rig(startValue = "2300K", meteredKelvin = 6_000)
        rig.press(CameraCommand.NavigateLeft, restedGapMs)
        assertEquals("Auto", rig.value())
        // Still inside the exit guard: the press is swallowed, Auto holds.
        rig.press(CameraCommand.NavigateLeft, ControlReducer.AF_EXIT_GUARD_MS - 100)
        assertEquals("Auto", rig.value(), "the same gesture must not bounce back to manual")
    }

    @Test
    fun `leaving Auto upward seeds at the metered kelvin then steps up one rung`() {
        val rig = Rig(startValue = "Auto", meteredKelvin = 6_000)
        rig.press(CameraCommand.NavigateRight, restedGapMs)
        val seeded = rig.value()
        val seededKelvin = seeded.removeSuffix("K").toInt()
        assertTrue(
            abs(seededKelvin - 6_000) <= 60,
            "leaving Auto must land on the meter's rung, got $seeded for metered 6000K",
        )
        rig.press(CameraCommand.NavigateRight, turningGapMs)
        assertEquals(
            rig.ladder[rig.ladder.indexOf(seeded) + 1],
            rig.value(),
            "the detent after the seed must step exactly one rung up",
        )
    }

    @Test
    fun `leaving Auto downward seeds at the same metered kelvin then steps down`() {
        val rig = Rig(startValue = "Auto", meteredKelvin = 6_000)
        rig.press(CameraCommand.NavigateLeft, restedGapMs)
        val seeded = rig.value()
        assertTrue(
            abs(seeded.removeSuffix("K").toInt() - 6_000) <= 60,
            "the seed must not depend on direction, got $seeded for metered 6000K",
        )
        rig.press(CameraCommand.NavigateLeft, turningGapMs)
        assertEquals(
            rig.ladder[rig.ladder.indexOf(seeded) - 1],
            rig.value(),
            "the detent after the seed must step exactly one rung down",
        )
    }

    /** Ladder index -> kelvin string, skipping "Auto" bookkeeping in the tests above. */
    private fun rigStart(index: Int): String = CameraCatalog.whiteBalanceLadder[index]
}
