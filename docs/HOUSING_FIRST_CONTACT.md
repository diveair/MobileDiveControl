# First Contact — connecting a real DIVE IT housing

Procedure for the first time the app meets real hardware. The goal of this session is not
to shoot photos; it is to settle the two things the protocol document cannot tell us: the
real UUIDs, and whether the button bytes match the spec.

Do this dry, on land, phone in hand.

---

## 1. Install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. Grant permissions

On first launch the app asks for Camera, media access, and — new — **Nearby devices**.
Grant all of them. On Android 11 and below it will ask for Location instead of Nearby
devices; that is a platform requirement for BLE scanning, not something the app uses.

If Nearby devices is denied, the app shows `SEARCHING FOR HOUSING` forever and logs the
blocking reason. Re-grant in Settings → Apps → DiveControl → Permissions.

## 3. Start capturing logs BEFORE powering the housing on

```bash
adb logcat -c
adb logcat -s HousingBle:* HousingLink:* DiveControl:* > first-contact.log
```

Leave that running for the whole session.

## 4. Power on the housing

Press the housing's **Shutter** button to power it on. The LED should flash blue while it
advertises, then go solid blue once connected.

Expected on-screen sequence, with no interaction at all:

| Banner | Meaning |
|---|---|
| `SEARCHING FOR HOUSING` | scanning |
| `CONNECTING…` | connect, discover, subscribe |
| *(banner disappears)* | connected — housing buttons are live |

The whole sequence should take a few seconds. There is deliberately no pairing screen and
no device picker: the app connects to the strongest nearby `DIVE IT`, remembers it, and
reconnects to it automatically from then on.

## 5. What to send back

### 5a. The discovery dump — the important one

In `first-contact.log`, find the block logged at INFO under tag `HousingBle` beginning with
the service tree. It lists every service and characteristic UUID the housing exposes, the
properties bitmask with decoded flags, and what each one resolved to. It ends with a line
like `RESOLVED 11/16` and a `MISSING …` list.

**Send that whole block back verbatim.** It answers:

- Which UUID base the housing really uses. The protocol doc's base
  (`23D1BCEA-5F78-2315-DEEF-1212xxxx-00000000`) is malformed — 36 hex digits where a UUID
  has 32 — so the hardcoded UUIDs in `core/HousingBleProfile.kt` are a guess and are
  probably wrong. The strong prediction is the Nordic nRF5 LED Button Service base,
  i.e. `0000XXXX-1212-EFDE-1523-785FEABCD123`, because the vendor's 0x1523/0x1524/0x1525
  triple is a verbatim match for that SDK example. The app does not depend on either
  answer — it resolves characteristics by the embedded 16-bit short code — but confirming
  it lets us delete the guess.
- Which optional characteristics this firmware actually has.
- Whether notify characteristics expose a CCCD descriptor.

### 5b. Button check

With the housing connected, press each button once and note what the app does. The decoded
byte appears in the Diagnostics screen (`Last raw button`) and in the log.

| Press | Expected byte | Expected app behaviour |
|---|---|---|
| Right | `0x10` | settings cursor moves right |
| Shutter | `0x20` | takes a photo |
| Up | `0x30` | selected setting value increases |
| Left | `0x40` | settings cursor moves left |
| Menu/OK (short) | `0x50` | opens / confirms |
| Down | `0x61` | selected setting value decreases |
| Down (long) | `0x60` | back / cancel |
| Slider + | `0x70` | zoom badge increases |
| Slider − | `0x80` | zoom badge decreases |

Report any button whose byte differs from the table, and any button that produces nothing.
A byte arriving as `Unknown` is logged rather than dropped, so it will be visible.

Known-good expectations for this build: the slider currently only moves an on-screen zoom
badge — it is not yet wired to the camera or assignable to other settings. That is the next
work item, not a bug in the link.

### 5c. Disconnect behaviour

Three things to try:

1. **Walk out of range** (or wrap the housing in foil). Expect the red
   `HOUSING DISCONNECTED — RECONNECTING` banner, then automatic reconnection when you
   return. No taps required, ever.
2. **Long-press Menu/OK to power the housing off.** Expect a distinct
   `HOUSING POWERED OFF` state rather than a reconnect spin — the app detects a
   peer-initiated close (GATT status 19) and reports it honestly.
3. **Turn Bluetooth off and on** on the phone. Expect the link to recover on its own.

### 5d. Anything that hangs

If the app sits on `CONNECTING…` indefinitely, that is the most useful failure you can
report. Send the log — the GATT queue times out every operation after 5 seconds and logs
which one stalled, so the stuck step will be named.

---

## Known limitations in this build

- Vacuum motor and solenoid commands are deliberately disabled
  (`HousingFeatureFlags.HIGH_RISK_COMMANDS_ENABLED = false`). Safety mode is still not
  reachable from the housing buttons; that is a separate work item.
- Manufacturer and firmware mismatches warn but do not block. If your housing reports
  something other than `UMEING` / `A4.0`, it will still connect and the warning will be in
  the log. Send that too — it tells us what to add to the supported matrix.
- The only thing that will refuse a device is the absence of the button characteristic
  (`0x1524`). That is the functional definition of "this is our housing".
- The debug simulation panel (bug icon, right edge) no longer fakes a connection on
  startup. It has an explicit SIM toggle for desk testing, which disables itself and reads
  `REAL HOUSING CONNECTED` whenever a real link is up.
