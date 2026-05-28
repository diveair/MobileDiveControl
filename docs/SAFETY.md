# MobileDiveControl — Safety System Reference

This document covers the safety architecture, vacuum workflow, alert priorities, and crash-safe defaults. Derived from `Claude.md` §17 and mapped to the code implementation.

---

## 1. Safety Philosophy

The safety system follows one absolute rule:

> **If the app is uncertain, it must say so.**

The app must never:
- Imply certainty where none exists
- Silently ignore critical housing input
- Falsely show the seal state as passed

---

## 2. Safety States

| State | Meaning | Display |
|---|---|---|
| `Unknown` | No trusted seal state available | ⚠️ `Seal State: Unknown` |
| `CoverOpen` | Air extraction cover is open | 🔵 `Cover: Open` |
| `ReadyToVacuum` | Ready to begin vacuum process | 🔵 `Ready to Vacuum` |
| `Vacuuming` | Motor is actively pumping | 🟡 `Vacuuming...` |
| `Stabilizing` | Pressure is being monitored post-pump | 🟡 `Stabilizing...` |
| `Passed` | Seal check passed | 🟢 `Seal: Passed` |
| `Warning` | Seal status uncertain | 🟠 `Seal: Warning` |
| `Failed` | Seal or pressure check failed | 🔴 `Seal: Failed` |

**Default after crash or restart:** `Unknown` — never `Passed`.

---

## 3. Vacuum Workflow State Machine

```
Idle
  → ConfirmCoverOpen     # Verify air extraction cover is open
  → OpenSolenoid          # Open solenoid valve
  → StartMotor            # Start vacuum pump
  → MonitorPressure       # Watch barometric pressure dropping
  → StopMotor             # Stop pump when target pressure reached
  → ConfirmCoverClosed    # Verify cover is closed
  → Stabilize             # Monitor pressure stability over time
  → Passed                # Seal check passed
  or
  → Failed                # Pressure leaked or threshold not met
```

### Transition Rules

| From | Event | To | Action |
|---|---|---|---|
| Idle | `StartVacuumCheck` | ConfirmCoverOpen | Prompt user to open cover |
| ConfirmCoverOpen | Cover sensor = open | OpenSolenoid | Open solenoid valve |
| OpenSolenoid | Solenoid confirmed | StartMotor | Start vacuum motor |
| StartMotor | Motor running | MonitorPressure | Begin reading barometric pressure |
| MonitorPressure | Target pressure reached | StopMotor | Stop motor |
| MonitorPressure | Timeout (motor too long) | Failed | Safety timeout — stop motor |
| StopMotor | Motor stopped | ConfirmCoverClosed | Prompt user to close cover |
| ConfirmCoverClosed | Cover sensor = closed | Stabilize | Begin stabilization monitoring |
| Stabilize | Pressure holds for threshold duration | Passed | Seal confirmed |
| Stabilize | Pressure rises beyond tolerance | Failed | Leak detected |
| Any | `CancelVacuumCheck` | Idle | Stop motor, close solenoid, reset |

### Safety Constraints on Vacuum Commands

All vacuum/solenoid commands are **high-risk** and require:
- Valid app state
- Trusted housing identity (verified BLE device)
- Safety preconditions met (correct workflow state)
- Timeout enforced (motor cannot run indefinitely)
- Result logged
- Explicit stop/failure behavior

**Rule:** These commands must never execute directly from raw button events. They require the safety state machine to validate the transition.

---

## 4. Safety Alert Priority

Alerts are displayed in priority order. Higher priority alerts override lower ones.

| Priority | Alert | Severity |
|---|---|---|
| 1 | **Leak or seal failure** | 🔴 Critical |
| 2 | **BLE disconnected** | 🔴 Critical |
| 3 | **Critical phone battery** | 🟠 High |
| 4 | **Critical housing battery** | 🟠 High |
| 5 | **Storage full** | 🟡 Medium |
| 6 | **Camera unavailable** | 🟡 Medium |
| 7 | **Sensor stale** | ⚪ Low |
| 8 | **Noncritical telemetry warning** | ⚪ Low |

### Display Rules
- Priority 1–2 alerts must appear in **all modes** (Camera, Transparent Phone, Safety, Diagnostics)
- Alerts must be visible without scrolling or navigating
- Alerts must not require a manual dismiss to see the current mode
- Priority 1 alerts must interrupt any active operation display

---

## 5. Crash-Safe Defaults

If the app crashes, is killed, or restarts unexpectedly, each subsystem returns to a safe default.

| Subsystem | Safe Default | Rationale |
|---|---|---|
| Seal state | `Unknown` (never `Passed`) | Cannot trust state from before crash |
| BLE control | Disabled until revalidated | Must re-establish trusted connection |
| Vacuum motor | Stop | Hardware safety — motor cannot run unattended |
| Solenoid | Known safe state or user verification | Cannot assume valve position |
| Camera | Keep recording if platform allows | Preserve user's footage |
| Transparent Phone Mode | Disable command execution until valid | Prevent unintended clicks |
| OTA | Abort and enter recovery flow | Firmware integrity must be verified |

---

## 6. Sensor Freshness Rules

| Sensor | Stale Threshold | Action When Stale |
|---|---|---|
| Barometric pressure (during vacuum) | > 5 seconds | Show `Sensor: Stale`, pause vacuum workflow |
| Barometric pressure (after seal pass) | > 30 seconds | Show warning |
| Water pressure | > 10 seconds | Show `Depth: Stale` |
| Water temperature | > 30 seconds | Show `Temp: Stale` |
| Housing battery | > 5 minutes | Show `Battery: Stale` |
| Cover state | > 10 seconds during vacuum | Show warning, pause workflow |

---

## 7. Failure Scenarios and Recovery

### BLE Disconnects During Dive
1. Keep camera preview alive
2. Keep recording if already recording (OS-dependent)
3. Show `HOUSING DISCONNECTED` prominently
4. Disable housing input processing
5. Attempt reconnect with backoff
6. Preserve all logs
7. Do not reset camera settings

### Camera Failure
1. Keep BLE connected
2. Keep safety telemetry alive
3. Show camera error with specific reason
4. Offer camera restart via housing button
5. Preserve diagnostics

### Accessibility Service Disabled
1. Keep Camera Mode fully functional
2. Disable Transparent Phone Mode
3. Show: `Accessibility Permission: Disabled`
4. Do not affect BLE or camera subsystems

### Overlay Permission Revoked
1. Keep AccessibilityService alive
2. Attempt Smart Target mode (no visual cursor)
3. Show: `Overlay Permission: Disabled — cursor unavailable`
4. Do not crash

---

## 8. Pre-Dive Checklist (Future Feature)

The safety system should support a pre-dive checklist:

```
☐ Housing connected
☐ Firmware compatible
☐ Housing battery > 20%
☐ Phone battery > 20%
☐ Seal check passed
☐ Camera operational
☐ Storage available
☐ Permissions granted
```

---

## 9. Code Location

| Component | File | Description |
|---|---|---|
| Safety state machine | `core/SafetyStateMachine.kt` | Vacuum workflow states and transitions |
| Safety commands | `core/Contracts.kt` | `SafetyCommand` sealed class |
| Safety state integration | `core/ControlCore.kt` | Safety state wired into AppState |
| Safety tests | `test/.../SafetyStateMachineTest.kt` | State transition validation |
| Safety scenario | `scenarios/vacuum-pass.scenario` | Scripted vacuum workflow test |

---

*Last updated: 2026-05-26*
