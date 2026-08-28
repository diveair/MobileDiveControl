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
    fun `hyperlapse shutter starts then stops without ordinary video pause chooser`() {
        val idle = readyState(
            mode = AppMode.CameraLive,
            camera = CameraState(activeMode = CameraModeId.Hyperlapse),
        )
        assertEquals(
            listOf(CameraCommand.ToggleVideoRecording),
            router.route(idle, HousingButtonEvent.Shutter).commands,
        )

        val recording = idle.copy(camera = idle.camera.copy(recording = true))
        assertEquals(
            listOf(CameraCommand.StopVideoRecording),
            router.route(recording, HousingButtonEvent.Shutter).commands,
        )
    }

    @Test
    fun `paused video shutter confirms each selected review action`() {
        val expected = mapOf(
            RecordingPausedAction.Resume to CameraCommand.ResumeVideoRecording,
            RecordingPausedAction.Preview to CameraCommand.PreviewVideoRecording,
            RecordingPausedAction.Stop to CameraCommand.StopVideoRecording,
            RecordingPausedAction.Delete to CameraCommand.DeleteVideoRecording,
        )
        expected.forEach { (action, command) ->
            val state = readyState(
                mode = AppMode.CameraLive,
                camera = CameraState(
                    activeMode = CameraModeId.Video,
                    recording = true,
                    recordingPaused = true,
                    recordingPausedAction = action,
                ),
            )
            assertEquals(
                listOf(command),
                router.route(state, HousingButtonEvent.Shutter).commands,
                "Shutter must confirm $action",
            )
        }
    }

    @Test
    fun `paused video shutter confirms save location controls`() {
        for (camera in listOf(
            CameraState(
                activeMode = CameraModeId.Video,
                recording = true,
                recordingPaused = true,
                recordingLocationFocused = true,
            ),
            CameraState(
                activeMode = CameraModeId.Video,
                recording = true,
                recordingPaused = true,
                recordingLocationFocused = true,
                recordingLocationChooserVisible = true,
            ),
        )) {
            val state = readyState(mode = AppMode.CameraLive, camera = camera)
            assertEquals(
                listOf(CameraCommand.Confirm),
                router.route(state, HousingButtonEvent.Shutter).commands,
            )
        }
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

    // --- Seal check interception ---

    @Test
    fun `ok starts the vacuum check while the cover is open`() {
        for (sealState in listOf(SealState.CoverOpen, SealState.ReadyToVacuum, SealState.Unknown)) {
            val state = readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = sealState, coverOpen = true),
            )
            val route = router.route(state, HousingButtonEvent.Ok)
            assertEquals(
                listOf(SafetyCommand.StartVacuumCheck),
                route.commands,
                "OK should start the check from $sealState",
            )
        }
    }

    @Test
    fun `ok is not intercepted when the cover is closed`() {
        // The two cap-prompt states are excluded: their banner promises "press Menu/OK for
        // camera controls" and shows regardless of the cover byte, so OK answers it there —
        // that case is pinned by `ok dismisses the close-the-cap banner...` below.
        for (sealState in SealState.entries - InputRouter.SEAL_CAP_PROMPT_STATES) {
            val state = readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = sealState, coverOpen = false),
            )
            val route = router.route(state, HousingButtonEvent.Ok)
            assertEquals(
                listOf(CameraCommand.Confirm),
                route.commands,
                "With the cap on, OK belongs to the camera ($sealState)",
            )
        }
    }

    @Test
    fun `ok is not intercepted before any cover reading exists`() {
        val state = readyState(mode = AppMode.CameraLive, safety = SafetyState(coverOpen = null))
        val route = router.route(state, HousingButtonEvent.Ok)
        assertEquals(listOf(CameraCommand.Confirm), route.commands)
    }

    @Test
    fun `ok is not intercepted once the prompt is dismissed`() {
        val state = readyState(
            mode = AppMode.CameraLive,
            safety = SafetyState(sealState = SealState.CoverOpen, coverOpen = true, checkDismissed = true),
        )
        val route = router.route(state, HousingButtonEvent.Ok)
        assertEquals(listOf(CameraCommand.Confirm), route.commands)
    }

    @Test
    fun `ok is not intercepted mid-workflow or after a verdict`() {
        // MotorStopping and WaitingForCoverClosed moved out of this list when their banner
        // gained "press Menu/OK for camera controls" — OK now answers the banner there.
        val untouched = listOf(
            SealState.Vacuuming,
            SealState.LeakMonitoring,
            SealState.Passed,
            SealState.Failed,
            SealState.Warning,
        )
        for (sealState in untouched) {
            val state = readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = sealState, coverOpen = true),
            )
            val route = router.route(state, HousingButtonEvent.Ok)
            assertEquals(listOf(CameraCommand.Confirm), route.commands, "OK stays with the camera in $sealState")
        }
    }

    @Test
    fun `back dismisses the prompt cancels the pump and skips the countdown`() {
        val dismiss = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.CoverOpen, coverOpen = true),
            ),
            HousingButtonEvent.BackOrSafety,
        )
        assertEquals(listOf(SafetyCommand.DismissSealCheck), dismiss.commands)

        val cancel = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.Vacuuming, coverOpen = true),
            ),
            HousingButtonEvent.BackOrSafety,
        )
        assertEquals(listOf(SafetyCommand.CancelVacuumCheck), cancel.commands)

        val skip = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.LeakMonitoring, coverOpen = false),
            ),
            HousingButtonEvent.BackOrSafety,
        )
        assertEquals(listOf(SafetyCommand.SkipToResult), skip.commands)
    }

    /**
     * The pump has already reached target here. Cancelling would discard a completed pump-down
     * to get rid of a chip, so Back only hides the chip and the vacuum survives.
     */
    @Test
    fun `back hides the close-the-cap chip without discarding the vacuum`() {
        for (sealState in listOf(SealState.MotorStopping, SealState.WaitingForCoverClosed)) {
            val route = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true),
                ),
                HousingButtonEvent.BackOrSafety,
            )
            assertEquals(
                listOf(SafetyCommand.DismissSealCheck),
                route.commands,
                "Back dismisses rather than cancels from $sealState",
            )
        }
    }

    /** This housing has no back key, so the banners answer to a plain Down press too. */
    @Test
    fun `down answers whichever centred seal banner is on screen`() {
        val prompt = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.CoverOpen, coverOpen = true),
            ),
            HousingButtonEvent.Down,
        )
        assertEquals(listOf(SafetyCommand.DismissSealCheck), prompt.commands)

        val pumping = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.Vacuuming, coverOpen = true),
            ),
            HousingButtonEvent.Down,
        )
        assertEquals(listOf(SafetyCommand.CancelVacuumCheck), pumping.commands)

        val capPrompt = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.MotorStopping, coverOpen = true),
            ),
            HousingButtonEvent.Down,
        )
        assertEquals(listOf(SafetyCommand.DismissSealCheck), capPrompt.commands)
    }

    /** No banner during the hold, so Down stays a camera control for the whole half hour. */
    @Test
    fun `down is not borrowed while leak monitoring runs behind the camera`() {
        val route = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.LeakMonitoring, coverOpen = false),
            ),
            HousingButtonEvent.Down,
        )
        assertEquals(listOf(CameraCommand.NavigateDown), route.commands)
    }

    /** While a primed boot record is undecided, OK must not be able to start the pump. */
    @Test
    fun `ok cannot start the pump while a boot record awaits its first sample`() {
        val route = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(
                    sealState = SealState.Unknown,
                    coverOpen = true,
                    verifiedVacuumKpa = 81.0,
                ),
            ),
            HousingButtonEvent.Ok,
        )
        assertEquals(listOf(CameraCommand.Confirm), route.commands)
    }

    /** The failed banner's confirm: UP resets the seal; anywhere else UP stays with the camera. */
    @Test
    fun `up confirms a failed seal and is otherwise never intercepted`() {
        val failed = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.Failed),
            ),
            HousingButtonEvent.Up,
        )
        assertEquals(listOf(SafetyCommand.ResetSealState), failed.commands)

        for (sealState in SealState.entries - SealState.Failed) {
            val route = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true),
                ),
                HousingButtonEvent.Up,
            )
            assertEquals(
                listOf(CameraCommand.NavigateUp),
                route.commands,
                "UP belongs to the camera in $sealState",
            )
        }
    }

    /** The adopted-vacuum reminder is answered by OK; without it, monitoring leaves OK alone. */
    @Test
    fun `ok clears the adopted-vacuum reminder and nothing else during monitoring`() {
        // Both holding states: adoption's own clock can promote the seal to Passed while the
        // reminder is still waiting to be answered, and OK must keep meaning "answer it".
        for (sealState in listOf(SealState.LeakMonitoring, SealState.Passed)) {
            val reminder = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, capCloseReminder = true),
                ),
                HousingButtonEvent.Ok,
            )
            assertEquals(
                listOf(SafetyCommand.DismissSealCheck),
                reminder.commands,
                "OK answers the reminder in $sealState",
            )
        }

        val plain = router.route(
            readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = SealState.LeakMonitoring),
            ),
            HousingButtonEvent.Ok,
        )
        assertEquals(listOf(CameraCommand.Confirm), plain.commands)
    }

    /** The banner says "press Menu/OK for camera controls" — so OK must close it, once. */
    @Test
    fun `ok dismisses the close-the-cap banner and the next ok is the camera's`() {
        for (sealState in listOf(SealState.MotorStopping, SealState.WaitingForCoverClosed)) {
            val visible = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true),
                ),
                HousingButtonEvent.Ok,
            )
            assertEquals(
                listOf(SafetyCommand.DismissSealCheck),
                visible.commands,
                "OK answers the banner in $sealState",
            )

            val dismissed = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true, checkDismissed = true),
                ),
                HousingButtonEvent.Ok,
            )
            assertEquals(
                listOf(CameraCommand.Confirm),
                dismissed.commands,
                "The next OK belongs to the camera in $sealState",
            )
        }
    }

    /** With the chip already hidden there is nothing left to dismiss, so Back is the camera's. */
    @Test
    fun `back returns to the camera once the close-the-cap chip is dismissed`() {
        for (sealState in listOf(SealState.MotorStopping, SealState.WaitingForCoverClosed)) {
            val route = router.route(
                readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true, checkDismissed = true),
                ),
                HousingButtonEvent.BackOrSafety,
            )
            assertEquals(
                listOf(CameraCommand.Back),
                route.commands,
                "A second Back belongs to the camera in $sealState",
            )
        }
    }

    @Test
    fun `back keeps its camera meaning outside an active seal stage`() {
        for (sealState in listOf(SealState.Passed, SealState.Failed, SealState.Warning, SealState.Unknown)) {
            val state = readyState(
                mode = AppMode.CameraLive,
                safety = SafetyState(sealState = sealState, coverOpen = false),
            )
            val route = router.route(state, HousingButtonEvent.BackOrSafety)
            assertEquals(listOf(CameraCommand.Back), route.commands, "Back stays with the camera in $sealState")
        }
    }

    @Test
    fun `shutter is never intercepted in any seal stage`() {
        for (sealState in SealState.entries) {
            for (coverOpen in listOf(null, true, false)) {
                for (dismissed in listOf(true, false)) {
                    val state = readyState(
                        mode = AppMode.CameraLive,
                        safety = SafetyState(
                            sealState = sealState,
                            coverOpen = coverOpen,
                            checkDismissed = dismissed,
                        ),
                    )
                    val route = router.route(state, HousingButtonEvent.Shutter)
                    assertEquals(
                        listOf(CameraCommand.CapturePhoto),
                        route.commands,
                        "Shutter must always take a photo ($sealState, cover=$coverOpen, dismissed=$dismissed)",
                    )
                }
            }
        }
    }

    @Test
    fun `no other button is intercepted during a seal stage`() {
        // Down is absent from this list by design: the housing has no back key, so Down answers
        // the centred seal banners — its borrowing is pinned by its own tests above. Failed is
        // excluded for UP's sake: UP is that banner's confirm, pinned by its own test above.
        val others = listOf(
            HousingButtonEvent.Up to CameraCommand.NavigateUp,
            HousingButtonEvent.Left to CameraCommand.NavigateLeft,
            HousingButtonEvent.Right to CameraCommand.NavigateRight,
            HousingButtonEvent.ZoomIn to CameraCommand.ZoomIn,
            HousingButtonEvent.ZoomOut to CameraCommand.ZoomOut,
        )
        for (sealState in SealState.entries - SealState.Failed) {
            for ((event, expected) in others) {
                val state = readyState(
                    mode = AppMode.CameraLive,
                    safety = SafetyState(sealState = sealState, coverOpen = true),
                )
                assertEquals(
                    listOf<ControlCommand>(expected),
                    router.route(state, event).commands,
                    "$event must stay with the camera in $sealState",
                )
            }
        }
    }

    @Test
    fun `seal interception still respects the locked and disabled gates`() {
        val promptSafety = SafetyState(sealState = SealState.CoverOpen, coverOpen = true)

        val locked = router.route(
            readyState(mode = AppMode.CameraLive, safety = promptSafety, controlsLocked = true),
            HousingButtonEvent.Ok,
        )
        assertTrue(locked.commands.isEmpty())
        assertEquals("Controls are locked.", locked.note)

        val noInput = router.route(
            AppState(mode = AppMode.CameraLive, safety = promptSafety),
            HousingButtonEvent.Ok,
        )
        assertTrue(noInput.commands.isEmpty())
        assertEquals("Housing input is disabled.", noInput.note)
    }

    @Test
    fun `diagnostics routes the housing dpad confirm shutter and back to its bottom actions`() {
        val state = readyState(mode = AppMode.Diagnostics)

        for (event in listOf(HousingButtonEvent.Up, HousingButtonEvent.Left)) {
            assertEquals(
                listOf(DiagnosticsCommand.NavigatePrevious),
                router.route(state, event).commands,
            )
        }
        for (event in listOf(HousingButtonEvent.Down, HousingButtonEvent.Right)) {
            assertEquals(
                listOf(DiagnosticsCommand.NavigateNext),
                router.route(state, event).commands,
            )
        }
        for (event in listOf(HousingButtonEvent.Ok, HousingButtonEvent.Shutter)) {
            assertEquals(
                listOf(DiagnosticsCommand.Confirm),
                router.route(state, event).commands,
            )
        }
        assertEquals(
            listOf(DiagnosticsCommand.Back),
            router.route(state, HousingButtonEvent.BackOrSafety).commands,
        )
    }

    private fun readyState(
        mode: AppMode,
        camera: CameraState = CameraState(),
        safety: SafetyState = SafetyState(),
        controlsLocked: Boolean = false,
    ): AppState {
        return AppState(
            mode = mode,
            housing = HousingState(
                connected = true,
                inputEnabled = true,
            ),
            camera = camera,
            safety = safety,
            controlsLocked = controlsLocked,
        )
    }
}
