# MobileDiveControl — Code-to-Spec Traceability

This document maps every code implementation detail to its spec requirement in `Claude.md`, and notes any deviations, simplifications, or additions.

---

## 1. Contracts.kt — Type System

### Enums

| Code Enum | Spec Section | Match |
|---|---|---|
| `AppMode` (6 values) | §10.3 | ✅ Exact match: CameraLive, CameraAdjust, PhoneCursor, PhoneTarget, Safety, Diagnostics |
| `BleConnectionState` (9 values) | §18.2 | ✅ Exact match |
| `SealState` (8 values) | §17.1 | ✅ Exact match |
| `CursorSpeedProfile` (4 values) | §20.2 | ✅ Exact match: Precision, Normal, Fast, SmartTarget |
| `AlertPriority` (4 values) | §17.3 | ✅ Maps to priority tiers (Critical=1-2, High=3-4, Medium=5-6, Low=7-8) |
| `PermissionKind` (7 values) | §21.1 | ✅ Covers all required Android permissions |
| `Direction` (4 values) | §16.1 | ✅ Up, Down, Left, Right |
| `CameraControl` (11 values) | §13.6 | ✅ Photo, Video, Zoom, Iso, ShutterSpeed, WhiteBalance, ManualFocus, ExposureCompensation, Lens, Grid, FocusPeaking |

### State Data Classes

| Code Type | Spec Section | Notes |
|---|---|---|
| `AppState` | §10.1 | ✅ Single source of truth — contains mode, bleConnectionState, housing, camera, phoneControl, safety, permissions, controlsLocked, lastWarning |
| `HousingState` | §10.1 (housing) | ✅ advertisingName, trustedIdentity, connected, inputEnabled, batteryPercent, firmwareVersion, lastButton, lastRawButton |
| `CameraState` | §10.1 (camera) | ✅ recording, supportedControls, selectedControlIndex, zoomFactor, capabilityTier |
| `PhoneControlState` | §10.1 (phoneControl) | ✅ cursorSpeedProfile, smartTargetEnabled, smartTargetAvailable |
| `SafetyState` | §10.1 (safety) | ✅ sealState, coverOpen, barometricPressureKpa, waterPressureKpa, waterTemperatureC, baselinePressureKpa, stabilizationSamples, warning |
| `PermissionsState` | §10.1 (permissions) | ✅ Per-permission booleans with `canUsePhoneControl()` and `canUseOverlayCursor()` helpers |

### Sealed Command Interfaces

| Code Type | Spec Section | Match |
|---|---|---|
| `HousingButtonEvent` (10 values) | §11.1 | ✅ Exact match |
| `CameraCommand` (18 values) | §11.2 | ✅ Exact match |
| `PhoneControlCommand` (17 values) | §11.3 | ✅ Matches + adds `MoveTarget(direction)` for directional Smart Target |
| `SafetyCommand` (8 values) | §11.4 | ✅ Exact match |
| `HousingCommand` (8 values) | §11.5 | ✅ Exact match |
| `SystemCommand` (7 values) | §11.6 | ✅ Exact match |

### Additional Types (Implementation-specific)

| Code Type | Purpose |
|---|---|
| `PlatformEffect` | Side effects produced by reducers — camera, phone control, housing, alert, reconnect, export |
| `SensorUpdate` | Sensor data events: CoverState, BarometricPressure, WaterPressure, WaterTemperature |
| `Reduction` | Reducer output: new state + effects + notes |
| `ProcessingOutcome` | Full processing result: state + effects + notes + exported files |

---

## 2. ProtocolParser.kt

| Feature | Spec Section | Match |
|---|---|---|
| 1-byte button packet validation | §18.6 | ✅ Rejects packets ≠ 1 byte |
| Button value → event mapping | §5.2 | ✅ All 9 values mapped correctly |
| Unknown button → `Unknown(rawValue)` | §18.6 | ✅ Does not crash |
| 1-byte battery level validation | §5.1 | ✅ Validates length and range (0–100) |
| Error codes for malformed packets | §18.6 | ✅ Structured `ProtocolError` with code and message |
| Parse result as sealed interface | §18.6 | ✅ `ParseResult.Success` / `ParseResult.Failure` |

### Not Yet Implemented (Expected Later)
- Sensor data parsing (water pressure, temperature, barometric pressure) — these will need little-endian multi-byte decoding
- Cover state parsing
- IR flashlight command encoding

---

## 3. BleConnectionMachine.kt

| Feature | Spec Section | Match |
|---|---|---|
| 9 connection states | §18.2 | ✅ Exact match |
| Reconnect on disconnect (if not Idle) | §18.5 | ✅ |
| Reconnect backoff schedule | §18.5 | ✅ Exact: 0, 500ms, 1s, 2s, 5s cap |
| Signal-driven transitions | §18.2 | ✅ 8 signals: StartScan, Connect, DiscoverServices, Subscribe, Ready, Disconnect, Degrade, Fail, Reset |

---

## 4. ButtonEventNormalizer.kt

| Feature | Spec Section | Match |
|---|---|---|
| Duplicate packet filtering | §19.1 | ✅ 75ms duplicate window |
| Repeat counting | §20.3 | ✅ Tracks repeat count per event |
| Reset capability | — | ✅ Implementation convenience |

---

## 5. InputRouter.kt

| Feature | Spec Section | Match |
|---|---|---|
| Mode-aware routing | §12.1 | ✅ Routes by AppMode |
| Controls locked check | §11.6 | ✅ Returns note if locked |
| Input disabled check | — | ✅ Returns note if housing input disabled |
| Unknown button ignored | §18.6 | ✅ Logged with hex, not crashed |

### CameraLive Mode Routing vs Spec §12.1

| Button | Spec Says | Code Does | Match |
|---|---|---|---|
| Up | Previous quick control | `SelectPreviousControl` | ✅ |
| Down | Next quick control | `SelectNextControl` | ✅ |
| Left | Previous control | `SelectPreviousControl` | ✅ |
| Right | Next control | `SelectNextControl` | ✅ |
| OK | Open adjust | Mode → `CameraAdjust` | ✅ |
| Shutter | Capture/record | `CapturePhoto` or `ToggleVideoRecording` (context-aware) | ✅ |
| Z+ | Zoom in | `ZoomIn` | ✅ |
| Z- | Zoom out | `ZoomOut` | ✅ |
| BackOrSafety | Back/cancel | Mode → `Safety` | ⚠️ Spec says "Back / cancel", code goes to Safety mode |

### CameraAdjust Mode Routing vs Spec §12.1

| Button | Spec Says | Code Does | Match |
|---|---|---|---|
| Up | Increase value | `IncreaseSelectedControl` | ✅ |
| Down | Decrease value | `DecreaseSelectedControl` | ✅ |
| Left | Fine decrease | `DecreaseSelectedControl` | ✅ |
| Right | Fine increase | `IncreaseSelectedControl` | ✅ |
| OK | Confirm / close | Mode → `CameraLive` | ✅ |
| Shutter | Capture if safe | Context-aware capture | ✅ |
| Z+ | Coarse increase | `ZoomIn` | ⚠️ Code uses ZoomIn, not a "coarse increase" of selected control |
| Z- | Coarse decrease | `ZoomOut` | ⚠️ Code uses ZoomOut, not a "coarse decrease" of selected control |
| BackOrSafety | Exit adjust | Mode → `CameraLive` | ✅ |

### PhoneCursor Mode — ✅ Fully matches spec §12.1
### PhoneTarget Mode — ✅ Fully matches spec §12.1 (uses `MoveTarget(direction)`)

### Safety Mode Routing

| Button | Code Does | Notes |
|---|---|---|
| OK | `StartVacuumCheck` | Custom safety routing |
| Shutter | `TriggerFlash` | ✅ |
| BackOrSafety | `CancelVacuumCheck` | ✅ |
| Others | Ignored with note | ✅ |

### Diagnostics Mode Routing

| Button | Code Does | Notes |
|---|---|---|
| OK | `ExportDiagnostics` | ✅ |
| BackOrSafety | Mode → `CameraLive` | ✅ |
| Others | Ignored with note | ✅ |

---

## 6. SafetyStateMachine.kt

| Feature | Spec Section | Match |
|---|---|---|
| Safety signals as sealed interface | §17 | ✅ 5 signals |
| Vacuum workflow | §17.2 | ✅ Simplified — uses cover sensor events instead of explicit ConfirmCoverOpen/ConfirmCoverClosed states |
| Motor + solenoid effects | §17.2 | ✅ `SetVacuumMotor` + `SetSolenoidValve` effects emitted |
| Stabilization with configurable thresholds | §17.2 | ✅ `SafetyThresholds` data class |
| Seal fail → Critical alert | §17.3 | ✅ `EmitAlert(Critical, ...)` |
| Cancel stops motor + solenoid | §17.2 | ✅ Both effects emitted |
| Reset returns to Unknown | §17.4 | ✅ |
| Cover must be open before vacuum | §17.2 | ✅ Returns Warning if not open |

### Configurable Thresholds

| Threshold | Default | Purpose |
|---|---|---|
| `vacuumPassDeltaKpa` | 5.0 | Pressure drop required to pass vacuum |
| `stabilizationToleranceKpa` | 0.5 | Max pressure variance during stabilization |
| `requiredStabilizationSamples` | 3 | Number of stable samples needed |

---

## 7. DiagnosticsStore.kt

| Feature | Spec Section | Match |
|---|---|---|
| Ring buffer sizes | §23.1 | ✅ Exact: 500/500/300/200/100/100 |
| JSONL export format | §22.3 | ✅ |
| Export bundle files | §23.3 | ✅ All 8 files: device-info, housing-info, camera-capabilities, permission-state, compatibility-info, event-log, error-log, latency-summary |
| State summary (no raw content) | §22.2 | ✅ Only mode/ble/seal/recording |
| Latency tracking | §19.2 | ✅ |

### Ring Buffer Implementation
- Uses `ArrayDeque` with manual capacity enforcement
- `RingBuffer<T>` is a generic bounded buffer
- Records use `Instant` timestamps

---

## 8. JsonSupport.kt

| Feature | Notes |
|---|---|
| `jsonObject(vararg pairs)` | Lightweight JSON builder — no external dependency |
| `escapeJson(value)` | Handles all JSON special characters + control chars |
| Supports: String, Number, Boolean, Enum, Iterable, null | ✅ |

---

## 9. ControlCore.kt — Orchestrator

| Feature | Spec Section | Match |
|---|---|---|
| Injectable Clock for testing | — | ✅ Good testability practice |
| `handleButtonPayload()` — full pipeline | §8.3 | ✅ BLE → parse → normalize → route → reduce → diagnose |
| `dispatch()` — direct command | §10.2 | ✅ Event → Reducer → State → Effect |
| `advanceBle()` — BLE state transitions | §18.2 | ✅ Delegates to BleConnectionMachine |
| `updatePermission()` — permission changes | §21 | ✅ Delegates to reducer with fallback logic |
| `updateBatteryLevel()` — housing battery | §5.1 | ✅ |
| `updateSensor()` — sensor data | §5.1 | ✅ Cover, barometric, water pressure, temperature |
| `forceMode()` — scenario/test mode switch | — | ✅ Implementation convenience |
| `exportDiagnostics()` — export bundle | §23.3 | ✅ |
| Latency tracking on every path | §19.2 | ✅ `elapsedMillis()` recorded to diagnostics |
| Reconnect attempt tracking | §18.5 | ✅ Increments on disconnect, resets on Ready/Idle |

### Critical Path Verification
The `handleButtonPayload` method follows the spec's critical path exactly:
```
BLE notification → timestamp → packet validation → protocol parser → semantic event
  → debounce/repeat → mode router → command executor → in-memory log
```
No network calls, disk writes, JSON serialization, or RN bridge calls in this path. ✅

---

## 10. ControlReducer.kt — State Reducer

| Feature | Spec Section | Match |
|---|---|---|
| `applyRouteDecision()` — process route output | §10.2 | ✅ Applies mode override + commands sequentially |
| Camera permission check on camera effects | §21.3 | ✅ Returns warning if camera permission disabled |
| Phone control permission check | §21.3 | ✅ Checks `canUsePhoneControl()` before any phone command |
| Overlay permission fallback to SmartTarget | §28.4 | ✅ Falls back to PhoneTarget if overlay revoked |
| Zoom clamping (1.0–8.0, 0.1 step) | §13.7 | ✅ Bounded zoom with step increments |
| Control strip cycling (wrapping index) | §13.6 | ✅ Modular index for next/previous control |
| Video recording toggle | §11.2 | ✅ Toggles `camera.recording` state |
| Cursor speed cycling | §20.2 | ✅ Precision ↔ Normal ↔ Fast (capped at boundaries) |
| Smart Target / Free Cursor switching | §15.2 | ✅ PhoneCursor ↔ PhoneTarget with availability check |
| Safety command delegation | §17 | ✅ Routes through SafetyStateMachine |
| BLE disconnect → `HOUSING DISCONNECTED` warning | §28.1 | ✅ |
| BLE reconnect scheduling | §18.5 | ✅ `ScheduleReconnect` effect emitted |
| Permission revocation → mode fallback | §21.3 | ✅ Falls back to CameraLive or Diagnostics |
| System command: mode switching with permission checks | §11.6 | ✅ |
| `ExportDiagnostics` → `PlatformEffect.ExportDiagnostics` | §23.3 | ✅ |
| `LockControls` / `UnlockControls` | §11.6 | ✅ |

### Reducer Behavior Details

| Scenario | Code Behavior |
|---|---|
| Camera permission revoked in CameraLive | Mode → Diagnostics + alert |
| Accessibility revoked in PhoneCursor | Mode → CameraLive (or Diagnostics) + alert |
| Overlay revoked in PhoneCursor | Mode → PhoneTarget (if SmartTarget available) or fallback |
| Zoom increase beyond 8.0 | Clamped to 8.0 |
| Zoom decrease below 1.0 | Clamped to 1.0 |
| Adjusting non-adjustable control (Photo/Video) | Note "not adjustable", no effect |
| Adjusting adjustable control (ISO, WB, etc.) | Emits `PlatformEffect.ExecuteCamera` |

---

## 11. Main.kt — Entry Point

| Feature | Notes |
|---|---|
| Accepts scenario file path as CLI argument | `args[0]` or defaults to `scenarios/smoke.scenario` |
| Creates `ScenarioScriptRunner` | Default `ControlCore` |
| Prints rendered transcript | `result.render()` |

---

## 12. ScenarioScriptRunner.kt — Test Harness

| Feature | Notes |
|---|---|
| Parses scenario files line by line | Empty lines and `#` comments ignored |
| Supports all BLE signals | `scan`, `connect`, `discover`, `subscribe`, `ready`, `disconnect`, `degrade`, `fail`, `reset` |
| Supports all permissions | 7 permission kinds with `on`/`off`/`true`/`false`/`enabled`/`disabled` |
| Supports all modes | `camera`, `camera-adjust`, `phone-cursor`, `phone-target`, `safety`, `diagnostics` |
| Supports button hex input | Parses `0x` prefix, validates 0–255 range |
| Supports `malformed` button keyword | Sends 2-byte payload to test parser hardening |
| Supports battery level | Integer 0–100 |
| Supports all sensor types | `cover`, `barometric`, `water-pressure`, `water-temp` |
| Supports vacuum commands | `start`, `cancel`, `reset` |
| Supports `export` | Triggers diagnostic export, captures files |
| Supports `state` | Prints current state summary |
| State rendering | `mode=X ble=X connected=X input=X battery=X zoom=X recording=X seal=X [warning=X]` |

---

## 13. run-gradle.ps1 — Gradle Helper

| Feature | Notes |
|---|---|
| Auto-detects JAVA_HOME | Searches Eclipse Adoptium, Java, Microsoft directories |
| Validates JDK exists | Checks for `bin/java.exe` |
| Sets GRADLE_USER_HOME | Falls back to `.gradle-user-home` in repo if needed |
| Invokes `gradlew.bat` | Passes all arguments through |
| Simplifies test commands | `.\run-gradle.ps1 test` instead of manual env setup |

---

## 14. Test Coverage Analysis

All 6 test files are now implemented. Here's what each covers:

### ProtocolParserTest.kt (3 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `decodes supported button values` | §5.2 | OK button (0x50) → `HousingButtonEvent.Ok` with correct rawValue |
| `maps unknown button values without crashing` | §18.6 | Unknown byte (0x7F) → `Unknown(rawValue)`, no crash |
| `rejects malformed button packets` | §18.6 | 2-byte payload → `Failure` with `button_length_invalid` code |

### BleConnectionMachineTest.kt (2 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `disconnect enters reconnecting with first retry immediate` | §18.5 | Ready → Disconnect → Reconnecting with Duration.ZERO |
| `reconnect backoff is capped at five seconds` | §18.5 | 500ms, 1s, 2s, 5s (attempt 9 still 5s) |

### InputRouterTest.kt (4 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `camera live shutter captures photo when photo is selected` | §12.1 | Shutter → CapturePhoto when Photo control selected |
| `camera live shutter toggles recording when video is selected` | §12.1 | Shutter → ToggleVideoRecording when Video control selected (index 1) |
| `phone target routes direction as smart target move` | §12.1 | Right → MoveTarget(Right) in PhoneTarget mode |
| `locked controls produce a visible note and no command` | §11.6 | controlsLocked=true → empty commands, "Controls are locked." note |

### SafetyStateMachineTest.kt (2 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `vacuum start requires cover open` | §17.2 | StartVacuumCheck without cover open → Warning state |
| `stable pressure samples pass seal workflow` | §17.2 | Full flow: CoverOpen → Vacuuming → pressure drop → Stabilizing → stable samples → Passed |

### DiagnosticsStoreTest.kt (2 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `raw packet buffer stays bounded` | §23.1 | 501 packets → only 500 retained (ring buffer enforcement) |
| `export bundle contains expected files` | §23.3 | Bundle includes event-log.jsonl, error-log.jsonl, latency-summary.json |

### ControlCoreTest.kt (3 tests)
| Test | Spec Requirement | Verifies |
|---|---|---|
| `camera shutter emits capture effect when camera is permitted` | §8.3, §12.1 | Full pipeline: BLE Ready → permission → button 0x20 → ExecuteCamera(CapturePhoto) |
| `malformed packet records error without changing state` | §18.6 | 2-byte payload → state unchanged, error count = 1, error message in notes |
| `permission revocation pushes phone mode into safe fallback` | §21.3 | PhoneCursor → accessibility revoked → Diagnostics mode + warning |

### Test Coverage Gaps (Potential Future Tests)
| Area | What's Missing | Spec Section |
|---|---|---|
| Battery level parsing | No test for battery range validation | §5.1 |
| Sensor data decoding | No tests for water pressure/temp/barometric parsing | §5.1 |
| Duplicate button filtering | No test for ButtonEventNormalizer 75ms window | §19.1 |
| Camera zoom clamping | No test for zoom bounds (1.0–8.0) | §13.7 |
| Cursor speed cycling | No test for speed profile transitions | §20.2 |
| Overlay fallback | No test for overlay revocation → SmartTarget fallback | §28.4 |
| BLE disconnect during recording | No test for camera continuing after BLE loss | §28.1 |
| Export bundle completeness | No test for all 8 export files | §23.3 |
| Vacuum cancel | No test for cancel stopping motor + solenoid | §17.2 |
| Seal failure | No test for pressure leak → Failed state | §17.2 |

---

## 15. Spec Deviations Summary

| Area | Deviation | Severity | Notes |
|---|---|---|---|
| CameraLive BackOrSafety | Spec: "Back/cancel"; Code: goes to Safety mode | Low | Reasonable design choice — BackOrSafety is the safety button |
| CameraAdjust Z+/Z- | Spec: "Coarse increase/decrease"; Code: ZoomIn/ZoomOut | Low | May need refinement when pro controls are fully implemented |
| Vacuum workflow | Spec has 8 explicit states; Code uses cover sensor events to simplify | Low | Functionally equivalent — cleaner implementation |
| PhoneControlCommand | Code adds `MoveTarget(direction)` not in spec §11.3 | None | Useful addition for directional Smart Target navigation |

---

## 16. Vendor Spec Mismatches (Resolved)

> Source: `tpyrced_Bluetooth-and-APP-protocol-description-A4.0-20241224.docx`

| Issue | Fix Applied | Code Location | Status |
|---|---|---|---|
| **Cover state mapping** | `0x00 = open, 0x01 = closed` — vendor polarity | `ProtocolParser.decodeCoverState()` | ✅ Fixed |
| **Sensor data width** | Exact 4-byte LE unsigned decoder | `ProtocolParser.decodeUnsignedInt32()` | ✅ Fixed |
| **Water pressure unit** | 0.1 mbar → `raw / 100.0` kPa | `ProtocolParser.decodeWaterPressure()` | ✅ Fixed |
| **Water temperature unit** | 0.01°C → `raw / 100.0` °C | `ProtocolParser.decodeWaterTemperature()` | ✅ Fixed |
| **Barometric pressure unit** | 1 Pa → `raw / 1000.0` kPa | `ProtocolParser.decodeBarometricPressure()` | ✅ Fixed |
| **Write command encoding** | Full byte encoder for all 4 write characteristics | `HousingCommandEncoder.kt` | ✅ Fixed |
| **Vendor base UUIDs** | Full 128-bit UUID generation from vendor template | `HousingBleProfile.vendorUuid()` | ✅ Fixed |

---

## 17. New Files Added (Vendor Protocol Patch)

| File | Purpose | Lines |
|---|---|---|
| `core/HexEncoding.kt` | Hex encode/decode utilities for BLE payloads | 40 |
| `core/HousingBleProfile.kt` | BLE characteristic enum with full vendor UUIDs + subscription/read orders | 86 |
| `core/HousingCommandEncoder.kt` | Encodes `PlatformEffect` → `BleTransportRequest` (write payloads) | 63 |
| `core/HousingProtocolAdapter.kt` | Wraps `ControlCore` + `HousingCommandEncoder` into single transport API | 91 |

### Updated Files

| File | Change | Size Delta |
|---|---|---|
| `core/Contracts.kt` | Added `IrFlashlightCommand` enum, `DeviceInfoUpdate` sealed interface, expanded `HousingState` with device info fields | +894 bytes |
| `core/ProtocolParser.kt` | Added sensor decoders, cover state, device info text parsing, notification routing | +6432 bytes |
| `core/ControlCore.kt` | Added `handleNotificationPayload()` characteristic-aware routing | (may differ) |

### New Tests

| Test File | Tests | Verifies |
|---|---|---|
| `HousingCommandEncoderTest.kt` | 4 tests | Motor encoding, IR flashlight encoding, device info reads, subscription order |
| `HousingProtocolAdapterTest.kt` | 2 tests | Full vacuum→write pipeline, firmware notification → state update |
| `ProtocolParserTest.kt` | +4 tests | Vendor water pressure example, barometric example, cover state 0x00=open, firmware text |
| `ControlCoreTest.kt` | +2 tests | Vendor cover mapping through full pipeline, device info → housing state |

---

*Last updated: 2026-05-26 — all vendor spec mismatches resolved*
