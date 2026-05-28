# MobileDiveControl — Diagnostics & Debugging Reference

Derived from `Claude.md` §22–23. Maps diagnostic requirements to the code implementation.

---

## 1. Diagnostics Philosophy

Diagnostics exist so that **support can diagnose a failure after the dive**. The diver should never need to debug anything underwater. The app captures everything needed automatically.

---

## 2. In-Memory Ring Buffers

All diagnostic data is held in bounded ring buffers. Data is **never** written to disk on every button press — it is flushed asynchronously.

| Buffer | Capacity | Content |
|---|---|---|
| Raw BLE packets | Last 500 | Raw byte arrays with timestamps |
| Decoded button events | Last 500 | Semantic events with source info |
| Commands | Last 300 | Dispatched commands with results |
| State transitions | Last 200 | Mode and state changes |
| Errors | Last 100 | All error events |
| Latency measurements | Last 100 | Button-to-command timing |

### Implementation
- Ring buffers are implemented in `core/DiagnosticsStore.kt`
- Each entry is timestamped at capture time
- Buffers overwrite oldest entries when full
- Thread-safe access required (concurrent BLE callbacks + UI reads)

---

## 3. Log Format: JSONL

All persisted logs use **JSONL** (JSON Lines) — one JSON object per line.

### Example Entry
```json
{"timestamp":"2026-05-26T12:00:00Z","source":"button","raw":"0x50","event":"Ok","mode":"CameraLive","command":"OpenAdjust","result":"success","latencyMs":42}
```

### Field Definitions

| Field | Type | Description |
|---|---|---|
| `timestamp` | ISO 8601 string | When the event occurred |
| `source` | string | Event source: `button`, `sensor`, `ble`, `camera`, `safety`, `system` |
| `raw` | string (optional) | Raw BLE hex value (for button/sensor events) |
| `event` | string | Semantic event name |
| `mode` | string | App mode at time of event |
| `command` | string (optional) | Command dispatched, if any |
| `result` | string | `success`, `failure`, `ignored`, `unsupported` |
| `latencyMs` | number (optional) | Milliseconds from BLE callback to command execution |
| `error` | string (optional) | Error message, if applicable |

---

## 4. Diagnostic Screen

The diagnostics screen shows real-time system state. Accessible via `SystemCommand.SwitchToDiagnosticsMode`.

### Always Shown

| Field | Source |
|---|---|
| Connected housing name | BLE connection |
| BLE state | BLE state machine |
| Firmware version | Device information characteristic |
| Housing battery | Battery characteristic |
| Last raw button byte | Protocol parser |
| Last decoded button event | Protocol parser |
| Active mode | AppState |
| Last command | Command executor |
| Last command result | Command executor |

### Sensor Data

| Field | Source |
|---|---|
| Water pressure | Characteristic `0x1625` |
| Water temperature | Characteristic `0x1626` |
| Barometric pressure | Characteristic `0x1627` |
| Cover state | Characteristic `0x1628` |

### System Status

| Field | Source |
|---|---|
| Camera capabilities | Camera capability detection |
| Accessibility status | AccessibilityService state |
| Overlay status | Overlay permission state |
| React Native shell status | RN bridge (if used) |

---

## 5. Diagnostic Export Bundle

Export creates a ZIP-like bundle containing:

| File | Content |
|---|---|
| `device-info.json` | Phone model, OS version, app version |
| `housing-info.json` | Housing name, firmware version, hardware revision, serial |
| `camera-capabilities.json` | Detected camera tier, available controls, lens info |
| `permission-state.json` | Per-permission grant/deny state |
| `compatibility-info.json` | Device compatibility tier, feature availability |
| `event-log.jsonl` | Decoded events from ring buffer |
| `error-log.jsonl` | Error events from ring buffer |
| `latency-summary.json` | Latency histogram/statistics |

### Export Rules
- Export must work offline
- Export must not include screen text, passwords, or accessibility tree dumps
- Export is triggered by `SystemCommand.ExportDiagnostics`
- Export target: share sheet or local file, user's choice

---

## 6. Data That Must NEVER Appear in Diagnostics

| Category | Examples |
|---|---|
| Screen text | Text content from any app |
| Screenshots | Screen captures |
| Passwords | Any credential |
| Messages | SMS, chat, email content |
| Contacts | Contact names, numbers |
| Payment data | Card numbers, transaction data |
| Raw accessibility trees | Full node dumps with text content |
| Third-party app content | Any content from other apps |

---

## 7. Allowed Opt-In Telemetry

If the user opts in to anonymous telemetry (future feature), only these categories are permitted:

| Category | Example |
|---|---|
| App version | `1.2.3` |
| Phone model | `Pixel 8 Pro` |
| OS version | `Android 15` |
| Firmware version | `2.1.0` |
| BLE disconnect category | `timeout`, `signal_loss` |
| Camera capability tier | `Pro`, `Advanced`, `Basic` |
| Permission state category | `all_granted`, `partial` |
| Error code counts | `{parser_error: 3, ble_timeout: 1}` |
| Latency histogram | `{p50: 42, p95: 110, p99: 180}` |

---

## 8. Performance Telemetry Targets

| Metric | Target |
|---|---|
| Button press → decoded event | < 100 ms typical |
| BLE callback → semantic event | < 5 ms |
| Event routing → command | < 5 ms |
| Button press → cursor movement | < 120 ms typical |
| Button press → camera command issued | < 150 ms typical |
| Button press → accessibility click dispatched | < 250 ms typical |
| Cursor frame update | 30–60 FPS |
| Temporary BLE reconnect | < 5 s target |

### Telemetry Update Rates

| Data | UI Update Rate |
|---|---|
| Button events | Immediate |
| Housing battery | On connect + every 60–120 s |
| Water temperature | 1 Hz or slower |
| Water pressure / depth | 1–5 Hz |
| Barometric pressure (during vacuum) | 2–5 Hz |
| Barometric pressure (after seal pass) | 0.2–1 Hz |
| Cover state | On change |

---

## 9. Code Location

| Component | File | Description |
|---|---|---|
| Ring buffer implementation | `core/DiagnosticsStore.kt` | Bounded circular buffers |
| JSONL formatting | `core/JsonSupport.kt` | JSON serialization helpers |
| Diagnostic export | `core/DiagnosticsStore.kt` | Export bundle assembly |
| Diagnostic tests | `test/.../DiagnosticsStoreTest.kt` | Buffer and export tests |
| Export scenario trigger | `scenarios/*.scenario` | `export` command in scenario language |

---

*Last updated: 2026-05-26*
