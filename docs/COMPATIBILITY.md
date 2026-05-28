# MobileDiveControl — Compatibility Matrix

This document defines the device/firmware compatibility tracking system. Derived from `Claude.md` §24.

---

## 1. Compatibility Tiers

| Tier | Meaning | Criteria |
|---|---|---|
| **Certified** | Tested and recommended | Full test suite passed, field tested |
| **Supported** | Works with known limitations | Core features verified, some edge cases documented |
| **Partial** | Core features work, some limited | Camera or Transparent Phone Mode partially functional |
| **Unsupported** | Not recommended | Critical features fail or untested |

---

## 2. Per-Device Compatibility Report

The app should display to the user:

```
This phone supports:
  Camera Tier: Pro / Advanced / Basic
  Transparent Phone Mode: Supported / Limited / Unavailable
  Overlay Cursor: Supported / Unavailable
  Housing Firmware: Compatible / Incompatible / Unknown
```

---

## 3. Camera Capability Tiers

| Tier | Features | Detection |
|---|---|---|
| **Basic Camera** | Photo, video, zoom | Default — available on all Camera2 devices |
| **Advanced Camera** | + Exposure compensation, lens switch, autofocus trigger | Detected via `CameraCharacteristics` |
| **Pro Camera** | + Manual ISO, shutter speed, manual focus, white balance | Requires `MANUAL_SENSOR` + `MANUAL_POST_PROCESSING` capabilities |
| **Unsupported** | Feature not exposed by device | Hide in UI, list in diagnostics |

### Detection Checklist (Android)

| Capability | API Check |
|---|---|
| Available lenses | `CameraManager.getCameraIdList()` |
| Zoom range | `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` or `CONTROL_ZOOM_RATIO_RANGE` |
| Exposure compensation range | `CONTROL_AE_COMPENSATION_RANGE` |
| Manual focus support | `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` |
| ISO range | `SENSOR_INFO_SENSITIVITY_RANGE` |
| Shutter speed range | `SENSOR_INFO_EXPOSURE_TIME_RANGE` |
| White balance support | `CONTROL_AWB_AVAILABLE_MODES` |
| Stabilization | `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` |
| RAW support | `REQUEST_AVAILABLE_CAPABILITIES` contains `RAW` |
| Video profiles | `CamcorderProfile` or `EncoderProfiles` |

---

## 4. Android Version Compatibility

| Android Version | API Level | Status | Notes |
|---|---|---|---|
| Android 15 | 35 | Target | Primary development platform |
| Android 14 | 34 | Supported | Full feature support |
| Android 13 | 33 | Supported | BLE permissions model change |
| Android 12 | 31–32 | Supported | New BLE permission model (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) |
| Android 11 | 30 | Partial | Scoped storage changes |
| Android 10 | 29 | Partial | Background location restrictions may affect BLE scanning |
| Android 9 and below | ≤ 28 | Unsupported | Missing required BLE and camera APIs |

---

## 5. BLE Stability Matrix

| Phone Model | Chipset | BLE Stability | Reconnect Behavior | Notes |
|---|---|---|---|---|
| *(To be populated during testing)* | | | | |

### Known BLE Issues to Watch

- Some Samsung devices aggressively kill background BLE connections
- Xiaomi/MIUI may restrict background Bluetooth access
- OnePlus battery optimization may disconnect BLE during recording
- Some MediaTek chipsets have BLE notification reliability issues

---

## 6. Transparent Phone Mode Compatibility

| Phone/OEM | Accessibility Behavior | Overlay Behavior | Smart Target | Notes |
|---|---|---|---|---|
| *(To be populated during testing)* | | | | |

### Known OEM Accessibility Issues

- Samsung One UI: May intercept accessibility events
- Xiaomi/MIUI: May require additional permissions for overlay
- Huawei/EMUI: May restrict overlay drawing
- Oppo/ColorOS: May kill foreground service aggressively

---

## 7. Housing Firmware Compatibility

| Firmware Version | App Version | Status | Notes |
|---|---|---|---|
| *(To be populated as firmware versions are tested)* | | | |

### Firmware Compatibility Check (On Connection)

1. Read device information characteristic (`0x180A`)
2. Read firmware revision
3. Compare with supported firmware matrix
4. Enable supported features
5. Disable unsupported features
6. Warn only if a missing feature affects user goals

---

## 8. Thermal Behavior Matrix

| Phone Model | Video Duration Before Throttle | Camera Behavior | BLE Behavior | Notes |
|---|---|---|---|---|
| *(To be populated during field testing)* | | | | |

---

## 9. Code Location

| Component | File | Description |
|---|---|---|
| Camera tier detection | *(Platform adapter — not yet implemented)* | Reads `CameraCharacteristics` |
| Compatibility display | `core/DiagnosticsStore.kt` → `compatibility-info.json` | Export includes compatibility tier |
| Permission state | `core/Contracts.kt` → `PermissionsState` | Tracks per-permission state |

---

*Last updated: 2026-05-26 — Template. Populate during testing phases.*
