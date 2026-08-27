# Graph Report - Mobile DiveControl  (2026-08-27)

## Corpus Check
- 58 changed files · ~121,274 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2701 nodes · 5989 edges · 237 communities (108 shown, 129 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 299 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output recorded (host-agent usage was not exposed)

## Graph Health
- 0 missing endpoints · 0 dangling endpoints · 0 collapsed edges
- 5 self-loops were retained and flagged. All five come from Kotlin anonymous callback objects in `Camera2HighSpeedRecorder.kt` and `CameraRuntimeController.kt`; they are graph-extractor artifacts, not source corruption.

## Community Hubs (Navigation)
- Camera CameraCatalog
- Gallery GalleryRepository
- Camera CameraShellScreen.kt
- Core TransportOutcome
- Underwater UnderwaterWhiteBalanceEstimator
- Core SafetyState
- Core ControlReducer
- Tests ControlCoreTest
- Tests ScaleLadderMigrationTest
- Camera PointingGestureRecognizer.kt
- Camera FocusPeakingSurfaceProcessor
- Bluetooth AndroidBleTransport
- Gallery GalleryScreen.kt
- Bluetooth SimulatedBleTransport
- Bluetooth HousingLink
- Camera CameraRuntimeController
- Bluetooth BleSignal
- Core Contracts.kt
- Core .decodeNotification
- Core CameraState
- Core CameraCommand
- Core AppState
- App DiveViewModel
- App WFH07 Bluetooth Communication Protocol
- Tests HousingIdentityVerifierTest
- Camera .execute
- Core PlatformEffect
- Camera Camera2HighSpeedRecorder
- Core ControlCore
- Camera CameraCatalogTest
- Tests .readyState
- Camera .wbPipeline
- Bluetooth HousingUuidResolverTest
- Safety SafetyStateMachine
- Bluetooth HousingCharacteristic
- Tutorial ButtonTutorialScreen.kt
- Tutorial HousingDiagram.kt
- Core Reduction
- Core GalleryCommand
- Camera CameraRuntimeController.kt
- Core HousingButtonEvent
- Diagnostics .add
- Bluetooth GattOutcome
- Camera .detectDeviceCapabilitiesViaCameraManager
- Heading HeadingMath
- Color DiveColors
- Camera .applySessionState
- Heading HeadingMathTest
- Bluetooth HousingUuidResolver
- Core AppMode
- App SealCheckIndicator.kt
- Core BleConnectionState
- Core PhoneControlCommand
- App DiveViewModel.kt
- Core HousingCommand
- Tutorial HousingDiagram
- Heading CompassHeadingMonitor.kt
- App MainActivity
- App CaptureRequest
- Camera .bindCamera
- Color DiveControl HLG to Samsung
- Bluetooth Water Pressure Little-Endian Encoding
- Core SealState
- Tests test.ps1
- Camera CameraHudOverlay.kt
- App Customer Trust Contract
- Core ButtonEventNormalizer
- Core GalleryPreviewAction
- Tests .turn
- Safety SafetyScreen
- Core CameraControl
- Core SamsungLogProfile
- Bluetooth HousingTransport
- Core SafetyCommand
- Core ControlCommand
- Core PermissionKind
- Diagnostics DiagnosticsStore
- Safety SafetyStateMachine.kt
- Bluetooth HousingStore
- Bluetooth .missing
- Camera VideoDynamicRangePolicy
- App AutoShrinkText
- Heading HeadingStabilizer
- Tests ProtocolParserTest
- Bluetooth High-Risk Housing Command Policy
- App PhoneBatteryMonitor
- App VacuumStore
- Tutorial .plate
- App Native Control Critical Path
- Tests Regression Acceptance Focus
- Tests SamsungLogProfileTest
- Color Maximum-information capture contract
- App DebugSimulationPanel
- Camera VideoDynamicRangePolicyTest
- Core .reducePhoneControl
- Core DeviceInfoUpdate
- Tests AutofocusHoldPolicyTest
- Color 100 Mbit s video
- App BatteryIndicator
- Diagnostics DiagnosticsScreen
- Documentation MobileDiveControl Documentation Index
- App Stable Internal Command Contract
- Core IrFlashlightCommand
- Bluetooth HousingBleProfile.kt
- Core jsonObject
- Tests Rig
- Bluetooth DIVE IT BLE Protocol
- Camera CaptureGuideGeometry.kt
- Camera cameraReadoutBottomPadding
- Tutorial IntroFrame
- App BLE Housing Hardware Interface
- Tests CircularScaleTest
- Tests HousingCommandEncoderTest
- Bluetooth Corrected Nordic nRF5 Base
- Camera CaptureGuideGeometryTest
- Core CameraUiZone
- Camera RecordingClock.kt
- App TemperatureDisplay
- App Accessibility Behavior Algorithm
- Bluetooth BleConnectionMachineTest
- App gradlew
- Documentation Device Compatibility Tiers
- Color DiveControl Samsung Log grading
- App run-gradle.ps1
- App BLE Connection State Machine
- App Camera Mode
- Documentation AppState Model
- App Boolean
- App Unit
- App android
- App Boolean
- App List
- App Long
- App String
- App Boolean
- App Double
- App Int
- App Array
- App Boolean
- App Byte
- App Double
- App Float
- App Int
- App List
- App Long
- App Map
- App Pair
- App Size
- App String
- App T
- App Unit
- App Boolean
- App Double
- App Int
- App List
- App String
- App Unit
- App Int
- App String
- App Boolean
- App Long
- App List
- App String
- App Unit
- App Boolean
- App String
- App Boolean
- App Double
- App Boolean
- App String
- App Boolean
- App Double
- App Dp
- App Float
- App List
- App Long
- App Pair
- App String
- App Boolean
- App List
- App String
- App Unit
- App Boolean
- App Int
- App Long
- App String
- App Double
- App List
- App Map
- App MutableMap
- App String
- App Boolean
- App ByteArray
- App Double
- App Int
- App List
- App Long
- App String
- App String
- App Camera
- App CameraCaptureSession
- App Class
- App Boolean
- App Int
- App List
- App Long
- App Map
- App String
- App Boolean
- App Int
- App Boolean
- App Double
- App Duration
- App Int
- App List
- App Long
- App Pair
- App String
- App Int
- App Long
- App String
- App Boolean
- App Int
- App Long
- App String
- App DoubleArray
- App FloatArray
- App ImageCapture
- App IntArray
- App SurfaceTexture
- App TonemapCurve

## God Nodes (most connected - your core abstractions)
1. `CameraRuntimeController` - 216 edges
2. `ControlReducer` - 129 edges
3. `AppState` - 126 edges
4. `CameraState` - 107 edges
5. `SafetyState` - 105 edges
6. `CameraCatalog` - 82 edges
7. `Reduction` - 64 edges
8. `DiveViewModel` - 58 edges
9. `CameraCommand` - 55 edges
10. `SafetyStateMachineTest` - 54 edges

## Surprising Connections (you probably didn't know these)
- `Scenario Test Language` --semantically_similar_to--> `Scenario Runner Language`  [INFERRED] [semantically similar]
  TEST_PROTOCOL.md → docs/SCENARIOS.md
- `Housing-to-Diagnostics Control Core Pipeline` --conceptually_related_to--> `Native Control Critical Path`  [INFERRED]
  README.md → Claude.md
- `VacuumReadout` --references--> `SafetyState`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/ui/components/DepthGauge.kt → core/src/main/kotlin/com/mobiledivecontrol/core/Contracts.kt
- `AndroidBleTransport` --references--> `NotificationListener`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/platform/ble/AndroidBleTransport.kt → core/src/main/kotlin/com/mobiledivecontrol/core/BleTransport.kt
- `AndroidBleTransport` --references--> `HousingCharacteristic`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/platform/ble/AndroidBleTransport.kt → core/src/main/kotlin/com/mobiledivecontrol/core/HousingBleProfile.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Samsung Log transform pipeline** — docs_color_workflow_analytic_hlg_inverse, docs_color_workflow_constrained_s24_1x_colour_matrix, docs_color_workflow_samsung_scene_linear_domain, docs_color_workflow_samsung_published_log_formula [EXTRACTED 1.00]
- **Maximum-information Log graph** — docs_color_workflow_preview_surface, docs_color_workflow_hlg10_videocapture_surface, docs_color_workflow_physical_camera_id_5, docs_color_workflow_minimal_processing_camera2_configuration, docs_color_workflow_live_camera2_controls [EXTRACTED 1.00]
- **Native Control Flow Architecture** — docs_architecture_native_first_underwater_control, docs_architecture_critical_path_constraints, docs_architecture_event_reducer_effect_flow, docs_architecture_boundary_rules [EXTRACTED 1.00]
- **Vendor Sensor Payload Family** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_temperature_encoding, docs_ble_protocol_barometric_pressure_encoding, docs_ble_protocol_cover_state_mapping [EXTRACTED 1.00]
- **Depth Measurement Evidence Chain** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_pressure_resolution, docs_diagnostics_sensor_diagnostic_fields, docs_diagnostics_sensor_update_rates, docs_safety_sensor_freshness_rules, docs_traceability_sensor_decoder_tests [INFERRED 0.95]
- **WFH07 Bluetooth Service Architecture** — manufactuer_s_spec_battery_service_180f, manufactuer_s_spec_device_information_service_180a, manufactuer_s_spec_key_service_1523, manufactuer_s_spec_sensor_vacuum_control_service_1623 [EXTRACTED 1.00]
- **Sensor and Vacuum Reporting Flow** — manufactuer_s_spec_sensor_vacuum_control_service_1623, manufactuer_s_spec_motor_control_characteristic_1624, manufactuer_s_spec_water_pressure_characteristic_1625, manufactuer_s_spec_water_temperature_characteristic_1626, manufactuer_s_spec_barometric_pressure_characteristic_1627, manufactuer_s_spec_air_extraction_cover_characteristic_1628, manufactuer_s_spec_solenoid_valve_characteristic_1629 [EXTRACTED 1.00]

## Communities (237 total, 129 thin omitted)

### Community 0 - "Camera CameraCatalog"
Cohesion: 0.05
Nodes (37): CameraCatalog, CameraModeProfile, CameraRailAction, TrackHeading, CameraRailEntry, CameraSettingSpec, IntRange, SettingsKey (+29 more)

### Community 1 - "Gallery GalleryRepository"
Cohesion: 0.05
Nodes (29): GalleryRepository, Result, Uri, mediaFolderDisplayName(), pinCreatedAlbumsFirst(), android, Context, Result (+21 more)

### Community 2 - "Camera CameraShellScreen.kt"
Cohesion: 0.06
Nodes (72): BottomBarChip(), bottomBarIcon(), bottomBarLabel(), bottomBarValue(), BottomEditCard(), BottomSettingsTray(), BottomSettingsTrayLegacy(), CameraPreviewPlaceholder() (+64 more)

### Community 3 - "Core TransportOutcome"
Cohesion: 0.07
Nodes (31): Array, String, main(), List, String, ScenarioExecution, ScenarioScriptRunner, Byte (+23 more)

### Community 4 - "Underwater UnderwaterWhiteBalanceEstimator"
Cohesion: 0.09
Nodes (18): DoubleArray, ImageProxy, UnderwaterFrameAnalyzer, AutoCloseable, Sample, UnderwaterWhiteBalanceTrace, UnderwaterWhiteBalanceTraceTest, ChromaticityUv (+10 more)

### Community 6 - "Core ControlReducer"
Cohesion: 0.09
Nodes (6): ControlReducer, ManualFocusPreparation, SliderLaw, Continuous, Discrete, SliderMotor

### Community 8 - "Tests ScaleLadderMigrationTest"
Cohesion: 0.07
Nodes (11): CameraSessionStore, snapFocusValuesToLadder(), snapScaleValuesToLadders(), snapToLadder(), FocusLadderMigrationTest, String, ScaleLadderMigrationTest, FocusCurveMode (+3 more)

### Community 9 - "Camera PointingGestureRecognizer.kt"
Cohesion: 0.10
Nodes (23): chainLinearity(), distance2(), distance3(), estimatePointingGesture(), FingerJoints, foldedScore(), GestureLandmark, isPointingHandLandmarks() (+15 more)

### Community 10 - "Camera FocusPeakingSurfaceProcessor"
Cohesion: 0.08
Nodes (23): android, durationMs, positionMs, LoopingVideo(), FocusPeakingSurfaceProcessor, FloatArray, SurfaceTexture, durationMs (+15 more)

### Community 11 - "Bluetooth AndroidBleTransport"
Cohesion: 0.11
Nodes (17): AndroidBleTransport, BluetoothGatt, BluetoothGattCharacteristic, Boolean, ByteArray, Int, List, Long (+9 more)

### Community 12 - "Gallery GalleryScreen.kt"
Cohesion: 0.10
Nodes (45): AlbumActionsOverlay(), AlbumCard(), ConfirmationOverlay(), CreateAlbumOverlay(), DetailsPanel(), DialogAction(), DirectionChevron(), EmptyGallery() (+37 more)

### Community 13 - "Bluetooth SimulatedBleTransport"
Cohesion: 0.08
Nodes (16): BleTransportOrchestrator, Connected, ConnectionResult, Failed, Map, String, encodeLittleEndianU32(), Boolean (+8 more)

### Community 14 - "Bluetooth HousingLink"
Cohesion: 0.12
Nodes (20): Ble, HousingLink, HousingLinkEvent, Identity, Kind, Any, Boolean, ByteArray (+12 more)

### Community 15 - "Camera CameraRuntimeController"
Cohesion: 0.08
Nodes (13): AwbCurvePoint, CameraRuntimeController, CaptureMetadataSnapshot, HarvestedWb, com, LifecycleOwner, RecorderVideoCapabilities, WbAnchor (+5 more)

### Community 16 - "Bluetooth BleSignal"
Cohesion: 0.09
Nodes (23): HousingLinkService, Boolean, Int, Intent, LinkStatus, BleConnectionMachine, BleSignal, BleTransition (+15 more)

### Community 17 - "Core Contracts.kt"
Cohesion: 0.06
Nodes (33): AlertPriority, Critical, High, Low, Medium, CameraRailLevel, Primary, Secondary (+25 more)

### Community 18 - "Core .decodeNotification"
Cohesion: 0.16
Nodes (24): BarometricPressure, CoverState, SensorUpdate, WaterPressure, WaterTemperature, Battery, Button, DecodedButtonPacket (+16 more)

### Community 19 - "Core CameraState"
Cohesion: 0.16
Nodes (4): CaptureRequestOptions, CameraState, Camera, Rect

### Community 20 - "Core CameraCommand"
Cohesion: 0.06
Nodes (35): AdjustOptionsItem, CameraCommand, CapturePhoto, DeleteVideoRecording, HighlightRecordingSaveLocation, NudgeSetting, OpenGallery, OpenRecordingSaveLocationChooser (+27 more)

### Community 21 - "Core AppState"
Cohesion: 0.15
Nodes (4): AppState, GalleryItem, GalleryState, GalleryMenuFlowTest

### Community 22 - "App DiveViewModel"
Cohesion: 0.12
Nodes (5): DiveViewModel, android, com, ByteArray, HousingLinkEvent

### Community 23 - "App WFH07 Bluetooth Communication Protocol"
Cohesion: 0.08
Nodes (32): Air Extraction Cover Signal Characteristic (UUID 0x1628), Automatic Vacuum Pumping Module, Barometric Pressure Characteristic (UUID 0x1627), Barometric Pressure Encoding: UInt32 Little-Endian, 1 Pa, Battery Level Characteristic (UUID 0x2A19), Battery Service (UUID 0x180F), Bluetooth Low Energy 5.0, Bluetooth Advertising Parameters (+24 more)

### Community 24 - "Tests HousingIdentityVerifierTest"
Cohesion: 0.11
Nodes (8): HousingIdentityVerifier, HousingService, Set, String, Rejected, VerificationResult, Verified, HousingIdentityVerifierTest

### Community 25 - "Camera .execute"
Cohesion: 0.13
Nodes (3): ImageCapture, Result, ImageCaptureException

### Community 26 - "Core PlatformEffect"
Cohesion: 0.08
Nodes (23): GalleryConsentRequest, GalleryMediaManagementRequest, Result, Ramp, CreateGalleryFolder, DeleteGalleryAlbum, DeleteGalleryFolder, DeleteGalleryItem (+15 more)

### Community 27 - "Camera Camera2HighSpeedRecorder"
Cohesion: 0.17
Nodes (10): Camera2HighSpeedRecorder, CameraDevice, CameraCaptureSession, SurfaceHolder, Result, Request, CameraConstrainedHighSpeedCaptureSession, MediaRecorder (+2 more)

### Community 28 - "Core ControlCore"
Cohesion: 0.21
Nodes (11): ProcessingOutcome, ControlCore, Boolean, ByteArray, Double, Instant, Int, List (+3 more)

### Community 31 - "Camera .wbPipeline"
Cohesion: 0.18
Nodes (6): android, DoubleArray, IntArray, SensorColorCalibration, WbPipeline, RggbChannelVector

### Community 33 - "Safety SafetyStateMachine"
Cohesion: 0.25
Nodes (7): Boolean, Double, List, Long, String, SafetyMachineResult, SafetyStateMachine

### Community 34 - "Bluetooth HousingCharacteristic"
Cohesion: 0.11
Nodes (11): BleConnectionParams, BleTransport, DiscoveredDevice, Boolean, ByteArray, Long, Set, String (+3 more)

### Community 35 - "Tutorial ButtonTutorialScreen.kt"
Cohesion: 0.21
Nodes (24): ContinuePill(), drawBanner(), drawCapComingOff(), drawCheckBadge(), drawDownAsk(), drawPermissions(), IntroCarouselScreen(), IntroMessage() (+16 more)

### Community 36 - "Tutorial HousingDiagram.kt"
Cohesion: 0.20
Nodes (24): arrowHead(), brightened(), drawButton(), drawFrame(), drawPlate(), drawSlider(), fitPlateText(), glowAnnotated() (+16 more)

### Community 38 - "Core GalleryCommand"
Cohesion: 0.08
Nodes (24): ActivateAlbumAction, ActivateBrowserAction, ActivateMediaAction, ActivatePreviewAction, Confirm, CreateFolder, DeleteFolder, GalleryCommand (+16 more)

### Community 39 - "Camera CameraRuntimeController.kt"
Cohesion: 0.10
Nodes (16): CameraEffect, com, LifecycleOwner, Modifier, StateDrivenCameraPreview(), AutofocusHoldPolicy, ExtensionsManager, ImageAnalysis (+8 more)

### Community 40 - "Core HousingButtonEvent"
Cohesion: 0.18
Nodes (13): BackOrSafety, Down, HousingButtonEvent, Left, Ok, Right, Shutter, Unknown (+5 more)

### Community 41 - "Diagnostics .add"
Cohesion: 0.15
Nodes (13): ErrorRecord, ByteArray, Instant, List, Long, Map, String, T (+5 more)

### Community 42 - "Bluetooth GattOutcome"
Cohesion: 0.22
Nodes (11): GattOutcome, GattQueue, Kind, BluetoothGatt, BluetoothGattCharacteristic, Boolean, ByteArray, Int (+3 more)

### Community 43 - "Camera .detectDeviceCapabilitiesViaCameraManager"
Cohesion: 0.13
Nodes (9): BackCameraProfile, IntRange, ManualFocusTransport, Fixed, Hybrid, PublicDiopter, SamsungLensPosition, PhysicalLensProfile (+1 more)

### Community 44 - "Heading HeadingMath"
Cohesion: 0.20
Nodes (5): ArrowVertex, CameraBasis, HeadingMath, FloatArray, Vector3

### Community 45 - "Color DiveColors"
Cohesion: 0.15
Nodes (10): DiveColors, Color, cardinal(), DepthGauge(), formatHeading(), headingReadoutColor(), Modifier, ReadoutSeparator() (+2 more)

### Community 46 - "Camera .applySessionState"
Cohesion: 0.21
Nodes (3): LensFocusCapabilityProfile, ManualFocusRequest, SessionSignature

### Community 48 - "Bluetooth HousingUuidResolver"
Cohesion: 0.25
Nodes (10): Ambiguous, HousingUuidResolver, Int, Pair, Set, String, Match, MatchSource (+2 more)

### Community 49 - "Core AppMode"
Cohesion: 0.15
Nodes (17): PointingGesture, Modifier, ModeIndicator(), DiveControlContent(), DiveControlScreen(), androidx, com, Modifier (+9 more)

### Community 50 - "App SealCheckIndicator.kt"
Cohesion: 0.27
Nodes (18): accented(), CenteredSealBanner(), Color, Modifier, passedLabel(), pressureDropKpa(), SealBanner(), SealBannerColumn() (+10 more)

### Community 51 - "Core BleConnectionState"
Cohesion: 0.18
Nodes (17): alertFor(), caution(), HousingLinkBanner(), info(), Modifier, LinkAlert, warning(), BleConnectionState (+9 more)

### Community 52 - "Core PhoneControlCommand"
Cohesion: 0.11
Nodes (18): Back, Click, DecreaseCursorSpeed, Home, IncreaseCursorSpeed, LongClick, MoveCursorDown, MoveCursorLeft (+10 more)

### Community 53 - "App DiveViewModel.kt"
Cohesion: 0.12
Nodes (12): AndroidViewModel, HeadingStore, StateFlow, BleSignal, SealConfidence, Conservative, ManufacturerMinimum, Monitoring (+4 more)

### Community 54 - "Core HousingCommand"
Cohesion: 0.14
Nodes (13): HousingFeatureFlags, Boolean, String, Boolean, Disconnect, HousingCommand, Reconnect, RequestBatteryRead (+5 more)

### Community 55 - "Tutorial HousingDiagram"
Cohesion: 0.13
Nodes (14): CameraPreview(), LifecycleOwner, Modifier, Double, Long, Modifier, VacuumTimerIndicator(), HousingDiagram() (+6 more)

### Community 56 - "Heading CompassHeadingMonitor.kt"
Cohesion: 0.17
Nodes (12): CompassAccuracy, High, Low, Medium, Unavailable, Unreliable, CompassHeadingMonitor, CompassReading (+4 more)

### Community 57 - "App MainActivity"
Cohesion: 0.19
Nodes (4): MainActivity, BluetoothAdapter, Bundle, ComponentActivity

### Community 60 - "Color DiveControl HLG to Samsung"
Cohesion: 0.13
Nodes (16): Affine/3D colour fit, Analytic HLG inverse, Constrained matrix RMSE validation, Constrained chart-derived S24 1x colour matrix, DiveControl_HLG_to_Samsung_Log.dctl, DiveControl_HLG_to_Samsung_Log_TransferOnly.dctl, EV dial creative offset, Controlled Galaxy S24 1x chart pair (+8 more)

### Community 61 - "Bluetooth Water Pressure Little-Endian Encoding"
Cohesion: 0.16
Nodes (15): Barometric Pressure Little-Endian Encoding, Vendor Cover State Polarity, Housing Hardware Specifications, Housing Sensor and Control Service, Water Pressure Little-Endian Encoding and kPa Conversion, Water Pressure Sensor 50 kPa Resolution, Water Temperature Little-Endian Encoding, Sensor Diagnostic Fields (+7 more)

### Community 62 - "Core SealState"
Cohesion: 0.14
Nodes (13): Modifier, SealStatusBadge(), SealState, CoverOpen, Failed, LeakMonitoring, MotorStopping, Passed (+5 more)

### Community 63 - "Tests test.ps1"
Cohesion: 0.15
Nodes (3): UnderwaterFrameAnalyzerTest, Get-AdbDeviceLines(), Resolve-TargetSerial()

### Community 64 - "Camera CameraHudOverlay.kt"
Cohesion: 0.27
Nodes (11): CameraHudOverlay(), formatTimestamp(), Modifier, OverlayPill(), rememberClockText(), TargetHeadingArrow(), ConnectionStatus(), Modifier (+3 more)

### Community 65 - "App Customer Trust Contract"
Cohesion: 0.15
Nodes (13): Customer Trust Contract, Diagnostics and Debugging, Bounded In-Memory Diagnostic Ring Buffers, Diagnostic Export Bundle, JSONL Diagnostic Log Format, Post-Dive Diagnostics Philosophy, Diagnostic Export Privacy, Local Data Classification (+5 more)

### Community 66 - "Core ButtonEventNormalizer"
Cohesion: 0.22
Nodes (5): AcceptedButtonEvent, ButtonEventNormalizer, Instant, Int, ButtonEventNormalizerTest

### Community 67 - "Core GalleryPreviewAction"
Cohesion: 0.17
Nodes (9): GalleryPreviewAction, Back, Delete, Details, Next, Options, PlayPause, Previous (+1 more)

### Community 68 - "Tests .turn"
Cohesion: 0.28
Nodes (5): Int, List, Long, String, ValueLadderGearingTest

### Community 69 - "Safety SafetyScreen"
Cohesion: 0.29
Nodes (11): coverStatusText(), androidx, Boolean, Double, ImageVector, Modifier, String, ResultStep() (+3 more)

### Community 70 - "Core CameraControl"
Cohesion: 0.17
Nodes (12): CameraControl, ExposureCompensation, FocusPeaking, Grid, Iso, Lens, ManualFocus, Photo (+4 more)

### Community 72 - "Bluetooth HousingTransport"
Cohesion: 0.20
Nodes (7): Unit, DisconnectCause, HousingTransport, List, Set, String, Unit

### Community 73 - "Core SafetyCommand"
Cohesion: 0.18
Nodes (11): AcknowledgeWarning, CancelVacuumCheck, CloseSolenoid, DismissSealCheck, OpenSolenoid, ResetSealState, SafetyCommand, SkipToResult (+3 more)

### Community 74 - "Core ControlCommand"
Cohesion: 0.18
Nodes (10): ControlCommand, ExportDiagnostics, LockControls, SwitchToCameraMode, SwitchToDiagnosticsMode, SwitchToSafetyMode, SwitchToTransparentPhoneMode, SystemCommand (+2 more)

### Community 75 - "Core PermissionKind"
Cohesion: 0.18
Nodes (8): PermissionKind, Accessibility, Bluetooth, ForegroundService, Microphone, Notifications, Overlay, PermissionsState

### Community 76 - "Diagnostics DiagnosticsStore"
Cohesion: 0.24
Nodes (5): ButtonRecord, DiagnosticsStore, Int, UByte, DiagnosticsStoreTest

### Community 77 - "Safety SafetyStateMachine.kt"
Cohesion: 0.31
Nodes (9): BarometricPressureSample, CancelVacuumCheckRequested, CoverStateChanged, DismissSealCheckRequested, ResetSealStateRequested, SafetySignal, SafetyThresholds, SkipToResultRequested (+1 more)

### Community 78 - "Bluetooth HousingStore"
Cohesion: 0.27
Nodes (5): DiveControlApp, HousingStore, Map, String, Application

### Community 79 - "Bluetooth .missing"
Cohesion: 0.42
Nodes (5): BlePermissions, Boolean, Context, List, String

### Community 80 - "Camera VideoDynamicRangePolicy"
Cohesion: 0.24
Nodes (3): IntArray, VideoDynamicRangePolicy, DynamicRange

### Community 81 - "App AutoShrinkText"
Cohesion: 0.20
Nodes (9): AutoShrinkText(), AnnotatedString, Color, Float, Int, Modifier, String, TextStyle (+1 more)

### Community 84 - "Bluetooth High-Risk Housing Command Policy"
Cohesion: 0.20
Nodes (10): High-Risk Housing Command Policy, OTA Disabled in MVP, Vendor-Specified Vacuum Procedure, High-Risk Safety Commands, High-Risk Housing Commands Disabled, Vacuum Command Safety Preconditions, Vacuum Workflow State Machine, High-Risk Command Safeguards (+2 more)

### Community 85 - "App PhoneBatteryMonitor"
Cohesion: 0.25
Nodes (5): Int, Intent, StateFlow, PhoneBatteryMonitor, BroadcastReceiver

### Community 86 - "App VacuumStore"
Cohesion: 0.28
Nodes (5): Double, Int, Long, PersistedVacuum, VacuumStore

### Community 87 - "Tutorial .plate"
Cohesion: 0.39
Nodes (6): HousingPlates, Bitmap, Context, ImageBitmap, Int, rememberHousingPlate()

### Community 88 - "App Native Control Critical Path"
Cohesion: 0.22
Nodes (9): Architecture Kill Rules, Native Control Critical Path, Native Core with Optional React Native Shell, Architecture Boundary Rules, Critical Path Constraints, Native Core and Optional RN Shell Architecture, Native-First Underwater Control System, Offline Core Network Policy (+1 more)

### Community 89 - "Tests Regression Acceptance Focus"
Cohesion: 0.22
Nodes (9): Simulator-Driven Development, Testing Strategy, Regression Scenario Suite, Scenario Runner Language, Test Coverage Gaps, Local Test and Scenario Workflow, Regression Acceptance Focus, Deterministic Test Protocol (+1 more)

### Community 91 - "Color Maximum-information capture contract"
Cohesion: 0.22
Nodes (9): Analysis stream, Auto Underwater scene estimation, HLG10 VideoCapture surface, Live Camera2 controls, Maximum-information capture contract, Minimal-processing Camera2 configuration, Physical camera ID 5, Preview surface (+1 more)

### Community 92 - "App DebugSimulationPanel"
Cohesion: 0.32
Nodes (7): DebugButtonCluster(), DebugSimulationPanel(), HousingButton(), Color, Dp, Modifier, String

### Community 94 - "Core .reducePhoneControl"
Cohesion: 0.29
Nodes (5): CursorSpeedProfile, Fast, Normal, Precision, SmartTarget

### Community 95 - "Core DeviceInfoUpdate"
Cohesion: 0.25
Nodes (7): DeviceInfoUpdate, FirmwareRevision, HardwareRevision, ManufacturerName, ModelNumber, SerialNumber, SoftwareRevision

### Community 97 - "Color 100 Mbit s video"
Cohesion: 0.25
Nodes (8): 100 Mbit/s video target, 10-bit HEVC BT.2020 HLG master, DiveControl Pro Video, Multi-segment lossless timestamp remux, One-segment direct MP4 publish, Private-files recording staging, Recorder.Builder.setTargetVideoEncodingBitRate, Samsung privileged native Log pipeline

### Community 98 - "App BatteryIndicator"
Cohesion: 0.43
Nodes (6): BatteryIndicator(), DualBatteryIndicator(), ImageVector, Int, Modifier, String

### Community 99 - "Diagnostics DiagnosticsScreen"
Cohesion: 0.48
Nodes (6): DiagnosticsScreen(), InfoCard(), InfoRow(), Modifier, String, SectionHeader()

### Community 100 - "Documentation MobileDiveControl Documentation Index"
Cohesion: 0.33
Nodes (7): MobileDiveControl Product Specification, Current JVM Control Core, MobileDiveControl Documentation Index, Traceability Audit Trail Rule, Code-to-Spec Traceability, Housing-to-Diagnostics Control Core Pipeline, Honest Platform Adapter Boundaries

### Community 101 - "App Stable Internal Command Contract"
Cohesion: 0.29
Nodes (7): AppState Single Source of Truth, Stable Internal Command Contract, Semantic Housing Button Events, Mode-Aware Input Routing Table, Stable Command Contract Reference, Contract Type Mapping, Input Router Spec Deviations

### Community 102 - "Core IrFlashlightCommand"
Cohesion: 0.29
Nodes (7): IrFlashlightCommand, DecreaseBrightness, FocusOrFlash, IncreaseBrightness, Sleep, SwitchLightType, Wake

### Community 103 - "Bluetooth HousingBleProfile.kt"
Cohesion: 0.43
Nodes (6): HousingBleProfile, List, String, standardUuid(), vendorUuid(), UShort

### Community 104 - "Core jsonObject"
Cohesion: 0.57
Nodes (6): escapeJson(), jsonObject(), jsonValue(), Any, Pair, String

### Community 106 - "Bluetooth DIVE IT BLE Protocol"
Cohesion: 0.29
Nodes (7): DIVE IT BLE Protocol Reference, Housing Identity Trust Model, UMEING Vendor Hardware Protocol A4.0, Housing Firmware Compatibility Check, Real Housing First Contact Procedure, BLE Device Trust Model, Parser Hardening Rules

### Community 107 - "Camera CaptureGuideGeometry.kt"
Cohesion: 0.73
Nodes (5): FibonacciGuideGeometry, mirroredIfNeeded(), normalizedAndMirrored(), NormalizedGuideArc, NormalizedGuideLine

### Community 109 - "Tutorial IntroFrame"
Cohesion: 0.40
Nodes (5): dwellFor(), IntroCarousel, Int, Long, IntroFrame

### Community 110 - "App BLE Housing Hardware Interface"
Cohesion: 0.33
Nodes (6): BLE Housing Hardware Interface, Safety and Vacuum Workflow, Security Architecture, Water Pressure and Depth Telemetry, Architecture Security Zones, Visible-Failure Defense in Depth

### Community 114 - "Bluetooth Corrected Nordic nRF5 Base"
Cohesion: 0.40
Nodes (6): Button Notification Service, Corrected Nordic nRF5 Base UUID, Legacy Malformed Vendor UUID String, BLE Characteristic Subscription Order, Hardware Button Byte Check, Hardware UUID Discovery Dump

### Community 116 - "Core CameraUiZone"
Cohesion: 0.40
Nodes (4): CameraUiZone, LiveView, ModeRail, SettingsPanel

### Community 117 - "Camera RecordingClock.kt"
Cohesion: 0.83
Nodes (3): Uri, RecordingClock, MutableState

### Community 118 - "App TemperatureDisplay"
Cohesion: 0.50
Nodes (3): Double, Modifier, TemperatureDisplay()

### Community 119 - "App Accessibility Behavior Algorithm"
Cohesion: 0.50
Nodes (4): Accessibility Behavior Algorithm, Transparent Phone Mode, Transparent Phone Mode Privacy, Permission and Accessibility Security

### Community 121 - "App gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 122 - "Documentation Device Compatibility Tiers"
Cohesion: 0.67
Nodes (3): Compatibility Matrix, Android Version Compatibility Matrix, Device Compatibility Tiers

### Community 123 - "Color DiveControl Samsung Log grading"
Cohesion: 0.67
Nodes (3): DiveControl Samsung Log grading workflow, Samsung APV, Samsung Log for Galaxy

## Ambiguous Edges - Review These
- `Corrected Nordic nRF5 Base UUID` → `Legacy Malformed Vendor UUID String`  [AMBIGUOUS]
  docs/BLE_PROTOCOL.md · relation: conceptually_related_to

## Knowledge Gaps
- **363 isolated node(s):** `Kind`, `BleConnectionParams`, `HousingService`, `Water Pressure and Depth Telemetry`, `Camera Mode` (+358 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **129 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Corrected Nordic nRF5 Base UUID` and `Legacy Malformed Vendor UUID String`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `CameraRuntimeController` connect `Camera CameraRuntimeController` to `Underwater UnderwaterWhiteBalanceEstimator`, `Camera CameraRuntimeController.kt`, `Tests ScaleLadderMigrationTest`, `Camera .bindCamera`, `Camera FocusPeakingSurfaceProcessor`, `Camera .detectDeviceCapabilitiesViaCameraManager`, `Camera PointingGestureRecognizer.kt`, `Camera .applySessionState`, `Core AppMode`, `Core CameraState`, `Core CameraCommand`, `Camera .execute`, `App CaptureRequest`, `Camera Camera2HighSpeedRecorder`, `Camera CameraCatalogTest`, `Camera .wbPipeline`?**
  _High betweenness centrality (0.131) - this node is a cross-community bridge._
- **Why does `AppState` connect `Core AppState` to `Gallery GalleryRepository`, `Core TransportOutcome`, `Core ControlReducer`, `Tests ControlCoreTest`, `Tests ScaleLadderMigrationTest`, `Core Contracts.kt`, `App DiveViewModel`, `Core ControlCore`, `Tests .readyState`, `Core Reduction`, `Core HousingButtonEvent`, `Diagnostics .add`, `Core AppMode`, `App DiveViewModel.kt`, `Tests test.ps1`, `Camera CameraHudOverlay.kt`, `Core GalleryPreviewAction`, `Tests .turn`, `Diagnostics DiagnosticsStore`, `Core .reducePhoneControl`, `Core DeviceInfoUpdate`, `Diagnostics DiagnosticsScreen`, `Tests Rig`, `Core .activateMode`, `Tests CircularScaleTest`?**
  _High betweenness centrality (0.109) - this node is a cross-community bridge._
- **Why does `BleConnectionState` connect `Core BleConnectionState` to `Camera CameraHudOverlay.kt`, `Tutorial ButtonTutorialScreen.kt`, `Core ControlReducer`, `Bluetooth SimulatedBleTransport`, `Bluetooth BleSignal`, `Core AppMode`, `Core Contracts.kt`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `CameraRuntimeController` (e.g. with `Camera2HighSpeedRecorder` and `UnderwaterFrameAnalyzer`) actually correct?**
  _`CameraRuntimeController` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 34 inferred relationships involving `ControlReducer` (e.g. with `CircularScaleTest` and `.`a quarter turn at max sensitivity spends the whole focus range`()`) actually correct?**
  _`ControlReducer` has 34 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `AppState` (e.g. with `.nudge()` and `.`export bundle contains expected files`()`) actually correct?**
  _`AppState` has 2 INFERRED edges - model-reasoned connections that need verification._
