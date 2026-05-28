# MobileDiveControl
## Scuba Housing Control System for Android and iPhone

## 1. Executive Summary

MobileDiveControl turns a scuba-rated Bluetooth phone housing into a reliable physical control system for a smartphone.

The user should be able to seal the phone inside the housing and still control the phone using the housing buttons only.

The product has two main user-facing modes:

1. **Camera Mode**  
   A dedicated underwater camera interface for photo, video, zoom, focus, ISO, shutter speed, white balance, exposure, lighting, and supported pro camera controls.

2. **Transparent Phone Mode**  
   A phone-control mode, primarily for Android, where housing buttons move a visible cursor, select native UI elements, click buttons, scroll, go back, and operate supported Android apps and settings through an overlay and Accessibility Service.

The product is not a normal mobile app.

It is an underwater control system.

The core engineering rule is:

```text
Housing button -> BLE packet -> native parser -> native command -> phone action
```

That control loop is the product.

If that loop is slow, fragile, insecure, or hard to debug, the product fails.

---

## 2. Customer Trust Contract

MobileDiveControl must make explicit promises to the user.

### 2.1 Product Promises

MobileDiveControl will:

- Work without internet for core underwater operation.
- Keep the camera running if the housing disconnects, where the OS allows.
- Show a clear warning if BLE disconnects.
- Never silently ignore critical housing input.
- Never falsely show the seal state as passed.
- Never require React Native or JavaScript to execute critical control actions.
- Never require Node.js in the mobile runtime.
- Never collect screen content from Transparent Phone Mode.
- Never store passwords, payment data, messages, contacts, or screenshots.
- Fail visibly when a feature is unavailable.
- Preserve useful diagnostics after failure.

### 2.2 Failure-State Standard

If the app is uncertain, it must say so.

Examples:

```text
Seal State: Unknown
Housing: Disconnected
Smart Target: Unavailable
Camera Pro Control: Unsupported on this phone
Accessibility Permission: Disabled
Overlay Permission: Disabled
```

The product must never imply certainty where none exists.

---

## 3. CEO-Level Product Design Principles

### 3.1 First-Principles Product Definition

The irreducible job is:

```text
A diver cannot touch the phone.
The phone is sealed inside a housing.
The only practical input is the housing.
The app must convert housing input into phone control.
```

Therefore, the critical path is:

```text
Physical button
  -> BLE notification
  -> native parser
  -> input router
  -> command executor
  -> phone action
```

Everything else is secondary.

### 3.2 Elon-Style Simplification

Delete anything that does not directly improve:

- Underwater control
- Safety
- Latency
- Reliability
- Debuggability
- Maintainability

Reject:

- Feature bloat
- Cloud dependency
- JavaScript-owned control loops
- Node.js mobile runtime
- Overengineered abstractions
- UI complexity
- OTA firmware updates in MVP
- Any dependency that cannot justify its risk

### 3.3 Bezos-Style Customer Obsession

Start with the underwater failure case.

The user is:

- Underwater
- Wearing gloves
- Unable to touch the screen
- Possibly cold
- Possibly in low visibility
- Possibly recording a once-in-a-lifetime shot

Therefore:

- Mode must be obvious.
- Recovery must be simple.
- Warnings must be clear.
- The app must not surprise the user.
- Support must be able to diagnose failure after the dive.

### 3.4 Gates-Style Platform Discipline

The product must work across real-world fragmentation:

- Android versions
- iOS versions
- Phone models
- Camera stacks
- BLE chipsets
- OEM accessibility behavior
- Overlay restrictions
- Housing firmware versions

Compatibility is not a later task. It is a product feature.

### 3.5 Zuckerberg-Style Feedback Loop

The product should learn from failures without collecting user content.

Allowed opt-in telemetry:

- App version
- Phone model
- OS version
- Firmware version
- BLE disconnect category
- Camera capability tier
- Permission state category
- Error code counts
- Latency histogram

Not allowed:

- Screen text
- Screenshots
- Passwords
- Messages
- Contacts
- Payment data
- Raw accessibility trees
- Third-party app content

---

## 4. Product Name

Recommended name:

```text
MobileDiveControl
```

Subtitle:

```text
Scuba Housing Control System for Android and iPhone
```

Optional tagline:

```text
Full phone control from inside the housing.
```

Mode names:

- Camera Mode
- Transparent Phone Mode
- Safety Mode
- Diagnostics Mode

---

## 5. Hardware Interface Summary

The housing communicates over Bluetooth Low Energy.

The uploaded protocol defines:

- BLE 5.0
- Advertising name: `DIVE IT`
- Battery service
- Device information service
- Button notification service
- Flash trigger
- Vacuum motor control
- Water pressure reporting
- Water temperature reporting
- Barometric pressure reporting
- Air extraction cover state
- Solenoid valve control
- Infrared flashlight control
- OTA firmware upgrade data path

The housing’s BLE services, button values, and sensor/control characteristics are defined in the uploaded protocol. :contentReference[oaicite:0]{index=0}

### 5.1 BLE Services and Characteristics

| Capability | Service / Characteristic | Direction | App Use |
|---|---:|---|---|
| Battery level | `0x180F / 0x2A19` | Housing to app | Housing battery display |
| Device information | `0x180A` | Housing to app | Firmware, hardware, serial, manufacturer |
| Button events | `0x1523 / 0x1524` | Housing to app | Main physical input stream |
| Flash trigger | `0x1525` | App to housing | Trigger flash once |
| Vacuum motor | `0x1624` | App to housing | Start or stop vacuum pump |
| Water pressure | `0x1625` | Housing to app | Depth or pressure display |
| Water temperature | `0x1626` | Housing to app | Water temperature display |
| Barometric pressure | `0x1627` | Housing to app | Vacuum and seal monitoring |
| Air extraction cover | `0x1628` | Housing to app | Cover open or closed state |
| Solenoid valve | `0x1629` | App to housing | Open or stop solenoid valve |
| IR flashlight control | `0x162A` | App to housing | Brightness, light mode, sleep, wake, focus or flashing mode |

### 5.2 Button Mapping

| Housing Button | BLE Value | Internal Event |
|---|---:|---|
| Right | `0x10` | Right |
| Shutter | `0x20` | Shutter / primary action |
| Up | `0x30` | Up |
| Left | `0x40` | Left |
| OK | `0x50` | Confirm |
| Down | `0x61` | Down |
| Down long press | `0x60` | Back / cancel / safety |
| Z+ | `0x70` | Zoom in / scroll up / speed up |
| Z- | `0x80` | Zoom out / scroll down / speed down |

Raw BLE values must be decoded only once inside the protocol layer.

No UI, camera, accessibility, React Native, or safety code may directly depend on raw byte values.

---

## 6. Technology Strategy

### 6.1 Final Architecture Decision

Use:

```text
Native Control Core + Optional React Native Presentation Shell
```

Do not use:

```text
Pure React Native control system
Node.js mobile runtime
JavaScript-owned control loop
Cloud-required underwater control
```

Critical rule:

```text
Native owns control.
React Native may observe and render noncritical UI.
```

### 6.2 Why Not Pure React Native

Pure React Native is the wrong default because the product depends on:

- BLE connection lifecycle
- Low-latency button input
- Camera2 / CameraX advanced camera control
- Android AccessibilityService
- Overlay cursor rendering
- Gesture dispatch
- Safety state machines
- Crash isolation
- Local-first operation
- Performance-sensitive diagnostics

Those should be native.

React Native may be used for:

- Onboarding
- Help screens
- Noncritical settings
- Diagnostics display
- Compatibility information
- Permission education

React Native must not own:

- BLE parser
- BLE reconnect logic
- Input routing
- Camera execution
- Cursor execution
- Accessibility execution
- Safety execution
- Diagnostic capture ring buffers

### 6.3 Node.js Decision

Node.js is allowed for:

- Build tooling
- Backend support systems
- Diagnostic ingestion, if later needed
- Admin dashboards
- Firmware distribution service, if OTA is later enabled
- Protocol schema generation
- Simulators
- Log analysis

Node.js is not allowed in the mobile runtime.

Core underwater operation must be offline and local-first.

---

## 7. Platform Strategy

### 7.1 Android

Android is the full-control platform.

Android supports:

- BLE housing communication
- Camera control
- Overlay cursor
- Accessibility-based native UI interaction
- Gesture dispatch
- Foreground service operation
- Local diagnostics

Recommended Android stack:

- Kotlin
- Coroutines and Flow
- Camera2 for advanced camera control
- CameraX only where it simplifies preview or capture without reducing manual control
- Android BLE APIs
- Android AccessibilityService
- Android overlay window
- Foreground service
- DataStore for lightweight settings
- Local JSONL logs

### 7.2 iOS

iOS supports:

- BLE housing communication
- Camera Mode
- Housing battery and telemetry
- Safety status
- Diagnostics

iOS should not be advertised as supporting full arbitrary native-app control unless Apple provides approved APIs for that behavior.

iOS stack:

- Swift
- SwiftUI or UIKit where appropriate
- Core Bluetooth
- AVFoundation
- Native diagnostics ring buffer
- Native safety state machine

### 7.3 Cross-Platform Parity

Parity means:

- Same product language
- Similar camera UI
- Similar button behavior where possible
- Similar diagnostics
- Similar safety states
- Similar compatibility reporting

Parity does not mean identical system-control capability.

---

## 8. System Architecture

### 8.1 Module Layout

```text
app-shell
  feature-camera
  feature-phone-control
  feature-safety
  feature-diagnostics
  feature-onboarding
  core-protocol
  core-input
  core-state
  core-logging
  core-permissions
  core-security
  platform-ble
  platform-camera
  platform-accessibility
  platform-overlay
  platform-storage
  platform-notifications
  optional-react-native-shell
```

### 8.2 Boundary Rule

```text
Native Core:
  owns control state
  owns hardware interaction
  owns safety actions
  owns command execution
  owns diagnostics capture

React Native Shell:
  observes state
  renders noncritical UI
  sends noncritical user intents
  never executes hardware-critical commands directly
```

### 8.3 Critical Path

The critical path must remain native:

```text
BLE notification
  -> timestamp
  -> packet validation
  -> protocol parser
  -> semantic event
  -> debounce/repeat
  -> mode router
  -> command executor
  -> in-memory log
```

Not allowed in the critical path:

- Network calls
- Disk writes
- JSON serialization
- React Native bridge calls
- Node.js
- Heavy cryptography for simple button events
- Full accessibility tree scans
- Dependency graph creation
- Blocking UI work

---

## 9. Security Architecture

### 9.1 Security Philosophy

The goal is not to claim the app is impossible to hack or crash.

The goal is:

```text
Make compromise difficult.
Make unsafe failure unlikely.
Make failure visible.
Make recovery fast.
Keep critical control alive when noncritical systems fail.
```

### 9.2 Security Zones

| Zone | Trust Level | Examples |
|---|---|---|
| Trusted Native Control Core | Highest | BLE parser, input router, camera executor, safety executor |
| Platform Permission Layer | High | AccessibilityService, overlay service, foreground service |
| Presentation Layer | Medium | Native UI or optional React Native shell |
| Diagnostics Layer | Medium | Local logs, export bundle |
| Network Layer | Low | Update checks, optional support upload |
| External Inputs | Untrusted | BLE packets, OTA files, Android intents, network responses |

Rule:

```text
Untrusted inputs must be parsed, validated, bounded, and logged before they influence control actions.
```

### 9.3 BLE Trust Model

Advertising name is not identity.

The app must not trust a device only because it advertises:

```text
DIVE IT
```

Required checks:

1. Match expected service UUIDs.
2. Read device information.
3. Read firmware revision.
4. Compare against supported firmware matrix.
5. Persist user-approved housing identity.
6. Warn if a different device appears with the same name.
7. Require manual confirmation if multiple housings are nearby.

### 9.4 High-Risk Commands

High-risk commands:

- Start vacuum motor
- Open solenoid valve
- OTA firmware update
- Future firmware write
- Any command that changes safety state

Rules:

- Never execute high-risk commands directly from raw button events.
- Require valid app state.
- Require trusted housing identity.
- Require safety preconditions.
- Require timeout.
- Require logged result.
- Require explicit stop/failure behavior.

### 9.5 OTA Firmware Policy

The uploaded protocol mentions OTA firmware upgrade data, but OTA must not be enabled in MVP. :contentReference[oaicite:1]{index=1}

OTA may be enabled only after:

- Firmware package signature verification
- Manufacturer public key pinning
- Hardware revision compatibility check
- Firmware compatibility check
- Housing battery threshold
- Phone battery threshold
- Transfer checksum
- Full-image validation
- Recovery or rollback strategy
- Interruption handling
- No unsigned firmware path
- No debug OTA path in production

MVP rule:

```text
OTA-ready architecture.
OTA disabled by default.
No unsigned firmware.
```

---

## 10. State Model

### 10.1 Single Source of Truth

```text
AppState
  mode
  housing
  camera
  phoneControl
  safety
  permissions
  diagnostics
  security
  compatibility
```

### 10.2 State Rules

Do not allow:

- Raw BLE state inside UI components
- Duplicate mode flags
- Camera settings copied into unrelated view models
- Accessibility state hidden inside service only
- React Native owning critical control state
- UI callbacks directly mutating hardware services

All actions flow through:

```text
Event
  -> Intent
  -> Reducer
  -> New State
  -> Side Effect Command
  -> Executor
  -> Result Event
```

### 10.3 App Modes

| Internal Mode | Purpose |
|---|---|
| CameraLive | Main camera preview and capture |
| CameraAdjust | Fine adjustment of selected camera control |
| PhoneCursor | Transparent Phone Mode with free cursor |
| PhoneTarget | Transparent Phone Mode with smart target navigation |
| Safety | Vacuum, seal, and pre-dive checks |
| Diagnostics | Debugging and support |

---

## 11. Stable Internal Command Contract

### 11.1 Housing Button Events

```text
HousingButtonEvent.Up
HousingButtonEvent.Down
HousingButtonEvent.Left
HousingButtonEvent.Right
HousingButtonEvent.Ok
HousingButtonEvent.Shutter
HousingButtonEvent.ZoomIn
HousingButtonEvent.ZoomOut
HousingButtonEvent.BackOrSafety
HousingButtonEvent.Unknown(rawValue)
```

### 11.2 Camera Commands

```text
CameraCommand.CapturePhoto
CameraCommand.ToggleVideoRecording
CameraCommand.StartVideoRecording
CameraCommand.StopVideoRecording
CameraCommand.ZoomIn
CameraCommand.ZoomOut
CameraCommand.SetZoom(value)
CameraCommand.SelectNextControl
CameraCommand.SelectPreviousControl
CameraCommand.IncreaseSelectedControl
CameraCommand.DecreaseSelectedControl
CameraCommand.SetIso(value)
CameraCommand.SetShutterSpeedNs(value)
CameraCommand.SetManualFocus(value)
CameraCommand.SetWhiteBalanceKelvin(value)
CameraCommand.SetExposureCompensation(value)
CameraCommand.SwitchLens(lensId)
CameraCommand.ToggleGrid
CameraCommand.ToggleFocusPeaking
CameraCommand.RestartCamera
```

### 11.3 Phone Control Commands

```text
PhoneControlCommand.MoveCursorUp
PhoneControlCommand.MoveCursorDown
PhoneControlCommand.MoveCursorLeft
PhoneControlCommand.MoveCursorRight
PhoneControlCommand.Click
PhoneControlCommand.LongClick
PhoneControlCommand.ScrollUp
PhoneControlCommand.ScrollDown
PhoneControlCommand.Back
PhoneControlCommand.Home
PhoneControlCommand.Recents
PhoneControlCommand.NextTarget
PhoneControlCommand.PreviousTarget
PhoneControlCommand.SwitchCursorMode
PhoneControlCommand.IncreaseCursorSpeed
PhoneControlCommand.DecreaseCursorSpeed
```

### 11.4 Safety Commands

```text
SafetyCommand.StartVacuumCheck
SafetyCommand.CancelVacuumCheck
SafetyCommand.OpenSolenoid
SafetyCommand.CloseSolenoid
SafetyCommand.StartVacuumMotor
SafetyCommand.StopVacuumMotor
SafetyCommand.AcknowledgeWarning
SafetyCommand.ResetSealState
```

### 11.5 Housing Commands

```text
HousingCommand.TriggerFlash
HousingCommand.SetVacuumMotor(enabled)
HousingCommand.SetSolenoidValve(open)
HousingCommand.SendIrFlashlightCommand(command)
HousingCommand.RequestBatteryRead
HousingCommand.RequestDeviceInfo
HousingCommand.Disconnect
HousingCommand.Reconnect
```

### 11.6 System Commands

```text
SystemCommand.SwitchToCameraMode
SystemCommand.SwitchToTransparentPhoneMode
SystemCommand.SwitchToSafetyMode
SystemCommand.SwitchToDiagnosticsMode
SystemCommand.ExportDiagnostics
SystemCommand.LockControls
SystemCommand.UnlockControls
```

---

## 12. Input Routing

### 12.1 Routing Table

| Input | CameraLive | CameraAdjust | PhoneCursor | PhoneTarget |
|---|---|---|---|---|
| Up | Previous quick control | Increase value | Move cursor up | Target above |
| Down | Next quick control | Decrease value | Move cursor down | Target below |
| Left | Previous control | Fine decrease | Move cursor left | Target left |
| Right | Next control | Fine increase | Move cursor right | Target right |
| OK | Open adjust | Confirm / close | Click | Click target |
| Shutter | Capture / record | Capture if safe | Click | Click target |
| Z+ | Zoom in | Coarse increase | Speed up / scroll up | Scroll up |
| Z- | Zoom out | Coarse decrease | Speed down / scroll down | Scroll down |
| Long Down | Back / cancel | Exit adjust | Android back | Android back |

### 12.2 Mode Toggle

Recommended default:

```text
Hold OK + Z+ for 800 ms
```

Avoid using OK long press alone until firmware behavior is confirmed, because the protocol indicates OK long press may be associated with power-off behavior. :contentReference[oaicite:2]{index=2}

---

## 13. Camera Mode

### 13.1 UI Thesis

Camera Mode should feel like an aircraft instrument overlay on top of a clean camera preview.

The center of the screen belongs to the image.

Controls live at the edges and bottom.

### 13.2 Layout

```text
------------------------------------------------
BLE | Housing Battery | Phone Battery | Seal
------------------------------------------------

                Camera Preview

                Focus Box

------------------------------------------------
PHOTO | ZOOM 1.4x | ISO 400 | 1/120 | WB 5200K
------------------------------------------------
```

### 13.3 Always Visible

- Current mode
- BLE state
- Housing battery
- Phone battery
- Seal state
- Recording state
- Selected camera control
- Current selected value

### 13.4 Visible When Relevant

- ISO
- Shutter speed
- White balance
- Focus distance
- Exposure compensation
- Zoom
- Depth
- Temperature
- Storage remaining

### 13.5 Hidden Unless Diagnosing

- Raw BLE bytes
- Firmware version
- RSSI
- Raw pressure bytes
- Raw accessibility node information
- Internal state-machine details

### 13.6 Camera Control Strip

Example:

```text
PHOTO | ZOOM 1.4x | ISO 400 | 1/120 | WB 5200K | MF 0.42m
```

Button behavior:

| Button | Action |
|---|---|
| Left / Right | Move between control tokens |
| Up / Down | Adjust selected token |
| OK | Open expanded adjustment |
| Shutter | Capture photo or start/stop recording |
| Z+ / Z- | Zoom by default |

### 13.7 Pro Control Adjustment

Use stepped value rails instead of drag sliders.

Example:

```text
ISO
100  125  160  200  250  320  [400]  500  640  800
Step: Fine
```

Example:

```text
White Balance
3200K  3800K  4500K  [5200K]  6000K  7000K  8000K
```

Rules:

- Values must be readable.
- Steps must be predictable.
- Unsupported controls must be hidden or disabled.
- Shutter should still capture unless the current screen is destructive.
- OK toggles coarse/fine mode where appropriate.

---

## 14. Camera Capability Matrix

### 14.1 Capability Tiers

| Tier | Meaning | Features |
|---|---|---|
| Basic Camera | Minimum viable operation | Photo, video, zoom |
| Advanced Camera | Better underwater shooting | Exposure compensation, lens switch, autofocus trigger |
| Pro Camera | Full manual-style control where supported | Manual ISO, shutter speed, manual focus, white balance |
| Unsupported | Not exposed by device | Hide feature and list in diagnostics |

### 14.2 Startup Detection

Detect:

- Available lenses
- Zoom range
- Exposure compensation range
- Manual focus support
- ISO range
- Shutter speed range
- White balance support
- Stabilization support
- RAW support
- Video profiles
- Frame rates
- Thermal risk indicators where available

### 14.3 Feature Rules

- Never assume every phone supports every pro control.
- Do not show unavailable controls as active.
- Do not allow silent failure.
- Show unsupported features in diagnostics.
- Prefer graceful fallback.

Example:

```text
Manual shutter speed is unavailable on this camera.
Exposure compensation is available instead.
```

---

## 15. Transparent Phone Mode

### 15.1 Purpose

Transparent Phone Mode allows Android users to control supported native UI while the phone is sealed inside the housing.

It uses:

- BLE housing input
- Native overlay cursor
- Android AccessibilityService
- Gesture dispatch
- Smart target navigation
- Free cursor fallback

### 15.2 Cursor Modes

#### Smart Target Mode

Directional buttons move between detected clickable UI elements.

Use when the screen exposes usable accessibility nodes.

#### Free Cursor Mode

Directional buttons move a visible cursor by coordinates.

Use when:

- Smart targets are unavailable.
- UI is custom-rendered.
- Node geometry is unreliable.
- The user needs manual control.

### 15.3 Overlay Rules

Show only:

- Cursor
- Optional target outline
- Small mode pill
- Small BLE status pill
- Optional scroll indicator

Do not show large menus unless deliberately opened.

Do not render the live cursor through React Native.

### 15.4 Limits

Transparent Phone Mode may not work on:

- Banking screens
- Payment screens
- DRM-protected screens
- Password prompts
- OEM-protected settings
- Apps that block accessibility
- Custom-rendered UI without useful accessibility nodes

Failure message:

```text
Smart Target unavailable.
Free Cursor enabled.
```

---

## 16. Accessibility Behavior Algorithm

### 16.1 Smart Target Discovery

Discover candidates using:

- Clickable nodes
- Focusable nodes
- Scrollable containers
- Enabled controls
- Visible bounds
- Role/class metadata
- Geometry

Do not store screen text by default.

### 16.2 Target Ranking

Rank by:

1. Directional validity
2. Distance
3. Alignment
4. Clickability
5. Visibility
6. Size
7. Confidence score

Example:

```text
Right press:
  1. Find targets whose center is right of current target.
  2. Prefer targets vertically aligned with current target.
  3. Choose nearest valid target.
  4. Snap cursor to target center.
```

### 16.3 Click Behavior

Priority:

1. If Smart Target is active and clickable, invoke accessibility click.
2. If that fails, dispatch coordinate tap at target center.
3. If no target exists, dispatch coordinate tap at cursor position.
4. Log failure if all methods fail.

### 16.4 Scroll Behavior

Priority:

1. Scroll selected node if scrollable.
2. Else scroll nearest scrollable parent.
3. Else scroll largest visible scrollable container.
4. Else perform coordinate swipe.
5. If all fail, show nonblocking feedback.

### 16.5 Safety Restrictions

- Do not auto-click permission dialogs.
- Do not auto-approve security prompts.
- Do not collect screen text.
- Do not log accessibility tree dumps.
- Do not interact with password/payment fields except through user-commanded navigation.

---

## 17. Safety and Housing Control

### 17.1 Safety State

| State | Meaning |
|---|---|
| Unknown | No trusted seal state |
| CoverOpen | Air extraction cover is open |
| ReadyToVacuum | Ready to begin vacuum process |
| Vacuuming | Motor is active |
| Stabilizing | Pressure is being monitored |
| Passed | Seal check passed |
| Warning | Seal status uncertain |
| Failed | Seal or pressure check failed |

### 17.2 Vacuum Workflow

The protocol supports motor control, solenoid valve control, barometric pressure reporting, and air extraction cover state. The uploaded protocol also describes a vacuum process involving suction-cover confirmation, solenoid valve operation, motor pumping, pressure threshold detection, stabilization, and leakage monitoring. :contentReference[oaicite:3]{index=3}

Implement as a state machine:

```text
Idle
  -> ConfirmCoverOpen
  -> OpenSolenoid
  -> StartMotor
  -> MonitorPressure
  -> StopMotor
  -> ConfirmCoverClosed
  -> Stabilize
  -> Passed or Failed
```

Do not implement this as disconnected button handlers.

### 17.3 Safety Alert Priority

| Priority | Alert |
|---:|---|
| 1 | Leak or seal failure |
| 2 | BLE disconnected |
| 3 | Critical phone battery |
| 4 | Critical housing battery |
| 5 | Storage full |
| 6 | Camera unavailable |
| 7 | Sensor stale |
| 8 | Noncritical telemetry warning |

### 17.4 Crash-Safe Defaults

| Subsystem | Safe Default |
|---|---|
| Seal state | Unknown, not Passed |
| BLE control | Disabled until revalidated |
| Vacuum motor | Stop |
| Solenoid | Known safe state or user verification required |
| Camera | Keep recording if platform allows |
| Transparent Phone Mode | Disable command execution until valid |
| OTA | Abort and enter recovery flow |

---

## 18. BLE Implementation

### 18.1 Device Discovery

Primary filter:

```text
Advertising name: DIVE IT
```

Secondary checks:

- Expected services
- Expected characteristics
- Known device identity
- Firmware compatibility
- User confirmation if multiple devices are nearby

### 18.2 Connection State Machine

```text
Idle
  -> Scanning
  -> Connecting
  -> DiscoveringServices
  -> Subscribing
  -> Ready
  -> Degraded
  -> Reconnecting
  -> Failed
```

### 18.3 Subscription Order

Subscribe in this order:

1. Button events
2. Battery notifications
3. Air extraction cover state
4. Barometric pressure
5. Water pressure
6. Water temperature

### 18.4 Timeout Defaults

| Operation | Timeout |
|---|---:|
| Scan for known housing | 10 seconds |
| Scan for any housing | 20 seconds |
| Connect attempt | 10 seconds |
| Service discovery | 8 seconds |
| Notification subscription | 5 seconds per required group |
| Characteristic write | 3 seconds |
| Characteristic read | 3 seconds |

### 18.5 Reconnection Backoff

```text
Attempt 1: immediate
Attempt 2: 500 ms
Attempt 3: 1 second
Attempt 4: 2 seconds
Attempt 5+: 5 seconds capped
```

### 18.6 Parser Hardening

Rules:

- Bound all packet lengths.
- Reject malformed packets.
- Reject unexpected characteristic lengths.
- Treat unknown buttons as `Unknown`.
- Do not crash on unknown telemetry.
- Do not allocate large buffers from packet data.
- Do not parse on the UI thread.
- Fuzz test malformed packets.

---

## 19. Performance Requirements

### 19.1 Hot Path

Hot path:

```text
Button packet -> command execution
```

Allowed:

- Packet validation
- Characteristic validation
- Trusted connection check
- Debounce/repeat
- Mode routing
- Command execution
- In-memory ring-buffer logging

Not allowed:

- Network calls
- Disk writes
- JSON serialization
- React Native bridge calls
- Full accessibility scans
- Firmware compatibility downloads
- Heavy cryptography for simple button input

### 19.2 Latency Targets

| Path | Target |
|---|---:|
| Button press to decoded event | Under 100 ms typical |
| BLE callback to semantic event | Under 5 ms |
| Event routing to command | Under 5 ms |
| Button press to cursor movement | Under 120 ms typical |
| Button press to camera command issued | Under 150 ms typical |
| Button press to accessibility click dispatched | Under 250 ms typical |
| Cursor frame update | 30 to 60 FPS depending device |
| Temporary BLE reconnect | Under 5 seconds target |

### 19.3 Telemetry Update Rates

| Data | UI Update Rate |
|---|---:|
| Button events | Immediate |
| Housing battery | On connect and every 60 to 120 seconds |
| Water temperature | 1 Hz or slower |
| Water pressure / depth | 1 to 5 Hz |
| Barometric pressure during vacuum | 2 to 5 Hz |
| Barometric pressure after seal pass | 0.2 to 1 Hz |
| Cover state | On change |

---

## 20. Cursor Performance

### 20.1 Cursor Rules

- Render cursor natively.
- Keep cursor state small.
- Avoid full-screen recomposition.
- Avoid high-frequency accessibility scans.
- Use Smart Target when available.
- Fall back to Free Cursor when needed.
- Do not route live cursor movement through React Native.

### 20.2 Cursor Speed Profiles

| Profile | Purpose |
|---|---|
| Precision | Small controls |
| Normal | General operation |
| Fast | Large screen movement |
| Smart Target | Jump between clickable elements |

### 20.3 Acceleration

```text
0 to 500 ms: single step
500 to 1500 ms: slow repeat
1500 ms and above: accelerated repeat
```

Z+ and Z- adjust cursor speed in Free Cursor Mode.

---

## 21. Permission Design

### 21.1 Android Permissions

Likely required:

| Permission / Capability | Purpose |
|---|---|
| Bluetooth scan | Find housing |
| Bluetooth connect | Connect to housing |
| Camera | Camera preview and capture |
| Microphone | Video audio |
| Media or storage access | Save photos/videos where required |
| Overlay permission | Draw cursor |
| AccessibilityService | Click, scroll, navigate Android UI |
| Foreground service | Active dive/camera reliability |
| Notifications | Foreground status and critical alerts |
| Ignore battery optimization recommendation | Long-session reliability |

### 21.2 Permission Onboarding

Bad:

```text
Enable Accessibility.
```

Good:

```text
To control Android buttons from the housing, enable MobileDiveControl Phone Control.
This lets the housing move a cursor and click on-screen controls while the phone is sealed inside the housing.
```

### 21.3 Permission Failure States

| Missing Permission | Behavior |
|---|---|
| Bluetooth | Cannot connect to housing |
| Camera | Camera Mode unavailable |
| Microphone | Video audio unavailable |
| Overlay | Cursor unavailable |
| AccessibilityService | Transparent Phone Mode unavailable |
| Foreground service | Reduced reliability warning |
| Notifications | Alert visibility may be limited |

---

## 22. Data Protection and Persistence

### 22.1 Store Locally

Persist:

- Last paired housing identity
- Last selected mode
- Camera settings preset
- Cursor speed profile
- Cursor mode preference
- Permission onboarding completion
- Crash diagnostic snapshot
- Safety check result history if required
- UI scale
- Measurement units

### 22.2 Do Not Store

Do not store:

- Passwords
- Payment data
- Message content
- Contact content
- Screen text from other apps
- Screenshots
- Raw accessibility trees
- Third-party app content

### 22.3 Diagnostics Log Format

Use JSONL.

Example:

```json
{"timestamp":"2026-05-26T12:00:00Z","source":"button","raw":"0x50","event":"Ok","mode":"CameraLive","command":"OpenAdjust","result":"success","latencyMs":42}
```

---

## 23. Diagnostics and Debugging

### 23.1 In-Memory Ring Buffers

| Buffer | Size |
|---|---:|
| Raw BLE packets | Last 500 |
| Decoded button events | Last 500 |
| Commands | Last 300 |
| State transitions | Last 200 |
| Errors | Last 100 |
| Latency measurements | Last 100 |

Flush asynchronously.

Do not write to disk on every button press.

### 23.2 Diagnostic Screen

Show:

- Connected housing name
- BLE state
- Firmware version
- Housing battery
- Last raw button byte
- Last decoded button event
- Active mode
- Last command
- Last command result
- Pressure
- Temperature
- Barometric pressure
- Cover state
- Camera capabilities
- Accessibility status
- Overlay status
- React Native shell status, if used

### 23.3 Diagnostic Export

Export:

```text
device-info.json
housing-info.json
camera-capabilities.json
permission-state.json
compatibility-info.json
event-log.jsonl
error-log.jsonl
latency-summary.json
```

---

## 24. Compatibility Matrix

Create and maintain:

```text
MobileDiveControl Compatibility Matrix
  Android version
  iOS version
  Phone model
  Camera tier
  BLE stability
  Transparent Phone Mode behavior
  Overlay behavior
  Accessibility behavior
  Thermal behavior
  Firmware version
  Known limitations
```

### 24.1 Compatibility Tiers

| Tier | Meaning |
|---|---|
| Certified | Tested and recommended |
| Supported | Works with known limitations |
| Partial | Core features work, some features limited |
| Unsupported | Not recommended |

### 24.2 Compatibility Must Be Visible

The app should show:

```text
This phone supports:
  Camera Tier: Pro
  Transparent Phone Mode: Supported
  Overlay Cursor: Supported
  Housing Firmware: Compatible
```

Or:

```text
This phone supports:
  Camera Tier: Basic
  Transparent Phone Mode: Limited
  Manual Shutter: Unavailable
```

---

## 25. Simulator-Driven Development

Build simulators before expanding features.

### 25.1 Required Simulators

- Housing button simulator
- BLE disconnect simulator
- Malformed packet simulator
- Low battery simulator
- Pressure/temperature simulator
- Vacuum workflow simulator
- Accessibility target map simulator
- Camera capability simulator
- Permission revoked simulator
- React Native shell failure simulator, if RN is used

### 25.2 Simulator Purpose

The simulator allows:

- Development without hardware
- Regression testing
- Failure reproduction
- Faster onboarding for developers
- Support debugging
- Automated CI tests

---

## 26. Release Strategy

### 26.1 Internal MVP

Purpose:

Prove the core loop.

Must include:

- BLE connection
- Button decode
- Camera shutter
- Video start/stop
- Zoom
- Basic cursor movement
- Basic click
- In-memory diagnostics
- Simulator

Internal MVP does not need full polish.

### 26.2 Public MVP

Purpose:

Ship trustworthy software.

Must include:

- Internal MVP features
- Permission onboarding
- Reconnect recovery
- Failure states
- Safety status
- Compatibility matrix
- Privacy disclosure
- Diagnostic export
- Local logs
- Security hardening
- Field-tested UI

### 26.3 No-Regression Release Rule

Every release must pass:

```text
Button -> decoded event
Button -> camera command
Button -> cursor movement
Button -> accessibility click
BLE disconnect -> reconnect
Permission revoked -> safe failure
Malformed packet -> no crash
React Native failure -> native control continues, if RN exists
```

No release ships if these regress.

---

## 27. Release and Versioning

### 27.1 Protocol Versioning

Persist:

- App version
- Protocol version
- Firmware version
- Hardware revision
- Last successful compatibility check

### 27.2 Firmware Compatibility

On connection:

1. Read device information.
2. Read firmware revision.
3. Compare with supported firmware matrix.
4. Enable supported features.
5. Disable unsupported features.
6. Warn only if a missing feature affects user goals.

### 27.3 Backward-Compatible Parsing

Rules:

- Unknown button values must not crash app.
- Unknown telemetry packets must be logged and ignored.
- Missing optional characteristics disable related features.
- Required characteristic failure puts connection in degraded or failed state.

### 27.4 Kill Switches

Use local feature-disable flags for:

- Vacuum motor control
- Solenoid control
- OTA
- IR flashlight control
- Smart Target beta behavior
- React Native shell, if it destabilizes startup or diagnostics

Do not use kill switches for marketing experiments.

Use them for safety and reliability.

---

## 28. Reliability Requirements

### 28.1 If BLE Fails

- Keep camera preview alive.
- Keep recording if already recording, where OS allows.
- Show `HOUSING DISCONNECTED`.
- Disable housing input.
- Attempt reconnect.
- Preserve logs.
- Do not reset camera settings unless necessary.

### 28.2 If Camera Fails

- Keep BLE connected.
- Keep safety telemetry alive.
- Show camera error.
- Offer camera restart.
- Preserve diagnostics.

### 28.3 If Accessibility Fails

- Keep Camera Mode alive.
- Disable Transparent Phone Mode.
- Show exact missing permission or service state.
- Do not affect BLE or camera.

### 28.4 If Overlay Fails

- Keep AccessibilityService alive.
- Use Smart Target if possible.
- Warn that cursor cannot be displayed.
- Do not crash.

### 28.5 If React Native Shell Fails

If React Native is used:

- Native camera must continue.
- BLE control must continue.
- Safety alerts must continue.
- Transparent Phone Mode must continue.
- Diagnostics capture must continue.
- App may show native fallback screen.

---

## 29. Testing Strategy

### 29.1 Unit Tests

Required:

- BLE UUID mapping
- Button decoding
- Sensor little-endian decoding
- Command encoding
- Debounce behavior
- Repeat behavior
- Mode routing
- Camera command mapping
- Cursor movement math
- Smart target ranking
- Safety state transitions
- Permission state transitions
- Parser fuzz tests
- Security boundary tests

### 29.2 Integration Tests

Required:

- BLE scan and connect
- Service discovery
- Notification subscription
- Reconnect and resubscribe
- Button event to camera command
- Button event to cursor movement
- Button event to accessibility click
- Vacuum workflow
- Permission denied states
- Camera fallback
- React Native shell crash with native control continuing, if RN is used

### 29.3 Field Tests

Required:

- Dry housing test
- Pool test
- Cold-water handling
- Glove usability
- Low visibility
- Night dive simulation
- Long video recording
- BLE disconnect underwater
- Phone thermal throttling
- Low phone battery
- Low housing battery
- Storage full
- Seal failure simulation

### 29.4 Security Tests

Required:

- Malformed BLE packets
- Same-name rogue BLE device
- Duplicate packet bursts
- Permission revocation
- Overlay revocation
- Accessibility disabled
- Intent injection
- Exported component review
- Log redaction
- Debug flag detection
- Dependency vulnerability scan
- Static analysis
- Release build hardening

---

## 30. Acceptance Criteria

### 30.1 BLE

| Requirement | Pass Criteria |
|---|---|
| Connect | App connects to known housing within target window |
| Button events | Supported buttons decode correctly |
| Duplicates | Duplicate packets do not trigger duplicate actions |
| Reconnect | App reconnects after temporary disconnect |
| Notifications | Notifications resume after reconnect |
| Optional missing characteristic | Related feature disables without crash |
| Required button characteristic missing | App reports unusable housing control state |

### 30.2 Camera

| Requirement | Pass Criteria |
|---|---|
| Preview | Opens and remains stable |
| Photo | Shutter captures photo |
| Video | Shutter starts/stops recording in Video Mode |
| Zoom | Z+ and Z- control zoom |
| Manual controls | Supported controls adjustable by housing buttons |
| Unsupported controls | Hidden or marked unavailable |
| BLE disconnect during recording | Recording continues where OS allows |
| Storage full | Clear warning and safe behavior |

### 30.3 Transparent Phone Mode

| Requirement | Pass Criteria |
|---|---|
| Overlay cursor | Cursor appears when permission granted |
| Free cursor | Directional buttons move cursor reliably |
| Click | OK and shutter click current cursor/target |
| Back | Long Down triggers Android back where permitted |
| Scroll | Z+ and Z- scroll where possible |
| Smart Target | Directional buttons move between accessible targets |
| Inaccessible UI | App falls back to Free Cursor |
| Permission revoked | App disables mode safely |

### 30.4 Safety

| Requirement | Pass Criteria |
|---|---|
| Battery | Housing battery shown after connection |
| Pressure | Water pressure updates when available |
| Temperature | Temperature updates when available |
| Barometric pressure | Updates when available |
| Cover state | Open/closed state displayed |
| Vacuum workflow | Valid state-machine transitions only |
| Critical alert | Appears in Camera and Transparent modes |

### 30.5 Architecture

| Requirement | Pass Criteria |
|---|---|
| Native critical path | BLE-to-command does not require React Native |
| React Native isolation | RN failure does not stop native control |
| Node.js exclusion | Node.js is not part of mobile runtime |
| Local-first | Core app works without internet |
| Security | Malformed packets cannot crash the app |
| OTA | Disabled unless signed, verified, recoverable |

---

## 31. Risk Register

### 31.1 Product Risks

| Risk | Impact | Mitigation |
|---|---|---|
| User confusion between modes | Wrong action underwater | Persistent mode indicator, deliberate toggle |
| UI too complex | Slow underwater use | Control strip, minimal overlays |
| Too many features | Lower reliability | Strict MVP scope |
| iOS native-control expectation | Product mismatch | Clear iOS limitations |
| Customer distrust after failure | Low adoption | Diagnostics, clear recovery, conservative warnings |

### 31.2 Technical Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Android Accessibility policy issue | Distribution risk | Clear disclosure and compliance review |
| OEM Android differences | Inconsistent Transparent Mode | Compatibility matrix and fallback cursor |
| Camera2 inconsistency | Pro controls vary | Capability detection |
| BLE instability | Lost control input | Reconnect state machine |
| Phone overheating | Recording/control failure | Thermal monitoring |
| Overlay permission denied | No visible cursor | Smart Target fallback if possible |
| Firmware protocol changes | App incompatibility | Versioned protocol layer |
| React Native overreach | Control-loop instability | Native core owns control |
| Node/server dependency | Underwater failure | Local-first, no Node runtime |
| Parser crash | Loss of control | Fuzz tests and bounded parsing |

### 31.3 Safety Risks

| Risk | Impact | Mitigation |
|---|---|---|
| False seal pass | Water damage risk | Conservative state, never fake certainty |
| Stale pressure data | Bad safety decision | Sensor freshness rules |
| Vacuum motor stuck on | Hardware risk | Timeout and explicit stop |
| Solenoid state unclear | Safety uncertainty | State validation and warning |

---

## 32. MVP Definition

### 32.1 Internal MVP

Must include:

- BLE connection
- Button event decoding
- Camera preview
- Photo capture
- Video start/stop
- Zoom
- Basic Transparent Phone cursor
- Basic click
- In-memory diagnostics
- Hardware simulator

### 32.2 Public MVP

Must include:

- All Internal MVP features
- Battery display
- Device information
- Water pressure
- Temperature
- Barometric pressure
- Cover state
- Flash command support
- IR flashlight command support
- Manual focus where supported
- ISO where supported
- Shutter speed where supported
- White balance where supported
- Camera control strip
- Accessibility click
- Back command
- Scroll command
- Basic Smart Target
- Safety status
- Diagnostics screen
- Diagnostic export
- Permission onboarding
- Compatibility matrix
- Local logs
- Security hardening
- Native control core

### 32.3 Public MVP May Include

Only if it does not delay or destabilize the native control core:

- React Native onboarding
- React Native help
- React Native noncritical settings
- React Native diagnostics display

### 32.4 MVP Must Not Include

- User accounts
- Cloud-required operation
- Social sharing
- Editing suite
- AI filters
- Dive community
- Map features
- Dive computer replacement
- Complex media library
- Themes
- Subscription system
- OTA firmware update
- Node.js mobile runtime
- JavaScript-owned control loop
- Remote JavaScript updates affecting control behavior

---

## 33. Development Phases

### Phase 1: Protocol and Hardware Bring-Up

Goal:

Prove reliable housing communication.

Deliver:

- BLE connection
- Service discovery
- Button notifications
- Battery read
- Device info read
- Sensor reads
- Flash and IR writes
- Diagnostic event log

### Phase 2: Native Camera Control

Goal:

Prove underwater camera operation.

Deliver:

- Camera preview
- Photo capture
- Video recording
- Zoom
- Pro controls
- Capability detection
- Camera control strip

### Phase 3: Native Transparent Phone Mode

Goal:

Prove Android native control.

Deliver:

- AccessibilityService
- Native overlay cursor
- Free Cursor
- Click
- Back
- Scroll
- Basic Smart Target
- Permission onboarding

### Phase 4: Safety System

Goal:

Integrate housing safety features.

Deliver:

- Vacuum workflow
- Seal state
- Safety alerts
- Pre-dive checklist

### Phase 5: Compatibility and Security Hardening

Goal:

Make it safe to ship.

Deliver:

- Compatibility matrix
- Parser fuzzing
- Dependency review
- Permission revocation tests
- Security tests
- Log redaction
- Release hardening

### Phase 6: Optional React Native Shell

Goal:

Improve noncritical UI velocity without touching the control loop.

Deliver only if justified:

- Onboarding shell
- Help shell
- Noncritical settings shell
- Diagnostics display shell

Acceptance condition:

```text
Native control continues working if the React Native shell fails.
```

### Phase 7: Field Hardening

Goal:

Make the app field reliable.

Deliver:

- Pool tests
- Long recording tests
- BLE reconnect tuning
- Battery tuning
- Latency tuning
- OEM compatibility testing
- Crash recovery
- Support diagnostics
- Google Play policy review

---

## 34. Architecture Kill Rules

Reject any feature or dependency that:

- Adds latency to the control loop.
- Requires internet underwater.
- Collects sensitive screen content.
- Depends on React Native for critical control.
- Depends on Node.js in the mobile runtime.
- Cannot fail safely.
- Cannot be tested without hardware.
- Makes support diagnosis harder.
- Adds unclear security risk.
- Requires OTA in MVP.
- Makes the underwater UI more complex without improving control.

---

## 35. Final Success Criteria

MobileDiveControl succeeds if a diver can:

1. Seal the phone in the housing.
2. Confirm housing connection.
3. Confirm seal and safety state.
4. Open Camera Mode.
5. Shoot photos and videos.
6. Adjust supported pro camera controls precisely.
7. Switch to Transparent Phone Mode.
8. Operate supported Android apps and settings where permitted.
9. Return to Camera Mode instantly.
10. Recover from BLE interruptions.
11. Understand every warning without reading a manual.
12. Continue critical operation even if noncritical UI layers fail.
13. Export diagnostics after a failed dive.
14. Trust that the app did not collect sensitive screen content.

The best version of this product is not the one with the most features.

It is the one that makes the phone feel like it was designed to be used underwater.