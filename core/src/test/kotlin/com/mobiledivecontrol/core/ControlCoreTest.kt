package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Navigate right from centered Modes to position the cursor on White Balance.
        repeat(3) {
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

        // repeatCount = 1: ignored under sensitivity 1 (skipInterval = 9)
        val red1 = reducer.reduce(stateWithLowSens.copy(
            camera = stateWithLowSens.camera.copy(settingsEditing = true, sliderEditTarget = SliderEditTarget.Value)
        ), CameraCommand.NavigateRight, repeatCount = 1)
        assertEquals(initialVal, red1.state.camera.settingValues["pro.white_balance"])

        // repeatCount = 9: applied under sensitivity 1 (9 % 9 == 0)
        val red9 = reducer.reduce(stateWithLowSens.copy(
            camera = stateWithLowSens.camera.copy(settingsEditing = true, sliderEditTarget = SliderEditTarget.Value)
        ), CameraCommand.NavigateRight, repeatCount = 9)
        assertTrue(red9.state.camera.settingValues["pro.white_balance"] != initialVal)
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

        repeat(3) {
            core.dispatch(CameraCommand.NavigateRight)
        }

        val editOutcome = core.dispatch(CameraCommand.Confirm)
        assertTrue(editOutcome.state.camera.settingsEditing)
        assertEquals("5600K", editOutcome.state.camera.settingValues["pro.white_balance"])

        // 0x10 = Right button (now adjusts value +1)
        val firstPress = core.handleButtonPayload(
            payload = byteArrayOf(0x10),
            receivedAt = Instant.parse("2026-05-27T12:00:00Z"),
        )
        assertEquals("6500K", firstPress.state.camera.settingValues["pro.white_balance"])

        val secondPress = core.handleButtonPayload(
            payload = byteArrayOf(0x10),
            receivedAt = Instant.parse("2026-05-27T12:00:00.250Z"),
        )
        assertEquals("7500K", secondPress.state.camera.settingValues["pro.white_balance"])
    }

    @Test
    fun `up on a highlighted bottom bar setting changes its value and emits a camera effect`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        val settingsOutcome = core.dispatch(CameraCommand.Confirm)
        assertEquals(CameraUiZone.SettingsPanel, settingsOutcome.state.camera.focusedZone)

        val evSelected = core.dispatch(CameraCommand.NavigateRight)
        assertEquals("0", evSelected.state.camera.settingValues["photo.exposure_compensation"])

        val adjusted = core.dispatch(CameraCommand.NavigateUp)
        // EV steps are 0.025 — first step from "0" is "+0.03" (0.025 formatted %.2f rounds up)
        assertEquals("+0.03", adjusted.state.camera.settingValues["photo.exposure_compensation"])
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SetExposureCompensation(0.03))),
            adjusted.effects,
        )
    }

    @Test
    fun `confirm on gallery shortcut switches to gallery mode`() {
        val core = ControlCore()
        core.advanceBle(BleSignal.Ready)
        core.updatePermission(PermissionKind.Camera, true)

        core.dispatch(CameraCommand.Confirm)
        repeat(4) {
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
    fun `focus options span 0_00 to 1_00 with 0_01 steps`() {
        // Get the focus setting from the Photo mode profile
        val photoProfile = CameraCatalog.profile(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra)
        val focusSpec = photoProfile.settings.first { it.id.endsWith(".manual_focus") }
        val options = focusSpec.options
        assertEquals("AF", options.first())
        assertEquals("0.00", options[1])
        assertEquals("0.01", options[2])
        assertEquals("0.50", options[51])
        assertEquals("1.00", options.last())
        assertEquals(102, options.size) // AF + 101 numeric values (0.00 to 1.00)
    }

    @Test
    fun `gallery navigation cycles through items`() {
        val reducer = ControlReducer()
        val items = listOf(
            GalleryItem(id = 1, name = "photo1.jpg", path = "/photo1.jpg"),
            GalleryItem(id = 2, name = "photo2.jpg", path = "/photo2.jpg"),
            GalleryItem(id = 3, name = "photo3.jpg", path = "/photo3.jpg"),
        )
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(items = items, selectedIndex = 0),
        )

        // Navigate down
        val down1 = reducer.reduce(state, GalleryCommand.NavigateDown)
        assertEquals(1, down1.state.gallery.selectedIndex)

        val down2 = reducer.reduce(down1.state, GalleryCommand.NavigateDown)
        assertEquals(2, down2.state.gallery.selectedIndex)

        // Navigate down at end clamps to last index
        val down3 = reducer.reduce(down2.state, GalleryCommand.NavigateDown)
        assertEquals(2, down3.state.gallery.selectedIndex)

        // Navigate up from 0 clamps at 0
        val up1 = reducer.reduce(state, GalleryCommand.NavigateUp)
        assertEquals(0, up1.state.gallery.selectedIndex)

        // Navigate up from middle
        val up2 = reducer.reduce(down1.state, GalleryCommand.NavigateUp)
        assertEquals(0, up2.state.gallery.selectedIndex)
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
        assertEquals(GalleryViewMode.Browser, confirmDelete.state.gallery.viewMode)
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
    fun `gallery left-right switches tabs`() {
        val reducer = ControlReducer()
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(tab = GalleryTab.Photos),
        )

        // Right goes to Videos
        val right1 = reducer.reduce(state, GalleryCommand.NavigateRight)
        assertEquals(GalleryTab.Videos, right1.state.gallery.tab)

        // Right again goes to Folders
        val right2 = reducer.reduce(right1.state, GalleryCommand.NavigateRight)
        assertEquals(GalleryTab.Folders, right2.state.gallery.tab)

        // Left from Folders back to Videos
        val left1 = reducer.reduce(right2.state, GalleryCommand.NavigateLeft)
        assertEquals(GalleryTab.Videos, left1.state.gallery.tab)
    }
}
