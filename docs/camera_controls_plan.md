# Camera controls consistency ? implementation handoff

Scope: refactor and repair the existing controls. No new screens or control panels. Original plan and its seven comments were read from the Antigravity artifact and transcript. Device: Galaxy S24 SM-S921W.

## Accepted decisions

- EV includes Auto and the advertised compensation range, up to ?4 EV.
- Portrait Video retains all detected lenses, including front.
- Existing Portrait effects use person segmentation and a recorded GPU effect.
- HDR selects an advertised 10-bit format; the setting is labelled HDR.
- Super Steady uses the discovered Samsung camera parameter and request/result flag.
- Night Hyperlapse uses GPU frame accumulation.
- Compile all changes, then user manual checks before automated tests.

## Implemented

- A pure pipeline selector and pipeline capabilities feed the clipped catalog. Runtime catalog resolution is cached per immutable camera state.
- Shared request sinks and look controls cover CameraX, direct manual-focus sessions, Prepared recorder, native LOG and 8K. High-speed auto-3A restrictions are explicit.
- Video torch no longer depends on a photo capture use case. EV clamps at its limits. Front selection clears incompatible LOG/8K/frame-rate settings. Expert RAW can select main-lens manual focus like Pro.
- Recorder zoom uses the selected lens and live zoom override. CameraX autofocus zoom ramps; manual focus retains its existing path. Standard EIS is disabled on 0.6x so it cannot block that lens selection.
- White-balance precedence is shared. Filters use relative AWB gains and quantized pressure depth; CameraX filter changes have a colour-only request path.
- CameraX aspect ratios use a ViewPort and matching preview area. Direct recorder pipelines expose their supported 16:9 shape. Video size fallback is reported and recording blocked until a supported size is selected.
- Auto FPS now exposes Off / 30fps / 30/60fps and drives AE ranges. H.264/HEVC is applied through a small adapter to the pinned CameraX 1.5.3 encoder factory.
- HDR selects advertised HDR10+, HDR10 or HLG. Native LOG uses runtime hook checks instead of a firmware string and retains result/output validation. Missing microphone permission uses the existing toast/error mechanisms.
- Portrait segmentation runs at most 12.5 Hz with one pending mask. Preview and recording have separate GPU outputs, preserving preview-only Focus Assist and exposure aids.
- Night Hyperlapse accumulates frames on the GPU. Night speed selections enable it. Samsung Auto-speed request/result handling is connected to the Prepared recorder.
- Unused legacy TimeLapse and HighSpeed recorder classes were removed; shared timing/codec functions remain in HyperlapseRecordingPolicy.
- Debug-only DiveControls logs show requested and returned exposure, white balance, flash, zoom, stabilization and focus keys at 1 Hz.

## Focus protection and validation

The initial delivery regressed focus despite leaving the focus algorithms unchanged. Comparison against GitHub main `a7d75153642245f6809ba7f8a45f1a2b6984c845` identified catalog validation that turned continuous manual-focus values into AF, plus preview routing that bypassed peaking. Source equality alone was insufficient validation.

Repair: preserve continuous focus values; route regular Prepared recorder previews through the existing peaking shader; retain SDR display peaking alongside HDR video; distinguish Portrait renderer topology when switching modes; and mark direct RAW preview presentation so later lens/focus changes can bind. Hyperlapse preview rotation was corrected against phone screenshots. No new UI controls were added.

Endpoint follow-up: intermediate focus movement did not prove the closest-focus endpoint. The named-lens capability cache omitted `vendorFocusCalibration`, while Auto inherited it correctly. This made Pro Photo/Auto reach actuator 4088 at 0.000 while Pro Video/1x stopped at 2657 on the same physical lens. Carry the factory calibration into every named rear-lens profile so the existing `reachableDiopters` mapping retains the actual range. Endpoint instrumentation now checks live actuator readback at 0.000, moves away, then checks the return to 0.000 against the factory actuator stop.

Endpoint device results: Pro/Auto, Pro/1x and Pro Video/1x all command and report 17.274597 diopters with actuator 4088 on physical camera 5 at 0.000. Pro/3x and Pro Video/3x both command and report 5.825 diopters with actuator 4088 on physical camera 6. All five tests returned to the same endpoint after moving to 0.800, with AF disabled. The published actuator upper bound is 4095; the observed near-stop readback is 4088 in both modes. Evidence: `tmp/focus-endpoint-*.txt` and `tmp/focus-endpoint-evidence/`.

Validation on the connected SM-S921W: manual focus commanded 8.0 and 2.0 diopters and sensor results confirmed both positions with AF disabled in Photo, Pro, Expert RAW, Pro Video SDR/HDR, Portrait Video, and Day/Night Hyperlapse. Screenshot comparisons confirmed visible green peaking with Assist On and no green overlay with Assist Off. The real Pro, Pro Video and Hyperlapse wheel paths moved the lens and entered AF, confirmed by sensor distance and AF mode 3. Evidence is saved under `tmp/focus-device-*.txt` and on the phone under `files/focus-validation/`.

Automated checks: 28 focused core tests and 35 existing app tests passed (focus values, wheel/AF boundaries, hold policy, migration, repeating surfaces and preview recovery). The first cold-launch tests changed mode before initialization finished and returned no telemetry; the harness now waits for launch telemetry before navigation. These results do not establish rapid cold-start navigation reliability or full recording acceptance.

Build: debug APK assembled with the existing signing key. Outputs are under `tmp/camera-build/app/outputs/apk/debug`; SDK and Gradle output directories are isolated under `tmp`.

## Infinity endpoint and mode-switch follow-up

Independent Camera2 probes on physical cameras 5 (1x) and 6 (3x) establish the public zero-diopter actuator positions as 700 and 1459 respectively. Both lenses report approximately 0.1 D at that position (`LENS_INFO_FOCUS_DISTANCE_CALIBRATION = APPROXIMATE`). Requests below zero change the reported distance but do not move either actuator farther. Those negative requests exist only in the diagnostic probe; production continues to map 1.000 to zero diopters. The actuator's mechanical lower bound must not be substituted for optical infinity. Evidence: `tmp/infinity-hardware-main.txt`, `tmp/infinity-hardware-tele.txt`.

The endpoint audit caught another routing mismatch in Hyperlapse/3x: logical zoom stayed on physical camera 5 in the indoor scene while focus used camera 6's calibration. Regular Prepared recorder outputs now use the selected physical camera in their output configurations. This also makes the selected physical lens part of the recorder configuration identity. The route remains pinned across manual/AF changes; native LOG and constrained high-speed topologies are unchanged.

Device acceptance: Pro/Auto, Pro/1x, Pro Video/1x and Hyperlapse/1x reach actuator 700 at 1.000; Pro/3x, Pro Video/3x and Hyperlapse/3x reach 1459. All return to actuator 4088 at 0.000 with AF disabled. Hyperlapse 0.6x presents from physical camera 2; 1x and 3x present from 5 and 6. Evidence: `tmp/infinity-final-Pro*.txt` and `tmp/acceptance-hyper-*-endpoints.txt`, `tmp/acceptance-hyper-ultrawide-route.txt`. The earlier failing Hyperlapse/3x result is retained as regression evidence.

Mode switching: shorten the post-presentation settling window from 1000 to 100 ms and settings debounce from 700 to 120 ms; explicit mode changes already bypass the latter. Same-camera replacement skips the separate availability wait after disconnecting the old GL consumer. Capture graph identity uses the actual still output instead of its redundant save-format label. The existing continuity frame now covers direct-recorder handoffs too, using PixelCopy for the current SurfaceView and clearing after a rendered replacement frame. Rapid navigation resolves the latest selected mode. No controls or other UI features were added.

A preview-use-case reuse experiment was rejected: it reduced some delays but downgraded Pro Video FHD to 720p. That implementation is absent from the delivered source. Native Samsung switching parity has not been established; switching between different stream configurations still requires camera/session work.

Final switch acceptance: 12 timed switches, four separate visual checks, and six rapid selections ending in Pro passed. Every settled preview had a fresh frame (12–49 ms old), live manual-focus telemetry, and AF off. The four transition scene samples contained no black pixels; the existing housing banner was excluded from that measurement. Timing and screenshot passes are separate because screenshot readback interrupts the test thread. The median frame gap for graph replacements improved from 997 to 830 ms; compatible Pro-to-Photo remained effectively continuous (43 ms, no bind). Individual replacement gaps were 615–1106 ms, and command-to-first-replacement-frame latency was 704–1267 ms. Hyperlapse-to-Pro Video was slower in this sample (997 to 1106 ms), so this is not a claim that every switch improved or that switching is instant. Pro Video retained FHD. Portrait Video still reports its pre-existing FHD-to-720p stream-combination rejection, which is not counted as recording acceptance. Evidence: `tmp/acceptance-switch-timing.txt`, `tmp/switch-comparison.json`, `tmp/switch-acceptance-evidence/`.

Broader test audit: 587 of 593 core tests passed. Six existing exposure/capability expectations fail in CameraCatalogTest (three), CircularScaleTest (one), ControlCoreTest (one high-speed/manual-control policy), and SlowMotionSettingsTest (one). They concern the prior refactor's Auto EV/range/clamping and high-speed policies; this endpoint/transition change does not modify those core implementations. The full suite is not green.

Focused checks: 44 tests passed (8 core focus-value/AF-hold tests, 27 preview-recovery tests, 2 repeating-surface tests, and 7 focus-ladder migration tests). App and instrumentation APK assembly passed in `tmp/infinity-device-tests-build.log`.

Final installed-build overlay regression: Pro Video, Day Hyperlapse, and Night Hyperlapse all passed manual focus movement and screenshot-based Assist Off/On checks. Green scene pixels were 0 with Assist Off, versus 62,326 / 23,923 / 13,322 with it On. Pro Video and Day Hyperlapse also passed real wheel movement and deliberate entry into AF, with live AF-mode 3 sensor results. Evidence: `tmp/acceptance-*-assist*.txt`, `tmp/acceptance-provideo-assist-wheel.txt`, and `tmp/acceptance-evidence/`.

## Manual acceptance still required

1. Hyperlapse first: 0.6x / 1x / 3x, wheel zoom, torch, EV limits, Day/Night and Night speeds; save and replay a short clip.
2. Focus: manual wheel and curves, AF pull/hold/tracking, and Focus Assist, comparing with the starting build and request logs.
3. Video and Pro Video: rear/front transitions, HDR/LOG, ISO/shutter/WB/EV where supported, filters, Auto FPS, codec, framing and saved dimensions.
4. Portrait: all lenses, FHD/UHD, each effect and strength, mask alignment, and confirmation that Focus Assist is absent from the saved clip.
5. High-speed and 8K: correct control availability, lens behavior, pause/resume, codec and playback metadata.
6. Super Steady: vendor-result acceptance and visual stabilization versus Samsung Camera.

Limitations: Portrait effects are SDR and person-oriented. Direct recorder Auto exposure currently uses HAL AE; cinematic exposure smoothing is connected to the CameraX analysis path. Samsung Super Steady parity, OTA compatibility, HDR output, night trails, segmentation alignment and sustained performance are unverified until these manual checks. Some unreachable CameraX Hyperlapse branches remain to be removed after the active recorder is accepted. No claim of complete phase acceptance or all-mode device verification is made.

## Delivered build

### November 19 controls retest update

This update supersedes the earlier EV clamping, resolution-fallback, static photo-size and failing-suite notes above. Scope is the existing camera controls and their real outputs.

- EV dials now cycle from -4.0 through +4.0, then Auto, then -4.0 in both directions. Slow Motion has the existing Pro-style focus, Focus Assist, focus curve and white-balance controls. Constrained high-speed sessions retain the hardware's auto-3A restriction; manual focus/WB and peaking are available in the regular 24/30/48/60 fps pipeline.
- Lens and FPS selections are independent. Regular Slow Motion pins the actual 0.6x/1x/3x physical sensors. Resolution/FPS menus intersect the selected sensor, stream configuration and recorder capabilities. On this SM-S921W, 1x supports the verified FHD 24/30/48/60/100/120/240 choices; front high-speed 120 fps is verified. Ultrawide and telephoto remain on their real lenses at regular rates rather than silently becoming a main-camera crop. Although the ultrawide advertises high-speed configurations, actual capture telemetry shows 60 fps at both 120 and 240 requests, so those choices are removed. 48 fps derives from 60; 100 derives from 120 with frame decimation before the 30 fps export.
- Pro Video requests its exact selected CameraX quality. Stream-combination recovery removes optional analysis before accepting a lower resolution; it no longer silently binds 720p for a 4K selection. A real Pro Video recording decoded as 3840x2160 at 30 fps. No accessible 8K stream was established on this firmware, so speculative 8K options are removed. Samsung's native application capability does not establish third-party access.
- Panorama wheel zoom reaches the active request path; its WB control is connected. Device checks confirmed physical cameras 5/2/6 for 1x/0.6x/3x, a 2x zoom request/readback, and manual WB. Lens changes still require session replacement; no claim of Samsung-instant switching is made.
- Photo preview and guides use the selected crop, with explicit texture clipping. Full uses the window aspect rather than the previously cropped preview size. The existing timer displays a countdown and delays capture. Photo size choices come from actual sensor output sizes: this device exposes approximately 12 MP main/ultrawide/front and 10 MP telephoto. It does not expose a maximum-resolution stream map or verified 24/50/100/200 MP output. Those labels are not offered or simulated by upscaling. Cropped 1:1/16:9/Full files naturally contain fewer pixels than the full sensor output.
- JPEG, RAW+JPEG, PNG and HEIF were saved and inspected on the phone. The Pro computational-photo path also honors the selected format. PNG/HEIF encoding uses the processed JPEG image as input; PNG does not recover detail lost in that source. RAW availability is clipped per sensor/pipeline. Verified files include square JPEG 3060x3060, 16:9 PNG 4080x2295, HEIF 4080x3060, and paired DNG 4064x3044 plus JPEG 4080x3060.
- ISO Sensitivity offers increments 1/2/3/5/10 or Regular, retaining exact intermediate values. Limits come from the selected sensor: 25 where advertised, 48 on this ultrawide. The short ISO/Shutter menus and taller Focus menu report their measured height to the depth/heading/temperature HUD, which now clears the actual menu instead of reserving the Focus menu's height for every control.

Validation: all 597 core and 266 app unit tests pass, including EV wrap, fine ISO, lens/FPS independence, selected-resolution FPS clipping and truthful photo options. App and instrumentation assembly succeeded in `tmp/controls-retest-build6.log`. Slow Motion focus sensor results confirmed movement with AF disabled; screenshot comparison found 0 green scene pixels with assist Off and 1,195 with assist On (`tmp/controls-slow-assist-final.txt`). Photo file signatures/dimensions, countdown and menu screenshots are retained in `tmp/controls-photo-device3/`. Panorama evidence is `tmp/controls-panorama-final.txt`; 4K evidence is `tmp/controls-provideo-4k.txt`; high-speed capture/export evidence is `tmp/controls-slow-high-final.txt` and `tmp/controls-slow-high-final/`. The rejected ultrawide high-speed recording remains diagnostic evidence, not an accepted result.

Remaining manual acceptance: retest the control feel, near/far subjects and peaking, menu spacing, each lens and supported recording combination, sustained recording and mode transitions. Universal 8K/240 fps/all-lens operation and native high-megapixel outputs are not implemented because the tested third-party pipelines cannot deliver them truthfully.

Current APK: `artifacts/camera-controls/app-debug.apk`, SHA-256 `72736e44f66c5127f918954719146846e57e5511082a763786bac82237b05666`. Installed with `adb install -r` on RFCX80XPC5P, retaining app data. Final assembly and full unit suites succeeded in `tmp/controls-retest-build6.log`; `git diff --check` is clean.

Final-build photo acceptance passed all five captures, including Full JPEG 4080x1883, with matching preview/guide crops. Evidence: `tmp/controls-photo-final.txt` and `tmp/controls-photo-final/`. Final-build main-lens 100/120/240 fps recordings passed sensor-frame-duration assertions, physical-lens checks, saved dimensions and monotonic timestamps (`tmp/controls-final-main-cadence.txt`).

Final-build 0.6x and 3x recordings passed physical-lens and 60 fps cadence assertions; front passed 120 fps. Evidence: `tmp/controls-final-ultra-cadence.txt`, `tmp/controls-final-tele-cadence.txt`, `tmp/controls-final-front-cadence-corrected.txt`. The first front invocation used an incorrectly capitalized test argument (`Front` instead of `front`); the corrected run passed without a production change. Pre-test camera preferences were restored from `tmp/controls-retest-before.xml`, verified by matching SHA-256 before launch, and MainActivity reopened for user retesting. Unsupported persisted combinations are subsequently clipped by the runtime catalog.
