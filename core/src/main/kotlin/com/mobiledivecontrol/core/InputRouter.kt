package com.mobiledivecontrol.core

data class RouteDecision(
    val commands: List<ControlCommand> = emptyList(),
    val modeOverride: AppMode? = null,
    val note: String? = null,
)

class InputRouter {
    fun route(state: AppState, event: HousingButtonEvent): RouteDecision {
        if (state.controlsLocked) {
            return RouteDecision(note = "Controls are locked.")
        }

        if (!state.housing.inputEnabled) {
            return RouteDecision(note = "Housing input is disabled.")
        }

        if (event is HousingButtonEvent.Unknown) {
            return RouteDecision(note = "Unknown button ${event.rawValue.toHexString()} ignored.")
        }

        sealCheckInterception(state, event)?.let { return it }

        return when (state.mode) {
            AppMode.CameraLive -> routeCameraLive(state, event)
            AppMode.CameraAdjust -> routeCameraLive(state, event)
            AppMode.PhoneCursor -> routePhoneCursor(event)
            AppMode.PhoneTarget -> routePhoneTarget(event)
            AppMode.Safety -> routeSafety(event)
            AppMode.Diagnostics -> routeDiagnostics(event)
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
        // The released-vacuum banner is a start prompt too, and it must not depend on the cover
        // byte: the vent that raised it is itself proof the port is open, while the byte is
        // routinely stale at exactly this moment.
        val promptVisible = (safety.coverOpen == true || safety.vacuumReleasedPrompt) &&
            safety.sealState in SEAL_START_STATES &&
            !safety.checkDismissed &&
            // A primed boot record still awaiting its first pressure sample means the housing may
            // already be sealed — offering (or accepting) a pump start in that window would run
            // the motor against a closed shell for nothing.
            safety.verifiedVacuumKpa == null

        return when (event) {
            // The failed banner's confirm: "check the O-ring, remove the cap, press UP". Reset
            // returns the seal to Unknown so the normal start flow takes over from there.
            HousingButtonEvent.Up ->
                if (safety.sealState == SealState.Failed) {
                    RouteDecision(commands = listOf(SafetyCommand.ResetSealState))
                } else {
                    null
                }

            HousingButtonEvent.Ok -> when {
                promptVisible -> RouteDecision(commands = listOf(SafetyCommand.StartVacuumCheck))
                // The close-the-cap banner tells the diver OK returns them to the camera, so the
                // press that answers it is consumed here: it closes the banner and nothing else.
                // The next OK is the camera's again.
                safety.sealState in SEAL_CAP_PROMPT_STATES && !safety.checkDismissed ->
                    RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                // The adopted-vacuum reminder: monitoring is already running underneath, so OK
                // only clears the banner — same promise its "camera controls" line makes. Passed
                // is included because adoption's own clock promotes the seal while the reminder
                // is still waiting to be answered.
                (safety.sealState == SealState.LeakMonitoring || safety.sealState == SealState.Passed) &&
                    safety.capCloseReminder && !safety.checkDismissed ->
                    RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                else -> null
            }

            // This housing has no dedicated back key — "back" is a long-press on Down that
            // firmware reports as a separate byte — so every centred seal banner also answers to
            // a plain Down press, and the hints say DOWN. Borrowed only while a banner is
            // actually on screen: during leak monitoring there is no banner, and Down stays a
            // camera control for the whole half-hour hold.
            HousingButtonEvent.Down -> when {
                promptVisible -> RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                safety.sealState in SEAL_CAP_PROMPT_STATES && !safety.checkDismissed ->
                    RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                safety.sealState == SealState.Vacuuming ->
                    RouteDecision(commands = listOf(SafetyCommand.CancelVacuumCheck))
                else -> null
            }
            HousingButtonEvent.BackOrSafety -> when {
                promptVisible -> RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                safety.sealState == SealState.Vacuuming ->
                    RouteDecision(commands = listOf(SafetyCommand.CancelVacuumCheck))
                safety.sealState in SEAL_CAP_PROMPT_STATES && !safety.checkDismissed ->
                    RouteDecision(commands = listOf(SafetyCommand.DismissSealCheck))
                safety.sealState == SealState.LeakMonitoring ->
                    RouteDecision(commands = listOf(SafetyCommand.SkipToResult))
                else -> null
            }
            // Shutter is never intercepted, in any seal stage.
            else -> null
        }
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
        HousingButtonEvent.Ok -> RouteDecision(commands = listOf(SafetyCommand.StartVacuumCheck))
        HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(HousingCommand.TriggerFlash))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(SafetyCommand.CancelVacuumCheck))
        else -> RouteDecision(note = "Safety mode ignores $event.")
    }

    private fun routeDiagnostics(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Ok -> RouteDecision(commands = listOf(SystemCommand.ExportDiagnostics))
        HousingButtonEvent.BackOrSafety -> RouteDecision(modeOverride = AppMode.CameraLive)
        else -> RouteDecision(note = "Diagnostics mode ignores $event.")
    }

    private fun cameraShutterCommand(cameraState: CameraState): CameraCommand {
        return if (cameraState.activeMode.captureType == CameraCaptureType.Video) {
            CameraCommand.ToggleVideoRecording
        } else {
            CameraCommand.CapturePhoto
        }
    }

    private fun routeGallery(event: HousingButtonEvent): RouteDecision = when (event) {
        HousingButtonEvent.Up -> RouteDecision(commands = listOf(GalleryCommand.NavigateUp))
        HousingButtonEvent.Down -> RouteDecision(commands = listOf(GalleryCommand.NavigateDown))
        HousingButtonEvent.Left -> RouteDecision(commands = listOf(GalleryCommand.NavigateLeft))
        HousingButtonEvent.Right -> RouteDecision(commands = listOf(GalleryCommand.NavigateRight))
        HousingButtonEvent.Ok -> RouteDecision(commands = listOf(GalleryCommand.Confirm))
        HousingButtonEvent.Shutter -> RouteDecision(commands = listOf(GalleryCommand.InitiateDelete))
        HousingButtonEvent.ZoomIn -> RouteDecision(commands = listOf(GalleryCommand.CreateFolder))
        HousingButtonEvent.ZoomOut -> RouteDecision(commands = listOf(GalleryCommand.Back))
        HousingButtonEvent.BackOrSafety -> RouteDecision(commands = listOf(GalleryCommand.Back))
        is HousingButtonEvent.Unknown -> RouteDecision()
    }

    internal companion object {
        /** Seal states in which the start prompt is offered. */
        val SEAL_START_STATES = setOf(
            SealState.CoverOpen,
            SealState.ReadyToVacuum,
            SealState.Unknown,
        )

        /**
         * Pump finished, cap still off — the "close the blue cap" chip is up and Back only
         * hides it. The vacuum survives; nothing about the workflow is torn down.
         */
        val SEAL_CAP_PROMPT_STATES = setOf(
            SealState.MotorStopping,
            SealState.WaitingForCoverClosed,
        )
    }
}
