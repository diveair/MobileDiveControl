# Test Protocol

The repo is set up for deterministic local testing. Use the commands below and send the output back unchanged.

## Prerequisites

- JDK 17+

## Automated Tests

Run:

```powershell
.\run-gradle.ps1 test
```

Report back:

- total passed
- total failed
- the first failing test name
- the full stack trace for the first failure

## Scenario Runner

Each scenario is a plain-text script. Run one with:

```powershell
.\run-gradle.ps1 run --args="scenarios/smoke.scenario"
```

Useful scenarios:

- `scenarios/smoke.scenario`
- `scenarios/phone-control.scenario`
- `scenarios/permission-revoked.scenario`
- `scenarios/vacuum-pass.scenario`
- `scenarios/malformed-packet.scenario`

## Report Format

Send results in this format:

```text
Command:
.\run-gradle.ps1 run --args="scenarios/smoke.scenario"

Observed output:
<paste output>

What you expected:
<short note>

What differed:
<short note or "none">
```

## Scenario Language

Supported commands:

```text
ble scan
ble connect
ble ready
ble disconnect
permission <name> <on|off>
mode <camera|camera-adjust|phone-cursor|phone-target|safety|diagnostics>
button <hex-byte>
battery <0-100>
sensor cover <open|closed>
sensor barometric <kpa>
sensor water-pressure <kpa>
sensor water-temp <celsius>
vacuum <start|cancel|reset>
export
state
```

## Acceptance Focus

For every change, rerun at least:

1. `.\run-gradle.ps1 test`
2. `.\run-gradle.ps1 run --args="scenarios/smoke.scenario"`
3. `.\run-gradle.ps1 run --args="scenarios/malformed-packet.scenario"`
4. `.\run-gradle.ps1 run --args="scenarios/permission-revoked.scenario"`

If the change touches safety logic, also run:

```powershell
.\run-gradle.ps1 run --args="scenarios/vacuum-pass.scenario"
```
