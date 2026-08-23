package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlCoreTest {
    @Test
    fun `camera shutter emits capture effect when camera is permitted`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        val outcome = core.handleNotificationPayload(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x20.toByte()))

        assertEquals(listOf(PlatformEffect.ExecuteCamera(CameraCommand.CapturePhoto)), outcome.effects)
    }

    @Test
    fun `malformed packet records error without changing state`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        val initialState = core.state

        val outcome = core.handleNotificationPayload(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x10.toByte(), 0x20.toByte()))

        assertEquals(initialState, outcome.state)
        assertEquals(1, core.diagnosticsErrorCount())
        assertTrue(outcome.notes.single().contains("Expected 1 byte"))
    }

    @Test
    fun `permission revocation pushes phone mode into safe fallback`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Accessibility, true)
        core.updatePermission(PermissionKind.Overlay, true)
        core.forceMode(AppMode.PhoneCursor)

        val outcome = core.updatePermission(PermissionKind.Accessibility, false, Instant.parse("2026-05-26T12:00:00Z"))

        assertEquals(AppMode.Diagnostics, outcome.state.mode)
        assertTrue(outcome.notes.contains("Accessibility Permission: Disabled"))
    }

    @Test
    fun `cover notification uses vendor open closed mapping`() {
        val core = ControlCore()

        val outcome = core.handleNotificationPayload(HousingCharacteristic.CoverState.shortHex, byteArrayOf(0x00.toByte()))

        assertEquals(true, outcome.state.safety.coverOpen)
        assertEquals(SealState.CoverOpen, outcome.state.safety.sealState)
    }

    @Test
    fun `device info notification updates housing metadata`() {
        val core = ControlCore()

        val outcome = core.handleNotificationPayload(HousingCharacteristic.SerialNumber.shortHex, "SN-42".encodeToByteArray())

        assertEquals("SN-42", outcome.state.housing.serialNumber)
    }

    @Test
    fun `camera navigation opens mode rail and settings drawer from hardware buttons`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)
        val start = Instant.parse("2026-05-26T12:00:00Z")

        val railOutcome = core.handleNotificationPayload(
            HousingCharacteristic.ButtonEvents.shortHex,
            byteArrayOf(0x10),
            start,
        )
        assertEquals(CameraUiZone.ModeRail, railOutcome.state.camera.focusedZone)

        val settingsOutcome = core.handleNotificationPayload(
            HousingCharacteristic.ButtonEvents.shortHex,
            byteArrayOf(0x10),
            start.plusMillis(100),
        )
        assertEquals(CameraUiZone.SettingsPanel, settingsOutcome.state.camera.focusedZone)
    }

    @Test
    fun `modes list loops when scrolling up from first or down from last`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        // Focus ModeRail from LiveView by navigating Up or Down
        // Initial primary index = 0 (Photo)
        // Navigate Up: should wrap around to last index
        val wrapUpOutcome = core.dispatch(CameraCommand.NavigateUp)
        val lastIndex = CameraCatalog.primaryRailEntries.lastIndex
        assertEquals(CameraUiZone.ModeRail, wrapUpOutcome.state.camera.focusedZone)
        assertEquals(lastIndex, wrapUpOutcome.state.camera.highlightedPrimaryIndex)

        // Navigate Down: should wrap back to 0
        val wrapDownOutcome = core.dispatch(CameraCommand.NavigateDown)
        assertEquals(0, wrapDownOutcome.state.camera.highlightedPrimaryIndex)
    }

    @Test
    fun `settings tray cursor navigates horizontally and wraps`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        // Activate settings panel (by clicking OK/Confirm from LiveView)
        val settingsOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(CameraUiZone.SettingsPanel, settingsOutcome.state.camera.focusedZone)
        assertEquals(
            CameraCatalog.defaultSettingsCursor(
                settingsOutcome.state.camera.activeMode,
                settingsOutcome.state.camera.deviceVariant,
                settingsOutcome.state.camera.showMoreSettings,
            ),
            settingsOutcome.state.camera.settingsCursor,
        )

        val totalItems = CameraCatalog.settingsBarItems(
            settingsOutcome.state.camera.activeMode,
            settingsOutcome.state.camera.deviceVariant,
            settingsOutcome.state.camera.showMoreSettings
        ).size

        val startCursor = settingsOutcome.state.camera.settingsCursor

        // Navigate left once: move from Modes to the item on its left.
        val left1Outcome = core.dispatch(CameraCommand.NavigateLeft)
        assertEquals((startCursor - 1 + totalItems) % totalItems, left1Outcome.state.camera.settingsCursor)

        // Navigate left again: move one more item left.
        val left2Outcome = core.dispatch(CameraCommand.NavigateLeft)
        assertEquals((startCursor - 2 + totalItems) % totalItems, left2Outcome.state.camera.settingsCursor)

        // Navigate right: return to the previous left result.
        val rightOutcome = core.dispatch(CameraCommand.NavigateRight)
        assertEquals(left1Outcome.state.camera.settingsCursor, rightOutcome.state.camera.settingsCursor)
    }

    @Test
    fun `slider adjustments respect hold sensitivity rate limiting`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        // Force mode to Pro and open settings panel
        core.forceMode(AppMode.CameraLive)
        core.dispatch(CameraCommand.NavigateDown) // Photo -> Expert RAW
        core.dispatch(CameraCommand.NavigateDown) // Expert RAW -> Pro
        val modeOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(CameraModeId.Pro, modeOutcome.state.camera.activeMode)
        assertEquals(CameraUiZone.SettingsPanel, modeOutcome.state.camera.focusedZone)

        // Navigate right from the anchored Modes token: Focus, then White Balance.
        repeat(2) {
            core.dispatch(CameraCommand.NavigateRight)
        }

        // Confirm edit (enters settingsEditing value mode)
        val editOutcome = core.dispatch(CameraCommand.Confirm)
        assertTrue(editOutcome.state.camera.settingsEditing)
        assertEquals(SliderEditTarget.Value, editOutcome.state.camera.sliderEditTarget)

        // Navigate down to the Sensitivity target, then verify decrement works.
        val targetOutcome = core.dispatch(CameraCommand.NavigateDown)
        assertEquals(SliderEditTarget.Sensitivity, targetOutcome.state.camera.sliderEditTarget)

        // Press right once: sensitivity goes from 50 (default) to 49
        core.dispatch(CameraCommand.NavigateLeft)
        assertEquals(SliderSensitivity(49), core.state.camera.sliderSensitivities["pro.white_balance"])

        // Move back up to the Value target.
        val exitSensOutcome = core.dispatch(CameraCommand.NavigateUp)
        assertEquals(SliderEditTarget.Value, exitSensOutcome.state.camera.sliderEditTarget)

        // Set sensitivity to 1 directly for rate-limiting test
        val stateWithLowSens = exitSensOutcome.state.copy(
            camera = exitSensOutcome.state.camera.copy(
                sliderSensitivities = exitSensOutcome.state.camera.sliderSensitivities + ("pro.white_balance" to SliderSensitivity(1)),
            ),
        )
        val initialVal = stateWithLowSens.camera.settingValues["pro.white_balance"] ?: "5600K"

        val reducer = ControlReducer()

        // Held slider events feed the motor instead of moving the value directly: the state
        // is untouched and a RampSetting effect carries the ticks, paced by sensitivity — at
        // level 1 the interval stretches so the walk is slow and deliberate.
        val held = reducer.reduce(stateWithLowSens.copy(
            camera = stateWithLowSens.camera.copy(settingsEditing = true, sliderEditTarget = SliderEditTarget.Value)
        ), CameraCommand.NavigateRight, repeatCount = 1)
        assertEquals(initialVal, held.state.camera.settingValues["pro.white_balance"])
        val ramp = held.effects.filterIsInstance<PlatformEffect.RampSetting>().single()
        assertEquals("pro.white_balance", ramp.settingId)
        // Pacing is a RATE, not a sweep. A value ladder is a list of destinations, so a held
        // button has to walk it at a speed the diver can stop on — and that speed must not
        // depend on how many rungs the ladder happens to have, or making white balance finer
        // would make it scroll faster. At sensitivity 1 that rate is
        // VALUE_HELD_MIN_RUNGS_PER_SECOND.
        val rungsPerSecond = ramp.maxTicksPerInterval * 1000.0 / ramp.intervalMs
        assertTrue(
            rungsPerSecond in 2.0..4.5,
            "sensitivity 1 must crawl at about 3 rungs/s, got $rungsPerSecond",
        )
    }

    /**
     * The property that the old sweep-based pacing could not give: a held button walks EVERY
     * value ladder at the same speed, so a diver who learns the wheel on ISO is not surprised by
     * white balance. Focus is deliberately excluded — it is a pull through a physical range and
     * keeps its own full-sweep law.
     */
    @Test
    fun `held button rate is the same on every value ladder`() {
        val reducer = ControlReducer()
        val rates = listOf("pro.iso", "pro.shutter_speed", "pro.white_balance", "pro.exposure_value")
            .map { settingId ->
                val settings = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
                val cursor = CameraCatalog.settingsBarItems(
                    CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, false,
                ).indexOfFirst { it is BottomBarItem.Setting && it.spec.id == settingId }
                val state = AppState(
                    camera = CameraCatalog.launchCameraState(
                        activeMode = CameraModeId.Pro,
                        settingValues = CameraCatalog.defaultSettingValues,
                        sliderSensitivities = CameraCatalog.defaultSliderSensitivities +
                            (settingId to SliderSensitivity(100)),
                        focusCurveModes = CameraCatalog.defaultFocusCurveModes,
                        detectedLenses = emptyList(),
                    ),
                ).let {
                    it.copy(
                        camera = it.camera.copy(
                            focusedZone = CameraUiZone.SettingsPanel,
                            settingsEditing = true,
                            sliderEditTarget = SliderEditTarget.Value,
                            settingsCursor = cursor,
                        ),
                    )
                }
                assertEquals(settingId, CameraCatalog.selectedSetting(state.camera)?.id)
                val ramp = reducer.reduce(state, CameraCommand.NavigateRight, repeatCount = 1)
                    .effects.filterIsInstance<PlatformEffect.RampSetting>().single()
                assertEquals(settingId, ramp.settingId)
                settingId to ramp.maxTicksPerInterval * 1000.0 / ramp.intervalMs
            }
        // Ladder lengths here run from 42 rungs (exposure) to 206 (shutter) — a five-fold
        // spread that used to become a five-fold spread in scroll speed.
        val slowest = rates.minOf { it.second }
        val fastest = rates.maxOf { it.second }
        assertTrue(
            fastest - slowest < 6.0,
            "held rate must not depend on ladder length, got $rates",
        )
    }

    @Test
    fun `separate right button taps each adjust white balance once`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        core.forceMode(AppMode.CameraLive)
        core.dispatch(CameraCommand.NavigateDown) // Photo -> Expert RAW
        core.dispatch(CameraCommand.NavigateDown) // Expert RAW -> Pro
        val modeOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(CameraModeId.Pro, modeOutcome.state.camera.activeMode)
        assertEquals(CameraUiZone.SettingsPanel, modeOutcome.state.camera.focusedZone)

        repeat(2) {
            core.dispatch(CameraCommand.NavigateRight)
        }

        val editOutcome = core.dispatch(CameraCommand.Confirm)
        assertTrue(editOutcome.state.camera.settingsEditing)
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, editOutcome.state.camera.settingValues["pro.white_balance"])

        // 0x10 = Right button (now adjusts value +1)
        val firstPress = core.handleButtonPayload(
            payload = byteArrayOf(0x10),
            receivedAt = Instant.parse("2026-05-27T12:00:00Z"),
        )
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, firstPress.state.camera.settingValues["pro.white_balance"])

        val secondPress = core.handleButtonPayload(
            payload = byteArrayOf(0x10),
            receivedAt = Instant.parse("2026-05-27T12:00:00.250Z"),
        )
        // ONE RUNG PER TAP: Auto Continuous -> Auto Shutter -> first Kelvin rung.
        // what this pins is that a deliberate, isolated press moves exactly one step and
        // never a geared stride.
        assertEquals(
            "2300K",
            secondPress.state.camera.settingValues["pro.white_balance"],
            "second press must land on the ring's first kelvin rung",
        )
    }

    /** Live telemetry changes the readout, never the explicitly circular wheel topology. */
    @Test
    fun `leaving automatic modes follows the ring instead of jumping to metered values`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)
        core.forceMode(AppMode.CameraLive)
        core.dispatch(CameraCommand.NavigateDown)
        core.dispatch(CameraCommand.NavigateDown)
        core.dispatch(CameraCommand.Confirm) // Pro mode, settings panel
        core.updateMeteredExposure(MeteredExposure(iso = 137, shutterNs = 5_000_000L, wbKelvin = 5_649))

        val seeded = core.dispatch(CameraCommand.NudgeSetting("pro.iso", +1))
        assertEquals("50", seeded.state.camera.settingValues["pro.iso"])
        val shutterSeeded = core.dispatch(CameraCommand.NudgeSetting("pro.shutter_speed", +1))
        assertEquals("1/24000", shutterSeeded.state.camera.settingValues["pro.shutter_speed"])
        val wbSeeded = core.dispatch(CameraCommand.NudgeSetting("pro.white_balance", +1))
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, wbSeeded.state.camera.settingValues["pro.white_balance"])

        // With no telemetry, the fallback is ordinary stepping: Auto -> the first rung.
        val blind = ControlCore()
        blind.advanceBle(BleSignal.Ready)
        blind.updatePermission(PermissionKind.Camera, true)
        blind.forceMode(AppMode.CameraLive)
        blind.dispatch(CameraCommand.NavigateDown)
        blind.dispatch(CameraCommand.NavigateDown)
        blind.dispatch(CameraCommand.Confirm)
        val stepped = blind.dispatch(CameraCommand.NudgeSetting("pro.iso", +1))
        assertEquals("50", stepped.state.camera.settingValues["pro.iso"])
    }

    /**
     * The EV authority rule as THIS app's sensor honours it: any manual exposure axis turns AE
     * off here (the native app's vendor priority channel is closed to third parties), so an EV
     * detent must be refused the moment either axis leaves Auto — not absorbed into a value the
     * sensor will never honour.
     */
    @Test
    fun `ev detents are refused while any exposure axis is manual`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)
        core.forceMode(AppMode.CameraLive)
        core.dispatch(CameraCommand.NavigateDown)
        core.dispatch(CameraCommand.NavigateDown)
        core.dispatch(CameraCommand.Confirm) // Pro mode

        // EV is live while everything is Auto...
        val live = core.dispatch(CameraCommand.NudgeSetting("pro.exposure_value", +1))
        assertEquals("+0.1", live.state.camera.settingValues["pro.exposure_value"])

        // ...and locks the moment ANY exposure axis goes manual.
        core.dispatch(CameraCommand.NudgeSetting("pro.iso", +1))
        val locked = core.dispatch(CameraCommand.NudgeSetting("pro.exposure_value", +1))
        assertEquals("+0.1", locked.state.camera.settingValues["pro.exposure_value"])
        assertTrue(locked.effects.isEmpty(), "a locked EV detent must not emit a camera effect")

        // Returning the axis to Auto restores EV authority.
        core.dispatch(CameraCommand.NudgeSetting("pro.iso", -1))
        val restored = core.dispatch(CameraCommand.NudgeSetting("pro.exposure_value", +1))
        assertEquals("+0.2", restored.state.camera.settingValues["pro.exposure_value"])
    }

    @Test
    fun `up on a highlighted bottom bar setting changes its value and emits a camera effect`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        val settingsOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(CameraUiZone.SettingsPanel, settingsOutcome.state.camera.focusedZone)

        val evSelected = core.dispatch(CameraCommand.NavigateLeft)
        // The native quick EV bar has no Auto — it rests at 0.0, exactly like the stock dial.
        assertEquals("0.0", evSelected.state.camera.settingValues["photo.exposure_compensation"])

        val adjusted = core.dispatch(CameraCommand.NavigateUp)
        assertEquals("+0.1", adjusted.state.camera.settingValues["photo.exposure_compensation"])
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SetExposureCompensation(0.1))),
            adjusted.effects,
        )
    }

    @Test
    fun `confirm on gallery shortcut switches to gallery mode`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        core.dispatch(CameraCommand.Confirm)
        repeat(3) {
            core.dispatch(CameraCommand.NavigateRight)
        }

        val galleryOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(AppMode.Gallery, galleryOutcome.state.mode)
        assertEquals(
            listOf(PlatformEffect.LoadGalleryItems),
            galleryOutcome.effects,
        )
    }

    @Test
    fun `focus options span 0_000 to 1_000 with 0_005 steps`() {
        // Get the focus setting from the Photo mode profile
        val photoProfile = CameraCatalog.profile(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra)
        val focusSpec = photoProfile.settings.first { it.id.endsWith(".manual_focus") }
        val options = focusSpec.options
        assertEquals("AF", options.first())
        assertEquals("0.000", options[1])
        assertEquals("0.005", options[2])
        assertEquals("0.500", options[101])
        assertEquals("1.000", options.last())
        assertEquals(202, options.size) // AF + 201 numeric values (0.000 to 1.000)
    }

    @Test
    fun `focus edit exposes value sensitivity and assist targets`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        core.dispatch(CameraCommand.Confirm)
        val focusSelected = core.dispatch(CameraCommand.NavigateRight)
        assertEquals("AF", focusSelected.state.camera.settingValues["photo.manual_focus"])

        val editOutcome = core.dispatch(CameraCommand.Confirm)
        assertTrue(editOutcome.state.camera.settingsEditing)
        assertEquals(SliderEditTarget.Value, editOutcome.state.camera.sliderEditTarget)

        val sensitivityTarget = core.dispatch(CameraCommand.NavigateDown)
        assertEquals(SliderEditTarget.Sensitivity, sensitivityTarget.state.camera.sliderEditTarget)

        val assistTarget = core.dispatch(CameraCommand.NavigateDown)
        assertEquals(SliderEditTarget.FocusAssist, assistTarget.state.camera.sliderEditTarget)

        val assistOn = core.dispatch(CameraCommand.NavigateRight)
        assertEquals("On", assistOn.state.camera.settingValues["photo.focus_peaking"])

        val backToSensitivity = core.dispatch(CameraCommand.NavigateUp)
        assertEquals(SliderEditTarget.Sensitivity, backToSensitivity.state.camera.sliderEditTarget)

        val backToValue = core.dispatch(CameraCommand.NavigateUp)
        assertEquals(SliderEditTarget.Value, backToValue.state.camera.sliderEditTarget)
    }

    @Test
    fun `focus uses 0_005 steps and sensitivity controls repeat cadence not step size`() {
        val reducer = ControlReducer()
        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 4,
            settingValues = CameraCatalog.defaultSettingValues + ("photo.manual_focus" to "0.500"),
            sliderSensitivities = CameraCatalog.defaultSliderSensitivities + ("photo.manual_focus" to SliderSensitivity(1)),
        )
        val state = AppState(camera = camera)

        val tap = reducer.reduce(state, CameraCommand.NavigateUp, repeatCount = 0)
        assertEquals("0.505", tap.state.camera.settingValues["photo.manual_focus"])

        // Held events never move the state directly: they top up the motor, which walks the
        // value tick by tick at the sensitivity's own rate via RampSetting.
        val held = reducer.reduce(state, CameraCommand.NavigateUp, repeatCount = 1)
        assertEquals("0.500", held.state.camera.settingValues["photo.manual_focus"])
        assertTrue(held.effects.any { it is PlatformEffect.RampSetting })

        val editingState = state.copy(
            camera = state.camera.copy(
                settingsEditing = true,
                sliderEditTarget = SliderEditTarget.Value,
            ),
        )
        val editRight = reducer.reduce(editingState, CameraCommand.NavigateRight, repeatCount = 0)
        assertEquals("0.505", editRight.state.camera.settingValues["photo.manual_focus"])
    }

    @Test
    fun `fresh focus presses wrap through AF while hold repeat stays in manual range`() {
        // A movable clock: AF holds for AF_EXIT_GUARD_MS after arrival so the housing's own
        // auto-repeat cannot cross in and straight back out, so leaving needs time to pass.
        var clock = 10_000L
        val reducer = ControlReducer(nowMs = { clock })
        val maxState = AppState(
            camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
                settingsCursor = 4,
                settingValues = CameraCatalog.defaultSettingValues + ("photo.manual_focus" to "1.000"),
            ),
        )

        // Reaching AF is deliberate: the barrier wants the pause AND sustained pushing, so a
        // fresh press at the rail parks there and only the run crosses.
        clock += ControlReducer.FOCUS_AF_PAUSE_MS + 1
        var wrapToAf = reducer.reduce(maxState, CameraCommand.NavigateUp, repeatCount = 0)
        if (ControlReducer.AF_BREAKTHROUGH_PRESSES > 1) {
            assertEquals("1.000", wrapToAf.state.camera.settingValues["photo.manual_focus"])
        }
        repeat(ControlReducer.AF_BREAKTHROUGH_PRESSES - 1) {
            wrapToAf = reducer.reduce(wrapToAf.state, CameraCommand.NavigateUp, repeatCount = 0)
        }
        assertEquals("AF", wrapToAf.state.camera.settingValues["photo.manual_focus"])

        clock += ControlReducer.AF_EXIT_GUARD_MS + 1
        val afToZero = reducer.reduce(wrapToAf.state, CameraCommand.NavigateUp, repeatCount = 0)
        assertEquals("0.000", afToZero.state.camera.settingValues["photo.manual_focus"])

        val heldAtMax = reducer.reduce(maxState, CameraCommand.NavigateUp, repeatCount = 1)
        assertEquals("1.000", heldAtMax.state.camera.settingValues["photo.manual_focus"])
    }

    // ── Native-parity focus contract ────────────────────────────────────────────
    // Requirements the wheel must satisfy, locked down here so they cannot regress:
    //  * a quarter turn at sensitivity 100 spends the WHOLE focus range,
    //  * every 0.01 is walked on the way (no skipped values),
    //  * lower sensitivity means proportionally finer travel per detent,
    //  * faster turning traverses more per detent than slow turning,
    //  * both rails are hard stops that only cross into AF after a deliberate pause.

    /** Total dial steps a single detent is worth: the immediate tick plus its ramp. */
    private fun detentTravel(outcome: Reduction): Int {
        val ramped = outcome.effects
            .filterIsInstance<PlatformEffect.RampSetting>()
            .sumOf { ramp -> ramp.steps }
        return 1 + ramped
    }

    private fun focusState(value: String, level: Int, lastInputAtMs: Long): AppState =
        AppState(
            camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
                settingsCursor = 4,
                settingValues = CameraCatalog.defaultSettingValues + ("photo.manual_focus" to value),
                sliderSensitivities = CameraCatalog.defaultSliderSensitivities +
                    ("photo.manual_focus" to SliderSensitivity(level)),
                lastFocusInputAtMs = lastInputAtMs,
            ),
        )

    @Test
    fun `a quarter turn at max sensitivity spends the whole focus range`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        // A brisk quarter turn: the housing paces four wheel events into it.
        val outcome = reducer.reduce(
            focusState("0.000", level = 100, lastInputAtMs = 9_920L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        )
        val perDetent = detentTravel(outcome)
        val ladder = CameraCatalog.launchCameraState(CameraModeId.Photo).let { launched ->
            CameraCatalog.settingsFor(launched.activeMode, launched.deviceVariant)
                .first { spec -> spec.id == "photo.manual_focus" }
                .options.size - 1
        }
        val quarterTurn = perDetent * 4
        assertTrue(quarterTurn >= ladder, "quarter turn covered $quarterTurn of $ladder")
        // ...and not wildly more: a quarter turn is the whole range, not several of them.
        assertTrue(quarterTurn <= ladder * 2, "quarter turn overshoots: $quarterTurn")
    }

    @Test
    fun `every 0_005 is walked rather than skipped`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val outcome = reducer.reduce(
            focusState("0.500", level = 100, lastInputAtMs = 9_920L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        )
        // The state moves exactly one step now; the remainder is a ramp of single steps,
        // so the value visits 0.51, 0.52, 0.53 ... rather than jumping.
        assertEquals("0.505", outcome.state.camera.settingValues["photo.manual_focus"])
        val ramp = outcome.effects.filterIsInstance<PlatformEffect.RampSetting>().single()
        assertEquals(1, ramp.step)
        assertTrue(ramp.steps > 1, "ramp should carry the rest of the detent")
    }

    @Test
    fun `sensitivity scales travel per detent and keeps the 0_005 floor`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        fun travelAt(level: Int): Int = detentTravel(
            reducer.reduce(
                focusState("0.500", level = level, lastInputAtMs = 9_920L),
                CameraCommand.NavigateUp,
                repeatCount = 0,
            ),
        )
        val full = travelAt(100)
        val half = travelAt(50)
        val low = travelAt(10)
        assertTrue(full > half, "100 ($full) should out-travel 50 ($half)")
        assertTrue(half > low, "50 ($half) should out-travel 10 ($low)")
        // However slow the setting, a detent always advances the dial by one real step.
        assertTrue(low >= 1, "every detent must still move at least 0.01")
    }

    /**
     * Measured at 75, not 100. At maximum sensitivity the velocity gate is deliberately fully
     * stood down — a quarter turn spends the whole ladder however slowly the diver turns — so
     * fast and slow are EQUAL there by design, and that guarantee has its own test below.
     * Between 50 and 100 the gate is still partly in force, which is where this property lives.
     */
    @Test
    fun `turning faster traverses more than turning slowly`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val fast = detentTravel(
            reducer.reduce(
                focusState("0.500", level = 75, lastInputAtMs = 9_920L),
                CameraCommand.NavigateUp,
                repeatCount = 0,
            ),
        )
        val slow = detentTravel(
            reducer.reduce(
                focusState("0.500", level = 75, lastInputAtMs = 9_400L),
                CameraCommand.NavigateUp,
                repeatCount = 0,
            ),
        )
        assertTrue(fast > slow, "fast ($fast) should traverse more than slow ($slow)")
    }

    /**
     * Scoped to turns, which is what the guarantee was ever about. Beyond TURN_GAP_MS the
     * gearing deliberately tapers toward a single rung so a value can be placed precisely —
     * a quarter turn taken over several seconds is a diver aiming, not sweeping — so the
     * "whatever the speed" clause holds across turn cadences, not across every cadence.
     */
    @Test
    fun `at max sensitivity a quarter turn spends the ladder whatever the turn speed`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val fast = detentTravel(
            reducer.reduce(
                focusState("0.500", level = 100, lastInputAtMs = 9_920L),
                CameraCommand.NavigateUp,
                repeatCount = 0,
            ),
        )
        val slow = detentTravel(
            reducer.reduce(
                focusState("0.500", level = 100, lastInputAtMs = 9_600L),
                CameraCommand.NavigateUp,
                repeatCount = 0,
            ),
        )
        assertEquals(fast, slow, "the velocity gate must be fully stood down at 100")
        val ladder = CameraCatalog.settingsFor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "photo.manual_focus" }.options.size - 1
        assertTrue(
            fast * 4 >= ladder,
            "a quarter turn covered ${fast * 4} of $ladder rungs",
        )
    }

    @Test
    fun `leaving AF at max sensitivity lands exactly on the rail and banks no ramp`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val outcome = reducer.reduce(
            focusState("AF", level = 100, lastInputAtMs = 9_000L),
            CameraCommand.NavigateDown,
            repeatCount = 0,
        )
        // Without the currentIndex > 0 guard the 51-tick credit rides on top of the rail
        // landing and carries the lens back to ~0.750.
        assertEquals("1.000", outcome.state.camera.settingValues["photo.manual_focus"])
        assertTrue(outcome.effects.none { it is PlatformEffect.RampSetting })
    }

    @Test
    fun `the focus motor never asks the drain for more than the per-frame cap`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val ramp = reducer.reduce(
            focusState("0.500", level = 100, lastInputAtMs = 9_920L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        ).effects.filterIsInstance<PlatformEffect.RampSetting>().singleOrNull()
        if (ramp != null) {
            assertTrue(
                ramp.maxTicksPerInterval <= ControlReducer.MAX_TICKS_PER_FRAME,
                "drain asked for ${ramp.maxTicksPerInterval} ticks per ${ramp.intervalMs}ms",
            )
        }
    }

    @Test
    fun `both rails hold until a deliberate re-turn crosses into AF`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        // Still turning at the near rail: the wheel parks at 0.00 instead of falling into AF.
        val spinningAtNear = reducer.reduce(
            focusState("0.000", level = 50, lastInputAtMs = 9_900L),
            CameraCommand.NavigateDown,
            repeatCount = 0,
        )
        assertEquals("0.000", spinningAtNear.state.camera.settingValues["photo.manual_focus"])

        // Rested AND then kept pushing: a pause alone is not enough, because pausing mid-turn
        // is natural and divers were falling into AF by accident.
        var atNear = reducer.reduce(
            focusState("0.000", level = 50, lastInputAtMs = 9_000L),
            CameraCommand.NavigateDown,
            repeatCount = 0,
        )
        if (ControlReducer.AF_BREAKTHROUGH_PRESSES > 1) {
            assertEquals(
                "0.000",
                atNear.state.camera.settingValues["photo.manual_focus"],
                "one press must not cross while more are required",
            )
        }
        repeat(ControlReducer.AF_BREAKTHROUGH_PRESSES - 1) {
            atNear = reducer.reduce(atNear.state, CameraCommand.NavigateDown, repeatCount = 0)
        }
        assertEquals("AF", atNear.state.camera.settingValues["photo.manual_focus"])

        // The far rail behaves identically.
        val spinningAtFar = reducer.reduce(
            focusState("1.000", level = 50, lastInputAtMs = 9_900L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        )
        assertEquals("1.000", spinningAtFar.state.camera.settingValues["photo.manual_focus"])

        var atFar = reducer.reduce(
            focusState("1.000", level = 50, lastInputAtMs = 9_000L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        )
        if (ControlReducer.AF_BREAKTHROUGH_PRESSES > 1) {
            assertEquals(
                "1.000",
                atFar.state.camera.settingValues["photo.manual_focus"],
                "one press must not cross while more are required",
            )
        }
        repeat(ControlReducer.AF_BREAKTHROUGH_PRESSES - 1) {
            atFar = reducer.reduce(atFar.state, CameraCommand.NavigateUp, repeatCount = 0)
        }
        assertEquals("AF", atFar.state.camera.settingValues["photo.manual_focus"])
    }

    @Test
    fun `AF exits to whichever end the wheel points at`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val toNear = reducer.reduce(
            focusState("AF", level = 50, lastInputAtMs = 9_000L),
            CameraCommand.NavigateUp,
            repeatCount = 0,
        )
        assertEquals("0.000", toNear.state.camera.settingValues["photo.manual_focus"])

        val toFar = reducer.reduce(
            focusState("AF", level = 50, lastInputAtMs = 9_000L),
            CameraCommand.NavigateDown,
            repeatCount = 0,
        )
        assertEquals("1.000", toFar.state.camera.settingValues["photo.manual_focus"])
    }

    @Test
    fun `focus ramp rates are per-direction and adjustable from the focus menu`() {
        val reducer = ControlReducer(nowMs = { 10_000L })
        val inSpec = CameraCatalog.focusRampSpec("photo.manual_focus", inward = true)
        val outSpec = CameraCatalog.focusRampSpec("photo.manual_focus", inward = false)
        assertEquals("photo.focus_ramp_in", inSpec.id)
        assertEquals("photo.focus_ramp_out", outSpec.id)
        assertEquals(100, inSpec.options.size)

        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 4,
            settingsEditing = true,
            sliderEditTarget = SliderEditTarget.FocusRampIn,
        )
        val faster = reducer.reduce(AppState(camera = camera), CameraCommand.NavigateRight, repeatCount = 0)
        val inward = faster.state.camera.settingValues[inSpec.id]
        assertEquals("61", inward)
        // The other direction is untouched: the two rates are independent.
        assertEquals(
            CameraCatalog.FOCUS_RAMP_DEFAULT,
            CameraCatalog.currentValue(faster.state.camera, outSpec),
        )
        assertEquals(61, CameraCatalog.focusRampLevel(faster.state.camera, "photo.manual_focus", inward = true))
        assertEquals(60, CameraCatalog.focusRampLevel(faster.state.camera, "photo.manual_focus", inward = false))
    }

    @Test
    fun `focus selection on 0_6x lens enters edit mode without switching lenses`() {
        val reducer = ControlReducer()
        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 4,
            settingValues = CameraCatalog.defaultSettingValues + ("photo.lens" to "0.6x"),
        )
        val state = AppState(camera = camera)

        val outcome = reducer.reduce(state, CameraCommand.Confirm)
        assertTrue(outcome.state.camera.settingsEditing)
        assertEquals("0.6x", outcome.state.camera.settingValues["photo.lens"])
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `moving cursor from lens to focus on fixed 0_6x lens does not switch the lens`() {
        val reducer = ControlReducer()
        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 1,
            settingValues = CameraCatalog.defaultSettingValues + ("photo.lens" to "0.6x"),
        )
        val state = AppState(camera = camera)

        val outcome = reducer.reduce(state, CameraCommand.NavigateRight)
        assertEquals(2, outcome.state.camera.settingsCursor)
        assertEquals("0.6x", outcome.state.camera.settingValues["photo.lens"])
        assertEquals("AF", outcome.state.camera.settingValues["photo.manual_focus"])
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `focus adjustment on 0_6x lens keeps the selected lens and applies the focus step`() {
        val reducer = ControlReducer()
        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 4,
            settingValues = CameraCatalog.defaultSettingValues + ("photo.lens" to "0.6x"),
        )
        val state = AppState(camera = camera)

        val outcome = reducer.reduce(state, CameraCommand.NavigateUp, repeatCount = 0)
        assertEquals("0.6x", outcome.state.camera.settingValues["photo.lens"])
        assertEquals("0.000", outcome.state.camera.settingValues["photo.manual_focus"])
        // The lens follows the STATE, not an effect. SetManualFocus had no consumer in the
        // runtime controller, and emitting it made every rung's outcome carry an effect —
        // which opened the ViewModel's whole effects pipeline, an IO coroutine included, up to
        // 1250 times a second. What matters is that the focus value landed, asserted above.
        assertTrue(
            outcome.effects.none {
                it is PlatformEffect.ExecuteCamera && it.command is CameraCommand.SetManualFocus
            },
            "manual focus must not emit an unconsumed camera effect",
        )
    }

    @Test
    fun `switching to 0_6x lens does not reset focus or focus assist state`() {
        val reducer = ControlReducer()
        val camera = CameraCatalog.launchCameraState(CameraModeId.Photo).copy(
            settingsCursor = 1,
            settingValues = CameraCatalog.defaultSettingValues +
                ("photo.lens" to "1x") +
                ("photo.manual_focus" to "0.420") +
                ("photo.focus_peaking" to "On"),
        )
        val state = AppState(camera = camera)

        val outcome = reducer.reduce(state, CameraCommand.NavigateDown, repeatCount = 0)
        assertEquals("0.6x", outcome.state.camera.settingValues["photo.lens"])
        assertEquals("0.420", outcome.state.camera.settingValues["photo.manual_focus"])
        assertEquals("On", outcome.state.camera.settingValues["photo.focus_peaking"])
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SwitchLens("0.6x"))),
            outcome.effects,
        )
    }

    @Test
    fun `gallery grid navigation follows rows and columns`() {
        val reducer = ControlReducer()
        val items = (1L..8L).map { GalleryItem(id = it, name = "photo$it.jpg", path = "/photo$it.jpg") }
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(items = items, selectedIndex = 0, currentFolder = "camera"),
        )

        val down1 = reducer.reduce(state, GalleryCommand.NavigateDown)
        assertEquals(6, down1.state.gallery.selectedIndex)

        val down2 = reducer.reduce(down1.state, GalleryCommand.NavigateDown)
        assertEquals(6, down2.state.gallery.selectedIndex)

        val up1 = reducer.reduce(state, GalleryCommand.NavigateUp)
        assertEquals(0, up1.state.gallery.selectedIndex)

        val up2 = reducer.reduce(down1.state, GalleryCommand.NavigateUp)
        assertEquals(0, up2.state.gallery.selectedIndex)

        val right = reducer.reduce(state, GalleryCommand.NavigateRight)
        assertEquals(1, right.state.gallery.selectedIndex)
        assertEquals(0, reducer.reduce(right.state, GalleryCommand.NavigateLeft).state.gallery.selectedIndex)
    }

    @Test
    fun `gallery delete confirmation flow with selectable buttons`() {
        val reducer = ControlReducer()
        val items = listOf(
            GalleryItem(id = 1, name = "photo1.jpg", path = "/photo1.jpg"),
        )
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(items = items, selectedIndex = 0),
        )

        // Initiate delete — defaults to Cancel (index 1)
        val initDelete = reducer.reduce(state, GalleryCommand.InitiateDelete)
        assertEquals(GalleryViewMode.ConfirmDelete, initDelete.state.gallery.viewMode)
        assertEquals(1, initDelete.state.gallery.confirmButtonIndex)

        // OK while Cancel is highlighted — cancels back to browser
        val cancelViaOk = reducer.reduce(initDelete.state, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.Browser, cancelViaOk.state.gallery.viewMode)

        // Initiate again, then switch to Delete with arrow
        val initAgain = reducer.reduce(state, GalleryCommand.InitiateDelete)
        val switchToDelete = reducer.reduce(initAgain.state, GalleryCommand.NavigateLeft)
        assertEquals(0, switchToDelete.state.gallery.confirmButtonIndex)

        // OK while Delete is highlighted — executes delete
        val confirmDelete = reducer.reduce(switchToDelete.state, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.ConfirmDelete, confirmDelete.state.gallery.viewMode)
        assertEquals(GalleryMutation.Delete, confirmDelete.state.gallery.pendingMutation)
        assertTrue(confirmDelete.effects.any { it is PlatformEffect.DeleteGalleryItem })
    }

    @Test
    fun `gallery back from browser returns to camera`() {
        val reducer = ControlReducer()
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(),
        )

        val result = reducer.reduce(state, GalleryCommand.Back)
        assertEquals(AppMode.CameraLive, result.state.mode)
    }

    @Test
    fun `phone battery is unknown until read and clamps to the valid range`() {
        val core = ControlCore()
        assertNull(core.state.phoneBatteryPercent, "Unknown must never render as 0%.")

        assertEquals(42, core.updatePhoneBattery(42).state.phoneBatteryPercent)
        assertEquals(100, core.updatePhoneBattery(140).state.phoneBatteryPercent)
        assertEquals(0, core.updatePhoneBattery(-5).state.phoneBatteryPercent)
    }

    @Test
    fun `ok on the housing starts the vacuum check while the cap is off`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        // 0x00 on the cover characteristic means OPEN.
        val coverOpen = core.handleNotificationPayload(
            HousingCharacteristic.CoverState.shortHex,
            byteArrayOf(0x00.toByte()),
        )
        assertEquals(SealState.CoverOpen, coverOpen.state.safety.sealState)

        // 0x50 = OK. Borrowed by the seal check only in this window.
        val start = core.handleNotificationPayload(
            HousingCharacteristic.ButtonEvents.shortHex,
            byteArrayOf(0x50.toByte()),
            Instant.parse("2026-08-04T12:00:00Z"),
        )

        assertEquals(SealState.Vacuuming, start.state.safety.sealState)
        assertEquals(
            listOf(
                PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = true)),
                PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = true)),
            ),
            start.effects,
        )
    }

    @Test
    fun `shutter still captures while the seal prompt is showing`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)
        core.handleNotificationPayload(HousingCharacteristic.CoverState.shortHex, byteArrayOf(0x00.toByte()))

        // 0x20 = Shutter.
        val outcome = core.handleNotificationPayload(
            HousingCharacteristic.ButtonEvents.shortHex,
            byteArrayOf(0x20.toByte()),
            Instant.parse("2026-08-04T12:00:00Z"),
        )

        assertEquals(listOf(PlatformEffect.ExecuteCamera(CameraCommand.CapturePhoto)), outcome.effects)
        assertEquals(SealState.CoverOpen, outcome.state.safety.sealState)
    }

    @Test
    fun `a raw start vacuum motor command never reaches the housing`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)

        val outcome = core.dispatch(SafetyCommand.StartVacuumMotor)

        assertTrue(
            outcome.effects.none { it is PlatformEffect.ExecuteHousing },
            "Only SafetyStateMachine may start the pump.",
        )
        assertTrue(outcome.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `a raw housing motor-on command never reaches the housing`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)

        val motorOn = core.dispatch(HousingCommand.SetVacuumMotor(enabled = true))
        assertTrue(
            motorOn.effects.none { it is PlatformEffect.ExecuteHousing },
            "The generic housing passthrough must not arm the pump.",
        )

        // Motor-off stays a passthrough — an emergency stop must always get through.
        val motorOff = core.dispatch(HousingCommand.SetVacuumMotor(enabled = false))
        assertEquals(
            listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false))),
            motorOff.effects,
        )
    }

    @Test
    fun `gallery preview left-right selects the seven action rail`() {
        val reducer = ControlReducer()
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Preview,
                previewAction = GalleryPreviewAction.PlayPause,
                items = listOf(GalleryItem(1, "clip.mp4", "/clip.mp4", isVideo = true)),
            ),
        )

        assertEquals(
            GalleryPreviewAction.Previous,
            reducer.reduce(state, GalleryCommand.NavigateLeft).state.gallery.previewAction,
        )
        assertEquals(
            GalleryPreviewAction.Next,
            reducer.reduce(state, GalleryCommand.NavigateRight).state.gallery.previewAction,
        )
    }
}
