# MobileDiveControl — BLE Protocol Reference

This document is the app-developer-facing reference for the BLE protocol used by the DIVE IT housing. It is derived from the product spec (`Claude.md` §5, §18), the vendor hardware protocol specification (`tpyrced_Bluetooth-and-APP-protocol-description-A4.0-20241224.docx`), and kept in sync with the actual code implementation.

---

## 1. BLE Overview

| Property | Value | Source |
|---|---|---|
| BLE Version | 5.0 | Vendor spec §4 |
| Transmitting Power | 4 dBm | Vendor spec §2 |
| Communication Distance | 100m | Vendor spec §2 |
| Reception Sensitivity | -95 dBm | Vendor spec §2 |
| Voltage | 1.8–3.6V | Vendor spec §2 |
| Advertising Name | `DIVE IT` | Vendor spec §4.1 |
| Advertising Type | Connectable Undirected | Vendor spec §4.1 |
| Advertising Interval | 200ms | Vendor spec §4.1 |
| Advertising Duration | 180s | Vendor spec §4.1 |
| Advertising Data | MAC address (6 bytes) | Vendor spec §4.1 |
| Address Type | Static Device Address | Vendor spec §4.1 |
| Identity | Must not trust advertising name alone (see §7) |

### LED Indicator States

| LED State | Meaning |
|---|---|
| Red on | Charging |
| Green on | Charging complete |
| Blue on (solid) | Bluetooth connected |
| Blue flashing | Bluetooth waiting to connect |

---

## 2. Services and Characteristics

### Standard Services

| Service | UUID | Direction | App Use |
|---|---|---|---|
| Battery Service | `0x180F` | Housing → App | Housing battery level |
| Device Information | `0x180A` | Housing → App | Firmware, hardware, serial, manufacturer |

#### Battery Level Characteristic
- **UUID:** `0x2A19`
- **Format:** 1 byte, unsigned integer, 0–100
- **Direction:** Housing → App (read + notify)
- **Example:** `0x64` = 100% battery

#### Device Information Service (`0x180A`)
| Characteristic | UUID | Permission |
|---|---|---|
| Manufacturer Name | `0x2A29` | Read |
| Software Revision | `0x2A28` | Read |
| Hardware Revision | `0x2A27` | Read |
| Firmware Revision | `0x2A26` | Read |
| Serial Number | `0x2A25` | Read |

- **Manufacturer:** `UMEING`

### Custom Services

#### Button Notification Service

| Characteristic | UUID | Direction | Description |
|---|---|---|---|
| Button Service | `0x1523` | — | Service container |
| Button Events | `0x1524` | Housing → App (read + notify) | Physical button press stream |
| Flash Trigger | `0x1525` | App → Housing (write) | Trigger external flash once |

> **Vendor Base UUID:** `23D1BCEA-5F78-2315-DEEF-1212xxxx-00000000`
> Replace `xxxx` with the short UUID (e.g., `0x1523` → `23D1BCEA-5F78-2315-DEEF-1212-1523-00000000`)

#### Housing Control & Sensor Service

| Characteristic | UUID | Direction | Description |
|---|---|---|---|
| Sensor/Control Service | `0x1623` | — | Service container |
| Vacuum Motor | `0x1624` | App → Housing (write) | Start/stop vacuum pump |
| Water Pressure | `0x1625` | Housing → App (read + notify) | Depth/pressure sensor |
| Water Temperature | `0x1626` | Housing → App (read + notify) | Water temperature sensor |
| Barometric Pressure | `0x1627` | Housing → App (read + notify) | Internal pressure for seal monitoring |
| Air Extraction Cover | `0x1628` | Housing → App (read + notify) | Cover open/closed state |
| Solenoid Valve | `0x1629` | App → Housing (write) | Open/stop solenoid valve |
| IR Flashlight | `0x162A` | App → Housing (write) | Infrared remote control |

> **Vendor Base UUID:** Same template — `23D1BCEA-5F78-2315-DEEF-1212xxxx-00000000`

---

## 3. Button Mapping

| Housing Button | BLE Value (hex) | BLE Value (dec) | Internal Event | Notes |
|---|---|---|---|---|
| Right | `0x10` | 16 | `HousingButtonEvent.Right` | Short press |
| Shutter | `0x20` | 32 | `HousingButtonEvent.Shutter` | Short press; also **Power On** |
| Up | `0x30` | 48 | `HousingButtonEvent.Up` | Short press |
| Left | `0x40` | 64 | `HousingButtonEvent.Left` | Short press |
| OK | `0x50` | 80 | `HousingButtonEvent.Ok` | Short press; long press = **Power Off** |
| Down | `0x61` | 97 | `HousingButtonEvent.Down` | Short press |
| Down (long press) | `0x60` | 96 | `HousingButtonEvent.BackOrSafety` | Long press |
| Z+ (Zoom In) | `0x70` | 112 | `HousingButtonEvent.ZoomIn` | Short press |
| Z- (Zoom Out) | `0x80` | 128 | `HousingButtonEvent.ZoomOut` | Short press |

### Parser Rules
- Raw BLE values are decoded **exactly once** in `ProtocolParser`
- No downstream code (UI, camera, accessibility) may depend on raw byte values
- Unknown values → `HousingButtonEvent.Unknown(rawValue)` — logged, not crashed
- Malformed packets (wrong length, out-of-range) → rejected, logged, not crashed
- Parsing must never occur on the UI thread

---

## 4. Sensor Data Encoding

All multi-byte sensor values use **little-endian** byte order, **4 bytes**, unsigned 32-bit integers.

> **Source:** Vendor spec `tpyrced_Bluetooth-and-APP-protocol-description-A4.0-20241224.docx`

| Sensor | Characteristic | Bytes | Raw Unit | Conversion to App Unit | Range |
|---|---|---|---|---|---|
| Water Pressure | `0x1625` | 4, LE, unsigned | 0.1 mbar | `raw / 100.0` → kPa | 0–1400 kPa (resolution 50 kPa) |
| Water Temperature | `0x1626` | 4, LE, unsigned | 0.01 °C | `raw / 100.0` → °C | — |
| Barometric Pressure | `0x1627` | 4, LE, unsigned | 1 Pa | `raw / 1000.0` → kPa | 30–120 kPa (resolution 5 kPa) |
| Air Extraction Cover | `0x1628` | 1 byte | — | `0x00 = OPEN`, `0x01 = CLOSED` | — |

### Vendor Encoding Examples

The vendor labels the first received byte as "1st byte (MSB)" but marks it "(LSB)" underneath — this confirms **the first byte received is the LSB** (standard little-endian).

| Sensor | Raw Bytes (as received) | LE Decode | Human Value |
|---|---|---|---|
| Water Pressure | `0x83270000` | `0x2783` = 10115 | 10115 × 0.1 mbar = 1011.5 mbar = 101.15 kPa |
| Water Temperature | `0x580b0000` | `0x0B58` = 2904 | 2904 × 0.01 = 29.04 °C |
| Barometric Pressure | `0x7f8a0100` | `0x18A7F` = 100991 | 100991 Pa = 100.991 kPa |

> [!CAUTION]
> **Cover state mapping is inverted from initial assumption.** The vendor spec defines `0x00 = cover open` and `0x01 = cover closed`. Previous internal docs had this backwards. The `ProtocolParser` and `SafetyStateMachine` must use the vendor mapping.

> [!IMPORTANT]
> **Unit conversion is mandatory.** The `SafetyStateMachine` uses kPa thresholds internally. The BLE adapter (or `ProtocolParser`) must convert vendor raw units to kPa/°C before passing values to `ControlCore`.

---

## 5. Subscription Order

Subscribe to characteristics in this priority order after service discovery:

1. **Button events** (`0x1524`) — critical input stream
2. **Battery notifications** (`0x2A19`) — housing health
3. **Air extraction cover** (`0x1628`) — safety prerequisite
4. **Barometric pressure** (`0x1627`) — seal monitoring
5. **Water pressure** (`0x1625`) — depth display
6. **Water temperature** (`0x1626`) — environmental display

---

## 6. BLE Connection State Machine

```
Idle
  → Scanning
  → Connecting
  → DiscoveringServices
  → Subscribing
  → Ready
  → Degraded (optional characteristic missing)
  → Reconnecting
  → Failed
```

### Timeout Defaults

| Operation | Timeout |
|---|---|
| Scan for known housing | 10 s |
| Scan for any housing | 20 s |
| Connect attempt | 10 s |
| Service discovery | 8 s |
| Notification subscription | 5 s per required group |
| Characteristic write | 3 s |
| Characteristic read | 3 s |

### Reconnection Backoff

| Attempt | Delay |
|---|---|
| 1 | Immediate |
| 2 | 500 ms |
| 3 | 1 s |
| 4 | 2 s |
| 5+ | 5 s (capped) |

### Vendor Connection Parameters

| Parameter | Value | Notes |
|---|---|---|
| Min Connection Interval | 25 ms | Configurable |
| Max Connection Interval | 125 ms | Configurable |
| Slave Latency | 0 | Configurable |
| Supervision Timeout | 4 s | Configurable |

> **iOS constraints:** Max × (Latency+1) ≤ 2s, Min ≥ 20ms, Min+20ms ≤ Max, Latency ≥ 4, Timeout ≤ 6s, Max × (Latency+1) × 3 < Timeout

---

## 7. Housing Identity Trust Model

The app must **not** trust a device solely because it advertises `DIVE IT`.

### Required Verification Steps:
1. Match expected service UUIDs
2. Read device information characteristic (`0x180A`)
3. Read firmware revision
4. Compare against supported firmware matrix
5. Persist user-approved housing identity
6. Warn if a different device appears with the same name
7. Require manual confirmation if multiple housings are nearby

---

## 8. Write Commands (App → Housing)

### Flash Trigger (`0x1525`)
- **Format:** 1 byte write
- **Values:** Any non-zero value triggers flash once (vendor example: `0x01`)
- No confirmation expected (fire-and-forget)
- **Note:** Belongs to Key Service (`0x1523`), not Sensor Service

### Vacuum Motor (`0x1624`)
- **Format:** 1 byte write
- **Values:** Any non-zero value (e.g. `0x01`) = start motor; `0x00` = stop motor
- **High-risk command** — requires valid app state, trusted housing, safety preconditions, timeout, logged result

### Solenoid Valve (`0x1629`)
- **Format:** 1 byte write
- **Values:** Any non-zero value (e.g. `0x01`) = open valve; `0x00` = close/stop valve
- **High-risk command** — same safeguards as vacuum motor

### IR Flashlight (`0x162A`)
- **Format:** 1 byte write
- **Values:**
  - `0x01` = increase flashlight brightness (≡ short press left button on remote)
  - `0x02` = switch flashlight light type (≡ short press middle button on remote)
  - `0x03` = decrease flashlight brightness (≡ short press right button on remote)
  - `0x04` = flashlight enters sleep mode (≡ long press middle button)
  - `0x05` = flashlight wakes up (≡ long press middle button again)
  - `0x06` = flashlight enters focus/flashing mode (≡ simultaneous left+right buttons)

---

## 9. High-Risk Command Policy

The following commands must **never** execute directly from raw button events:

- Start vacuum motor
- Open solenoid valve
- OTA firmware update (disabled in MVP)
- Any command that changes safety state

### Required Preconditions:
- Valid app state
- Trusted housing identity
- Safety preconditions met
- Timeout enforced
- Result logged
- Explicit stop/failure behavior defined

---

## 10. OTA Firmware (MVP: Disabled)

The protocol includes an OTA data path, but OTA is **disabled by default in MVP**.

OTA may only be enabled after:
- Firmware package signature verification
- Manufacturer public key pinning
- Hardware revision compatibility check
- Firmware compatibility check
- Housing battery threshold check
- Phone battery threshold check
- Transfer checksum
- Full-image validation
- Recovery/rollback strategy
- Interruption handling
- No unsigned firmware path
- No debug OTA path in production

---

## 11. Code Mapping

> This section maps protocol elements to their implementation in the codebase.

| Protocol Element | Code Location | Status |
|---|---|---|
| Button value → event decoding | `core/ProtocolParser.kt` | ✅ Implemented |
| Battery level decoding | `core/ProtocolParser.kt` | ✅ Implemented |
| Sensor data decoding (4-byte LE + unit conversion) | `core/ProtocolParser.kt` | ✅ Implemented |
| Cover state decoding (inverted: 0x00=open) | `core/ProtocolParser.kt` | ✅ Implemented |
| Write command encoding (flash, motor, solenoid, IR) | `core/HousingCommandEncoder.kt` | ✅ Implemented |
| Device info decoding (text payloads) | `core/ProtocolParser.kt` | ✅ Implemented |
| BLE characteristic profile (full UUIDs) | `core/HousingBleProfile.kt` | ✅ Implemented |
| BLE transport interface (platform-agnostic) | `core/BleTransport.kt` | ✅ Implemented |
| BLE connection orchestrator (full lifecycle) | `core/BleTransportOrchestrator.kt` | ✅ Implemented |
| Simulated BLE transport (testing) | `core/SimulatedBleTransport.kt` | ✅ Implemented |
| Protocol adapter (notification → command → write) | `core/HousingProtocolAdapter.kt` | ✅ Implemented |
| Housing identity verification | `core/HousingIdentityVerifier.kt` | ✅ Implemented |
| Service container UUIDs (4 services) | `core/HousingIdentityVerifier.kt` (`HousingService` enum) | ✅ Implemented |
| Power-off handling (OK long press → Idle) | `core/BleConnectionMachine.kt` (`HousingPoweredOff` signal) | ✅ Implemented |
| Hex encoding utilities | `core/HexEncoding.kt` | ✅ Implemented |
| Duplicate filtering | `core/ButtonEventNormalizer.kt` | ✅ Implemented |
| BLE connection states | `core/BleConnectionMachine.kt` | ✅ Implemented |
| Command contracts | `core/Contracts.kt` | ✅ Implemented |
| Input routing by mode | `core/InputRouter.kt` | ✅ Implemented |
| Safety state machine (7-step vendor flow) | `core/SafetyStateMachine.kt` | ✅ Implemented |

> [!WARNING]
> **Vendor base UUIDs:** Custom services use vendor base UUID `23D1BCEA-5F78-2315-DEEF-1212xxxx0000`. Replace `xxxx` with the short UUID. SIG standard services use `0000xxxx-0000-1000-8000-00805F9B34FB`. Both are defined in `HousingBleProfile.kt`.

---

## 12. Housing Hardware Specifications

> Source: Vendor spec §2

| Component | Specification |
|---|---|
| Battery | Lithium polymer, 300 mAh, 3.7V |
| Charging | TYPE-C 5V/1A, ~2h charge time |
| Charge cycles | 500 times |
| Runtime | >24h (estimated) |
| Standby (shutdown) | >60 days (estimated) |
| Electric pumping times | ~100 times |
| Water depth detection | 0–1400 kPa, resolution 50 kPa |
| Air pressure detection | 30–120 kPa, resolution 5 kPa |
| Button durability | >10,000 presses |

### Vacuum Procedure (Vendor-Specified Steps)

1. Confirm opening the suction cover for motor exhaust
2. Open the solenoid valve to prevent exhausted gas re-entering
3. Turn on the motor and start pumping air
4. When target pressure reached, stop pumping and turn off motor
5. Confirm suction cover is closed (detection is less accurate if open)
6. Close the solenoid valve (saves power, allows easy cover opening later)
7. Monitor for 5+ minutes — check for air/water leakage based on pressure

> [!NOTE]
> The vendor spec notes: "Can use the internal air pressure value of the phone. Hardware can cancel the pressure sensor." This means the barometric baseline can come from the phone's barometer if the housing sensor is unavailable.

---

*Last updated: 2026-05-26 — fully cross-referenced with vendor spec A4.0*
