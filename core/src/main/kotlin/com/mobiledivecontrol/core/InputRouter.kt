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
}
