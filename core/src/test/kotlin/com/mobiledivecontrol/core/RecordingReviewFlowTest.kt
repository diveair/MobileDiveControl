package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingReviewFlowTest {
    private val reducer = ControlReducer()

    private fun pausedState(
        action: RecordingPausedAction = RecordingPausedAction.Resume,
        previewVisible: Boolean = false,
    ): AppState = AppState(
        camera = CameraState(
            activeMode = CameraModeId.Video,
            recording = true,
            recordingPaused = true,
            recordingPausedAction = action,
            recordingPreviewVisible = previewVisible,
        ),
    )

    @Test
    fun `paused recording chooser is a four-action ring`() {
        var state = pausedState()
        val forward = listOf(
            RecordingPausedAction.Preview,
            RecordingPausedAction.Stop,
            RecordingPausedAction.Delete,
            RecordingPausedAction.Resume,
        )
        forward.forEach { expected ->
            state = reducer.reduce(state, CameraCommand.NavigateRight).state
            assertEquals(expected, state.camera.recordingPausedAction)
        }

        state = reducer.reduce(state, CameraCommand.NavigateLeft).state
        assertEquals(RecordingPausedAction.Delete, state.camera.recordingPausedAction)
    }

    @Test
    fun `preview toggles without ending or resuming the recording session`() {
        val show = reducer.reduce(
            pausedState(action = RecordingPausedAction.Preview).copy(
                camera = pausedState(action = RecordingPausedAction.Preview).camera.copy(
                    recordingLocationFocused = true,
                ),
            ),
            CameraCommand.PreviewVideoRecording,
        )
        assertTrue(show.state.camera.recording)
        assertTrue(show.state.camera.recordingPaused)
        assertTrue(show.state.camera.recordingPreviewVisible)
        assertFalse(show.state.camera.recordingLocationFocused)
        assertTrue(show.effects.isEmpty())

        val hide = reducer.reduce(show.state, CameraCommand.Confirm)
        assertFalse(hide.state.camera.recordingPreviewVisible)
    }

    @Test
    fun `save location is housing navigable above the paused action rail`() {
        val focused = reducer.reduce(pausedState(), CameraCommand.NavigateUp)
        assertTrue(focused.state.camera.recordingLocationFocused)

        val opened = reducer.reduce(focused.state, CameraCommand.Confirm)
        assertTrue(opened.state.camera.recordingLocationChooserVisible)
        assertEquals(listOf(PlatformEffect.LoadRecordingSaveLocations), opened.effects)

        val destinations = listOf(
            RecordingSaveLocation.Default,
            RecordingSaveLocation(name = "Dive Archive", relativePath = "DCIM/Dive Archive/"),
            RecordingSaveLocation(name = "Wrecks", relativePath = "DCIM/Wrecks/"),
            RecordingSaveLocation(name = "Training", relativePath = "Movies/Training/"),
        )
        val loaded = reducer.reduce(
            opened.state,
            CameraCommand.LoadRecordingSaveLocations(destinations),
        )
        val moved = reducer.reduce(loaded.state, CameraCommand.NavigateDown)
        assertEquals(3, moved.state.camera.recordingSaveLocationIndex)

        val highlighted = reducer.reduce(
            moved.state,
            CameraCommand.HighlightRecordingSaveLocation(1),
        )
        assertEquals(1, highlighted.state.camera.recordingSaveLocationIndex)
        assertEquals(RecordingSaveLocation.Default, highlighted.state.camera.recordingSaveLocation)
        assertTrue(highlighted.state.camera.recordingLocationChooserVisible)

        val selected = reducer.reduce(highlighted.state, CameraCommand.Confirm)
        assertEquals(destinations[1], selected.state.camera.recordingSaveLocation)
        assertFalse(selected.state.camera.recordingLocationChooserVisible)
        assertTrue(selected.state.camera.recordingLocationFocused)

        val returnedToRail = reducer.reduce(selected.state, CameraCommand.NavigateDown)
        assertFalse(returnedToRail.state.camera.recordingLocationFocused)
    }

    @Test
    fun `back from save album grid cancels without applying the highlighted album`() {
        val destination = RecordingSaveLocation(name = "Wrecks", relativePath = "DCIM/Wrecks/")
        val opened = reducer.reduce(
            pausedState().copy(
                camera = pausedState().camera.copy(
                    recordingLocationFocused = true,
                    recordingLocationChooserVisible = true,
                    recordingSaveLocations = listOf(RecordingSaveLocation.Default, destination),
                ),
            ),
            CameraCommand.HighlightRecordingSaveLocation(1),
        )

        val cancelled = reducer.reduce(opened.state, CameraCommand.Back)

        assertFalse(cancelled.state.camera.recordingLocationChooserVisible)
        assertTrue(cancelled.state.camera.recordingLocationFocused)
        assertEquals(RecordingSaveLocation.Default, cancelled.state.camera.recordingSaveLocation)
    }

    @Test
    fun `resume stop and delete have distinct state and platform commands`() {
        val resumed = reducer.reduce(pausedState(), CameraCommand.Confirm)
        assertTrue(resumed.state.camera.recording)
        assertFalse(resumed.state.camera.recordingPaused)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.ResumeVideoRecording)),
            resumed.effects,
        )

        val stopped = reducer.reduce(
            pausedState(action = RecordingPausedAction.Stop),
            CameraCommand.Confirm,
        )
        assertFalse(stopped.state.camera.recording)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.StopVideoRecording)),
            stopped.effects,
        )

        val deleted = reducer.reduce(
            pausedState(action = RecordingPausedAction.Delete),
            CameraCommand.Confirm,
        )
        assertFalse(deleted.state.camera.recording)
        assertEquals(
            listOf(PlatformEffect.ExecuteCamera(CameraCommand.DeleteVideoRecording)),
            deleted.effects,
        )
    }

    @Test
    fun `gallery details are opt-in and back hides them before leaving preview`() {
        val item = GalleryItem(id = 7, name = "clip.mp4", path = "/clip.mp4", isVideo = true)
        val initial = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Preview,
                items = listOf(item),
                previewAction = GalleryPreviewAction.Details,
            ),
        )

        val show = reducer.reduce(initial, GalleryCommand.Confirm)
        assertTrue(show.state.gallery.detailsVisible)
        assertEquals(listOf(PlatformEffect.LoadExifData(item)), show.effects)

        val hideWithBack = reducer.reduce(show.state, GalleryCommand.Back)
        assertFalse(hideWithBack.state.gallery.detailsVisible)
        assertEquals(GalleryViewMode.Preview, hideWithBack.state.gallery.viewMode)

        val leave = reducer.reduce(hideWithBack.state, GalleryCommand.Back)
        assertEquals(GalleryViewMode.Browser, leave.state.gallery.viewMode)
    }
}
