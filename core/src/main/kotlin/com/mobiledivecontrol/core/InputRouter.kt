package com.mobiledivecontrol.core

import com.mobiledivecontrol.core.HousingSealTransitions.Banner

data class RouteDecision(
    val commands: List<ControlCommand> = emptyList(),
    val modeOverride: AppMode? = null,
    val note: String? = null,
)

fun AppState.continuationOwnsOk(visibleSeal: Banner? = safety.sealMonitor.banner): Boolean {
    if (!diveProfile.continuationPending || airAlertVisible(this) || safety.vacuumReleasedPrompt ||
        safety.sealState == SealState.Failed) return false
    return when (visibleSeal) {
        Banner.B06, Banner.B07, Banner.B08, Banner.B10, Banner.B15, Banner.Release, Banner.ConfirmRelease -> false
        else -> true
    }
}

class InputRouter {
    fun route(
        state: AppState,
        event: HousingButtonEvent,
        visibleSeal: Banner? = state.safety.sealMonitor.banner,
        onScreenButton: Boolean = false,
    ): RouteDecision {
        if (state.housingStandby) return RouteDecision()
        if (state.controlsLocked) {
            return RouteDecision(note = "Controls are locked.")
        }

        if (!state.housing.inputEnabled && !onScreenButton) {
            return RouteDecision(note = "Housing input is disabled.")
        }

        if (event is HousingButtonEvent.Unknown) {
            return RouteDecision(note = "Unknown button ${event.rawValue.toHexString()} ignored.")
        }

        val shutOffSelected = state.camera.focusedZone == CameraUiZone.ModeRail &&
            state.camera.railLevel == CameraRailLevel.Primary &&
            state.camera.highlightedPrimaryIndex == CameraCatalog.SHUT_OFF_RAIL_INDEX &&
            (state.mode == AppMode.CameraLive || state.mode == AppMode.CameraAdjust)
        // Only the highlighted vacuum and standby actions borrow the camera shutter.
        if (event == HousingButtonEvent.Shutter && state.mode in setOf(AppMode.CameraLive, AppMode.CameraAdjust)) {
            if (shutOffSelected || (state.camera.vacuumEntrySelected && state.vacuumMenuAvailable)) {
                return RouteDecision(commands = listOf(CameraCommand.Confirm))
            }
            return routeCameraLive(state, event)
        }

        // The critical gas card is drawn over the stop UI, so its visible action owns OK.
        val air = state.airAlerts.simulation?.air ?: state.airAlerts
        if (air.gasWarning?.level?.critical == true || air.buddyConfirmation ||
            air.entry?.purpose == AirEntryPurpose.NextTank) routeAirAlerts(state, event)?.let { return it }

        if (event == HousingButtonEvent.Ok && state.continuationOwnsOk(visibleSeal))
            return RouteDecision(commands = listOf(DiveSettingsCommand.ConfirmContinuationReserve))

        // The foreground stop owns OK until minimized/dismissed. Hidden prompts cannot act on it.
        if (event == HousingButtonEvent.Ok && state.diveProfile.stopExpanded && !airAlertVisible(state)) {
            return RouteDecision(commands = listOf(DiveSettingsCommand.AcknowledgeStop))
        }
        if (shutOffSelected && event == HousingButtonEvent.Ok && !airAlertVisible(state) &&
            (visibleSeal == Banner.Reached || visibleSeal == Banner.Pumping || visibleSeal == Banner.B16)) {
            return RouteDecision(commands = listOf(CameraCommand.Confirm))
        }
        val ordinaryHold = visibleSeal == null && state.safety.sealState == SealState.LeakMonitoring &&
            state.safety.sealMonitor.verification == Verification.Idle && state.safety.sealMonitor.incidents.isEmpty()
        if ((visibleSeal == state.safety.sealMonitor.banner || ordinaryHold) && !state.diveProfile.stopExpanded &&
            !airAlertVisible(state) && !state.camera.recordingLocationChooserVisible &&
            state.mode !in setOf(AppMode.Diagnostics, AppMode.DiveSettings)) {
            sealCheckInterception(state, event)?.let { return it }
        }
        routeAirAlerts(state, event)?.let { return it }
        if (event == HousingButtonEvent.Ok && state.diveProfile.continuationPending) return RouteDecision()

        return when (state.mode) {
            AppMode.CameraLive -> routeCameraLive(state, event)
            AppMode.CameraAdjust -> routeCameraLive(state, event)
            AppMode.PhoneCursor -> routePhoneCursor(event)
            AppMode.PhoneTarget -> routePhoneTarget(event)
            AppMode.Safety -> routeSafety(event)
            AppMode.Diagnostics -> routeDiagnostics(event)
            AppMode.DiveSettings -> routeDiveSettings(event)
            AppMode.Gallery -> routeGallery(event)
        }
    }

    /**
     * Lets the seal check borrow OK and Back, in the narrowest windows that make it usable
     * from inside the housing — and nowhere else.
     *
     * OK is only borrowed with the suction cover physically off, which means the diver is on
     * the boat with the cap in their hand, not shooting. Back is only borrowed while a seal
     * element is genuinely on screen. Every other button, and Shutter in particular, keeps its
     * camera meaning at all times: a missed once-in-a-lifetime shot is not an acceptable
     * price for a prompt.
     *
     * Back means "make this go away", and what that costs depends on the stage:
     *
     * - [SealState.Vacuuming]: the motor is physically running, so the only way to make the
     *   banner go away is to stop the pump — Back cancels.
     * - [SealState.MotorStopping] / [SealState.WaitingForCoverClosed]: the pump is already off
     *   and the shell is already holding its vacuum. Cancelling here would throw away a
     *   completed pump-down over a chip the diver merely wanted off the viewfinder, so Back
     *   dismisses the chip and leaves the workflow running; screwing the cap back on still
     *   advances it. Once dismissed the interception stops, so a second Back is the camera's.
     * - [SealState.LeakMonitoring]: the hold is the measurement, and ending it early is a real
     *   decision with a real verdict — Back skips to whatever confidence has been earned.
     *
     * Returns null to fall through to normal mode dispatch.
     */
    private fun sealCheckInterception(state: AppState, event: HousingButtonEvent): RouteDecision? {
        val safety = state.safety
        val banner = safety.sealMonitor.banner
        val command = when (banner) {
            Banner.ConfirmRelease -> when (event) {
                HousingButtonEvent.Left -> SafetyCommand.SelectReleaseAnswer(false)
                HousingButtonEvent.Right -> SafetyCommand.SelectReleaseAnswer(true)
                HousingButtonEvent.Ok -> SafetyCommand.ConfirmReleaseAnswer
                else -> null
            }
            Banner.Release -> when (event) {
                HousingButtonEvent.Left, HousingButtonEvent.BackOrSafety ->
                    SafetyCommand.SelectVacuumReleaseChoice(VacuumReleaseChoice.ShutDown)
                HousingButtonEvent.Right -> SafetyCommand.SelectVacuumReleaseChoice(VacuumReleaseChoice.RestartPump)
                HousingButtonEvent.Ok -> SafetyCommand.ConfirmVacuumReleaseChoice
                else -> null
            }
            Banner.B03 -> when (event) {
                HousingButtonEvent.Down -> SafetyCommand.StartVacuumCheck
                HousingButtonEvent.Ok, HousingButtonEvent.BackOrSafety -> SafetyCommand.DismissSealCheck
                else -> null
            }
            Banner.B15 -> when (event) {
                HousingButtonEvent.Ok -> SafetyCommand.ConfirmInspection
                HousingButtonEvent.Down -> return RouteDecision()
                else -> null
            }
            Banner.B04, Banner.B05, Banner.B09, Banner.B13, Banner.B14, Banner.B17, Banner.B19,
            Banner.NoSuction, Banner.NotBuilding -> when (event) {
                HousingButtonEvent.Down -> SafetyCommand.OpenVacuumSetup
                HousingButtonEvent.Ok -> SafetyCommand.AcknowledgeSealBanner
                else -> null
            }
            Banner.B06, Banner.B07, Banner.B08, Banner.B10 ->
                if (event == HousingButtonEvent.Ok) SafetyCommand.AcknowledgeSealBanner else null
            Banner.Pumping, Banner.B16 ->
                if ((event == HousingButtonEvent.Down && safety.sealState == SealState.Vacuuming) ||
                    event == HousingButtonEvent.BackOrSafety) SafetyCommand.CancelVacuumCheck else null
            Banner.Reached -> if (event in setOf(HousingButtonEvent.Ok, HousingButtonEvent.Down,
                HousingButtonEvent.BackOrSafety)) SafetyCommand.DismissSealCheck else null
            else -> if (event == HousingButtonEvent.BackOrSafety && safety.sealState == SealState.LeakMonitoring)
                SafetyCommand.SkipToResult else null
        }
        return command?.let { RouteDecision(commands = listOf(it)) }
    }

    private fun routeCameraLive(state: AppState, event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up -> RouteDecision(commands = listOf(CameraCommand.NavigateUp))
        HousingButtonEvent.Down -> RouteDecision(commands = listOf(CameraCommand.NavigateDown))
        HousingButtonEvent.Left -> RouteDecision(commands = listOf(CameraCommand.NavigateLeft))
        HousingButtonEvent.Right -> RouteDecision(commands = listOf(CameraCommand.NavigateRight))
        HousingButtonEvent.Ok -> RouteDecision(commands = listOf(CameraCommand.Confirm))
        HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(cameraShutterCommand(state.camera)))
        HousingButtonEvent.ZoomIn -> RouteDecision(commands = listOf(CameraCommand.ZoomIn))
        HousingButtonEvent.ZoomOut -> RouteDecision(commands = listOf(CameraCommand.ZoomOut))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(CameraCommand.Back))
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    private fun routePhoneCursor(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up -> RouteDecision(commands = listOf(PhoneControlCommand.MoveCursorUp))
        HousingButtonEvent.Down -> RouteDecision(commands = listOf(PhoneControlCommand.MoveCursorDown))
        HousingButtonEvent.Left -> RouteDecision(commands = listOf(PhoneControlCommand.MoveCursorLeft))
        HousingButtonEvent.Right -> RouteDecision(commands = listOf(PhoneControlCommand.MoveCursorRight))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(PhoneControlCommand.Click))
        HousingButtonEvent.ZoomIn -> RouteDecision(commands = listOf(PhoneControlCommand.IncreaseCursorSpeed))
        HousingButtonEvent.ZoomOut -> RouteDecision(commands = listOf(PhoneControlCommand.DecreaseCursorSpeed))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(PhoneControlCommand.Back))
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    private fun routePhoneTarget(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up -> RouteDecision(commands = listOf(PhoneControlCommand.MoveTarget(Direction.Up)))
        HousingButtonEvent.Down -> RouteDecision(commands = listOf(PhoneControlCommand.MoveTarget(Direction.Down)))
        HousingButtonEvent.Left -> RouteDecision(commands = listOf(PhoneControlCommand.MoveTarget(Direction.Left)))
        HousingButtonEvent.Right -> RouteDecision(commands = listOf(PhoneControlCommand.MoveTarget(Direction.Right)))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(PhoneControlCommand.Click))
        HousingButtonEvent.ZoomIn -> RouteDecision(commands = listOf(PhoneControlCommand.ScrollUp))
        HousingButtonEvent.ZoomOut -> RouteDecision(commands = listOf(PhoneControlCommand.ScrollDown))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(PhoneControlCommand.Back))
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    private fun routeSafety(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Ok -> RouteDecision(commands = listOf(SystemCommand.SwitchToCameraMode))
        HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(HousingCommand.TriggerFlash))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(SafetyCommand.CancelVacuumCheck))
        else -> RouteDecision(note = "Safety mode ignores $event.")
    }

    private fun routeDiveSettings(event: HousingButtonEvent): RouteDecision = RouteDecision(commands = when (event) {
        HousingButtonEvent.Up -> listOf(DiveSettingsCommand.Navigate(-1))
        HousingButtonEvent.Down -> listOf(DiveSettingsCommand.Navigate(1))
        HousingButtonEvent.Left, HousingButtonEvent.ZoomOut -> listOf(DiveSettingsCommand.Adjust(-1))
        HousingButtonEvent.Right, HousingButtonEvent.ZoomIn -> listOf(DiveSettingsCommand.Adjust(1))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter -> listOf(DiveSettingsCommand.Confirm)
        HousingButtonEvent.BackOrSafety -> listOf(DiveSettingsCommand.Back)
        else -> emptyList()
    })

    private fun routeDiagnostics(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up, HousingButtonEvent.Left ->
            RouteDecision(commands = listOf(DiagnosticsCommand.NavigatePrevious))
        HousingButtonEvent.Down, HousingButtonEvent.Right ->
            RouteDecision(commands = listOf(DiagnosticsCommand.NavigateNext))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter ->
            RouteDecision(commands = listOf(DiagnosticsCommand.Confirm))
        HousingButtonEvent.BackOrSafety ->
            RouteDecision(commands = listOf(DiagnosticsCommand.Back))
        HousingButtonEvent.ZoomIn, HousingButtonEvent.ZoomOut ->
            RouteDecision(note = "Diagnostics mode ignores $event.")
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    private fun cameraShutterCommand(cameraState: CameraState): CameraCommand {
        if (cameraState.panoramaReviewAvailable) {
            return CameraCommand.Confirm
        }
        if (cameraState.activeMode.captureType != CameraCaptureType.Video) {
            return CameraCommand.CapturePhoto
        }
        // The whole recording lifecycle lives on the shutter for every video capture mode,
        // including Hyperlapse. The second press closes a valid segment and opens the shared
        // save / preview / stop / resume chooser; subsequent presses confirm the action selected
        // with the housing controls. Finalising at that boundary is what makes Preview and Delete
        // reliable on every CameraX and direct Camera2 recorder.
        return when {
            !cameraState.recording -> CameraCommand.ToggleVideoRecording
            !cameraState.recordingPaused -> CameraCommand.PauseVideoRecording
            cameraState.recordingLocationChooserVisible || cameraState.recordingLocationFocused ->
                CameraCommand.Confirm
            cameraState.recordingPausedAction == RecordingPausedAction.Resume ->
                CameraCommand.ResumeVideoRecording
            cameraState.recordingPausedAction == RecordingPausedAction.Preview ->
                CameraCommand.PreviewVideoRecording
            cameraState.recordingPausedAction == RecordingPausedAction.Stop ->
                CameraCommand.StopVideoRecording
            else -> CameraCommand.DeleteVideoRecording
        }
    }

    private fun routeGallery(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up -> RouteDecision(commands = listOf(GalleryCommand.NavigateUp))
        HousingButtonEvent.Down -> RouteDecision(commands = listOf(GalleryCommand.NavigateDown))
        HousingButtonEvent.Left -> RouteDecision(commands = listOf(GalleryCommand.NavigateLeft))
        HousingButtonEvent.Right -> RouteDecision(commands = listOf(GalleryCommand.NavigateRight))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter ->
            RouteDecision(commands = listOf(GalleryCommand.Confirm))
        HousingButtonEvent.ZoomIn, HousingButtonEvent.ZoomOut ->
            RouteDecision(commands = listOf(GalleryCommand.Back))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(GalleryCommand.Back))
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    internal companion object {
        val SEAL_CAP_PROMPT_STATES = setOf(
            SealState.MotorStopping,
            SealState.WaitingForCoverClosed,
        )
    }
}
