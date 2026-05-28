package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputRouterTest {
    private val router = InputRouter()

    @Test
    fun `camera live shutter captures photo when photo is selected`() {
        val state = readyState(mode = AppMode.CameraLive)
        val route = router.route(state, HousingButtonEvent.Shutter)
        assertEquals(listOf(CameraCommand.CapturePhoto), route.commands)
    }

    @Test
    fun `camera live shutter toggles recording when video is selected`() {
        val state = readyState(
            mode = AppMode.CameraLive,
            camera = CameraState(activeMode = CameraModeId.Video),
        )
        val route = router.route(state, HousingButtonEvent.Shutter)
        assertEquals(listOf(CameraCommand.ToggleVideoRecording), route.commands)
    }

    @Test
    fun `camera live right focuses the mode rail`() {
        val state = readyState(mode = AppMode.CameraLive)
        val route = router.route(state, HousingButtonEvent.Right)
        assertEquals(listOf(CameraCommand.NavigateRight), route.commands)
    }

    @Test
    fun `camera live back stays in camera navigation flow`() {
        val state = readyState(mode = AppMode.CameraLive)
        val route = router.route(state, HousingButtonEvent.BackOrSafety)
        assertEquals(listOf(CameraCommand.Back), route.commands)
    }

    @Test
    fun `phone target routes direction as smart target move`() {
        val state = readyState(mode = AppMode.PhoneTarget)
        val route = router.route(state, HousingButtonEvent.Right)
        assertEquals(listOf(PhoneControlCommand.MoveTarget(Direction.Right)), route.commands)
    }

    @Test
    fun `locked controls produce a visible note and no command`() {
        val state = readyState(mode = AppMode.CameraLive, controlsLocked = true)
        val route = router.route(state, HousingButtonEvent.Shutter)
        assertTrue(route.commands.isEmpty())
        assertEquals("Controls are locked.", route.note)
    }

    private fun readyState(
        mode: AppMode,
        camera: CameraState = CameraState(),
        controlsLocked: Boolean = false,
    ): AppState {
        return AppState(
            mode = mode,
            housing = HousingState(
                connected = true,
                inputEnabled = true,
            ),
            camera = camera,
            controlsLocked = controlsLocked,
        )
    }
}
