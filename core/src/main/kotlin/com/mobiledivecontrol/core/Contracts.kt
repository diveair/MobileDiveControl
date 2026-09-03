package com.mobiledivecontrol.core

import java.time.Duration

enum class AppMode {
    CameraLive,
    CameraAdjust,
    PhoneCursor,
    PhoneTarget,
    Safety,
    Diagnostics,
    Gallery,
}

enum class DiagnosticsAction {
    BackToCamera,
    Export,
}

enum class BleConnectionState {
    Idle,
    Scanning,
    Connecting,
    DiscoveringServices,
    Subscribing,
    Ready,
    Degraded,
    Reconnecting,
    Failed,
}

enum class SealState {
    Unknown,
    CoverOpen,
    ReadyToVacuum,
    Vacuuming,
    MotorStopping,
    WaitingForCoverClosed,
    LeakMonitoring,
    Passed,
    Warning,
    Failed,
}

enum class CursorSpeedProfile {
    Precision,
    Normal,
    Fast,
    SmartTarget,
}

enum class AlertPriority {
    Critical,
    High,
    Medium,
    Low,
}

enum class PermissionKind {
    Bluetooth,
    Camera,
    Microphone,
    Overlay,
    Accessibility,
    ForegroundService,
    Notifications,
}

enum class Direction {
    Up,
    Down,
    Left,
    Right,
}

enum class CameraUiZone {
    LiveView,
    ModeRail,
    SettingsPanel,
}

enum class CameraRailLevel {
    Primary,
    Secondary,
}

enum class CameraCaptureType {
    Photo,
    Video,
    Hybrid,
}

enum class CameraSettingKind {
    Choice,
    Toggle,
    Slider,
}

enum class CameraFeatureStatus {
    Confirmed,
    NeedsVerification,
    /** Listed for parity/reference, but deliberately non-adjustable until a real pipeline exists. */
    Unavailable,
}

@JvmInline
value class SliderSensitivity(val level: Int) {
    companion object {
        val MIN = SliderSensitivity(1)
        val DEFAULT = SliderSensitivity(50)
        val MAX = SliderSensitivity(100)
        fun of(level: Int) = SliderSensitivity(level.coerceIn(MIN.level, MAX.level))
    }
}

enum class SliderEditTarget {
    Value,
    Sensitivity,
    FocusAssist,
    FocusCurve,
    FocusDirection,
    FocusRampIn,
    FocusRampOut,
}

enum class FocusCurveMode {
    Linear,
    SquareRoot,
    Logarithmic,
}

enum class GalaxyDeviceVariant {
    S26,
    S26Plus,
    S26Ultra,
}

enum class CameraModeId(
    val label: String,
    val captureType: CameraCaptureType,
) {
    Photo("Photo", CameraCaptureType.Photo),
    Portrait("Portrait", CameraCaptureType.Photo),
    Pro("Pro", CameraCaptureType.Photo),
    ExpertRaw("Expert RAW", CameraCaptureType.Photo),
    Video("Video", CameraCaptureType.Video),
    ProVideo("Pro Video", CameraCaptureType.Video),
    Night("Night", CameraCaptureType.Photo),
    Burst("Burst", CameraCaptureType.Photo),
    Panorama("Panorama", CameraCaptureType.Photo),
    SingleTake("Single Take", CameraCaptureType.Hybrid),
    Food("Food", CameraCaptureType.Photo),
    SlowMotion("Slow Motion", CameraCaptureType.Video),
    SuperSlowMotion("Super Slow Motion", CameraCaptureType.Video),
    Hyperlapse("Hyperlapse", CameraCaptureType.Video),
    DualRecording("Dual Record", CameraCaptureType.Video),
    PortraitVideo("Portrait Video", CameraCaptureType.Video),
    DirectorsView("Director's View", CameraCaptureType.Video),
    Macro("Macro", CameraCaptureType.Photo),
    NightVideo("Night Video", CameraCaptureType.Video),
    BixbyVision("Bixby Vision", CameraCaptureType.Photo),
    ArZone("AR Zone", CameraCaptureType.Photo),
}

enum class CameraControl {
    Photo,
    Video,
    Zoom,
    Iso,
    ShutterSpeed,
    WhiteBalance,
    ManualFocus,
    ExposureCompensation,
    Lens,
    Grid,
    FocusPeaking,
}

data class PermissionsState(
    val bluetooth: Boolean = false,
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val foregroundService: Boolean = false,
    val notifications: Boolean = false,
) {
    fun with(permission: PermissionKind, granted: Boolean): PermissionsState = when (permission) {
        PermissionKind.Bluetooth -> copy(bluetooth = granted)
        PermissionKind.Camera -> copy(camera = granted)
        PermissionKind.Microphone -> copy(microphone = granted)
        PermissionKind.Overlay -> copy(overlay = granted)
        PermissionKind.Accessibility -> copy(accessibility = granted)
        PermissionKind.ForegroundService -> copy(foregroundService = granted)
        PermissionKind.Notifications -> copy(notifications = granted)
    }

    fun canUsePhoneControl(): Boolean = accessibility

    fun canUseOverlayCursor(): Boolean = accessibility && overlay
}

data class HousingState(
    val advertisingName: String = HousingBleProfile.advertisingName,
    val trustedIdentity: String? = null,
    val connected: Boolean = false,
    val inputEnabled: Boolean = false,
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val softwareVersion: String? = null,
    val manufacturerName: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val lastButton: HousingButtonEvent? = null,
    val lastRawButton: UByte? = null,
)

/** Ordered exactly as the paused-recording chooser is painted and traversed. */
enum class RecordingPausedAction {
    Preview,
    Resume,
    Stop,
    Delete,
}

/** Ordered exactly as the completed-panorama chooser is painted and traversed. */
enum class PanoramaReviewAction {
    Save,
    Delete,
}

enum class RecordingSaveConfirmationAction {
    Back,
    Confirm,
}

data class RecordingSaveLocation(
    val name: String,
    val relativePath: String,
    /** MediaStore URI for the album's newest item. Empty for a newly-created empty album. */
    val coverContentUri: String = "",
    val mediaCount: Int = 0,
    val coverIsVideo: Boolean = false,
) {
    companion object {
        val Default = RecordingSaveLocation(
            name = "Camera",
            relativePath = "DCIM/Camera/",
        )
    }
}

data class CameraState(
    val recording: Boolean = false,
    /** True while the cumulative recording is finalising/reviewable and its action chooser owns input. */
    val recordingPaused: Boolean = false,
    /** The selected action in the paused recording chooser. */
    val recordingPausedAction: RecordingPausedAction = RecordingPausedAction.Resume,
    /** True while all footage recorded in this session is shown over the live camera preview. */
    val recordingPreviewVisible: Boolean = false,
    /** True while the save-location button above the paused action rail owns focus. */
    val recordingLocationFocused: Boolean = false,
    /** True while the housing-navigable destination list is open. */
    val recordingLocationChooserVisible: Boolean = false,
    /** True after an album is activated and its Back/Confirm decision owns housing input. */
    val recordingSaveConfirmationVisible: Boolean = false,
    val recordingSaveConfirmationAction: RecordingSaveConfirmationAction =
        RecordingSaveConfirmationAction.Confirm,
    val recordingSaveLocation: RecordingSaveLocation = RecordingSaveLocation.Default,
    val recordingSaveLocations: List<RecordingSaveLocation> = listOf(RecordingSaveLocation.Default),
    val recordingSaveLocationIndex: Int = 0,
    /** True from the stop boundary until the staged panorama is saved or deleted. */
    val panoramaReviewAvailable: Boolean = false,
    /** False briefly after review opens so the stop gesture cannot also activate Save. */
    val panoramaReviewInputArmed: Boolean = false,
    /** The selected action in the completed-panorama chooser. */
    val panoramaReviewAction: PanoramaReviewAction = PanoramaReviewAction.Save,
    /**
     * What this phone's camera hardware actually offers, probed at bind time by the platform
     * layer. Null until the probe reports (or forever, in the simulator) — the static catalog
     * stands in. The catalog clips its option ladders to these ranges and drops controls the
     * hardware cannot honour, per the product rule that a dead control must never render live.
     */
    val capabilities: CameraCapabilities? = null,
    val zoomFactor: Double = 1.0,
    val capabilityTier: String = "Samsung Galaxy S26 Camera Shell",
    val deviceVariant: GalaxyDeviceVariant = GalaxyDeviceVariant.S26Ultra,
    val activeMode: CameraModeId = CameraModeId.Photo,
    val focusedZone: CameraUiZone = CameraUiZone.LiveView,
    val modeRailReturnZone: CameraUiZone = CameraUiZone.LiveView,
    val railLevel: CameraRailLevel = CameraRailLevel.Primary,
    val highlightedPrimaryIndex: Int = 0,
    val highlightedSecondaryIndex: Int = 0,
    val settingsCursor: Int = 0,
    /** Selected row in the vertical Options panel. Independent of [settingsCursor]. */
    val optionsMenuCursor: Int = 0,
    val settingsEditing: Boolean = false,
    val sliderEditTarget: SliderEditTarget = SliderEditTarget.Value,
    val settingValues: Map<String, String> = CameraCatalog.defaultSettingValues,
    val sliderSensitivities: Map<String, SliderSensitivity> = CameraCatalog.defaultSliderSensitivities,
    val focusCurveModes: Map<String, FocusCurveMode> = CameraCatalog.defaultFocusCurveModes,
    val detectedLenses: List<String> = emptyList(),
    val supportedControls: List<CameraControl> = listOf(
        CameraControl.Photo,
        CameraControl.Video,
        CameraControl.Zoom,
    ),
    val selectedControlIndex: Int = 0,
    val showMoreSettings: Boolean = false,
    val captureCounter: Int = 0,
    /**
     * When focus last took a user input, for the AF gate: entering AF from either rail
     * requires the wheel to STOP first — a genuine pause — and then travel again.
     */
    val lastFocusInputAtMs: Long = 0L,
    /**
     * Consecutive presses pushed against a focus rail since the deliberate pause that armed
     * them. Zeroed the moment focus moves normally, so it can only ever count a sustained,
     * intentional push and never accumulate across a dive.
     */
    val focusRailPresses: Int = 0,
    /**
     * What auto-exposure and auto-white-balance are choosing RIGHT NOW, observed off the capture
     * pipe at ~2 Hz. Platform telemetry merged outside the critical path, exactly like
     * [AppState.phoneBatteryPercent]: it can never change a mode or issue a command. Two uses,
     * both copied from the native camera: the HUD prints the live value beside "Auto" the way the
     * native chips do, and the first wheel movement out of Auto seeds manual control from the
     * metered value rather than from a fixed default. Never persisted.
     */
    val meteredExposure: MeteredExposure = MeteredExposure(),
)

/**
 * Live values chosen by AE / AWB, read from the capture results. Any field may be null when the
 * pipe has not reported yet or the device does not expose it (the kelvin and EV meters ride
 * Samsung vendor result tags that may not resolve on other hardware).
 */
data class MeteredExposure(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val wbKelvin: Int? = null,
    /** AU's signed CIE 1960 tint distance. Null while the OEM or a manual rung owns WB. */
    val wbTintDuv: Double? = null,
    /** Confidence in AU's physical white-point estimate, in [0, 1]. */
    val wbConfidence: Double? = null,
    /** Metered EV deviation in tenths of a stop — the read-only meter the native app shows while both ISO and shutter are manual. */
    val evTenths: Int? = null,
)

val CameraState.selectedControl: CameraControl
    get() = if (activeMode.captureType == CameraCaptureType.Video) CameraControl.Video else CameraControl.Photo

data class PhoneControlState(
    val cursorSpeedProfile: CursorSpeedProfile = CursorSpeedProfile.Normal,
    val smartTargetEnabled: Boolean = true,
    val smartTargetAvailable: Boolean = true,
)

/**
 * How much evidence stands behind a passed seal check.
 *
 * Published practice varies by a factor of six — this housing's maker says "more than 5
 * minutes", Weefine asks 30 minutes for their own smartphone housing, and the common
 * vacuum systems land near 10. Rather than pick one and call the rest wrong, the check keeps
 * running and the confidence keeps climbing, so the diver can read how much evidence exists
 * and decide when to get in the water.
 */
enum class SealConfidence {
    Monitoring,
    Provisional,
    ManufacturerMinimum,
    Recommended,
    Conservative,
}

data class SafetyState(
    val sealState: SealState = SealState.Unknown,
    val sealConfidence: SealConfidence = SealConfidence.Monitoring,
    val coverOpen: Boolean? = null,
    val barometricPressureKpa: Double? = null,
    /**
     * Atmospheric pressure captured while the suction cover was open.
     *
     * With the cover open the shell is vented, so the internal barometric sensor is reading
     * true atmosphere — the only moment that reading can serve as a depth reference. Once a
     * vacuum is pulled the live reading is ~20 kPa below ambient, and using it for depth would
     * put the diver two metres under while still on the boat.
     */
    val surfaceAmbientKpa: Double? = null,
    val waterPressureKpa: Double? = null,
    val waterTemperatureC: Double? = null,
    val baselinePressureKpa: Double? = null,
    val stabilizationSamples: List<Double> = emptyList(),
    val motorStartedAtEpochMs: Long? = null,
    val leakMonitoringStartedAtEpochMs: Long? = null,
    val leakMonitoringElapsedMs: Long = 0L,
    /** Set when the diver dismisses the prompt; cleared whenever the cover opens again. */
    val checkDismissed: Boolean = false,
    /** Lowest pressure seen this pump run, for stall detection. Meaningful only while Vacuuming. */
    val vacuumBestKpa: Double? = null,
    /** When the pump last made real progress. Meaningful only while Vacuuming. */
    val vacuumLastProgressAtMs: Long? = null,
    /** Rolling rate-watch checkpoint (time and reading). Meaningful only while Vacuuming. */
    val vacuumRateWindowStartMs: Long? = null,
    val vacuumRateWindowStartKpa: Double? = null,
    /**
     * Adoption candidate awaiting its confirming sample. A held vacuum is FLAT; a shell mid-way
     * through venting is moving — requiring two agreeing readings is what stops a draining
     * shell's transient from being adopted as a fresh vacuum on its way to ambient.
     */
    val pendingAdoptionKpa: Double? = null,
    /**
     * A monitoring sample quarantined by the glitch gate: it jumped implausibly from the last
     * accepted reading and waits for a second reading to agree before it can mean anything.
     */
    val pendingOutlierKpa: Double? = null,
    /**
     * The pressure a previous session recorded after its hold passed the hard verify, primed at
     * launch from persistence. Consumed by the next adoption decision: a live reading that still
     * matches it means the seal held strong across the reboot and deserves its earned trust back.
     */
    val verifiedVacuumKpa: Double? = null,
    /** The confidence tier the persisted hold had earned; restored verbatim on a boot-time match. */
    val verifiedVacuumConfidence: SealConfidence? = null,
    /**
     * Epoch millis the persisted hold originally began, so a restart restores the TRUE clock
     * instead of backdating to the tier floor — a 25-minute hold must not restart as a
     * 10-minute one and then wait 20 more real minutes for its next tier.
     */
    val verifiedVacuumStartedAtEpochMs: Long? = null,
    /** Epoch millis the record was last written — the last moment the seal was known good. */
    val verifiedVacuumRecordedAtEpochMs: Long? = null,
    /**
     * Set when a boot record's vacuum turned out lost or degraded on restart: minutes between
     * the last known-good write and the reading that disproved it. Drives the
     * "SEAL FAILED (TIME)" banner; null for every other failure.
     */
    val restartFailAgoMinutes: Long? = null,
    /**
     * True when a vacuum was adopted rather than pumped, until the diver answers the reminder.
     * An adopted vacuum arrives with no evidence the blue cap was ever screwed back down, so the
     * close-the-cap banner shows once on top of the monitoring that is already running.
     */
    val capCloseReminder: Boolean = false,
    /**
     * True while the current hold began as an ADOPTED vacuum — one the housing was already
     * holding before this app was watching (fresh adoption or a verified reboot restore). A full
     * vent of an adopted hold is always deliberate whatever this app's clock says, because the
     * housing proved the seal long before the clock started. A hold pumped by this app never
     * sets this, however its cap banner was raised.
     */
    val adoptedHold: Boolean = false,
    /**
     * True after a deliberate vacuum release at the surface, until the diver answers it. Drives
     * the "VACUUM RELEASED" banner — cap already off, re-pump or open the housing — instead of
     * the remove-the-cap doorway, which would be asking them to do what they just did.
     */
    val vacuumReleasedPrompt: Boolean = false,
    val warning: String? = null,
)

enum class GalleryTab {
    Photos,
    Videos,
    Folders,
}

const val GALLERY_ALBUM_COLUMNS = 4
const val GALLERY_MEDIA_COLUMNS = 6

enum class GalleryViewMode {
    Browser,
    AlbumActions,
    MediaActions,
    Preview,
    Options,
    Move,
    Rename,
    ConfirmDelete,
    ConfirmFolderDelete,
    CreateFolder,
}

enum class GalleryPreviewAction {
    Delete,
    Back,
    Options,
    Previous,
    PlayPause,
    Next,
    Details,
}

enum class GalleryBrowserAction {
    Back,
    CreateAlbum,
}

fun galleryBrowserActions(showingAlbums: Boolean): List<GalleryBrowserAction> = buildList {
    add(GalleryBrowserAction.Back)
    if (showingAlbums) add(GalleryBrowserAction.CreateAlbum)
}

enum class GalleryAlbumAction {
    Back,
    Preview,
    Delete,
}

enum class GalleryMediaAction {
    Back,
    Preview,
    Delete,
}

/**
 * Single preview rail. Videos center Play/Pause between Previous and Next and
 * place Delete at the far right. Photos omit Play so Previous and Next remain
 * adjacent, but keep the same Details → Delete ending.
 */
fun galleryPreviewRailActions(isVideo: Boolean): List<GalleryPreviewAction> = buildList {
    if (isVideo) {
        add(GalleryPreviewAction.Back)
        add(GalleryPreviewAction.Options)
        add(GalleryPreviewAction.Previous)
        add(GalleryPreviewAction.PlayPause)
        add(GalleryPreviewAction.Next)
        add(GalleryPreviewAction.Details)
        add(GalleryPreviewAction.Delete)
    } else {
        add(GalleryPreviewAction.Back)
        add(GalleryPreviewAction.Options)
        add(GalleryPreviewAction.Previous)
        add(GalleryPreviewAction.Next)
        add(GalleryPreviewAction.Details)
        add(GalleryPreviewAction.Delete)
    }
}

enum class GalleryMutation {
    Delete,
    Move,
    Rename,
    CreateAlbum,
}

data class GalleryItem(
    val id: Long,
    val name: String,
    val path: String,
    /** Stable MediaStore URI. Album cards carry their cover item's URI here. */
    val contentUri: String = "",
    /** MediaStore BUCKET_ID. Album cards store the same value in [path]. */
    val albumId: String? = null,
    /** BUCKET_DISPLAY_NAME carried by media rows and shown by their album card. */
    val folderDisplayName: String = "",
    /** MediaStore RELATIVE_PATH, used as the safe destination for a move operation. */
    val relativePath: String? = null,
    val isVideo: Boolean = false,
    val isFolder: Boolean = false,
    /** Non-zero only for album cards. */
    val mediaCount: Int = 0,
    val sizeBytes: Long = 0,
    val dateAdded: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

data class GalleryState(
    val tab: GalleryTab = GalleryTab.Folders,
    val viewMode: GalleryViewMode = GalleryViewMode.Browser,
    val items: List<GalleryItem> = emptyList(),
    val selectedIndex: Int = 0,
    /** Null shows the album grid; otherwise this is the selected MediaStore BUCKET_ID. */
    val currentFolder: String? = null,
    val currentFolderName: String? = null,
    val folderName: String = "",
    val previewExifLines: List<String> = emptyList(),
    /** Metadata is opt-in so the recorded image/video owns the preview screen by default. */
    val detailsVisible: Boolean = false,
    /** Housing-selected metadata row; the UI keeps this row visible in the scrollable panel. */
    val detailsLineIndex: Int = 0,
    /** Selected preview action; photo rails omit PlayPause while video rails include it. */
    val previewAction: GalleryPreviewAction = GalleryPreviewAction.PlayPause,
    val videoPlaying: Boolean = false,
    /** Compatibility flag mirrored while [browserAction] is Back. */
    val browserBackFocused: Boolean = false,
    val browserAction: GalleryBrowserAction? = null,
    /** Preview/Delete/Back selection shown after activating an album card. */
    val albumAction: GalleryAlbumAction = GalleryAlbumAction.Preview,
    /** Back/Preview/Delete selection shown after activating a media cell. */
    val mediaAction: GalleryMediaAction = GalleryMediaAction.Preview,
    /** Move/Rename/Cancel selection inside the Options sheet. */
    val optionIndex: Int = 0,
    val moveTargets: List<GalleryItem> = emptyList(),
    val moveTargetIndex: Int = 0,
    val renameDraft: String = "",
    val pendingMutation: GalleryMutation? = null,
    val operationMessage: String? = null,
    val confirmationReturnToPreview: Boolean = false,
    val confirmationReturnToMediaActions: Boolean = false,
    val folderDeleteReturnToActions: Boolean = false,
    val confirmButtonIndex: Int = 1, // 0 = Delete/Confirm, 1 = Cancel (default to Cancel for safety)
)

data class AppState(
    val mode: AppMode = AppMode.CameraLive,
    val bleConnectionState: BleConnectionState = BleConnectionState.Idle,
    val housing: HousingState = HousingState(),
    val camera: CameraState = CameraState(),
    val phoneControl: PhoneControlState = PhoneControlState(),
    val safety: SafetyState = SafetyState(),
    val gallery: GalleryState = GalleryState(),
    /** Focused action in the bottom diagnostics navigation row. */
    val diagnosticsAction: DiagnosticsAction = DiagnosticsAction.BackToCamera,
    val permissions: PermissionsState = PermissionsState(),
    /** Phone battery 0–100, or null when not yet read. Null must never render as 0. */
    val phoneBatteryPercent: Int? = null,
    val controlsLocked: Boolean = false,
    val lastWarning: String? = null,
)

sealed interface HousingButtonEvent {
    data object Up : HousingButtonEvent
    data object Down : HousingButtonEvent
    data object Left : HousingButtonEvent
    data object Right : HousingButtonEvent
    data object Ok : HousingButtonEvent
    data object Shutter : HousingButtonEvent
    data object ZoomIn : HousingButtonEvent
    data object ZoomOut : HousingButtonEvent
    data object BackOrSafety : HousingButtonEvent
    data class Unknown(val rawValue: UByte) : HousingButtonEvent
}

sealed interface ControlCommand

sealed interface CameraCommand : ControlCommand {
    data object CapturePhoto : CameraCommand
    /** Runtime-to-reducer event emitted at the stop boundary to open panorama review. */
    data object PanoramaReviewReady : CameraCommand
    /** Arms review choices after the stop gesture has completely drained. */
    data object ArmPanoramaReviewInput : CameraCommand
    data object SavePanorama : CameraCommand
    data object DeletePanorama : CameraCommand
    data object ToggleVideoRecording : CameraCommand
    data object StartVideoRecording : CameraCommand
    data object StopVideoRecording : CameraCommand
    data object PauseVideoRecording : CameraCommand
    data object ResumeVideoRecording : CameraCommand
    data object PreviewVideoRecording : CameraCommand
    data object DeleteVideoRecording : CameraCommand
    data object OpenRecordingSaveLocationChooser : CameraCommand
    data class HighlightRecordingSaveLocation(val index: Int) : CameraCommand
    data class OpenRecordingSaveLocationConfirmation(val index: Int) : CameraCommand
    data class SelectRecordingSaveLocation(val index: Int) : CameraCommand
    data class LoadRecordingSaveLocations(val locations: List<RecordingSaveLocation>) : CameraCommand
    data object NavigateUp : CameraCommand
    data object NavigateDown : CameraCommand
    data object NavigateLeft : CameraCommand
    data object NavigateRight : CameraCommand
    data object Confirm : CameraCommand
    data object Back : CameraCommand
    /** Opens the camera mode rail directly from the touch UI. */
    data object OpenModeRail : CameraCommand
    /** Touch-accessible equivalent of highlighting and confirming a primary mode-rail row. */
    data class ActivateModeRailEntry(val index: Int) : CameraCommand
    /** Touch-accessible equivalent of activating the far-left Options tile. */
    data object ToggleOptionsMenu : CameraCommand
    /** Selects a vertical Options row without disturbing the horizontal settings cursor. */
    data class SelectOptionsItem(val index: Int) : CameraCommand
    /** Selects and advances a vertical Options row; used by direct touch interaction. */
    data class AdjustOptionsItem(val index: Int, val step: Int) : CameraCommand
    data object ZoomIn : CameraCommand
    data object ZoomOut : CameraCommand
    data class SetZoom(val value: Double) : CameraCommand
    data class SetIso(val value: Int) : CameraCommand
    data class SetShutterSpeedNs(val value: Long) : CameraCommand
    data class SetManualFocus(val value: Double) : CameraCommand
    data class SetWhiteBalanceKelvin(val value: Int) : CameraCommand
    data class SetExposureCompensation(val value: Double) : CameraCommand
    data class SwitchLens(val lensId: String) : CameraCommand
    data class SetFlashMode(val mode: String) : CameraCommand
    data class SetPhotoResolution(val value: String) : CameraCommand
    data class SetCaptureFormat(val value: String) : CameraCommand
    data class SetHdrLogMode(val value: String) : CameraCommand
    data class SetFilter(val value: String) : CameraCommand
    /** Runtime acknowledgement that a requested camera operation was rejected or failed. */
    data class ReportRuntimeFailure(val message: String) : CameraCommand
    /** One single tick of a slider setting — the ramp engine's unit of motion. */
    data class NudgeSetting(val settingId: String, val step: Int) : CameraCommand
    data class UpdateDetectedLenses(val lenses: List<String>) : CameraCommand
    data class UpdateCameraCapabilities(val capabilities: CameraCapabilities) : CameraCommand
    data object OpenGallery : CameraCommand
    data object ToggleGrid : CameraCommand
    data object ToggleFocusPeaking : CameraCommand
    data object RestartCamera : CameraCommand
}

sealed interface PhoneControlCommand : ControlCommand {
    data object MoveCursorUp : PhoneControlCommand
    data object MoveCursorDown : PhoneControlCommand
    data object MoveCursorLeft : PhoneControlCommand
    data object MoveCursorRight : PhoneControlCommand
    data class MoveTarget(val direction: Direction) : PhoneControlCommand
    data object Click : PhoneControlCommand
    data object LongClick : PhoneControlCommand
    data object ScrollUp : PhoneControlCommand
    data object ScrollDown : PhoneControlCommand
    data object Back : PhoneControlCommand
    data object Home : PhoneControlCommand
    data object Recents : PhoneControlCommand
    data object NextTarget : PhoneControlCommand
    data object PreviousTarget : PhoneControlCommand
    data object SwitchCursorMode : PhoneControlCommand
    data object IncreaseCursorSpeed : PhoneControlCommand
    data object DecreaseCursorSpeed : PhoneControlCommand
}

sealed interface SafetyCommand : ControlCommand {
    data object StartVacuumCheck : SafetyCommand
    data object CancelVacuumCheck : SafetyCommand
    /** Silences the pre-dive prompt without disabling the feature; cleared when the cover opens. */
    data object DismissSealCheck : SafetyCommand
    /** Ends leak monitoring early, keeping whatever confidence tier was reached. */
    data object SkipToResult : SafetyCommand
    data object OpenSolenoid : SafetyCommand
    data object CloseSolenoid : SafetyCommand
    data object StartVacuumMotor : SafetyCommand
    data object StopVacuumMotor : SafetyCommand
    data object AcknowledgeWarning : SafetyCommand
    data object ResetSealState : SafetyCommand
}

sealed interface HousingCommand : ControlCommand {
    data object TriggerFlash : HousingCommand
    data class SetVacuumMotor(val enabled: Boolean) : HousingCommand
    data class SetSolenoidValve(val open: Boolean) : HousingCommand
    data class SendIrFlashlightCommand(val command: IrFlashlightCommand) : HousingCommand
    data object RequestBatteryRead : HousingCommand
    data object RequestDeviceInfo : HousingCommand
    data object Disconnect : HousingCommand
    data object Reconnect : HousingCommand
}

enum class IrFlashlightCommand(val wireValue: UByte) {
    IncreaseBrightness(0x01u),
    SwitchLightType(0x02u),
    DecreaseBrightness(0x03u),
    Sleep(0x04u),
    Wake(0x05u),
    FocusOrFlash(0x06u),
}

sealed interface SystemCommand : ControlCommand {
    data object SwitchToCameraMode : SystemCommand
    data object SwitchToTransparentPhoneMode : SystemCommand
    data object SwitchToSafetyMode : SystemCommand
    data object SwitchToDiagnosticsMode : SystemCommand
    data object ExportDiagnostics : SystemCommand
    data object LockControls : SystemCommand
    data object UnlockControls : SystemCommand
}

sealed interface DiagnosticsCommand : ControlCommand {
    data object NavigatePrevious : DiagnosticsCommand
    data object NavigateNext : DiagnosticsCommand
    data object Confirm : DiagnosticsCommand
    data object Back : DiagnosticsCommand
    /** Touch activation also updates the shared focus state before executing the action. */
    data class Activate(val action: DiagnosticsAction) : DiagnosticsCommand
}

sealed interface GalleryCommand : ControlCommand {
    data object NavigateUp : GalleryCommand
    data object NavigateDown : GalleryCommand
    data object NavigateLeft : GalleryCommand
    data object NavigateRight : GalleryCommand
    data object Confirm : GalleryCommand
    data object Back : GalleryCommand
    data object InitiateDelete : GalleryCommand
    data object CreateFolder : GalleryCommand
    data object DeleteFolder : GalleryCommand
    data class ActivateBrowserAction(val action: GalleryBrowserAction) : GalleryCommand
    data class ActivateAlbumAction(val action: GalleryAlbumAction) : GalleryCommand
    data class ActivateMediaAction(val action: GalleryMediaAction) : GalleryCommand
    data class OpenItem(val index: Int) : GalleryCommand
    data class ActivatePreviewAction(val action: GalleryPreviewAction) : GalleryCommand
    data class SelectOption(val index: Int) : GalleryCommand
    data class SelectMoveTarget(val index: Int) : GalleryCommand
    data class SelectConfirmation(val index: Int) : GalleryCommand
    data class SetRenameDraft(val value: String) : GalleryCommand
    data class SetFolderName(val value: String) : GalleryCommand
    data class LoadItems(val items: List<GalleryItem>) : GalleryCommand
    data class LoadMoveTargets(val items: List<GalleryItem>) : GalleryCommand
    data class SetExifLines(val lines: List<String>) : GalleryCommand
    data class OperationSucceeded(val message: String) : GalleryCommand
    data class OperationFailed(val message: String) : GalleryCommand
}

sealed interface PlatformEffect {
    data class ExecuteCamera(val command: CameraCommand) : PlatformEffect
    data class ExecutePhoneControl(val command: PhoneControlCommand) : PlatformEffect
    data class ExecuteHousing(val command: HousingCommand) : PlatformEffect
    data class EmitAlert(val priority: AlertPriority, val message: String) : PlatformEffect
    data class ScheduleReconnect(val attempt: Int, val delay: Duration) : PlatformEffect
    data object ExportDiagnostics : PlatformEffect
    /** Store the current optical-axis compass bearing as the target heading. */
    data object TrackCurrentHeading : PlatformEffect

    /**
     * Asks the platform to feed [steps] further [CameraCommand.NudgeSetting] ticks back into
     * the core, one every [intervalMs]. This is how a high-sensitivity wheel click traverses
     * EVERY 0.01 focus step visibly instead of jumping: the state itself walks the ladder.
     */
    data class RampSetting(
        val settingId: String,
        val steps: Int,
        val step: Int,
        val intervalMs: Long,
        /** Drain ceiling per interval — sensitivity's second job: the sweep RATE. */
        val maxTicksPerInterval: Int = 3,
        /** Wheel-silence window after which undrained ticks are discarded (stop-on-stop). */
        val stopTimeoutMs: Long = 200L,
        /**
         * Time constant for the OUTSTANDING distance, not a duration.
         *
         * The drain re-derives rate = remaining / spanMs at each wheel event. Pacing from the
         * newest detent's credit alone under-delivers permanently — injection exceeds drain
         * every detent and the lens falls progressively further behind on a fast spin. Pacing
         * from the debt self-corrects: the debt grows until drain matches injection exactly.
         */
        val spanMs: Long = 250L,
    ) : PlatformEffect
    data object LoadGalleryItems : PlatformEffect
    data object LoadGalleryMoveTargets : PlatformEffect
    data object LoadRecordingSaveLocations : PlatformEffect
    data class DeleteGalleryItem(val item: GalleryItem) : PlatformEffect
    data class MoveGalleryItem(val item: GalleryItem, val targetAlbum: GalleryItem) : PlatformEffect
    data class RenameGalleryItem(val item: GalleryItem, val newName: String) : PlatformEffect
    data class CreateGalleryFolder(val name: String) : PlatformEffect
    data class DeleteGalleryFolder(val path: String) : PlatformEffect
    data class DeleteGalleryAlbum(val album: GalleryItem) : PlatformEffect
    data class LoadExifData(val item: GalleryItem) : PlatformEffect
}

sealed interface SensorUpdate {
    data class CoverState(val open: Boolean) : SensorUpdate
    data class BarometricPressure(val kpa: Double) : SensorUpdate
    data class WaterPressure(val kpa: Double) : SensorUpdate
    data class WaterTemperature(val celsius: Double) : SensorUpdate
}

sealed interface DeviceInfoUpdate {
    data class ManufacturerName(val value: String) : DeviceInfoUpdate
    data class ModelNumber(val value: String) : DeviceInfoUpdate
    data class SerialNumber(val value: String) : DeviceInfoUpdate
    data class FirmwareRevision(val value: String) : DeviceInfoUpdate
    data class HardwareRevision(val value: String) : DeviceInfoUpdate
    data class SoftwareRevision(val value: String) : DeviceInfoUpdate
}

data class Reduction(
    val state: AppState,
    val effects: List<PlatformEffect> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class ProcessingOutcome(
    val state: AppState,
    val effects: List<PlatformEffect> = emptyList(),
    val notes: List<String> = emptyList(),
    val exportedFiles: Map<String, String> = emptyMap(),
)

/**
 * Platform-neutral description of what the physical camera can do. Android fills this from
 * Camera2 [android.hardware.camera2.CameraCharacteristics]; an iOS build would fill it from
 * AVFoundation. The pure-Kotlin core never knows which.
 */
data class CameraCapabilities(
    val isoMin: Int? = null,
    val isoMax: Int? = null,
    val exposureMinNs: Long? = null,
    val exposureMaxNs: Long? = null,
    val evMin: Double? = null,
    val evMax: Double? = null,
    val manualFocusSupported: Boolean? = null,
    val zoomMaxRatio: Double? = null,
    /** Empty until probed; otherwise only FPS values the active recording pipeline can schedule. */
    val availableVideoFrameRates: List<Int> = emptyList(),
    /** Empty until probed; exact capture-pipeline quality labels for the active lens. */
    val availableVideoResolutions: List<String> = emptyList(),
    /**
     * Recorder-compatible FPS values per quality label. This is deliberately a pair map rather
     * than two independent lists: a lens may offer 240 fps at HD but only 60 fps at UHD.
     */
    val videoFrameRatesByResolution: Map<String, List<Int>> = emptyMap(),
    val videoStabilizationSupported: Boolean? = null,
    val ultraHdrJpegSupported: Boolean? = null,
    /** True only when CameraX can write a sensor RAW frame as an Adobe DNG. */
    val rawCaptureSupported: Boolean? = null,
    /** True only when the active camera can deliver DNG and JPEG from the same exposure. */
    val rawJpegCaptureSupported: Boolean? = null,
)

sealed interface BottomBarItem {
    data object ModesButton : BottomBarItem
    data class Setting(val spec: CameraSettingSpec) : BottomBarItem
    data class LensShortcut(val value: String) : BottomBarItem
    data object GalleryShortcut : BottomBarItem
    data object MoreSettings : BottomBarItem
}
