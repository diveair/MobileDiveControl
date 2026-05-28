# MobileDiveControl — Security Reference

Derived from `Claude.md` §9. This document covers the security architecture, threat model, and hardening requirements.

---

## 1. Security Philosophy

```
Make compromise difficult.
Make unsafe failure unlikely.
Make failure visible.
Make recovery fast.
Keep critical control alive when noncritical systems fail.
```

The goal is **not** to claim the app is impossible to hack. The goal is defense-in-depth with visible failure.

---

## 2. Security Zones

| Zone | Trust Level | Components | Boundary Rules |
|---|---|---|---|
| **Trusted Native Control Core** | Highest | BLE parser, input router, camera executor, safety executor | Only native code; no RN bridge in critical path |
| **Platform Permission Layer** | High | AccessibilityService, overlay service, foreground service | OS-enforced permissions |
| **Presentation Layer** | Medium | Native UI or optional React Native shell | May fail without affecting control |
| **Diagnostics Layer** | Medium | Local logs, export bundle | No sensitive content; redacted |
| **Network Layer** | Low | Update checks, optional support upload | Non-blocking; not required for core operation |
| **External Inputs** | Untrusted | BLE packets, OTA files, Android intents, network responses | Must be parsed, validated, bounded, logged |

---

## 3. BLE Trust Model

### The Problem
Anyone can advertise a BLE device named `DIVE IT`. The advertising name is **not** identity.

### Required Verification Steps

1. **Service UUID check** — match expected service UUIDs (`0x1523`, `0x180F`, `0x180A`, etc.)
2. **Device information read** — read `0x180A` characteristics
3. **Firmware revision read** — verify firmware version
4. **Firmware matrix check** — compare against supported firmware list
5. **Persist trusted identity** — store user-approved housing identity (e.g., MAC + firmware hash)
6. **Same-name warning** — warn if a different device appears with the name `DIVE IT`
7. **Multi-device confirmation** — require manual user confirmation if multiple `DIVE IT` devices are nearby

### Implementation Status
- `HousingState.trustedIdentity` — persisted identity field (in `Contracts.kt`)
- Verification logic — *(not yet implemented; requires platform BLE adapter)*

---

## 4. High-Risk Command Safeguards

### High-Risk Commands
- `SetVacuumMotor(enabled=true)` — starts vacuum pump
- `SetSolenoidValve(open=true)` — opens solenoid valve
- OTA firmware update — *(disabled in MVP)*
- Any command that changes safety state

### Required Preconditions (All Must Be True)

| Precondition | Description |
|---|---|
| Valid app state | App is in correct mode for the command |
| Trusted housing identity | BLE device verified and user-approved |
| Safety preconditions | Safety state machine permits the transition |
| Timeout enforced | Motor/valve cannot run indefinitely |
| Result logged | Command + result written to diagnostics |
| Stop/failure behavior | Explicit handling for timeout or error |

### Implementation
- `SafetyStateMachine` enforces state transitions before emitting hardware effects
- `startVacuumCheck()` validates cover is open before emitting motor/solenoid effects
- `cancelVacuumCheck()` always emits stop-motor + close-solenoid effects

---

## 5. Parser Hardening Rules

| Rule | Implementation | Status |
|---|---|---|
| Bound all packet lengths | `ProtocolParser.decodeButtonPacket` checks `payload.size != 1` | ✅ |
| Reject malformed packets | Returns `ParseResult.Failure` with error code | ✅ |
| Reject unexpected characteristic lengths | Battery parser validates 1-byte length | ✅ |
| Unknown buttons → `Unknown(rawValue)` | Does not crash, logs the value | ✅ |
| Do not crash on unknown telemetry | Parser returns failure, not exception | ✅ |
| Do not allocate large buffers from packet data | No dynamic allocation from payload | ✅ |
| Do not parse on UI thread | *(Architecture enforced in platform adapter)* | 🔲 |
| Fuzz test malformed packets | `malformed-packet.scenario` + unit tests | ✅ |

---

## 6. OTA Firmware Security (MVP: Disabled)

OTA is architecture-ready but **disabled by default** in MVP.

### Required Before Enabling OTA

| Requirement | Status |
|---|---|
| Firmware package signature verification | 🔲 Not implemented |
| Manufacturer public key pinning | 🔲 Not implemented |
| Hardware revision compatibility check | 🔲 Not implemented |
| Firmware compatibility check | 🔲 Not implemented |
| Housing battery threshold (minimum %) | 🔲 Not implemented |
| Phone battery threshold (minimum %) | 🔲 Not implemented |
| Transfer checksum | 🔲 Not implemented |
| Full-image validation | 🔲 Not implemented |
| Recovery/rollback strategy | 🔲 Not implemented |
| Interruption handling | 🔲 Not implemented |
| No unsigned firmware path | 🔲 Enforced by disabling OTA |
| No debug OTA path in production | 🔲 Enforced by disabling OTA |

---

## 7. Permission Security

### Permission Revocation Handling

| Revoked Permission | System Response |
|---|---|
| Bluetooth | Cannot connect to housing; disable all housing input |
| Camera | Camera Mode unavailable; show error |
| Microphone | Video audio unavailable; warn user |
| Overlay | Cursor unavailable; fall back to Smart Target if possible |
| Accessibility | Transparent Phone Mode unavailable; disable |
| Foreground Service | Reduced reliability warning |
| Notifications | Alert visibility may be limited |

### Implementation
- `PermissionsState` in `Contracts.kt` tracks all permission states
- `canUsePhoneControl()` — returns `true` only if accessibility is granted
- `canUseOverlayCursor()` — returns `true` only if both accessibility AND overlay are granted
- `InputRouter` checks `housing.inputEnabled` before routing

---

## 8. Accessibility Security

### What the AccessibilityService Does
- Reads node geometry (bounding rectangles)
- Reads clickability/focusability flags
- Reads scrollability flags
- Dispatches tap/click/scroll gestures

### What the AccessibilityService Must NOT Do
- Read text content from UI elements
- Store screen text
- Log accessibility tree dumps with text
- Auto-click permission dialogs
- Auto-approve security prompts
- Interact with password/payment fields (except user-commanded navigation)

---

## 9. Release Hardening Checklist

| Check | Description | Status |
|---|---|---|
| Debug flags removed | No debug/test flags in production build | 🔲 |
| Log redaction | Sensitive data never appears in logs | ✅ (DiagnosticsStore uses state summaries) |
| Exported components | Review all exported Android components | 🔲 |
| Intent injection | Validate all incoming intents | 🔲 |
| Dependency vulnerability scan | Check all dependencies for known CVEs | 🔲 |
| Static analysis | Run lint and security analysis tools | 🔲 |
| ProGuard/R8 obfuscation | Release builds obfuscated | 🔲 |
| Network security config | Certificate pinning for any network calls | 🔲 |
| Signing key management | Secure signing key storage | 🔲 |

---

## 10. Security Test Requirements

### Must Pass Before Release

| Test | Description |
|---|---|
| Malformed BLE packets | App does not crash on any input |
| Same-name rogue BLE device | App warns user; does not auto-connect |
| Duplicate packet bursts | Normalizer filters duplicates |
| Permission revocation | App disables features safely |
| Overlay revocation | Cursor disappears; app continues |
| Accessibility disabled | Phone mode disabled; camera continues |
| Intent injection | No unintended actions from external intents |
| Exported component review | No sensitive data exposed |
| Log redaction | No passwords/tokens/screen text in logs |
| Debug flag detection | No debug code in release builds |
| Dependency vulnerability scan | No known critical CVEs |
| Static analysis | No critical findings |
| Release build hardening | Obfuscation, signing, network security |

---

## 11. Kill Switches

Local feature-disable flags for safety:

| Feature | Kill Switch | Purpose |
|---|---|---|
| Vacuum motor control | `feature.vacuum.enabled` | Disable if motor behavior is unsafe |
| Solenoid control | `feature.solenoid.enabled` | Disable if valve behavior is uncertain |
| OTA firmware | `feature.ota.enabled` (default: false) | Disabled in MVP |
| IR flashlight control | `feature.ir_flashlight.enabled` | Disable if command format changes |
| Smart Target beta | `feature.smart_target.enabled` | Disable if accessibility behavior is unstable |
| React Native shell | `feature.rn_shell.enabled` | Disable if RN destabilizes startup |

Kill switches are for **safety and reliability only** — not for marketing experiments.

---

*Last updated: 2026-05-26*
