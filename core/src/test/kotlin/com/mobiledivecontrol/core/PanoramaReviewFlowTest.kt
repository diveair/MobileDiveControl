package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanoramaReviewFlowTest {
    private val reducer = ControlReducer()

    private fun reviewState(
        action: PanoramaReviewAction = PanoramaReviewAction.Save,
    ) = AppState(
        mode = AppMode.CameraLive,
        housing = HousingState(inputEnabled = true),
        camera = CameraState(
            activeMode = CameraModeId.Panorama,
            panoramaReviewAvailable = true,
            panoramaReviewInputArmed = true,
            panoramaReviewAction = action,
        ),
    )

    @Test
    fun `runtime ready event opens automatic preview with two-action chooser on Save`() {
        val ready = reducer.reduce(
            AppState(camera = CameraState(activeMode = CameraModeId.Panorama)),
            CameraCommand.PanoramaReviewReady,
        )

        assertTrue(ready.state.camera.panoramaReviewAvailable)
        assertFalse(ready.state.camera.panoramaReviewInputArmed)
        assertEquals(PanoramaReviewAction.Save, ready.state.camera.panoramaReviewAction)
        assertEquals(
            listOf(PanoramaReviewAction.Save, PanoramaReviewAction.Delete),
            PanoramaReviewAction.entries,
        )
        assertTrue(ready.effects.isEmpty())
    }

    @Test
    fun `stop gesture cannot fall through into the default Save action`() {
        val opened = reducer.reduce(
            AppState(camera = CameraState(activeMode = CameraModeId.Panorama)),
            CameraCommand.PanoramaReviewReady,
        ).state

        val leakedConfirm = reducer.reduce(opened, CameraCommand.Confirm)
        assertTrue(leakedConfirm.state.camera.panoramaReviewAvailable)
        assertTrue(leakedConfirm.effects.isEmpty())
        val leakedTouchSave = reducer.reduce(opened, CameraCommand.SavePanorama)
        assertTrue(leakedTouchSave.state.camera.panoramaReviewAvailable)
        assertTrue(leakedTouchSave.effects.isEmpty())

        val armed = reducer.reduce(opened, CameraCommand.ArmPanoramaReviewInput).state
        val deliberateConfirm = reducer.reduce(armed, CameraCommand.Confirm)
        assertFalse(deliberateConfirm.state.camera.panoramaReviewAvailable)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SavePanorama)),
            deliberateConfirm.effects,
        )
    }

    @Test
    fun `both housing axes traverse panorama actions circularly`() {
        var state = reviewState()
        state = reducer.reduce(state, CameraCommand.NavigateDown).state
        assertEquals(PanoramaReviewAction.Delete, state.camera.panoramaReviewAction)
        state = reducer.reduce(state, CameraCommand.NavigateRight).state
        assertEquals(PanoramaReviewAction.Save, state.camera.panoramaReviewAction)
        state = reducer.reduce(state, CameraCommand.NavigateDown).state
        assertEquals(PanoramaReviewAction.Delete, state.camera.panoramaReviewAction)
        state = reducer.reduce(state, CameraCommand.NavigateUp).state
        assertEquals(PanoramaReviewAction.Save, state.camera.panoramaReviewAction)
    }

    @Test
    fun `Back cannot close the automatic preview or discard the staged panorama`() {
        val unchanged = reducer.reduce(reviewState(), CameraCommand.Back)
        assertTrue(unchanged.state.camera.panoramaReviewAvailable)
        assertEquals(PanoramaReviewAction.Save, unchanged.state.camera.panoramaReviewAction)
        assertTrue(unchanged.effects.isEmpty())
    }

    @Test
    fun `Save and Delete close review and issue distinct platform actions`() {
        val saved = reducer.reduce(
            reviewState(PanoramaReviewAction.Save),
            CameraCommand.Confirm,
        )
        assertFalse(saved.state.camera.panoramaReviewAvailable)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SavePanorama)),
            saved.effects,
        )

        val deleted = reducer.reduce(
            reviewState(PanoramaReviewAction.Delete),
            CameraCommand.Confirm,
        )
        assertFalse(deleted.state.camera.panoramaReviewAvailable)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.DeletePanorama)),
            deleted.effects,
        )
    }

    @Test
    fun `housing shutter confirms selected panorama review action`() {
        val state = reviewState(PanoramaReviewAction.Save)
        val route = InputRouter().route(state, HousingButtonEvent.Shutter)
        assertEquals(listOf(CameraCommand.Confirm), route.commands)

        val confirmed = reducer.reduce(state, route.commands.single())
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.SavePanorama)),
            confirmed.effects,
        )
    }

    @Test
    fun `capture command cannot start another panorama while review owns input`() {
        val ignored = reducer.reduce(reviewState(), CameraCommand.CapturePhoto)
        assertTrue(ignored.state.camera.panoramaReviewAvailable)
        assertTrue(ignored.effects.isEmpty())
    }
}
