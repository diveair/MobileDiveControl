package com.mobiledivecontrol.ui

import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.CameraState
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

        assertEquals(86.dp, cameraReadoutBottomPadding(AppMode.CameraAdjust, bottomEditorOpen))
    }

    @Test
    fun `closing bottom editor restores live position`() {
        assertEquals(
            54.dp,
            cameraReadoutBottomPadding(AppMode.CameraLive, CameraState(settingsEditing = false)),
        )
    }
}
