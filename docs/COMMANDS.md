# MobileDiveControl — Command Contract Reference

This document defines every command in the system. Commands are the **stable internal API** — the contract between the input layer and the execution layer. All commands are defined as sealed types in `core/Contracts.kt`.

---

## 1. Housing Button Events

These are the semantic events produced by `ProtocolParser` after decoding raw BLE bytes. They are the **only** representation of button input used by the rest of the system.

| Event | Raw BLE Value | Description |
|---|---|---|
| `Up` | `0x30` | Directional up |
| `Down` | `0x61` | Directional down |
| `Left` | `0x40` | Directional left |
| `Right` | `0x10` | Directional right |
| `Ok` | `0x50` | Confirm / select |
| `Shutter` | `0x20` | Primary action (capture / click) |
| `ZoomIn` | `0x70` | Zoom in / scroll up / speed up |
| `ZoomOut` | `0x80` | Zoom out / scroll down / speed down |
| `BackOrSafety` | `0x60` | Back / cancel / safety (Down long press) |
| `Unknown(rawValue)` | any other | Logged, not crashed |

---

## 2. Camera Commands

Produced by `InputRouter` when the app is in `CameraLive` or `CameraAdjust` mode.

| Command | Description | Mode |
|---|---|---|
| `CapturePhoto` | Take a photo | CameraLive |
| `ToggleVideoRecording` | Start or stop video recording | CameraLive |
| `StartVideoRecording` | Explicitly start recording | CameraLive |
| `StopVideoRecording` | Explicitly stop recording | CameraLive |
| `ZoomIn` | Increase zoom level | CameraLive |
| `ZoomOut` | Decrease zoom level | CameraLive |
| `SetZoom(value)` | Set specific zoom level | Any camera mode |
| `SelectNextControl` | Move to next control in strip | CameraLive |
| `SelectPreviousControl` | Move to previous control in strip | CameraLive |
| `IncreaseSelectedControl` | Increase current control value | CameraAdjust |
| `DecreaseSelectedControl` | Decrease current control value | CameraAdjust |
| `SetIso(value)` | Set manual ISO | CameraAdjust |
| `SetShutterSpeedNs(value)` | Set manual shutter speed (nanoseconds) | CameraAdjust |
| `SetManualFocus(value)` | Set manual focus distance | CameraAdjust |
| `SetWhiteBalanceKelvin(value)` | Set white balance in Kelvin | CameraAdjust |
| `SetExposureCompensation(value)` | Set exposure compensation | CameraAdjust |
| `SwitchLens(lensId)` | Switch to a specific lens | CameraLive |
| `ToggleGrid` | Toggle grid overlay | CameraLive |
| `ToggleFocusPeaking` | Toggle focus peaking overlay | CameraLive |
| `RestartCamera` | Restart camera pipeline | Any camera mode |

---

## 3. Phone Control Commands

Produced by `InputRouter` when the app is in `PhoneCursor` or `PhoneTarget` mode.

| Command | Description | Mode |
|---|---|---|
| `MoveCursorUp` | Move cursor up by step | PhoneCursor |
| `MoveCursorDown` | Move cursor down by step | PhoneCursor |
| `MoveCursorLeft` | Move cursor left by step | PhoneCursor |
| `MoveCursorRight` | Move cursor right by step | PhoneCursor |
| `Click` | Tap at current cursor/target position | Both |
| `LongClick` | Long-press at current position | Both |
| `ScrollUp` | Scroll upward | Both |
| `ScrollDown` | Scroll downward | Both |
| `Back` | Android back action | Both |
| `Home` | Android home action | Both |
| `Recents` | Android recents action | Both |
| `NextTarget` | Move to next Smart Target | PhoneTarget |
| `PreviousTarget` | Move to previous Smart Target | PhoneTarget |
| `SwitchCursorMode` | Toggle between Free Cursor and Smart Target | Both |
| `IncreaseCursorSpeed` | Speed up cursor movement | PhoneCursor |
| `DecreaseCursorSpeed` | Slow down cursor movement | PhoneCursor |

---

## 4. Safety Commands

Produced during safety workflows. All are **high-risk** and require validated preconditions.

| Command | Description | Preconditions |
|---|---|---|
| `StartVacuumCheck` | Begin vacuum seal check workflow | Valid housing, idle state |
| `CancelVacuumCheck` | Abort vacuum check | Active vacuum check |
| `OpenSolenoid` | Open solenoid valve | Trusted housing, safety state valid |
| `CloseSolenoid` | Close solenoid valve | Trusted housing |
| `StartVacuumMotor` | Start vacuum pump | Within vacuum workflow, cover confirmed |
| `StopVacuumMotor` | Stop vacuum pump | Motor running or timeout |
| `AcknowledgeWarning` | User acknowledges a safety warning | Active warning |
| `ResetSealState` | Reset seal state to Unknown | Any safety state |

---

## 5. Housing Commands

Direct hardware commands sent to the housing over BLE.

| Command | Description | Risk Level |
|---|---|---|
| `TriggerFlash` | Fire external flash | Low |
| `SetVacuumMotor(enabled)` | Start/stop vacuum pump | **High** |
| `SetSolenoidValve(open)` | Open/close solenoid | **High** |
| `SendIrFlashlightCommand(cmd)` | IR flashlight control | Low |
| `RequestBatteryRead` | Read housing battery level | Low |
| `RequestDeviceInfo` | Read device information | Low |
| `Disconnect` | Disconnect BLE | Low |
| `Reconnect` | Initiate BLE reconnect | Low |

---

## 6. System Commands

App-level mode and state commands.

| Command | Description |
|---|---|
| `SwitchToCameraMode` | Enter Camera Mode |
| `SwitchToTransparentPhoneMode` | Enter Transparent Phone Mode |
| `SwitchToSafetyMode` | Enter Safety Mode |
| `SwitchToDiagnosticsMode` | Enter Diagnostics Mode |
| `ExportDiagnostics` | Export diagnostic bundle |
| `LockControls` | Lock all housing input |
| `UnlockControls` | Unlock housing input |

---

## 7. Input Routing Table

This table shows which command each button produces in each mode.

| Input | CameraLive | CameraAdjust | PhoneCursor | PhoneTarget |
|---|---|---|---|---|
| **Up** | SelectPreviousControl | IncreaseSelectedControl | MoveCursorUp | MoveTarget(Up) |
| **Down** | SelectNextControl | DecreaseSelectedControl | MoveCursorDown | MoveTarget(Down) |
| **Left** | SelectPreviousControl | DecreaseSelectedControl | MoveCursorLeft | MoveTarget(Left) |
| **Right** | SelectNextControl | IncreaseSelectedControl | MoveCursorRight | MoveTarget(Right) |
| **OK** | Mode → CameraAdjust | Mode → CameraLive | Click | Click |
| **Shutter** | CapturePhoto / ToggleVideo¹ | CapturePhoto / ToggleVideo¹ | Click | Click |
| **Z+** | ZoomIn | ZoomIn | IncreaseCursorSpeed | ScrollUp |
| **Z-** | ZoomOut | ZoomOut | DecreaseCursorSpeed | ScrollDown |
| **BackOrSafety** | Mode → Safety | Mode → CameraLive | Back | Back |

> ¹ Shutter dispatches `ToggleVideoRecording` when the selected control is `Video`, otherwise `CapturePhoto`.

### Mode Toggle
- **Hold OK + Z+ for 800 ms** — toggles between Camera Mode and Transparent Phone Mode
- Avoid OK long press alone (may conflict with housing power-off behavior)

---

## 8. Code Location

| Contract | File |
|---|---|
| All sealed command types | `core/Contracts.kt` |
| Button → command routing | `core/InputRouter.kt` |
| State reduction | `core/ControlReducer.kt` |
| Safety command validation | `core/SafetyStateMachine.kt` |
| BLE command execution | `core/BleConnectionMachine.kt` |

---

*Last updated: 2026-05-26*
