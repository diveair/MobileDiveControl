# MobileDiveControl — Architecture Overview

## 1. System Philosophy

MobileDiveControl is a **native-first underwater control system**. The irreducible job is:

```
A diver cannot touch the phone.
The phone is sealed inside a housing.
The only practical input is the housing.
The app must convert housing input into phone control.
```

The critical path is always native:

```
Physical button → BLE notification → native parser → input router → command executor → phone action
```

Everything else is secondary.

---

## 2. Architecture Decision: Native Core + Optional RN Shell

| Layer | Responsibility | Technology |
|---|---|---|
| **Native Control Core** | BLE, camera, accessibility, safety, diagnostics capture | Kotlin (Android) / Swift (iOS) |
| **Optional RN Shell** | Onboarding, help, noncritical settings, diagnostics display | React Native (if justified) |

**Rule:** Native owns control. React Native may observe and render noncritical UI. React Native **never** executes hardware-critical commands.

**Excluded:** Pure React Native control, Node.js mobile runtime, JavaScript-owned control loops, cloud-required underwater control.

---

## 3. Module Layout

```
app-shell
  ├── feature-camera          # Camera preview, capture, pro controls
  ├── feature-phone-control   # Transparent Phone Mode (cursor, a11y, gestures)
  ├── feature-safety          # Vacuum workflow, seal state, safety alerts
  ├── feature-diagnostics     # Debug screen, diagnostic export
  ├── feature-onboarding      # Permission education, setup flow
  ├── core-protocol           # BLE packet parser, button decoder
  ├── core-input              # Input router, debounce, repeat, mode routing
  ├── core-state              # AppState, reducer, side-effect executor
  ├── core-logging            # Ring buffers, JSONL log, diagnostic capture
  ├── core-permissions        # Permission state tracking
  ├── core-security           # BLE trust model, command validation
  ├── platform-ble            # Android/iOS BLE adapter
  ├── platform-camera         # Camera2/CameraX (Android), AVFoundation (iOS)
  ├── platform-accessibility  # AccessibilityService adapter (Android only)
  ├── platform-overlay        # Overlay cursor rendering (Android only)
  ├── platform-storage        # DataStore, local JSONL logs
  ├── platform-notifications  # Foreground service, alerts
  └── optional-react-native-shell  # (Only if justified)
```

---

## 4. Security Zones

| Zone | Trust Level | Examples |
|---|---|---|
| Trusted Native Control Core | Highest | BLE parser, input router, camera executor, safety executor |
| Platform Permission Layer | High | AccessibilityService, overlay service, foreground service |
| Presentation Layer | Medium | Native UI or optional React Native shell |
| Diagnostics Layer | Medium | Local logs, export bundle |
| Network Layer | Low | Update checks, optional support upload |
| External Inputs | Untrusted | BLE packets, OTA files, Android intents, network responses |

**Rule:** Untrusted inputs must be parsed, validated, bounded, and logged before they influence control actions.

---

## 5. Critical Path Constraints

### Allowed on the hot path (button → command):
- Packet validation
- Characteristic validation
- Trusted connection check
- Debounce/repeat
- Mode routing
- Command execution
- In-memory ring-buffer logging

### NOT allowed on the hot path:
- Network calls
- Disk writes
- JSON serialization
- React Native bridge calls
- Full accessibility scans
- Heavy cryptography for simple button input

---

## 6. State Model

### Single Source of Truth

```
AppState
  ├── mode            # CameraLive, CameraAdjust, PhoneCursor, PhoneTarget, Safety, Diagnostics
  ├── housing         # BLE connection state, identity, battery, firmware
  ├── camera          # Preview state, capture mode, pro control values, capability tier
  ├── phoneControl    # Cursor position, mode, speed, active target
  ├── safety          # Seal state, vacuum workflow, sensor readings
  ├── permissions     # Per-permission grant state
  ├── diagnostics     # Ring buffer stats, export state
  ├── security        # Trusted housing identity, threat flags
  └── compatibility   # Device tier, feature availability
```

### State Flow

```
Event → Intent → Reducer → New State → Side Effect Command → Executor → Result Event
```

---

## 7. Data Flow Diagram

```
┌──────────────┐
│ BLE Housing  │
│ (DIVE IT)    │
└──────┬───────┘
       │ BLE 5.0 notifications
       ▼
┌──────────────┐     ┌──────────────┐
│ core-protocol│────▶│ core-input   │
│ (parser)     │     │ (router)     │
└──────────────┘     └──────┬───────┘
                            │ semantic events
                            ▼
                     ┌──────────────┐
                     │ core-state   │
                     │ (reducer)    │
                     └──────┬───────┘
                            │ commands
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
       ┌────────────┐ ┌──────────┐ ┌──────────────┐
       │ feature-   │ │ feature- │ │ feature-     │
       │ camera     │ │ phone-   │ │ safety       │
       │            │ │ control  │ │              │
       └────────────┘ └──────────┘ └──────────────┘
              │             │             │
              ▼             ▼             ▼
       ┌────────────┐ ┌──────────┐ ┌──────────────┐
       │ platform-  │ │ platform-│ │ platform-ble │
       │ camera     │ │ a11y +   │ │ (write cmds) │
       │            │ │ overlay  │ │              │
       └────────────┘ └──────────┘ └──────────────┘
```

---

## 8. Boundary Rules

| Boundary | Rule |
|---|---|
| Raw BLE bytes → Protocol layer | Decoded exactly once; no downstream code depends on raw bytes |
| Protocol events → Input router | Router decides command based on current mode |
| Commands → Executors | Executors are platform-specific; core is platform-agnostic |
| Native → React Native | State observation only; no control delegation |
| UI → Hardware services | UI callbacks never directly mutate hardware services |

---

## 9. Platform Strategy

### Android (Full-control platform)
- Kotlin, Coroutines + Flow
- Camera2 for advanced control, CameraX where it simplifies without reducing manual control
- Android BLE APIs
- AccessibilityService + Overlay window
- Foreground service
- DataStore + local JSONL logs

### iOS (Camera + safety platform)
- Swift, SwiftUI or UIKit
- Core Bluetooth
- AVFoundation
- Native diagnostics ring buffer
- Native safety state machine
- No Transparent Phone Mode (no approved API)

---

## 10. Current Implementation Status

> **This section is auto-maintained.** It reflects what exists in the repo.

### Kotlin JVM Control Core (non-Android, testable)

The current codebase is a **JVM-only Kotlin** implementation of the control core. It is not yet an Android app — it validates the control loop logic independent of Android APIs.

| Component | File | Status |
|---|---|---|
| Entry point | `app/Main.kt` | ✅ Implemented |
| Scenario runner | `app/ScenarioScriptRunner.kt` | ✅ Implemented |
| Gradle helper | `run-gradle.ps1` | ✅ Implemented |
| BLE connection state machine | `core/BleConnectionMachine.kt` | ✅ Implemented |
| Button event normalizer | `core/ButtonEventNormalizer.kt` | ✅ Implemented |
| Command contracts | `core/Contracts.kt` | ✅ Implemented |
| Control core orchestrator | `core/ControlCore.kt` | ✅ Implemented |
| Control reducer | `core/ControlReducer.kt` | ✅ Implemented |
| Diagnostics ring buffers | `core/DiagnosticsStore.kt` | ✅ Implemented |
| Hex encoding utilities | `core/HexEncoding.kt` | ✅ Implemented |
| BLE characteristic profile | `core/HousingBleProfile.kt` | ✅ Implemented |
| Write command encoder | `core/HousingCommandEncoder.kt` | ✅ Implemented |
| Protocol adapter | `core/HousingProtocolAdapter.kt` | ✅ Implemented |
| Input router | `core/InputRouter.kt` | ✅ Implemented |
| JSON support | `core/JsonSupport.kt` | ✅ Implemented |
| Protocol parser | `core/ProtocolParser.kt` | ✅ Implemented |
| Safety state machine | `core/SafetyStateMachine.kt` | ✅ Implemented |

### Tests

| Test | File | Status | Coverage |
|---|---|---|---|
| BLE connection machine | `BleConnectionMachineTest.kt` | ✅ | Disconnect→reconnect, backoff schedule |
| Control core integration | `ControlCoreTest.kt` | ✅ | Camera shutter, malformed packet, permission fallback, vendor cover mapping, device info |
| Diagnostics store | `DiagnosticsStoreTest.kt` | ✅ | Ring buffer bounding, export bundle contents |
| Housing command encoder | `HousingCommandEncoderTest.kt` | ✅ | Motor encode, IR flashlight encode, device info reads, subscription order |
| Housing protocol adapter | `HousingProtocolAdapterTest.kt` | ✅ | Vacuum→write pipeline, firmware notification |
| Input router | `InputRouterTest.kt` | ✅ | Camera shutter routing, video toggle, SmartTarget direction, controls locked |
| Protocol parser | `ProtocolParserTest.kt` | ✅ | Button decode, battery, water pressure, barometric, cover state, firmware text |
| Safety state machine | `SafetyStateMachineTest.kt` | ✅ | Vacuum requires cover open, full vacuum→stabilize→pass workflow |

### Not Yet Implemented (Platform Layer)
- Android app module (Gradle, Manifest, Activities)
- Android BLE adapter (real BLE hardware integration)
- Android camera adapter (Camera2/CameraX)
- Android AccessibilityService
- Android overlay cursor
- iOS project (Swift/SwiftUI)
- React Native shell (if justified)
- Standalone simulators (hardware-free development)

---

*Last updated: 2026-05-26*
