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


## Live pressure verification

The existing Sensors card displays external pressure to 0.01 kPa and internal pressure to
0.001 kPa. The depth readout and camera depth input use the same accepted external value and
captured surface reference. Stale/disconnected readings become unavailable after at most
3 seconds instead of presenting an old surface value as a current depth.

For a shallow-water check, note surface external pressure, then place the **external sensor**
0.5 m below the water surface. Freshwater should add approximately 4.905 kPa. For example,
101.15 kPa at the surface and 106.06 kPa submerged give 0.5005 m. Keep the reference captured
before immersion; account for the sensor's stated accuracy and any difference from that reference.
Verify that vacuum-pressure changes on `1627` do not change depth. Disconnect the housing and
confirm pressure and depth become unavailable; reconnect and confirm fresh readings return.

The core diagnostic bundle includes `pressure-sensors.json`, which identifies the two characteristics, the exact last accepted payload, read/notification source,
receive time and count; `raw-packets.jsonl` includes rejected packets as well. An advancing
receipt count with unchanged bytes during immersion isolates the problem to what the housing
reports, rather than a UI that failed to update. The Android Export action does not currently
persist this bundle; for device investigation, the read-only
`PressureTelemetryInstrumentation` probe writes a report to the app external-files directory
under `pressure-validation/report.json`, retrievable over authorized USB debugging.


## F3.08 housing investigation, 2026-09-23

A read-only on-device probe collected 10 direct reads per sensor and the normal notification
stream using the existing GATT queue. External pressure `00001625-1212-efde-1523-785feabcd123`
returned `83 27 00 00` in all 10 reads and all 19 received external-pressure packets. This is
101.15 kPa, also the WFH07 protocol example value. Internal pressure (`1627`) and temperature
(`1626`) changed during this capture. The user's immersion test likewise reported 101.15 kPa
both in air and 0.5 m underwater. This establishes that the app is receiving repeated identical
external values, rather than retaining an old UI value. It does not establish whether the cause
is housing firmware, the external sensor or its pressure port.

The 105.5 kPa reference reported in the immersion test was captured from the internal sensor.
A 4.36 kPa difference between it and the external reading clamps the displayed depth to zero.
Changing that reference cannot recover depth from an external-pressure channel which does not
respond to immersion. Do not replace external pressure with the working internal vacuum sensor,
auto-zero on reconnect, or silently treat the protocol example value as a valid changing depth.

The protocol exposes read/notify for pressure and cover; it specifies no BLE command to initiate
an external ADC conversion or repair the sensor. HP5834 I2C conversion commands belong to the
housing MCU, not the phone's Bluetooth interface. Manufacturer firmware/sensor investigation
is needed if the housing continues returning these bytes during immersion.

A separate app defect was found during this investigation: the GATT queue logged a callback
UUID mismatch but still completed the pending read with that payload. Mismatched callbacks
are now rejected, with tests that isolate external pressure, internal pressure and cover reads.
The capture above showed repeated matching external-pressure values; the queue defect is not
claimed to be the demonstrated cause of that constant output.


A second capture was taken after the user confirmed the housing was under stable vacuum.
All 10 direct external reads and all 20 received external packets (19 notifications, one read)
again contained `83 27 00 00`. Internal pressure changed between 78.232 and 78.251 kPa; the
last displayed sample was 78.250 kPa. Temperature also changed. The user reported a vacuum
readout of -23.1 kPa. All 10 cover reads returned `00`, and its priming read also returned `00`.
The user confirmed the suction cap itself was closed. The protocol calls `1628` the **air
extraction cover**, not the main housing door; `00` means open and `01` means closed. Stable
vacuum does not change that byte's definition, so the app must not invent a closed-cap report.
The persistent incorrect cap output requires a housing cap-switch/firmware investigation.

Local raw evidence: `artifacts/pressure-probe-before.json` and
`artifacts/pressure-probe-vacuum.json` (ignored development artifacts). Both captures used the
installed app before the callback-UUID guard was rebuilt. Direct reads and independently
received notifications agree on the constant external value.


## Diagnostics-triggered pressure capture

Entering the existing Diagnostics page starts a new capture segment. Leaving Diagnostics stops
it. No UI controls are added. While that mode is active, the application records the external
pressure (`1625`), internal pressure (`1627`), temperature (`1626`) and suction-cap (`1628`)
packets delivered by HousingLink, including invalid packets and unchanged values. Each JSONL
record contains wall-clock and monotonic receive times, capture and process identifiers,
sequence, sensor, source (read/notification), exact spaced hex bytes, unsigned byte array,
LE32 raw value, decoded pressure in kPa and validation result. Start/stop markers and BLE
connection changes identify recording and connection gaps. The start marker records the current
surface reference. Logging does not add reads, writes, filters or UI refresh timers.

Files are under the Android app external-files directory:
`/sdcard/Android/data/com.mobiledivecontrol/files/pressure-logs/pressure-current.jsonl` and
`pressure-previous.jsonl`. Only these two rotating files are retained, each limited to 4 MiB
for normal BLE-sized records. A bounded 256-entry queue keeps file IO off Bluetooth and UI
threads; disk writes are buffered and flushed approximately once per second while active.
Overflow is explicitly recorded as `dropped_samples`. A storage error stops logging and is
reported to Android logcat without interrupting the sensors. An abrupt process kill can lose
the last unflushed second. No periodic writer wakes run while idle.

For immersion: open Diagnostics and leave it open; hold the external sensor in air for 10 s,
lower it to a measured 0.5 m for 10 s, then return it to air for 10 s. A USB connection is not
required during capture. Leave Diagnostics, allow 2 s for flushing, reconnect USB and retrieve
the two files before reinstalling or clearing app storage. Compare raw external changes with
the independent internal-pressure samples; a 0.5 m freshwater change is approximately 4.905 kPa.


The new logged immersion test is still pending; the user confirmed that no immersion occurred
during these new capture segments. As a separate cap-reporting safeguard, the existing sensor
monitor now rereads `1628` every 2 seconds when notifications are absent. This recovers a lost
cap-change notification without deriving a closed-cap state from vacuum. Fresh `00` reads with
the physical cap closed still indicate an incorrect switch/firmware report and are retained raw.


With the cap-refresh build installed, capture `a967fc7b-be1f-42fb-98d0-cd80b2b64a73`
recorded 10 direct `1628` reads about 2.1 seconds apart. All returned `00`; the user confirmed
the blue cap remained closed and the housing under vacuum throughout. The final internal
pressure sample was 78.681 kPa. This rules out a missed close notification for this capture;
it does not distinguish a reversed switch convention from a fixed switch/firmware output.
A dry open/closed comparison has been requested and is pending. Evidence is retained in
`artifacts/pressure-cap-refresh.jsonl`. No new immersion test has occurred.


The user then opened the blue cap while the housing remained dry. Fresh Cover reads stayed
`00` (evidence: `artifacts/pressure-cap-open.jsonl`). The user next released vacuum; internal
pressure rose from about 78.7 kPa to 100.94 kPa while external pressure remained 101.15 kPa
and Cover remained `00` (`artifacts/pressure-vacuum-release.jsonl`). The user subsequently
opened the main door with the blue cap still open. This is a separate fully open, vented
condition (`artifacts/pressure-housing-open.jsonl`). Reclosing the blue cap at ambient pressure
has not yet been confirmed. The new logged immersion test is still pending.


The user next confirmed **blue cap on, main housing door open**. The latest direct Cover
reads again returned `00`; no `01` has appeared in the capture. Internal pressure was
101.168 kPa in the final sample, and external pressure remained 101.15 kPa. Evidence:
`artifacts/pressure-cap-on-door-open.jsonl`. Closing the main door with the blue cap kept on,
without pumping vacuum, is the remaining ambient-pressure comparison.


The user then confirmed **door closed, cap on, pump not started**. Fresh Cover reads stayed
`00`, with internal pressure 107.947 kPa and external pressure 101.15 kPa in the final sample
(`artifacts/pressure-cap-on-door-closed-no-vacuum.jsonl`). The complete dry comparison has now
shown `00` with both open, cap on/door open, and both closed, as well as under vacuum. The
current data do not support simply reversing the Cover decoder. The elevated internal pressure
also exposes the existing one-sided surface-baseline cross-check: it rejects internal pressure
far below external pressure but accepts elevated internal pressure. That baseline limitation
remains under investigation; no baseline correction was deployed during the physical tests.

The user subsequently started another pump cycle with the cap off. A short Diagnostics visit
recorded internal pressure changing from 100.207 to 100.278 kPa while water remained
`83 27 00 00` (101.15 kPa). The capture stopped when Diagnostics was left, so this is not a
complete pump trace (`artifacts/pressure-pump-cycle.jsonl`).


Pump regression investigation: the user reports that pumping worked before these changes but
now repeatedly stops with `NO_SUCTION_WARNING`. The existing state machine produces that
warning after a 1-second no-progress window with less than 0.5 kPa total suction. Those
thresholds and motor/valve command bytes have not been changed in this task. To remove possible
GATT contention introduced by telemetry polling, fallback reads now pause before the pump
workflow submits actuator writes and until the motor-off write completes. Notifications remain
live; reads that were already in flight are discarded if the pause invalidates them. Two tests
cover pause/resume and stale in-flight reads. Small `DivePump` log entries now identify command
send/completion timing and safety-state/warning transitions. This is a regression mitigation,
not a demonstrated hardware diagnosis or proof that pump operation has been restored.

`PressureDiscoveryInstrumentation` performs a fresh scan and service discovery, then reads and
subscribes to the four documented sensor characteristics without constructing HousingLink,
ControlCore or a ViewModel. It independently decodes the two raw pressure fields and retains the
full discovered GATT tree. It sends no motor or valve commands. This probe is for the user's
requested independent re-scan; running it replaces the application process, so normal app launch
must follow it and it must be run with the pump stopped.


## Latest independent scan and immersion result (2026-09-23)

The standalone discovery probe passed on F3.08: 7 services, all 16 mapped characteristics,
no additional pressure channel. Its 20 water-pressure reads and 39 notifications were all
`83 27 00 00` (101.15 kPa). Internal pressure varied from 101.144 to 101.173 kPa.
Evidence: `artifacts/pressure-fresh-discovery.json`.

The subsequent user-triggered pump cycle reached its target after about 8.9 seconds:
internal pressure fell from 104.174 to 83.986 kPa, motor-off succeeded, and leak monitoring
began. This confirms one successful cycle after the polling pause change, not the cause of
the preceding failures. Safety thresholds remain unchanged.

The user then reported 10 seconds at the surface and 10 seconds underwater in Diagnostics.
The latest 33.57-second capture contains 67 external notifications, all 101.15 kPa, alongside
67 changing internal notifications (84.269–84.557 kPa). Sequence numbers are contiguous.
A captured surface baseline is absent in this segment. Earlier reference problems do not
explain identical raw external bytes throughout immersion. Depth response remains unresolved;
the next check is manufacturer-app behavior or F3.08 sensor-acquisition requirements.
Full evidence and limitations: `artifacts/pressure-immersion-analysis.md` and
`artifacts/pressure-immersion-current.jsonl`.


## Confirmed depth response and live HUD (2026-09-23)

The user's subsequent immersion over 2 m recorded 135 valid external notifications in 67 s,
with water pressure 101.15–129.71 kPa and calculated depth 0–2.893476 m (displayed 2.9 m).
The final sample returned to 0.0 m. All capture sequences are present, with no invalid samples
or drop markers. Internal pressure independently ranged 83.244–83.519 kPa. Evidence is
`artifacts/pressure-two-metre-test/pressure-current.jsonl`; the physical depth was approximate.
This confirms changing external pressure during this test, without establishing why earlier
captures stayed fixed.

Water log rows now include `rawPressureLE24`, `depthReferenceKpa`, `calculatedDepthMeters`
and `depthDisplay`. The camera receives each accepted reading through ControlCore ->
DiveViewModel StateFlow -> DiveControlContent -> rememberLivePressure -> DepthGauge, using
exactly the same shared pressure-depth function. No smoothing or periodic depth UI polling
is used. The maximum is accumulated in core state, independently of the currently shown screen.
