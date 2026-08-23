package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gearing law for VALUE ladders — ISO, shutter, white balance, exposure.
 *
 * These settings broke when their ladders were made finer, and the reason was that they shared
 * focus's law: "a quarter turn at sensitivity 100 sweeps the WHOLE ladder". That is right for a
 * focus pull, where the rungs are just how finely a continuous range is sampled, and wrong for a
 * list of named values the diver means to LAND on, because the stride then grows with the ladder.
 *
 * Concretely, and this is the white-balance regression the field reported as "does not work at
 * all": at sensitivity 100 the old law pinned the velocity gate open (sensFloor = 1.0) and made a
 * detent worth ceil(options / 4) rungs. With 79 white-balance options that is 20 rungs — 2000 K —
 * at every turn speed, so the only kelvin values the wheel could reach from Auto were 4200 K,
 * 6200 K, 8200 K and 10000 K. The entire warm half of the scale, which is the half a diver needs,
 * could not be selected at all.
 *
 * These tests pin the replacement: one detent is one rung, and speed and sensitivity multiply on
 * top of that rather than replacing it.
 */
class ValueLadderGearingTest {

    private val valueSettings = listOf(
        "pro.iso", "pro.shutter_speed", "pro.white_balance", "pro.exposure_value",
    )

    /** Drops the cursor on [settingId] with its value editor open, at the given sensitivity. */
    private fun editing(settingId: String, level: Int): AppState {
        val cursor = CameraCatalog.settingsBarItems(
            CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, false,
        ).indexOfFirst { it is BottomBarItem.Setting && it.spec.id == settingId }
        check(cursor >= 0) { "no bottom-bar item for $settingId" }
        val state = AppState(
            camera = CameraCatalog.launchCameraState(
                activeMode = CameraModeId.Pro,
                settingValues = CameraCatalog.defaultSettingValues,
                sliderSensitivities = CameraCatalog.defaultSliderSensitivities +
                    (settingId to SliderSensitivity(level)),
                focusCurveModes = CameraCatalog.defaultFocusCurveModes,
                detectedLenses = emptyList(),
            ),
        )
        return state.copy(
            camera = state.camera.copy(
                focusedZone = CameraUiZone.SettingsPanel,
                settingsEditing = true,
                sliderEditTarget = SliderEditTarget.Value,
                settingsCursor = cursor,
            ),
        )
    }

    /**
     * Turns the wheel [detents] times at a steady [gapMs] cadence and reports the rung the diver
     * actually ARRIVES at each time — the value the reducer sets plus the distance the ramp
     * effect still owes, since the intervening rungs stream past rather than being places the
     * wheel stops.
     */
    private fun turn(
        settingId: String,
        level: Int,
        gapMs: Long,
        detents: Int = 12,
        startIndex: Int = 1,
    ): List<Int> {
        var clock = 100_000L
        val reducer = ControlReducer(nowMs = { clock })
        var state = editing(settingId, level)
        val options = CameraCatalog.selectedSetting(state.camera)!!.options
        assertEquals(settingId, CameraCatalog.selectedSetting(state.camera)?.id)
        // Seed a known rung. The defaults differ per setting (ISO starts at "100", focus at
        // "AF"), and focus's AF rail has deliberate bookkeeping of its own, so a walk that began
        // at whatever the default happened to be would measure that instead of the gearing.
        var index = startIndex
        state = state.copy(
            camera = state.camera.copy(
                settingValues = state.camera.settingValues + (settingId to options[index]),
            ),
        )

        val strides = mutableListOf<Int>()
        repeat(detents) {
            clock += gapMs
            val out = reducer.reduce(
                state.copy(camera = state.camera.copy(lastFocusInputAtMs = clock - gapMs)),
                CameraCommand.NavigateRight,
                repeatCount = 0,
            )
            val moved = out.state.camera.settingValues[settingId]!!
            // The rung the diver ARRIVES at: what the reducer set, plus the distance the ramp
            // effect still owes — the rungs in between stream past rather than being places the
            // wheel stops.
            val owed = out.effects.filterIsInstance<PlatformEffect.RampSetting>()
                .firstOrNull()?.steps ?: 0
            val next = (options.indexOf(moved).coerceAtLeast(0) + owed)
                .coerceAtMost(options.lastIndex)
            strides += next - index
            index = next
            state = out.state.copy(
                camera = out.state.camera.copy(
                    settingValues = out.state.camera.settingValues + (settingId to options[index]),
                ),
            )
        }
        return strides
    }

    /**
     * THE GUARANTEE THE FIELD ASKED FOR, in the words of the report: "ISO needs to be adjustable
     * like focus where you traverse each iso value one at a time 0 > 1 > 2 etc presently we skip
     * values."
     *
     * Unconditional on sensitivity, which is the part the old law got wrong: above 50 it stood
     * the velocity gate down, so a slow, careful turn at high sensitivity still leapt.
     */
    @Test
    fun `a slow deliberate turn moves exactly one rung, at every sensitivity`() {
        valueSettings.forEach { settingId ->
            listOf(1, 25, 50, 75, 100).forEach { level ->
                val strides = turn(settingId, level, gapMs = 400L)
                assertEquals(
                    List(strides.size) { 1 },
                    strides,
                    "$settingId at sensitivity $level skipped values on a slow turn",
                )
            }
        }
    }

    /**
     * Every rung has to be selectable, or it is decoration. This is the direct regression test
     * for the white-balance break: under the old law this walk visited four kelvin values out of
     * seventy-eight.
     */
    @Test
    fun `every rung on every value ladder is reachable by turning slowly`() {
        valueSettings.forEach { settingId ->
            val options = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
                .first { it.id == settingId }.options
            val strides = turn(settingId, level = 100, gapMs = 400L, detents = options.size - 2)
            assertEquals(
                List(strides.size) { 1 },
                strides,
                "$settingId cannot reach every rung",
            )
        }
    }

    /**
     * Sensitivity and speed must still BUY something, or the wheel becomes unusable on a long
     * ladder. They stack on top of the one-rung floor rather than replacing it.
     */
    @Test
    fun `speed and sensitivity add multiples on top of the one-rung floor`() {
        val slow = turn("pro.iso", level = 100, gapMs = 400L).first()
        val moderate = turn("pro.iso", level = 100, gapMs = 150L).first()
        val fast = turn("pro.iso", level = 100, gapMs = 80L).first()
        assertEquals(1, slow, "a slow detent is always one rung")
        assertTrue(moderate in 2..11, "a moderate detent should be a few rungs, got $moderate")
        assertEquals(
            ControlReducer.VALUE_MAX_DETENT_RUNGS.toInt(), fast,
            "a fast detent at full sensitivity is capped at a few native rungs — about a stop on ISO",
        )
        // ...and at the bottom of the dial, speed buys nothing: sensitivity 1 is a vernier.
        assertEquals(1, turn("pro.iso", level = 1, gapMs = 80L).first())
    }

    /**
     * THE STRIDE MUST NOT DEPEND ON LADDER LENGTH. This is the property whose absence made every
     * one of these settings worse as it was made finer — the bug behind the whole redesign.
     */
    @Test
    fun `the same gesture moves the same number of rungs on every value ladder`() {
        listOf(400L, 150L, 80L).forEach { gap ->
            val strides = valueSettings.associateWith { turn(it, level = 100, gapMs = gap).first() }
            assertEquals(
                1, strides.values.distinct().size,
                "stride varies with ladder length at ${gap}ms: $strides",
            )
        }
    }

    /**
     * OFF LIMITS: focus keeps the law the diver signed off on. Sensitivity 100 sweeps the whole
     * scale in a quarter turn however slowly the hand moves — the exact behaviour the value
     * ladders now reject — so this fails if the two laws are ever merged back together.
     */
    @Test
    fun `focus is untouched and still sweeps at full sensitivity on a slow turn`() {
        val focus = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.manual_focus" }.options
        // Started mid-scale, clear of the AF rail's own deliberate bookkeeping.
        val perDetent = turn(
            "pro.manual_focus", level = 100, gapMs = 400L, detents = 1, startIndex = 1,
        ).first()
        val expected = kotlin.math.ceil((focus.size - 1) / ControlReducer.QUARTER_TURN_DETENTS).toInt()
        assertEquals(
            expected, perDetent,
            "focus must still spend the whole ladder in a quarter turn at sensitivity 100",
        )
        assertTrue(perDetent > 40, "focus stride collapsed to the value-ladder law")

        // And its fine end is still a vernier.
        assertEquals(
            1,
            turn("pro.manual_focus", level = 1, gapMs = 400L, detents = 1, startIndex = 1).first(),
        )
    }
}
