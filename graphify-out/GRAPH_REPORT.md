# Graph Report - Mobile DiveControl  (2026-11-18)

## Corpus Check
- 0 files · ~0 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2282 nodes · 5040 edges · 126 communities (87 shown, 39 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 275 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- CameraCatalog
- GalleryRepository
- AndroidBleTransport
- TransportOutcome
- LinearRgb
- ControlCore
- CameraShellScreen.kt
- ControlCoreTest
- SafetyState
- ScaleLadderMigrationTest
- PointingGestureRecognizer.kt
- PermissionKind
- FocusPeakingSurfaceProcessor
- CameraRuntimeController
- HeadingMath
- CameraState
- .reduce
- RecordingSaveLocation
- CameraCommand
- DiveViewModel
- .decodeNotification
- SimulatedBleTransport
- HousingLink
- .execute
- WFH07 Bluetooth Communication Protocol A4.0
- HousingIdentityVerifierTest
- LoopingVideoTextureView
- .readyState
- GalaxyDeviceVariant
- CameraCatalogTest
- ControlReducer
- AppState
- ButtonTutorialScreen.kt
- HousingUuidResolverTest
- GalleryState
- SafetyStateMachine
- HousingCharacteristic
- GalleryCommand
- HousingButtonEvent
- .detectDeviceCapabilitiesViaCameraManager
- DiagnosticsStore
- SealCheckIndicator.kt
- .matchCharacteristic
- .applySessionState
- Reduction
- PlatformEffect
- HousingCommand
- CameraModeId
- .submitNativeRepeatingRequest
- DepthGauge.kt
- GattQueue
- PhoneControlCommand
- SealConfidence
- DiveControl_HLG_to_Samsung_Log.dctl
- MainActivity
- Water Pressure Little-Endian Encoding and kPa Conversion
- SealState
- test.ps1
- BleConnectionState
- Customer Trust Contract
- BlePermissions
- DebugSimulationPanel
- ValueLadderGearingTest
- SafetyScreen.kt
- UnderwaterFrameAnalyzerTest.kt
- CameraControl
- SamsungLogProfile
- DiveViewModel.kt
- VideoDynamicRangePolicy
- CameraHudOverlay.kt
- AppMode
- SystemCommand
- GalleryScreen.kt
- SafetyStateMachine.kt
- HeadingStabilizer
- ProtocolParserTest
- High-Risk Housing Command Policy
- PhoneBatteryMonitor
- VacuumStore
- Native Control Critical Path
- Regression Acceptance Focus
- SamsungLogProfileTest
- Maximum-information capture contract
- VideoDynamicRangePolicyTest
- Contracts.kt
- SliderEditTarget
- AutofocusHoldPolicyTest
- Rig
- 100 Mbit/s video target
- BatteryIndicator.kt
- DiveControlScreen.kt
- MobileDiveControl Documentation Index
- Stable Internal Command Contract
- DeviceInfoUpdate
- IrFlashlightCommand
- jsonObject
- DIVE IT BLE Protocol Reference
- CaptureGuideGeometry.kt
- BLE Housing Hardware Interface
- ControlCommand
- CircularScaleTest
- HousingCommandEncoderTest
- Corrected Nordic nRF5 Base UUID
- CaptureGuideGeometryTest
- AlertPriority
- Direction
- GalleryMutation
- RecordingClock.kt
- Accessibility Behavior Algorithm
- CameraRuntimeController.kt
- CameraCaptureType
- BleConnectionMachineTest
- gradlew
- Device Compatibility Tiers
- DiveControl Samsung Log grading workflow
- run-gradle.ps1
- BLE Connection State Machine
- Camera Mode
- AppState Model

## God Nodes (most connected - your core abstractions)
1. `CameraRuntimeController` - 190 edges
2. `ControlReducer` - 134 edges
3. `AppState` - 130 edges
4. `CameraState` - 107 edges
5. `SafetyState` - 105 edges
6. `CameraCatalog` - 81 edges
7. `Reduction` - 67 edges
8. `ControlCoreTest` - 58 edges
9. `CameraCommand` - 57 edges
10. `SafetyStateMachineTest` - 54 edges

## Surprising Connections (you probably didn't know these)
- `Scenario Test Language` --semantically_similar_to--> `Scenario Runner Language`  [INFERRED] [semantically similar]
  TEST_PROTOCOL.md → docs/SCENARIOS.md
- `VacuumReadout` --references--> `SafetyState`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/ui/components/DepthGauge.kt → core/src/main/kotlin/com/mobiledivecontrol/core/Contracts.kt
- `ConnectionStatus()` --references--> `BleConnectionState`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/ui/components/ConnectionStatus.kt → core/src/main/kotlin/com/mobiledivecontrol/core/Contracts.kt
- `Housing-to-Diagnostics Control Core Pipeline` --conceptually_related_to--> `Native Control Critical Path`  [INFERRED]
  README.md → Claude.md
- `BottomSettingsTrayLegacy()` --references--> `CameraModeProfile`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/ui/camera/CameraShellScreen.kt → core/src/main/kotlin/com/mobiledivecontrol/core/CameraCatalog.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Maximum-information Log graph** — docs_color_workflow_preview_surface, docs_color_workflow_hlg10_videocapture_surface, docs_color_workflow_physical_camera_id_5, docs_color_workflow_minimal_processing_camera2_configuration, docs_color_workflow_live_camera2_controls [EXTRACTED 1.00]
- **Samsung Log transform pipeline** — docs_color_workflow_analytic_hlg_inverse, docs_color_workflow_constrained_s24_1x_colour_matrix, docs_color_workflow_samsung_scene_linear_domain, docs_color_workflow_samsung_published_log_formula [EXTRACTED 1.00]
- **WFH07 Bluetooth Service Architecture** — manufactuer_s_spec_battery_service_180f, manufactuer_s_spec_device_information_service_180a, manufactuer_s_spec_key_service_1523, manufactuer_s_spec_sensor_vacuum_control_service_1623 [EXTRACTED 1.00]
- **Sensor and Vacuum Reporting Flow** — manufactuer_s_spec_sensor_vacuum_control_service_1623, manufactuer_s_spec_motor_control_characteristic_1624, manufactuer_s_spec_water_pressure_characteristic_1625, manufactuer_s_spec_water_temperature_characteristic_1626, manufactuer_s_spec_barometric_pressure_characteristic_1627, manufactuer_s_spec_air_extraction_cover_characteristic_1628, manufactuer_s_spec_solenoid_valve_characteristic_1629 [EXTRACTED 1.00]
- **Native Control Flow Architecture** — docs_architecture_native_first_underwater_control, docs_architecture_critical_path_constraints, docs_architecture_event_reducer_effect_flow, docs_architecture_boundary_rules [EXTRACTED 1.00]
- **Vendor Sensor Payload Family** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_temperature_encoding, docs_ble_protocol_barometric_pressure_encoding, docs_ble_protocol_cover_state_mapping [EXTRACTED 1.00]
- **Depth Measurement Evidence Chain** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_pressure_resolution, docs_diagnostics_sensor_diagnostic_fields, docs_diagnostics_sensor_update_rates, docs_safety_sensor_freshness_rules, docs_traceability_sensor_decoder_tests [INFERRED 0.95]

## Communities (126 total, 39 thin omitted)

### Community 0 - "CameraCatalog"
Cohesion: 0.09
Nodes (7): CameraCatalog, CameraRailAction, Diagnostics, TrackHeading, CameraRailEntry, CameraSettingSpec, SettingsKey

### Community 1 - "GalleryRepository"
Cohesion: 0.10
Nodes (8): GalleryRepository, mediaFolderDisplayName(), pinCreatedAlbumsFirst(), GalleryRepositoryOrderingTest, GalleryTab, Folders, Photos, Videos

### Community 3 - "TransportOutcome"
Cohesion: 0.08
Nodes (13): main(), ScenarioExecution, ScenarioScriptRunner, parseHexByte(), parseHexPayload(), BleTransportRequest, HousingCommandEncoder, Read (+5 more)

### Community 4 - "LinearRgb"
Cohesion: 0.09
Nodes (14): UnderwaterFrameAnalyzer, Sample, UnderwaterWhiteBalanceTrace, UnderwaterWhiteBalanceTraceTest, ChromaticityUv, ChromaticityXy, CorrelatedTemperature, LinearRgb (+6 more)

### Community 5 - "ControlCore"
Cohesion: 0.09
Nodes (17): HousingLinkService, LinkStatus, BleConnectionMachine, BleSignal, BleTransition, Connect, Degrade, Disconnect (+9 more)

### Community 6 - "CameraShellScreen.kt"
Cohesion: 0.09
Nodes (48): BottomBarChip(), bottomBarIcon(), bottomBarLabel(), bottomBarValue(), BottomEditCard(), BottomSettingsTray(), BottomSettingsTrayLegacy(), CameraPreviewPlaceholder() (+40 more)

### Community 9 - "ScaleLadderMigrationTest"
Cohesion: 0.07
Nodes (10): CameraSessionStore, snapFocusValuesToLadder(), snapScaleValuesToLadders(), snapToLadder(), FocusLadderMigrationTest, ScaleLadderMigrationTest, FocusCurveMode, Linear (+2 more)

### Community 10 - "PointingGestureRecognizer.kt"
Cohesion: 0.12
Nodes (15): chainLinearity(), distance2(), distance3(), estimatePointingGesture(), FingerJoints, foldedScore(), GestureLandmark, isPointingHandLandmarks() (+7 more)

### Community 11 - "PermissionKind"
Cohesion: 0.18
Nodes (8): PermissionKind, Accessibility, Bluetooth, ForegroundService, Microphone, Notifications, Overlay, PermissionsState

### Community 13 - "CameraRuntimeController"
Cohesion: 0.07
Nodes (8): AwbCurvePoint, CameraRuntimeController, HarvestedWb, RecorderVideoCapabilities, SensorColorCalibration, WbAnchor, WbCalPoint, WbPipeline

### Community 14 - "HeadingMath"
Cohesion: 0.09
Nodes (8): HeadingStore, ArrowVertex, CameraBasis, HeadingMath, NavigationArrowMesh, ProjectedArrowPoint, Vector3, HeadingMathTest

### Community 16 - ".reduce"
Cohesion: 0.15
Nodes (3): DiagnosticsAction, BackToCamera, Export

### Community 17 - "RecordingSaveLocation"
Cohesion: 0.14
Nodes (8): RecordingSessionMuxer, RecordingPausedAction, Delete, Preview, Resume, Stop, RecordingSaveLocation, RecordingReviewFlowTest

### Community 18 - "CameraCommand"
Cohesion: 0.05
Nodes (37): ActivateModeRailEntry, AdjustOptionsItem, CameraCommand, CapturePhoto, DeleteVideoRecording, HighlightRecordingSaveLocation, NudgeSetting, OpenGallery (+29 more)

### Community 19 - "DiveViewModel"
Cohesion: 0.11
Nodes (4): DiveViewModel, GalleryConsentRequest, GalleryMediaManagementRequest, Ramp

### Community 20 - ".decodeNotification"
Cohesion: 0.20
Nodes (17): BarometricPressure, CoverState, SensorUpdate, WaterPressure, WaterTemperature, Battery, Button, DecodedButtonPacket (+9 more)

### Community 21 - "SimulatedBleTransport"
Cohesion: 0.12
Nodes (7): BleTransportOrchestrator, Connected, ConnectionResult, Failed, encodeLittleEndianU32(), SimulatedBleTransport, BleTransportOrchestratorTest

### Community 22 - "HousingLink"
Cohesion: 0.18
Nodes (10): Ble, HousingLink, HousingLinkEvent, Identity, Kind, Notification, Session, SessionResult (+2 more)

### Community 24 - "WFH07 Bluetooth Communication Protocol A4.0"
Cohesion: 0.08
Nodes (32): Air Extraction Cover Signal Characteristic (UUID 0x1628), Automatic Vacuum Pumping Module, Barometric Pressure Characteristic (UUID 0x1627), Barometric Pressure Encoding: UInt32 Little-Endian, 1 Pa, Battery Level Characteristic (UUID 0x2A19), Battery Service (UUID 0x180F), Bluetooth Low Energy 5.0, Bluetooth Advertising Parameters (+24 more)

### Community 25 - "HousingIdentityVerifierTest"
Cohesion: 0.11
Nodes (6): HousingIdentityVerifier, HousingService, Rejected, VerificationResult, Verified, HousingIdentityVerifierTest

### Community 26 - "LoopingVideoTextureView"
Cohesion: 0.11
Nodes (6): Camera2HighSpeedRecorder, CameraDevice, CameraCaptureSession, SurfaceHolder, Request, LoopingVideoTextureView

### Community 28 - "GalaxyDeviceVariant"
Cohesion: 0.19
Nodes (8): CameraModeProfile, CameraFeatureStatus, Confirmed, NeedsVerification, GalaxyDeviceVariant, S26, S26Plus, S26Ultra

### Community 29 - "CameraCatalogTest"
Cohesion: 0.08
Nodes (3): CameraCapabilities, MeteredExposure, CameraCatalogTest

### Community 30 - "ControlReducer"
Cohesion: 0.08
Nodes (8): SliderSensitivitySelector(), SliderSensitivity, ControlReducer, ManualFocusPreparation, SliderLaw, Continuous, Discrete, SliderMotor

### Community 32 - "ButtonTutorialScreen.kt"
Cohesion: 0.07
Nodes (41): DiveControlApp, HousingStore, CameraPreview(), AutoShrinkText(), VacuumTimerIndicator(), ContinuePill(), drawBanner(), drawCapComingOff() (+33 more)

### Community 34 - "GalleryState"
Cohesion: 0.12
Nodes (6): PreviewActionRail(), galleryBrowserActions(), GalleryItem, galleryPreviewRailActions(), GalleryState, GalleryMenuFlowTest

### Community 37 - "HousingCharacteristic"
Cohesion: 0.09
Nodes (6): BleConnectionParams, BleTransport, DiscoveredDevice, NotificationListener, HousingBleProfile, HousingCharacteristic

### Community 38 - "GalleryCommand"
Cohesion: 0.09
Nodes (23): ActivateAlbumAction, ActivateBrowserAction, ActivateMediaAction, ActivatePreviewAction, CreateFolder, DeleteFolder, GalleryCommand, InitiateDelete (+15 more)

### Community 39 - "HousingButtonEvent"
Cohesion: 0.11
Nodes (16): AcceptedButtonEvent, ButtonEventNormalizer, BackOrSafety, Down, HousingButtonEvent, Left, Ok, Right (+8 more)

### Community 40 - ".detectDeviceCapabilitiesViaCameraManager"
Cohesion: 0.16
Nodes (7): BackCameraProfile, ManualFocusTransport, Fixed, Hybrid, PublicDiopter, SamsungLensPosition, PhysicalLensProfile

### Community 41 - "DiagnosticsStore"
Cohesion: 0.13
Nodes (9): ButtonRecord, DiagnosticsStore, ErrorRecord, LatencyRecord, RawPacketRecord, RingBuffer, StateTransitionRecord, TimedJsonLine (+1 more)

### Community 42 - "SealCheckIndicator.kt"
Cohesion: 0.26
Nodes (16): accented(), CenteredSealBanner(), formatRemaining(), passedLabel(), pressureDropKpa(), SealBanner(), SealBannerColumn(), SealBannerColumns() (+8 more)

### Community 43 - ".matchCharacteristic"
Cohesion: 0.29
Nodes (6): Ambiguous, HousingUuidResolver, Match, MatchSource, None, Unique

### Community 46 - "PlatformEffect"
Cohesion: 0.10
Nodes (20): PointingGesture, StateDrivenCameraPreview(), CreateGalleryFolder, DeleteGalleryAlbum, DeleteGalleryFolder, DeleteGalleryItem, EmitAlert, ExecuteCamera (+12 more)

### Community 47 - "HousingCommand"
Cohesion: 0.09
Nodes (21): HousingFeatureFlags, AcknowledgeWarning, CancelVacuumCheck, CloseSolenoid, Disconnect, DismissSealCheck, HousingCommand, OpenSolenoid (+13 more)

### Community 48 - "CameraModeId"
Cohesion: 0.08
Nodes (22): CameraModeId, ArZone, BixbyVision, Burst, DirectorsView, DualRecording, ExpertRaw, Food (+14 more)

### Community 50 - "DepthGauge.kt"
Cohesion: 0.21
Nodes (8): cardinal(), DepthGauge(), depthMetersFromPressure(), formatHeading(), headingReadoutColor(), ReadoutSeparator(), VacuumReadout, DepthGaugeColorTest

### Community 51 - "GattQueue"
Cohesion: 0.36
Nodes (4): GattOutcome, GattQueue, Kind, Operation

### Community 52 - "PhoneControlCommand"
Cohesion: 0.12
Nodes (17): Click, DecreaseCursorSpeed, Home, IncreaseCursorSpeed, LongClick, MoveCursorDown, MoveCursorLeft, MoveCursorRight (+9 more)

### Community 53 - "SealConfidence"
Cohesion: 0.33
Nodes (6): SealConfidence, Conservative, ManufacturerMinimum, Monitoring, Provisional, Recommended

### Community 54 - "DiveControl_HLG_to_Samsung_Log.dctl"
Cohesion: 0.13
Nodes (16): Affine/3D colour fit, Analytic HLG inverse, Constrained matrix RMSE validation, Constrained chart-derived S24 1x colour matrix, DiveControl_HLG_to_Samsung_Log.dctl, DiveControl_HLG_to_Samsung_Log_TransferOnly.dctl, EV dial creative offset, Controlled Galaxy S24 1x chart pair (+8 more)

### Community 56 - "Water Pressure Little-Endian Encoding and kPa Conversion"
Cohesion: 0.16
Nodes (15): Barometric Pressure Little-Endian Encoding, Vendor Cover State Polarity, Housing Hardware Specifications, Housing Sensor and Control Service, Water Pressure Little-Endian Encoding and kPa Conversion, Water Pressure Sensor 50 kPa Resolution, Water Temperature Little-Endian Encoding, Sensor Diagnostic Fields (+7 more)

### Community 57 - "SealState"
Cohesion: 0.15
Nodes (12): SealStatusBadge(), SealState, CoverOpen, Failed, LeakMonitoring, MotorStopping, Passed, ReadyToVacuum (+4 more)

### Community 59 - "BleConnectionState"
Cohesion: 0.19
Nodes (16): alertFor(), caution(), HousingLinkBanner(), info(), LinkAlert, warning(), BleConnectionState, Connecting (+8 more)

### Community 60 - "Customer Trust Contract"
Cohesion: 0.15
Nodes (13): Customer Trust Contract, Diagnostics and Debugging, Bounded In-Memory Diagnostic Ring Buffers, Diagnostic Export Bundle, JSONL Diagnostic Log Format, Post-Dive Diagnostics Philosophy, Diagnostic Export Privacy, Local Data Classification (+5 more)

### Community 62 - "DebugSimulationPanel"
Cohesion: 0.83
Nodes (3): DebugButtonCluster(), DebugSimulationPanel(), HousingButton()

### Community 64 - "SafetyScreen.kt"
Cohesion: 0.60
Nodes (5): coverStatusText(), ResultStep(), SafetyScreen(), StepPrompt(), VacuumingStep()

### Community 67 - "CameraControl"
Cohesion: 0.17
Nodes (12): CameraControl, ExposureCompensation, FocusPeaking, Grid, Iso, Lens, ManualFocus, Photo (+4 more)

### Community 70 - "DiveViewModel.kt"
Cohesion: 0.15
Nodes (8): CompassAccuracy, High, Low, Medium, Unavailable, Unreliable, CompassHeadingMonitor, CompassReading

### Community 72 - "CameraHudOverlay.kt"
Cohesion: 0.23
Nodes (8): CameraHudOverlay(), cameraReadoutBottomPadding(), formatTimestamp(), OverlayPill(), rememberClockText(), TargetHeadingArrow(), ConnectionStatus(), CameraHudOverlayLayoutTest

### Community 73 - "AppMode"
Cohesion: 0.13
Nodes (13): ModeIndicator(), AppMode, CameraAdjust, CameraLive, Diagnostics, Gallery, PhoneCursor, PhoneTarget (+5 more)

### Community 75 - "SystemCommand"
Cohesion: 0.25
Nodes (8): ExportDiagnostics, LockControls, SwitchToCameraMode, SwitchToDiagnosticsMode, SwitchToSafetyMode, SwitchToTransparentPhoneMode, SystemCommand, UnlockControls

### Community 76 - "GalleryScreen.kt"
Cohesion: 0.07
Nodes (50): AlbumActionsOverlay(), AlbumCard(), ConfirmationOverlay(), CreateAlbumOverlay(), DetailsPanel(), DialogAction(), DirectionChevron(), EmptyGallery() (+42 more)

### Community 78 - "SafetyStateMachine.kt"
Cohesion: 0.31
Nodes (9): BarometricPressureSample, CancelVacuumCheckRequested, CoverStateChanged, DismissSealCheckRequested, ResetSealStateRequested, SafetySignal, SafetyThresholds, SkipToResultRequested (+1 more)

### Community 82 - "High-Risk Housing Command Policy"
Cohesion: 0.20
Nodes (10): High-Risk Housing Command Policy, OTA Disabled in MVP, Vendor-Specified Vacuum Procedure, High-Risk Safety Commands, High-Risk Housing Commands Disabled, Vacuum Command Safety Preconditions, Vacuum Workflow State Machine, High-Risk Command Safeguards (+2 more)

### Community 88 - "Native Control Critical Path"
Cohesion: 0.22
Nodes (9): Architecture Kill Rules, Native Control Critical Path, Native Core with Optional React Native Shell, Architecture Boundary Rules, Critical Path Constraints, Native Core and Optional RN Shell Architecture, Native-First Underwater Control System, Offline Core Network Policy (+1 more)

### Community 89 - "Regression Acceptance Focus"
Cohesion: 0.22
Nodes (9): Simulator-Driven Development, Testing Strategy, Regression Scenario Suite, Scenario Runner Language, Test Coverage Gaps, Local Test and Scenario Workflow, Regression Acceptance Focus, Deterministic Test Protocol (+1 more)

### Community 91 - "Maximum-information capture contract"
Cohesion: 0.22
Nodes (9): Analysis stream, Auto Underwater scene estimation, HLG10 VideoCapture surface, Live Camera2 controls, Maximum-information capture contract, Minimal-processing Camera2 configuration, Physical camera ID 5, Preview surface (+1 more)

### Community 94 - "Contracts.kt"
Cohesion: 0.09
Nodes (22): CameraRailLevel, Primary, Secondary, CameraSettingKind, Choice, Slider, Toggle, CursorSpeedProfile (+14 more)

### Community 95 - "SliderEditTarget"
Cohesion: 0.25
Nodes (8): SliderEditTarget, FocusAssist, FocusCurve, FocusDirection, FocusRampIn, FocusRampOut, Sensitivity, Value

### Community 98 - "100 Mbit/s video target"
Cohesion: 0.25
Nodes (8): 100 Mbit/s video target, 10-bit HEVC BT.2020 HLG master, DiveControl Pro Video, Multi-segment lossless timestamp remux, One-segment direct MP4 publish, Private-files recording staging, Recorder.Builder.setTargetVideoEncodingBitRate, Samsung privileged native Log pipeline

### Community 100 - "DiveControlScreen.kt"
Cohesion: 0.19
Nodes (9): DiveColors, DiagnosticsActionTile(), DiagnosticsScreen(), InfoCard(), InfoRow(), SectionHeader(), DiveControlContent(), DiveControlScreen() (+1 more)

### Community 101 - "MobileDiveControl Documentation Index"
Cohesion: 0.33
Nodes (7): MobileDiveControl Product Specification, Current JVM Control Core, MobileDiveControl Documentation Index, Traceability Audit Trail Rule, Code-to-Spec Traceability, Housing-to-Diagnostics Control Core Pipeline, Honest Platform Adapter Boundaries

### Community 102 - "Stable Internal Command Contract"
Cohesion: 0.29
Nodes (7): AppState Single Source of Truth, Stable Internal Command Contract, Semantic Housing Button Events, Mode-Aware Input Routing Table, Stable Command Contract Reference, Contract Type Mapping, Input Router Spec Deviations

### Community 103 - "DeviceInfoUpdate"
Cohesion: 0.29
Nodes (7): DeviceInfoUpdate, FirmwareRevision, HardwareRevision, ManufacturerName, ModelNumber, SerialNumber, SoftwareRevision

### Community 104 - "IrFlashlightCommand"
Cohesion: 0.29
Nodes (7): IrFlashlightCommand, DecreaseBrightness, FocusOrFlash, IncreaseBrightness, Sleep, SwitchLightType, Wake

### Community 106 - "jsonObject"
Cohesion: 1.00
Nodes (3): escapeJson(), jsonObject(), jsonValue()

### Community 107 - "DIVE IT BLE Protocol Reference"
Cohesion: 0.29
Nodes (7): DIVE IT BLE Protocol Reference, Housing Identity Trust Model, UMEING Vendor Hardware Protocol A4.0, Housing Firmware Compatibility Check, Real Housing First Contact Procedure, BLE Device Trust Model, Parser Hardening Rules

### Community 111 - "CaptureGuideGeometry.kt"
Cohesion: 0.73
Nodes (5): FibonacciGuideGeometry, mirroredIfNeeded(), normalizedAndMirrored(), NormalizedGuideArc, NormalizedGuideLine

### Community 113 - "BLE Housing Hardware Interface"
Cohesion: 0.33
Nodes (6): BLE Housing Hardware Interface, Safety and Vacuum Workflow, Security Architecture, Water Pressure and Depth Telemetry, Architecture Security Zones, Visible-Failure Defense in Depth

### Community 114 - "ControlCommand"
Cohesion: 0.22
Nodes (8): Activate, Back, Confirm, ControlCommand, DiagnosticsCommand, NavigateNext, NavigatePrevious, CommandRecord

### Community 117 - "Corrected Nordic nRF5 Base UUID"
Cohesion: 0.40
Nodes (6): Button Notification Service, Corrected Nordic nRF5 Base UUID, Legacy Malformed Vendor UUID String, BLE Characteristic Subscription Order, Hardware Button Byte Check, Hardware UUID Discovery Dump

### Community 120 - "AlertPriority"
Cohesion: 0.40
Nodes (5): AlertPriority, Critical, High, Low, Medium

### Community 122 - "Direction"
Cohesion: 0.40
Nodes (5): Direction, Down, Left, Right, Up

### Community 123 - "GalleryMutation"
Cohesion: 0.40
Nodes (5): GalleryMutation, CreateAlbum, Delete, Move, Rename

### Community 126 - "Accessibility Behavior Algorithm"
Cohesion: 0.50
Nodes (4): Accessibility Behavior Algorithm, Transparent Phone Mode, Transparent Phone Mode Privacy, Permission and Accessibility Security

### Community 128 - "CameraCaptureType"
Cohesion: 0.50
Nodes (4): CameraCaptureType, Hybrid, Photo, Video

### Community 133 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 135 - "Device Compatibility Tiers"
Cohesion: 0.67
Nodes (3): Compatibility Matrix, Android Version Compatibility Matrix, Device Compatibility Tiers

### Community 136 - "DiveControl Samsung Log grading workflow"
Cohesion: 0.67
Nodes (3): DiveControl Samsung Log grading workflow, Samsung APV, Samsung Log for Galaxy

## Ambiguous Edges - Review These
- `Corrected Nordic nRF5 Base UUID` → `Legacy Malformed Vendor UUID String`  [AMBIGUOUS]
  docs/BLE_PROTOCOL.md · relation: conceptually_related_to

## Knowledge Gaps
- **372 isolated node(s):** `FirmwareRevision`, `HardwareRevision`, `ManufacturerName`, `ModelNumber`, `SerialNumber` (+367 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **39 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Work-memory lessons

**Preferred sources** — corroborated by past sessions; start here.
- `GalleryScreen.kt` (7× useful, score=2.244730959)
- `CameraSessionStore` (5× useful, score=1.898784636)
- `AppState` (4× useful, score=2.073249996)
- `.applyState()` (3× useful, score=1.176799123) _(code changed — re-verify)_
- `.bindCamera()` (3× useful, score=1.176799123) _(code changed — re-verify)_
- `MeteredExposure` (3× useful, score=0.968506747)
- `Contracts.kt` (3× useful, score=0.961593736)
- `SealCheckIndicator.kt` (2× useful, score=1.068641536)
- `CapturePhoto` (2× useful, score=0.810174742)
- `LensFocusCapabilityProfile` (2× useful, score=0.809234563) _(code changed — re-verify)_

**Known dead ends** — questions that led nowhere; don't re-derive.
- "check your compass heading value, I am comparing with an actual compass and I get 332deg when you are showing 280" -> `Sensor`

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Corrected Nordic nRF5 Base UUID` and `Legacy Malformed Vendor UUID String`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `AppState` connect `AppState` to `TransportOutcome`, `ControlCore`, `ControlCoreTest`, `ScaleLadderMigrationTest`, `.reduce`, `RecordingSaveLocation`, `DiveViewModel`, `.readyState`, `ControlReducer`, `GalleryState`, `HousingButtonEvent`, `DiagnosticsStore`, `Reduction`, `ValueLadderGearingTest`, `DiveViewModel.kt`, `CameraHudOverlay.kt`, `Contracts.kt`, `Rig`, `DiveControlScreen.kt`, `CircularScaleTest`?**
  _High betweenness centrality (0.134) - this node is a cross-community bridge._
- **Why does `CameraRuntimeController` connect `CameraRuntimeController` to `.bindCamera`, `.detectDeviceCapabilitiesViaCameraManager`, `ScaleLadderMigrationTest`, `.applySessionState`, `PlatformEffect`, `CameraState`, `.submitNativeRepeatingRequest`, `CameraCommand`, `.execute`, `CameraCatalogTest`, `CameraRuntimeController.kt`?**
  _High betweenness centrality (0.132) - this node is a cross-community bridge._
- **Why does `CameraState` connect `CameraState` to `CameraCatalog`, `.bindCamera`, `CameraShellScreen.kt`, `HousingButtonEvent`, `CameraHudOverlay.kt`, `AppMode`, `.applySessionState`, `CameraRuntimeController`, `PlatformEffect`, `CameraModeId`, `.submitNativeRepeatingRequest`, `RecordingSaveLocation`, `ControlReducer`, `.readyState`, `AppState`, `Contracts.kt`, `CameraRuntimeController.kt`?**
  _High betweenness centrality (0.131) - this node is a cross-community bridge._
- **Are the 34 inferred relationships involving `ControlReducer` (e.g. with `CircularScaleTest` and `.`a quarter turn at max sensitivity spends the whole focus range`()`) actually correct?**
  _`ControlReducer` has 34 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `AppState` (e.g. with `.nudge()` and `.`export bundle contains expected files`()`) actually correct?**
  _`AppState` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 51 inferred relationships involving `SafetyState` (e.g. with `.`a boot record refuted at ambient is a restart seal failure, confirmed by two readings`()` and `.`a capped pump is stopped within seconds, not left grinding`()`) actually correct?**
  _`SafetyState` has 51 INFERRED edges - model-reasoned connections that need verification._