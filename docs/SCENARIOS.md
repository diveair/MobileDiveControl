# MobileDiveControl — Scenario Language Reference

This document describes the scenario scripting language used by `ScenarioScriptRunner` for deterministic testing without hardware.

---

## 1. Overview

Scenarios are plain-text scripts that simulate housing events, sensor data, and app state changes. They drive the control core through its full pipeline: BLE events → parser → normalizer → router → reducer → effects.

Scenario files use the `.scenario` extension and are stored in the `scenarios/` directory.

### Running a Scenario

```powershell
.\run-gradle.ps1 run --args="scenarios/smoke.scenario"
```

---

## 2. Command Reference

### BLE Commands

| Command | Description | Example |
|---|---|---|
| `ble scan` | Simulate BLE scan start | `ble scan` |
| `ble connect` | Simulate successful BLE connection | `ble connect` |
| `ble discover` | Simulate service discovery | `ble discover` |
| `ble subscribe` | Simulate notification subscription | `ble subscribe` |
| `ble ready` | Simulate BLE fully ready | `ble ready` |
| `ble disconnect` | Simulate BLE disconnection | `ble disconnect` |
| `ble degrade` | Simulate degraded connection (optional characteristic missing) | `ble degrade` |
| `ble fail` | Simulate connection failure | `ble fail` |
| `ble reset` | Reset BLE to idle | `ble reset` |

### Permission Commands

| Command | Description | Example |
|---|---|---|
| `permission <name> on` | Grant a permission | `permission bluetooth on` |
| `permission <name> off` | Revoke a permission | `permission accessibility off` |

Aliases: `on`/`true`/`enabled` all grant; `off`/`false`/`disabled` all revoke.

Valid permission names: `bluetooth`, `camera`, `microphone`, `overlay`, `accessibility`, `foreground-service`, `notifications`

### Mode Commands

| Command | Description | Example |
|---|---|---|
| `mode <mode>` | Switch to a specific mode | `mode camera` |

Valid mode values: `camera`, `camera-adjust`, `phone-cursor`, `phone-target`, `safety`, `diagnostics`

### Button Commands

| Command | Description | Example |
|---|---|---|
| `button <hex-byte>` | Simulate a button press with raw BLE value | `button 0x50` |
| `notify <characteristic> <hex-payload>` | Inject a raw BLE notification or read response | `notify 0x1628 00` |
| `notify-text <characteristic> <text>` | Inject a UTF-8 device-info response | `notify-text 0x2A26 A4.0` |

Standard button hex values:
- `0x10` — Right
- `0x20` — Shutter
- `0x30` — Up
- `0x40` — Left
- `0x50` — OK
- `0x60` — BackOrSafety (Down long press)
- `0x61` — Down
- `0x70` — ZoomIn (Z+)
- `0x80` — ZoomOut (Z-)

### Battery Commands

| Command | Description | Example |
|---|---|---|
| `battery <0-100>` | Simulate housing battery level update through `0x2A19` | `battery 85` |

### Sensor Commands

| Command | Description | Example |
|---|---|---|
| `sensor cover <open\|closed>` | Simulate cover state change | `sensor cover open` |
| `sensor barometric <kpa>` | Simulate barometric pressure reading | `sensor barometric 101.3` |
| `sensor water-pressure <kpa>` | Simulate water pressure reading | `sensor water-pressure 200.0` |
| `sensor water-temp <celsius>` | Simulate water temperature reading | `sensor water-temp 22.5` |

### Vacuum Commands

| Command | Description | Example |
|---|---|---|
| `vacuum start` | Trigger vacuum check workflow | `vacuum start` |
| `vacuum cancel` | Cancel active vacuum check | `vacuum cancel` |
| `vacuum reset` | Reset seal state to Unknown | `vacuum reset` |

### Diagnostic Commands

| Command | Description | Example |
|---|---|---|
| `export` | Trigger diagnostic export and print bundle | `export` |
| `state` | Print current app state summary | `state` |

---

## 3. Available Scenarios

### smoke.scenario
**Purpose:** Basic end-to-end verification — connect, press buttons, verify routing.

### phone-control.scenario
**Purpose:** Test Transparent Phone Mode — cursor movement, click, back, scroll.

### permission-revoked.scenario
**Purpose:** Test behavior when permissions are revoked — should disable affected features safely.

### vacuum-pass.scenario
**Purpose:** Full vacuum seal check workflow — cover open → vacuum → stabilize → pass.

### malformed-packet.scenario
**Purpose:** Feed invalid BLE data to verify parser hardening — no crashes.

---

## 4. Writing New Scenarios

### Rules
- One command per line
- Empty lines are ignored
- Lines starting with or containing `#` are treated as comments (text after `#` is stripped)
- Commands execute sequentially
- The runner prints state changes and effects after each command
- Raw protocol commands also print `tx:` lines for the BLE reads and writes the transport layer should execute

### Template

```
# Description of what this scenario tests
ble scan
ble connect
ble ready

# Set up permissions
permission bluetooth on
permission camera on

# Switch mode and test buttons
mode camera
button 0x20
button 0x70

# Check state
state

# Export diagnostics
export
```

---

## 5. Acceptance Focus

For every code change, re-run at minimum:

1. `.\run-gradle.ps1 test` — all unit tests
2. `.\run-gradle.ps1 run --args="scenarios/smoke.scenario"` — basic flow
3. `.\run-gradle.ps1 run --args="scenarios/malformed-packet.scenario"` — parser hardening
4. `.\run-gradle.ps1 run --args="scenarios/permission-revoked.scenario"` — permission safety

If the change touches safety logic:

5. `.\run-gradle.ps1 run --args="scenarios/vacuum-pass.scenario"` — vacuum workflow

---

## 6. Report Format

When reporting scenario results:

```
Command:
.\run-gradle.ps1 run --args="scenarios/<name>.scenario"

Observed output:
<paste output>

What you expected:
<short note>

What differed:
<short note or "none">
```

---

*Last updated: 2026-05-26*
