package com.mobiledivecontrol.ui

import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.CameraUiZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CameraHudOverlayLayoutTest {
    @Test
    fun `track heading side rail does not lift centred readout`() {
        val sideRailOpen = CameraState(
            focusedZone = CameraUiZone.ModeRail,
            settingsEditing = false,
        )

        assertEquals(54.dp, cameraReadoutBottomPadding(AppMode.CameraLive, sideRailOpen))
    }

    @Test
    fun `full width bottom editor lifts centred readout`() {
        val bottomEditorOpen = CameraState(
            focusedZone = CameraUiZone.SettingsPanel,
            settingsEditing = true,
        )

        assertEquals(235.dp, cameraReadoutBottomPadding(AppMode.CameraAdjust, bottomEditorOpen))
    }

    @Test
    fun `options editor lifts the complete dive readout like focus`() {
        val optionsOpen = CameraState(
            focusedZone = CameraUiZone.SettingsPanel,
            showMoreSettings = true,
        )

        assertEquals(235.dp, cameraReadoutBottomPadding(AppMode.CameraLive, optionsOpen))
    }

    @Test
    fun `closing bottom editor restores live position`() {
        assertEquals(
            54.dp,
            cameraReadoutBottomPadding(AppMode.CameraLive, CameraState(settingsEditing = false)),
        )
    }

    @Test
    fun `gallery never inherits the dive readout`() {
        assertEquals(false, cameraDiveReadoutVisible(AppMode.Gallery))
        assertEquals(true, cameraDiveReadoutVisible(AppMode.CameraLive))
        assertEquals(true, cameraDiveReadoutVisible(AppMode.Safety))
    }

    @Test
    fun `top status reports the selected video resolution`() {
        val camera = CameraCatalog.launchCameraState(CameraModeId.ProVideo).copy(
            settingValues = CameraCatalog.defaultSettingValues +
                ("pro_video.resolution" to "FHD 1920×824"),
        )

        assertEquals("FHD · 1920×824", currentCameraResolutionLabel(camera))
    }

    @Test
    fun `top status falls back to megapixels for photo modes`() {
        val camera = CameraCatalog.launchCameraState(CameraModeId.Pro).copy(
            settingValues = CameraCatalog.defaultSettingValues + ("pro.megapixels" to "50MP"),
        )

        assertEquals("50MP", currentCameraResolutionLabel(camera))
    }

    @Test
    fun `video shorthand expands to exact frame dimensions`() {
        assertEquals("8K · 7680×4320", detailedResolutionLabel("8K"))
        assertEquals("UHD 4K · 3840×2160", detailedResolutionLabel("UHD 4K"))
        assertEquals("FHD · 1920×1080", detailedResolutionLabel("FHD"))
        assertEquals("HD · 1280×720", detailedResolutionLabel("HD 720p"))
        assertEquals("SD · 720×480", detailedResolutionLabel("SD 480p"))
    }

    @Test
    fun `top status reports log and hdr without repeating the mode name`() {
        val base = CameraCatalog.launchCameraState(CameraModeId.ProVideo).copy(
            settingValues = CameraCatalog.defaultSettingValues + ("pro_video.resolution" to "8K"),
        )
        val log = base.copy(
            settingValues = base.settingValues + ("pro_video.log" to "On"),
        )
        val hdr = base.copy(
            settingValues = base.settingValues + ("pro_video.hdr" to "On"),
        )

        assertEquals("LOG", currentCameraHdrLogLabel(log))
        assertEquals("HDR", currentCameraHdrLogLabel(hdr))
        assertEquals(null, currentCameraHdrLogLabel(base))
        assertEquals("LOG 8K · 7680×4320", currentCameraResolutionStatusLabel(log))
        assertEquals("HDR 8K · 7680×4320", currentCameraResolutionStatusLabel(hdr))
        assertEquals("8K · 7680×4320", currentCameraResolutionStatusLabel(base))
    }

    @Test
    fun `dynamic range prefix follows every video resolution`() {
        val dimensions = mapOf(
            "8K" to "8K · 7680×4320",
            "UHD 4K" to "UHD 4K · 3840×2160",
            "FHD" to "FHD · 1920×1080",
            "HD 720p" to "HD · 1280×720",
            "SD 480p" to "SD · 720×480",
        )
        dimensions.forEach { (resolution, label) ->
            val camera = CameraCatalog.launchCameraState(CameraModeId.ProVideo).copy(
                settingValues = CameraCatalog.defaultSettingValues +
                    ("pro_video.resolution" to resolution) +
                    ("pro_video.hdr" to "On"),
            )
            assertEquals("HDR $label", currentCameraResolutionStatusLabel(camera))
        }
    }
}
