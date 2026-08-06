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

data class CameraState(
    val recording: Boolean = false,
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

enum class GalleryViewMode {
    Browser,
    Preview,
    ConfirmDelete,
    ConfirmFolderDelete,
    CreateFolder,
}

data class GalleryItem(
    val id: Long,
    val name: String,
    val path: String,
    val isVideo: Boolean = false,
    val isFolder: Boolean = false,
    val sizeBytes: Long = 0,
    val dateAdded: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

data class GalleryState(
    val tab: GalleryTab = GalleryTab.Photos,
    val viewMode: GalleryViewMode = GalleryViewMode.Browser,
    val items: List<GalleryItem> = emptyList(),
    val selectedIndex: Int = 0,
    val currentFolder: String? = null,
    val folderName: String = "",
    val previewExifLines: List<String> = emptyList(),
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
    data object ToggleVideoRecording : CameraCommand
    data object StartVideoRecording : CameraCommand
    data object StopVideoRecording : CameraCommand
    data object NavigateUp : CameraCommand
    data object NavigateDown : CameraCommand
    data object NavigateLeft : CameraCommand
    data object NavigateRight : CameraCommand
    data object Confirm : CameraCommand
    data object Back : CameraCommand
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
    data class UpdateDetectedLenses(val lenses: List<String>) : CameraCommand
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
    data class LoadItems(val items: List<GalleryItem>) : GalleryCommand
    data class SetExifLines(val lines: List<String>) : GalleryCommand
}

sealed interface PlatformEffect {
    data class ExecuteCamera(val command: CameraCommand) : PlatformEffect
    data class ExecutePhoneControl(val command: PhoneControlCommand) : PlatformEffect
    data class ExecuteHousing(val command: HousingCommand) : PlatformEffect
    data class EmitAlert(val priority: AlertPriority, val message: String) : PlatformEffect
    data class ScheduleReconnect(val attempt: Int, val delay: Duration) : PlatformEffect
    data object ExportDiagnostics : PlatformEffect
    data object LoadGalleryItems : PlatformEffect
    data class DeleteGalleryItem(val item: GalleryItem) : PlatformEffect
    data class CreateGalleryFolder(val name: String) : PlatformEffect
    data class DeleteGalleryFolder(val path: String) : PlatformEffect
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

sealed interface BottomBarItem {
    data object ModesButton : BottomBarItem
    data class Setting(val spec: CameraSettingSpec) : BottomBarItem
    data class LensShortcut(val value: String) : BottomBarItem
    data object GalleryShortcut : BottomBarItem
    data object MoreSettings : BottomBarItem
}

