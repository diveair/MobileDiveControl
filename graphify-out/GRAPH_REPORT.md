# Graph Report - C:\Users\PC\Desktop\Mobile DiveControl  (2026-08-22)

## Corpus Check
- Large corpus: 152 files · ~1,385,337 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 1887 nodes · 4575 edges · 95 communities (80 shown, 15 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 249 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Camera Capability Catalog
- Transport Outcome
- Button Tutorial Screen
- Add
- Android BLE Transport
- Safety Sensor State
- Dive View Model
- Camera Shell Screen
- Camera Runtime Controller
- Scale Ladder Migration Test
- Contracts
- Control Core Test
- App State
- Ble Signal
- Housing BLE Link
- Decode Notification
- Gallery Repository
- Simulated Ble Transport
- Manufacturer BLE Protocol
- Wb Pipeline
- Control State Reducer
- Housing Identity Verifier Test
- Dive Control Content
- Camera State
- Ready State
- Seal Check Indicator
- Housing Uuid Resolver Test
- Camera Catalog Test
- Safety State Machine
- Focus Peaking Surface Processor
- Housing Button Event
- Commit Reduction
- Housing Command
- Housing Characteristics
- Gatt Outcome
- Remember
- Apply Camera2 Options
- Activate Highlighted Item
- Housing Uuid Resolver
- Camera Runtime Controller
- Manual Wb Colour
- Phone Control Command
- Submit Native Repeating Request
- Rig
- Water Pressure Decoding
- Customer Trust Contract
- Button Event Normalizer
- Turn
- Notification
- Connect To Housing
- Platform Effect
- Missing
- Camera HUD Overlay
- Housing Link Banner
- Safety Command
- Safety State Machine
- Auto Shrink Text
- Protocol Parser Test
- High Risk Housing Command
- Phone Battery Monitor
- Vacuum Store
- Dive Colors
- Plate
- Native Control Critical Path
- Regression Acceptance Focus
- Control Command
- State Driven Camera Preview
- Battery Indicator
- Depth Gauge
- Mobile Dive Control Documentation
- Stable Internal Command Contract
- Housing Ble Profile
- Dive It Ble Protocol
- Ble Housing Hardware Interface
- Housing Command Encoder Test
- Corrected Nordic N Rf5
- Recording Clock
- With
- Test Ps1
- Temperature Display
- Accessibility Behavior Algorithm
- Ble Connection Machine Test
- Gradlew
- Device Compatibility Tiers
- Cursor Speed Profile
- Discover Services
- Run Gradle Ps1
- Ble Connection State Machine
- Camera Mode
- Of
- App State Model

## God Nodes (most connected - your core abstractions)
1. `CameraRuntimeController` - 164 edges
2. `SafetyState` - 100 edges
3. `ControlReducer` - 90 edges
4. `AppState` - 80 edges
5. `CameraState` - 75 edges
6. `CameraCatalog` - 68 edges
7. `SafetyStateMachineTest` - 54 edges
8. `AndroidBleTransport` - 47 edges
9. `DiveViewModel` - 46 edges
10. `Reduction` - 45 edges

## Surprising Connections (you probably didn't know these)
- `Scenario Test Language` --semantically_similar_to--> `Scenario Runner Language`  [INFERRED] [semantically similar]
  TEST_PROTOCOL.md → docs/SCENARIOS.md
- `VacuumReadout` --references--> `SafetyState`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/ui/components/DepthGauge.kt → core/src/main/kotlin/com/mobiledivecontrol/core/Contracts.kt
- `Housing-to-Diagnostics Control Core Pipeline` --conceptually_related_to--> `Native Control Critical Path`  [INFERRED]
  README.md → Claude.md
- `AndroidBleTransport` --references--> `NotificationListener`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/platform/ble/AndroidBleTransport.kt → core/src/main/kotlin/com/mobiledivecontrol/core/BleTransport.kt
- `AndroidBleTransport` --references--> `HousingCharacteristic`  [EXTRACTED]
  app/src/main/kotlin/com/mobiledivecontrol/platform/ble/AndroidBleTransport.kt → core/src/main/kotlin/com/mobiledivecontrol/core/HousingBleProfile.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Native Control Flow Architecture** — docs_architecture_native_first_underwater_control, docs_architecture_critical_path_constraints, docs_architecture_event_reducer_effect_flow, docs_architecture_boundary_rules [EXTRACTED 1.00]
- **Vendor Sensor Payload Family** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_temperature_encoding, docs_ble_protocol_barometric_pressure_encoding, docs_ble_protocol_cover_state_mapping [EXTRACTED 1.00]
- **Depth Measurement Evidence Chain** — docs_ble_protocol_water_pressure_encoding, docs_ble_protocol_water_pressure_resolution, docs_diagnostics_sensor_diagnostic_fields, docs_diagnostics_sensor_update_rates, docs_safety_sensor_freshness_rules, docs_traceability_sensor_decoder_tests [INFERRED 0.95]
- **WFH07 Bluetooth Service Architecture** — manufactuer_s_spec_battery_service_180f, manufactuer_s_spec_device_information_service_180a, manufactuer_s_spec_key_service_1523, manufactuer_s_spec_sensor_vacuum_control_service_1623 [EXTRACTED 1.00]
- **Sensor and Vacuum Reporting Flow** — manufactuer_s_spec_sensor_vacuum_control_service_1623, manufactuer_s_spec_motor_control_characteristic_1624, manufactuer_s_spec_water_pressure_characteristic_1625, manufactuer_s_spec_water_temperature_characteristic_1626, manufactuer_s_spec_barometric_pressure_characteristic_1627, manufactuer_s_spec_air_extraction_cover_characteristic_1628, manufactuer_s_spec_solenoid_valve_characteristic_1629 [EXTRACTED 1.00]

## Communities (95 total, 15 thin omitted)

### Community 0 - "Camera Capability Catalog"
Cohesion: 0.12
Nodes (15): CameraCatalog, CameraModeProfile, CameraRailEntry, CameraSettingSpec, Boolean, Int, IntRange, List (+7 more)

### Community 1 - "Transport Outcome"
Cohesion: 0.07
Nodes (31): Array, String, main(), List, String, ScenarioExecution, ScenarioScriptRunner, Byte (+23 more)

### Community 2 - "Button Tutorial Screen"
Cohesion: 0.09
Nodes (60): ContinuePill(), drawBanner(), drawCapComingOff(), drawCheckBadge(), drawDownAsk(), drawPermissions(), dwellFor(), IntroCarousel (+52 more)

### Community 3 - "Add"
Cohesion: 0.06
Nodes (34): Boolean, Unit, MainActivity, DiveControlTheme(), Modifier, ModeIndicator(), Bundle, ComponentActivity (+26 more)

### Community 4 - "Android BLE Transport"
Cohesion: 0.09
Nodes (23): AndroidBleTransport, BluetoothGatt, BluetoothGattCharacteristic, Boolean, ByteArray, Int, List, Long (+15 more)

### Community 6 - "Dive View Model"
Cohesion: 0.07
Nodes (24): AndroidViewModel, Modifier, SealStatusBadge(), DebugButtonCluster(), DebugSimulationPanel(), HousingButton(), Color, Dp (+16 more)

### Community 7 - "Camera Shell Screen"
Cohesion: 0.10
Nodes (53): BottomBarChip(), bottomBarIcon(), bottomBarLabel(), bottomBarValue(), BottomEditCard(), BottomSettingsTray(), BottomSettingsTrayLegacy(), CameraPreviewPlaceholder() (+45 more)

### Community 8 - "Camera Runtime Controller"
Cohesion: 0.07
Nodes (22): CameraRuntimeController, com, IntRange, LifecycleOwner, List, Unit, WbCalPoint, CameraCaptureSession (+14 more)

### Community 9 - "Scale Ladder Migration Test"
Cohesion: 0.08
Nodes (17): CameraSessionStore, exposureMagnitude(), isoMagnitude(), kelvinMagnitude(), Double, List, Map, MutableMap (+9 more)

### Community 10 - "Contracts"
Cohesion: 0.07
Nodes (49): AlertPriority, Back, CameraCaptureType, CameraCommand, CameraControl, CameraRailLevel, CameraSettingKind, CameraUiZone (+41 more)

### Community 11 - "Control Core Test"
Cohesion: 0.07
Nodes (8): MeteredExposure, SliderSensitivity, ControlCore, Int, ControlCoreTest, Int, Long, String

### Community 12 - "App State"
Cohesion: 0.11
Nodes (6): AppState, GalleryState, PermissionKind, Reduction, Boolean, Duration

### Community 13 - "Ble Signal"
Cohesion: 0.09
Nodes (23): HousingLinkService, Boolean, Int, Intent, LinkStatus, BleConnectionMachine, BleSignal, BleTransition (+15 more)

### Community 14 - "Housing BLE Link"
Cohesion: 0.15
Nodes (16): HousingLink, Boolean, ByteArray, Map, Set, String, Session, SessionResult (+8 more)

### Community 15 - "Decode Notification"
Cohesion: 0.16
Nodes (24): BarometricPressure, CoverState, SensorUpdate, WaterPressure, WaterTemperature, Battery, Button, DecodedButtonPacket (+16 more)

### Community 16 - "Gallery Repository"
Cohesion: 0.12
Nodes (22): GalleryRepository, android, Boolean, List, Long, String, ButtonHint(), ConfirmationOverlay() (+14 more)

### Community 17 - "Simulated Ble Transport"
Cohesion: 0.11
Nodes (10): BleTransportOrchestrator, Boolean, encodeLittleEndianU32(), Boolean, ByteArray, Int, Long, UByte (+2 more)

### Community 18 - "Manufacturer BLE Protocol"
Cohesion: 0.08
Nodes (32): Air Extraction Cover Signal Characteristic (UUID 0x1628), Automatic Vacuum Pumping Module, Barometric Pressure Characteristic (UUID 0x1627), Barometric Pressure Encoding: UInt32 Little-Endian, 1 Pa, Battery Level Characteristic (UUID 0x2A19), Battery Service (UUID 0x180F), Bluetooth Low Energy 5.0, Bluetooth Advertising Parameters (+24 more)

### Community 19 - "Wb Pipeline"
Cohesion: 0.15
Nodes (8): android, androidx, Array, Double, Pair, SensorColorCalibration, FocusCurveMode, DoubleArray

### Community 20 - "Control State Reducer"
Cohesion: 0.19
Nodes (4): ControlReducer, Int, List, Pair

### Community 21 - "Housing Identity Verifier Test"
Cohesion: 0.11
Nodes (8): HousingIdentityVerifier, HousingService, Set, String, Rejected, VerificationResult, Verified, HousingIdentityVerifierTest

### Community 22 - "Dive Control Content"
Cohesion: 0.11
Nodes (27): DiagnosticsScreen(), InfoCard(), InfoRow(), Modifier, String, SectionHeader(), DiveControlContent(), DiveControlScreen() (+19 more)

### Community 23 - "Camera State"
Cohesion: 0.22
Nodes (3): Size, String, CameraState

### Community 24 - "Ready State"
Cohesion: 0.13
Nodes (3): HousingState, InputRouterTest, Boolean

### Community 25 - "Seal Check Indicator"
Cohesion: 0.16
Nodes (27): accented(), CenteredSealBanner(), formatRemaining(), Boolean, Color, Double, Dp, Float (+19 more)

### Community 28 - "Safety State Machine"
Cohesion: 0.25
Nodes (7): Boolean, Double, List, Long, String, SafetyMachineResult, SafetyStateMachine

### Community 29 - "Focus Peaking Surface Processor"
Cohesion: 0.13
Nodes (13): FocusPeakingSurfaceProcessor, Int, String, EGLConfig, EGLContext, EGLDisplay, EGLSurface, FloatBuffer (+5 more)

### Community 30 - "Housing Button Event"
Cohesion: 0.17
Nodes (13): BackOrSafety, Down, HousingButtonEvent, Left, Ok, Right, Shutter, Unknown (+5 more)

### Community 31 - "Commit Reduction"
Cohesion: 0.22
Nodes (9): ProcessingOutcome, Boolean, ByteArray, Double, Instant, List, Long, Map (+1 more)

### Community 32 - "Housing Command"
Cohesion: 0.10
Nodes (19): HousingFeatureFlags, Boolean, String, DeviceInfoUpdate, Disconnect, FirmwareRevision, HardwareRevision, HousingCommand (+11 more)

### Community 33 - "Housing Characteristics"
Cohesion: 0.13
Nodes (9): BleConnectionParams, BleTransport, Boolean, ByteArray, Set, String, NotificationListener, ByteArray (+1 more)

### Community 34 - "Gatt Outcome"
Cohesion: 0.22
Nodes (11): GattOutcome, GattQueue, Kind, BluetoothGatt, BluetoothGattCharacteristic, Boolean, ByteArray, Int (+3 more)

### Community 35 - "Remember"
Cohesion: 0.12
Nodes (12): DiveControlApp, HousingStore, Map, String, CameraPreview(), LifecycleOwner, Modifier, Double (+4 more)

### Community 36 - "Apply Camera2 Options"
Cohesion: 0.16
Nodes (5): Float, Camera, CaptureRequestOptions, Rect, TonemapCurve

### Community 37 - "Activate Highlighted Item"
Cohesion: 0.17
Nodes (7): SealConfidence, Double, Long, String, ManualFocusPreparation, SliderLaw, SliderMotor

### Community 38 - "Housing Uuid Resolver"
Cohesion: 0.25
Nodes (10): Ambiguous, HousingUuidResolver, Int, Pair, Set, String, Match, MatchSource (+2 more)

### Community 39 - "Camera Runtime Controller"
Cohesion: 0.18
Nodes (9): BackCameraProfile, Map, LensFocusCapabilityProfile, ManualFocusRequest, ManualFocusTransport, PhysicalLensProfile, SessionSignature, WbAnchor (+1 more)

### Community 40 - "Manual Wb Colour"
Cohesion: 0.20
Nodes (6): AwbCurvePoint, HarvestedWb, Int, Long, FloatArray, RggbChannelVector

### Community 41 - "Phone Control Command"
Cohesion: 0.12
Nodes (17): Click, DecreaseCursorSpeed, Home, IncreaseCursorSpeed, LongClick, MoveCursorDown, MoveCursorLeft, MoveCursorRight (+9 more)

### Community 42 - "Submit Native Repeating Request"
Cohesion: 0.26
Nodes (4): Boolean, Byte, T, CaptureRequest

### Community 43 - "Rig"
Cohesion: 0.32
Nodes (5): Int, Long, String, Rig, WbAutoGateTest

### Community 44 - "Water Pressure Decoding"
Cohesion: 0.16
Nodes (15): Barometric Pressure Little-Endian Encoding, Vendor Cover State Polarity, Housing Hardware Specifications, Housing Sensor and Control Service, Water Pressure Little-Endian Encoding and kPa Conversion, Water Pressure Sensor 50 kPa Resolution, Water Temperature Little-Endian Encoding, Sensor Diagnostic Fields (+7 more)

### Community 45 - "Customer Trust Contract"
Cohesion: 0.15
Nodes (13): Customer Trust Contract, Diagnostics and Debugging, Bounded In-Memory Diagnostic Ring Buffers, Diagnostic Export Bundle, JSONL Diagnostic Log Format, Post-Dive Diagnostics Philosophy, Diagnostic Export Privacy, Local Data Classification (+5 more)

### Community 46 - "Button Event Normalizer"
Cohesion: 0.22
Nodes (5): AcceptedButtonEvent, ButtonEventNormalizer, Instant, Int, ButtonEventNormalizerTest

### Community 47 - "Turn"
Cohesion: 0.28
Nodes (5): Int, List, Long, String, ValueLadderGearingTest

### Community 48 - "Notification"
Cohesion: 0.24
Nodes (8): Ble, HousingLinkEvent, Identity, Kind, Any, Int, List, Notification

### Community 49 - "Connect To Housing"
Cohesion: 0.29
Nodes (5): Connected, ConnectionResult, Failed, Map, String

### Community 50 - "Platform Effect"
Cohesion: 0.17
Nodes (12): CreateGalleryFolder, DeleteGalleryFolder, DeleteGalleryItem, EmitAlert, ExecuteCamera, ExecuteHousing, ExecutePhoneControl, LoadExifData (+4 more)

### Community 51 - "Missing"
Cohesion: 0.36
Nodes (5): BlePermissions, Boolean, Context, List, String

### Community 52 - "Camera HUD Overlay"
Cohesion: 0.27
Nodes (9): CameraHudOverlay(), formatTimestamp(), Boolean, Modifier, String, OverlayPill(), rememberClockText(), ConnectionStatus() (+1 more)

### Community 53 - "Housing Link Banner"
Cohesion: 0.38
Nodes (10): alertFor(), caution(), HousingLinkBanner(), info(), Boolean, Modifier, String, LinkAlert (+2 more)

### Community 54 - "Safety Command"
Cohesion: 0.18
Nodes (11): AcknowledgeWarning, CancelVacuumCheck, CloseSolenoid, DismissSealCheck, OpenSolenoid, ResetSealState, SafetyCommand, SkipToResult (+3 more)

### Community 55 - "Safety State Machine"
Cohesion: 0.31
Nodes (9): BarometricPressureSample, CancelVacuumCheckRequested, CoverStateChanged, DismissSealCheckRequested, ResetSealStateRequested, SafetySignal, SafetyThresholds, SkipToResultRequested (+1 more)

### Community 56 - "Auto Shrink Text"
Cohesion: 0.20
Nodes (9): AutoShrinkText(), AnnotatedString, Color, Float, Int, Modifier, String, TextStyle (+1 more)

### Community 58 - "High Risk Housing Command"
Cohesion: 0.20
Nodes (10): High-Risk Housing Command Policy, OTA Disabled in MVP, Vendor-Specified Vacuum Procedure, High-Risk Safety Commands, High-Risk Housing Commands Disabled, Vacuum Command Safety Preconditions, Vacuum Workflow State Machine, High-Risk Command Safeguards (+2 more)

### Community 59 - "Phone Battery Monitor"
Cohesion: 0.25
Nodes (5): Int, Intent, StateFlow, PhoneBatteryMonitor, BroadcastReceiver

### Community 60 - "Vacuum Store"
Cohesion: 0.28
Nodes (5): Double, Int, Long, PersistedVacuum, VacuumStore

### Community 61 - "Dive Colors"
Cohesion: 0.28
Nodes (5): DiveColors, Boolean, Color, Double, Int

### Community 62 - "Plate"
Cohesion: 0.39
Nodes (6): HousingPlates, Bitmap, Context, ImageBitmap, Int, rememberHousingPlate()

### Community 63 - "Native Control Critical Path"
Cohesion: 0.22
Nodes (9): Architecture Kill Rules, Native Control Critical Path, Native Core with Optional React Native Shell, Architecture Boundary Rules, Critical Path Constraints, Native Core and Optional RN Shell Architecture, Native-First Underwater Control System, Offline Core Network Policy (+1 more)

### Community 64 - "Regression Acceptance Focus"
Cohesion: 0.22
Nodes (9): Simulator-Driven Development, Testing Strategy, Regression Scenario Suite, Scenario Runner Language, Test Coverage Gaps, Local Test and Scenario Workflow, Regression Acceptance Focus, Deterministic Test Protocol (+1 more)

### Community 65 - "Control Command"
Cohesion: 0.22
Nodes (9): ControlCommand, ExportDiagnostics, LockControls, SwitchToCameraMode, SwitchToDiagnosticsMode, SwitchToSafetyMode, SwitchToTransparentPhoneMode, SystemCommand (+1 more)

### Community 66 - "State Driven Camera Preview"
Cohesion: 0.25
Nodes (7): com, LifecycleOwner, List, Modifier, String, Unit, StateDrivenCameraPreview()

### Community 67 - "Battery Indicator"
Cohesion: 0.43
Nodes (6): BatteryIndicator(), DualBatteryIndicator(), ImageVector, Int, Modifier, String

### Community 68 - "Depth Gauge"
Cohesion: 0.38
Nodes (6): DepthGauge(), Boolean, Double, Modifier, temperatureTint(), VacuumReadout

### Community 69 - "Mobile Dive Control Documentation"
Cohesion: 0.33
Nodes (7): MobileDiveControl Product Specification, Current JVM Control Core, MobileDiveControl Documentation Index, Traceability Audit Trail Rule, Code-to-Spec Traceability, Housing-to-Diagnostics Control Core Pipeline, Honest Platform Adapter Boundaries

### Community 70 - "Stable Internal Command Contract"
Cohesion: 0.29
Nodes (7): AppState Single Source of Truth, Stable Internal Command Contract, Semantic Housing Button Events, Mode-Aware Input Routing Table, Stable Command Contract Reference, Contract Type Mapping, Input Router Spec Deviations

### Community 71 - "Housing Ble Profile"
Cohesion: 0.43
Nodes (6): HousingBleProfile, List, String, standardUuid(), vendorUuid(), UShort

### Community 72 - "Dive It Ble Protocol"
Cohesion: 0.29
Nodes (7): DIVE IT BLE Protocol Reference, Housing Identity Trust Model, UMEING Vendor Hardware Protocol A4.0, Housing Firmware Compatibility Check, Real Housing First Contact Procedure, BLE Device Trust Model, Parser Hardening Rules

### Community 73 - "Ble Housing Hardware Interface"
Cohesion: 0.33
Nodes (6): BLE Housing Hardware Interface, Safety and Vacuum Workflow, Security Architecture, Water Pressure and Depth Telemetry, Architecture Security Zones, Visible-Failure Defense in Depth

### Community 75 - "Corrected Nordic N Rf5"
Cohesion: 0.40
Nodes (6): Button Notification Service, Corrected Nordic nRF5 Base UUID, Legacy Malformed Vendor UUID String, BLE Characteristic Subscription Order, Hardware Button Byte Check, Hardware UUID Discovery Dump

### Community 76 - "Recording Clock"
Cohesion: 0.40
Nodes (4): Boolean, Long, RecordingClock, MutableState

### Community 79 - "Temperature Display"
Cohesion: 0.50
Nodes (3): Double, Modifier, TemperatureDisplay()

### Community 80 - "Accessibility Behavior Algorithm"
Cohesion: 0.50
Nodes (4): Accessibility Behavior Algorithm, Transparent Phone Mode, Transparent Phone Mode Privacy, Permission and Accessibility Security

### Community 82 - "Gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 83 - "Device Compatibility Tiers"
Cohesion: 0.67
Nodes (3): Compatibility Matrix, Android Version Compatibility Matrix, Device Compatibility Tiers

## Ambiguous Edges - Review These
- `Corrected Nordic nRF5 Base UUID` → `Legacy Malformed Vendor UUID String`  [AMBIGUOUS]
  docs/BLE_PROTOCOL.md · relation: conceptually_related_to

## Knowledge Gaps
- **59 isolated node(s):** `Kind`, `BleConnectionParams`, `AlertPriority`, `Direction`, `CameraRailLevel` (+54 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **15 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Corrected Nordic nRF5 Base UUID` and `Legacy Malformed Vendor UUID String`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `AppState` connect `App State` to `Transport Outcome`, `Add`, `Activate Highlighted Item`, `Dive View Model`, `Scale Ladder Migration Test`, `Contracts`, `Control Core Test`, `Rig`, `Turn`, `Camera HUD Overlay`, `Control State Reducer`, `Dive Control Content`, `Ready State`, `Housing Button Event`, `Commit Reduction`?**
  _High betweenness centrality (0.201) - this node is a cross-community bridge._
- **Why does `CameraRuntimeController` connect `Camera Runtime Controller` to `State Driven Camera Preview`, `Apply Camera2 Options`, `Camera Runtime Controller`, `Manual Wb Colour`, `Submit Native Repeating Request`, `Wb Pipeline`, `Camera State`, `Camera Catalog Test`, `Focus Peaking Surface Processor`?**
  _High betweenness centrality (0.142) - this node is a cross-community bridge._
- **Why does `DiveViewModel` connect `Dive View Model` to `Remember`, `Activate Highlighted Item`, `App State`, `Housing BLE Link`, `Decode Notification`, `Platform Effect`, `Housing Button Event`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Are the 51 inferred relationships involving `SafetyState` (e.g. with `.`a boot record refuted at ambient is a restart seal failure, confirmed by two readings`()` and `.`a capped pump is stopped within seconds, not left grinding`()`) actually correct?**
  _`SafetyState` has 51 INFERRED edges - model-reasoned connections that need verification._
- **Are the 23 inferred relationships involving `ControlReducer` (e.g. with `.`a quarter turn at max sensitivity spends the whole focus range`()` and `.`AF exits to whichever end the wheel points at`()`) actually correct?**
  _`ControlReducer` has 23 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Kind`, `BleConnectionParams`, `AlertPriority` to the rest of the system?**
  _74 weakly-connected nodes found - possible documentation gaps or missing edges._