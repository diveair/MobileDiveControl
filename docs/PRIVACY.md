# MobileDiveControl — Privacy & Data Protection Policy

Derived from `Claude.md` §2, §22, §3.5. This document defines what the app collects, stores, and explicitly must never collect.

---

## 1. Privacy Promise

MobileDiveControl makes explicit promises to users:

- **Never** collect screen content from Transparent Phone Mode
- **Never** store passwords, payment data, messages, contacts, or screenshots
- **Never** require internet for core underwater operation
- **Never** silently ignore critical housing input
- **Never** falsely show seal state as passed
- Fail visibly when a feature is unavailable
- Preserve useful diagnostics after failure

---

## 2. Data Classification

### ✅ Stored Locally (On-Device Only)

| Data | Purpose | Storage |
|---|---|---|
| Last paired housing identity | Auto-reconnect | DataStore / SharedPreferences |
| Last selected mode | Restore user preference | DataStore |
| Camera settings preset | Restore camera configuration | DataStore |
| Cursor speed profile | User preference | DataStore |
| Cursor mode preference | Free Cursor vs Smart Target | DataStore |
| Permission onboarding completion | Don't re-show completed steps | DataStore |
| Crash diagnostic snapshot | Post-crash support | Local file |
| Safety check result history | Pre-dive reference | Local file |
| UI scale | User preference | DataStore |
| Measurement units | Metric/imperial preference | DataStore |
| Diagnostic ring buffers | Support debugging | In-memory, async flush |
| JSONL event logs | Support debugging | Local files |

### 🔴 Never Stored (Anywhere)

| Data | Reason |
|---|---|
| Passwords | Privacy — no legitimate use |
| Payment data | Privacy — no legitimate use |
| Message content | Privacy — no legitimate use |
| Contact content | Privacy — no legitimate use |
| Screen text from other apps | Privacy — Transparent Phone Mode must not capture content |
| Screenshots | Privacy — no screen recording |
| Raw accessibility trees | Privacy — may contain sensitive text |
| Third-party app content | Privacy — no data exfiltration |

---

## 3. Transparent Phone Mode Privacy

Transparent Phone Mode uses Android's AccessibilityService to navigate UI. This requires special privacy handling.

### What the app reads from accessibility:
- Node geometry (bounding rectangles)
- Clickability / focusability flags
- Scrollability flags
- Role/class metadata
- Visibility state

### What the app does NOT read:
- Text content of UI elements (by default)
- Password field values
- Input field content
- Notification content
- Message previews

### What the app does NOT store:
- Any screen text
- Any accessibility tree dumps
- Any content from other apps

### Permission Education

When requesting AccessibilityService, the app must explain clearly:

> *"To control Android buttons from the housing, enable MobileDiveControl Phone Control. This lets the housing move a cursor and click on-screen controls while the phone is sealed inside the housing."*

Not:

> ~~"Enable Accessibility."~~

---

## 4. Diagnostics Privacy

### Diagnostic Export Contains:
- Device model and OS version
- App version
- Housing firmware version
- Camera capability tier
- Permission grant states
- Decoded event logs (button events, commands, results)
- Error logs
- Latency statistics

### Diagnostic Export Does NOT Contain:
- Screen text
- Screenshots
- Passwords
- Messages
- Contacts
- Raw accessibility data
- Third-party app content

---

## 5. Optional Telemetry (Opt-In Only)

If anonymous telemetry is ever enabled, it must be:
- **Opt-in** (not opt-out)
- **Category-level only** (no raw content)
- **Clearly disclosed**

### Permitted Telemetry Categories

| Category | Example Value |
|---|---|
| App version | `1.2.3` |
| Phone model | `Pixel 8 Pro` |
| OS version | `Android 15` |
| Firmware version | `2.1.0` |
| BLE disconnect category | `timeout` |
| Camera capability tier | `Pro` |
| Permission state category | `all_granted` |
| Error code counts | `{parser_error: 3}` |
| Latency histogram | `{p50: 42, p95: 110}` |

### Prohibited Telemetry

| Category | Reason |
|---|---|
| Screen text | Content privacy |
| Screenshots | Content privacy |
| Passwords | Security |
| Messages | Content privacy |
| Contacts | Content privacy |
| Payment data | Security |
| Raw accessibility trees | Content privacy |
| Third-party app content | Content privacy |

---

## 6. Network Policy

| Context | Network Required? |
|---|---|
| Core underwater operation | ❌ No — fully offline |
| BLE housing connection | ❌ No |
| Camera operation | ❌ No |
| Transparent Phone Mode | ❌ No |
| Safety features | ❌ No |
| Diagnostics capture | ❌ No |
| Diagnostic export (local file) | ❌ No |
| Diagnostic upload (if offered) | ✅ Yes — opt-in only |
| Update checks | ✅ Yes — non-blocking |
| Firmware compatibility check | ✅ Yes — cacheable |

---

## 7. Google Play Policy Considerations

### AccessibilityService Disclosure
- Must clearly state the purpose of the AccessibilityService
- Must not collect screen content
- Must comply with Google Play Accessibility API policy
- Must demonstrate that accessibility is used for the app's core functionality (housing control)

### Permissions Justification

| Permission | Justification |
|---|---|
| Bluetooth scan/connect | Connect to BLE dive housing |
| Camera | Underwater camera preview and capture |
| Microphone | Video audio recording |
| Storage/Media | Save photos and videos |
| Overlay | Draw cursor for housing-based phone control |
| AccessibilityService | Enable housing buttons to navigate Android UI |
| Foreground service | Maintain reliable BLE connection during dive |
| Notifications | Show foreground service status and critical alerts |

---

## 8. Data Retention

| Data | Retention |
|---|---|
| In-memory ring buffers | Until app restart |
| JSONL log files | Until user clears or app data cleared |
| Settings/preferences | Until user clears or uninstalls |
| Diagnostic exports | User-managed files |

---

*Last updated: 2026-05-26*
