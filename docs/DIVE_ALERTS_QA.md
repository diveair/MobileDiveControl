# Dive Alerts verification

## October 1 — shallow dives, reconnect and overlapping alerts

- Installed the new debug build on Samsung SM-S921W. **833 automated tests passed: 590 core + 243 app.** The single-worker build completed successfully with both app and instrumentation APKs.
- Regression coverage includes the measured October 1 segment maxima/durations and short surface intervals, independent 11 m stop qualification, confirmed surface shutdown, delayed BLE disconnect after telemetry expiry, ambient versus retained/degraded vacuum, underwater leak protection, air reminders during stops, and Shutter/OK ownership.
- Repaired today's four fragments into two records. Phone-local time is America/Panama: morning 10:11 (5:46.497, 2.338 m); afternoon 14:55 (61:03.989, 4.824 m). All **716 original depth points** and the original stop outcome are retained. The two unobserved surface gaps are marked rather than filled with invented measurements. The production codec validates the repaired file and confirms unchanged current settings and physiological checkpoint. Phone bytes were verified after atomic replacement.
- Original file is retained on the phone as `files/dive-profile-before-oct1-repair.bin` and locally under `.codex-tmp/oct1-dive-forensics/`. Alert preferences were compared before/after and are unchanged.
- Extended `AirAlertWiringInstrumentation` to exercise the actual shared stop/air stack, explicit stop OK, Record transparency and pressure-input routing. **On-phone execution is pending phone availability**: another app was in use, so no foreground takeover was performed. These automated regressions are not a physical pressure/vacuum or underwater validation.
- The first dive's yellow Pro Video tooltip could not be identified from retained evidence. The available logcat begins after that dive; no exact tooltip text was recovered.


## Production wiring and test-button removal — 2026-09-27

- Removed the notification test field/action from Dive Alerts in all builds, and stopped enabling internal simulation controls in the ViewModel. The actual **Your Alert** settings preview remains. Installed the updated debug APK on the Samsung SM-S921W.
- **818 automated tests passed:** 575 core + 243 app. Nine new `AirAlertEndToEndTest` cases exercise saved settings through actual pressure-packet parsing, `ControlCore.tickDiveProfile`, housing packet decoding and command reducers. They cover recurring/once/sequence schedules, per-alert text/actions, confirmation cadence, expiry/hold, missed-deadline coalescing, safety-screen deferral, confirmed baseline/pressure learning/readout, receiver freshness/disconnection, Off and surface cleanup/new-dive reset. These tests use synthetic inputs and do not establish physical receiver compatibility.
- Traced the live ViewModel's 500 ms scheduler, saved-settings store, state publication, shared Compose overlay, receiver callback/request handling and critical-audio handler. Production reminders do not depend on the retired debug test action.
- Built and installed `AirAlertWiringInstrumentation`, an isolated on-phone harness using a separate preferences file and in-memory core. Final on-device rendering/touch verification is pending: the phone was locked. No synthetic dive was added to the user's logs. Existing saved air settings were compared after installation and preserved (Remind only, 3300 PSIG start, six-minute interval).
- Readiness: reminder scheduling and manual-entry software paths pass the current automated checks. Underwater use, physical housing-button delivery, Swift/Tern TX live-pressure streaming and audible warning effectiveness remain outside this verification. A useful future UX improvement is direct manual entry when a display-only receiver alert loses its signal.

## Automatic receiver list — 2026-09-27

- **809 tests passed:** 566 core + 243 app tests. Debug build succeeded and was installed on the connected Samsung SM-S921W.
- Entering disconnected Live receiver automatically starts a bounded scan. The phone found 51 nearby devices in the captured scan, with individually tappable name/address rows and no registration step. No unidentified device was connected.
- Real-device checks verified connection-gated options, Scan again, Back navigation and a non-scrollable Dive Settings sidebar. The long device list scrolls independently while Alert type, Scan again and BACK remain fixed. Captured screenshots were visually reviewed; no fatal app exception appeared in the current process log.
- Nine new core tests cover direct address selection, identical device names, housing Up/Down + OK, focus stability across 40 devices, stale/missing selections, duplicate-request suppression, same-device mapping preservation, failure/retry, successful connection gating and dive/menu guards. Physical receiver connection and pressure streaming remain unverified.
- Saved alert preferences were compared before/after and preserved. The phone was left on the Live receiver device list. Screenshots: `.codex-tmp/air-receiver-list-qa/`.

## Earlier update — 2026-09-27

- **800 tests passed:** 557 core + 243 app tests. The final debug build assembled and was installed on the connected Samsung SM-S921W.
- Off shows only Alert type and Back, with no preview, timing, appearance, tank or test controls. Core coverage confirms it clears scheduled prompts and gas warnings, including when ordinary presentation is blocked.
- Remind only is the new-install default; saved modes are preserved. The live reminder and preview share one component. Screenshots confirm horizontal and vertical centering, DIVE TIME **6:00**, NEXT ALERT **12:00**, and a working **OK** dismissal button. No pressure controls appear. Completed one-time/sequence schedules report no next alert in the core tests.
- Unconnected Live receiver shows only Alert type, Register receiver and Back. Registration started a real Bluetooth scan, found nearby devices, and prompted for device selection. No unidentified device was connected. Mapping, timing, appearance and testing remained hidden before connection.
- Registration success, disconnect/failure gating, raw signal decoding, freshness, per-device isolation, independent labelled values and settings migration were verified in core tests. These tests use synthetic receiver states; successful physical pressure streaming and the connected mapping screens were not hardware-verified.
- The phone's existing alert settings, including Live receiver, 3300 PSIG start, 450 PSIG reserve and six-minute interval, were compared before/after and preserved. The test dive was ended and the phone was left on Dive Alerts.
- Screenshots: `.codex-tmp/air-minimal-qa/`. No new performance benchmark was run for this UI update; the earlier measurements below remain dated observations.

## Earlier verification — 2026-09-26

Device: connected Samsung SM-S921W, Android debug build, landscape 2340 × 1080. Tests used the isolated simulated dive; no real dive log, gas confirmation, or receiver packet was fabricated. The housing disconnected after the user left; later checks used touch controls. Housing routing is additionally covered by core tests.

### Functional checks

- Full local suite: 549 core tests + 243 app tests = **792 passed**. The final debug APK assembled successfully with these checks.
- Reminder-only preview and actual notification contain only **CHECK YOUR AIR**; tapping the banner dismisses it. Configured opacity, colour, duration and flashing remain active. The final disconnected-housing check verified that the one-line reminder sits above the connection notice without overlapping it.
- Reserve and critical warning screens rendered with actionable pressure, tank-switch and buddy-air controls.
- Early critical example: 3300 PSIG start, 1450 PSIG reported after a simulated six minutes at 20 m, configured 4 m / three-minute stop, 450 PSIG reserve. The warning appeared while the planned surface estimate was **333 PSIG**, before a projected zero.
- Opening/cancelling tank or buddy-air confirmation retained the critical warning. Explicit tank confirmation created a new baseline and an early follow-up pressure check.
- Confirmed buddy air silenced the original-tank alarm, restored the alarm-volume setting to its previous value (11), and displayed **BUDDY AIR · GAS UNKNOWN**. No further speech started during a 15-second observation. Own-tank estimates were hidden during sharing. The return-to-own-tank confirmation and subsequent consumption reading succeeded.
- Test exit stopped the debug alarm and discarded simulated gas history. Saved alert preferences were compared before/after and preserved.
- Core tests cover slower measured ascent, fast-ascent capping, reaction margin, reported zero versus projected risk, missed data, surface clearance, receiver isolation, actual hardware-button routing, finite schedules, and preserving stop duration/progress/decompression obligations. A newly confirmed empty or near-empty tank starts the critical alarm during an active dive even when the depth sensor is unavailable.

### Performance samples

Android `dumpsys gfxinfo` / `meminfo` samples; camera preview remained active. Steady samples lasted 30 seconds each. These are short debug-build bench measurements, not underwater, release-build, battery-endurance, thermal-soak or certified alarm-latency tests.

| Sample | Frames | Delayed frames | Frame p95 | Frame p99 | App PSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Camera baseline | 1064 | 3 (0.28%) | 13 ms | 14 ms | 456 MiB |
| Critical warning, repeating audio | 1092 | 19 (1.74%) | 15 ms | 16 ms | 449 MiB |
| Confirmed buddy-air screen | 1085 | 7 (0.65%) | 14 ms | 15 ms | 447 MiB |
| Full scripted navigation / alerts / keypad / confirmations | 4296 | 733 (17.06%) | 20 ms | 34 ms | 289 MiB |

No application crashes were observed. The mixed script includes startup, camera/menu transitions, screenshots and repeated accessibility-tree inspection; its higher missed-frame rate remains a measured limitation, and has not been attributed solely to the new alert UI. Steady rendering had substantially fewer missed deadlines. PSS varies with camera and runtime warm-up, so these samples do not establish long-term memory stability. CPU snapshots spanning process startup were not used as reliable per-scenario comparisons.

Raw captures and screenshots are under `.codex-tmp/air-performance/` and `.codex-tmp/air-info-qa/` in the development workspace.

### Scope of assurance

The pressure forecast and 60-second reaction margin are engineering assumptions based on measured consumption, depth, and the conservative ascent budget. They do not guarantee surfacing with breathable gas. The 10 PSIG floor is not a safe planned reserve. A buddy's gas supply is deliberately not estimated without donor measurements. Required decompression is not shortened or cleared. Offline speech playback and volume restoration were exercised; audibility through a submerged housing was not verified. Swift/Tern TX live pressure forwarding remains unverified and is not claimed as working integration. See [Dive Alerts](DIVE_ALERTS.md) for model boundaries and the supporting DAN guidance.
