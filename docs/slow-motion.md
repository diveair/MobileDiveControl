# Slow-motion capture and validation

Validated on Samsung Galaxy S24 SM-S921W, Android 16, on 2026-09-03.

## Recording contract

| Selection | Camera stream | Saved playback | Processing |
| --- | --- | --- | --- |
| 48 fps | 60 fps | 30 fps; 1.6× slower | Select real frames at 48/60, encode once, then set an even playback clock |
| 60 fps | Fixed 60 fps | 30 fps; 2× slower | Preserve compressed frames; update MP4 timing metadata |
| 120 fps | Advertised fixed high-speed range | 30 fps; 4× slower nominally | Preserve compressed frames; update MP4 timing metadata |
| 240 fps | Advertised fixed high-speed range | 30 fps; 8× slower | Preserve compressed frames; update MP4 timing metadata |

48 fps is an effective cadence derived from 60 fps, not a native, evenly sampled 48 fps sensor mode. No generated or interpolated frames are used. The S24's nominal 120 fps sensor period measured 8,436,240 ns (about 118.54 fps); 240 fps measured 4,166,666 ns. Startup and resume settling time is not encoded as duplicate frames.

Slow Motion prefers a supported HEVC encoder. At 120/240, MediaRecorder receives both the actual capture rate and the 30 fps playback rate, matching the native Samsung contract. FHD HEVC targets approximately 10.2 Mbps on that slowed encoder clock, based on the Samsung APK's slow-motion bitrate table; AVC fallback receives a larger budget. Normal 48/60 selections retain a real-time 60 fps source with a correspondingly scaled budget. Encoder limits still apply. The 48 fps export also has an explicit quality budget and disables B-frames. It is remuxed to an even 30 fps clock after fractional frame selection.

## Settings audit

- Resolution and rate: intersect recorder and Camera2 capabilities. Refresh when the capture camera changes. Wait for a supported selection before opening a constrained session. This S24's public constrained map offers FHD 120/240 on the rear main/ultrawide and FHD 120 on the front camera. Samsung's private UHD 120 path is not claimed or exposed as verified here.
- Lens and wheel zoom: route the selected optical ratio, including 0.6×, to the direct recorder. An explicit wheel zoom remains effective until another lens is selected. Samsung may choose a different physical sensor at a requested zoom depending on light and focus distance.
- Exposure: show the public capture-camera compensation range and apply its actual compensation step. Request automatic anti-banding explicitly. No automatic reduction of the chosen capture rate is used to brighten dark scenes.
- Focus: 48/60 supports continuous or single autofocus where the lens supports it. Constrained 120/240 uses continuous autofocus. Fixed-focus lenses have a fixed-focus option. Old unsupported selections resolve to a supported setting.
- Torch: apply the real torch request; cameras without a flash expose Off only.
- HDR: remains unavailable in the public SDR high-speed path. A persisted On value does not enable an unsupported recording configuration.
- Guides: remain preview overlays and are not burned into saved video.
- Stabilization: request optical stabilization where advertised. Electronic stabilization remains off, avoiding its additional crop and resampling.
- Preview, pause/resume, Save and Delete: retain the existing review flow. Save exports the 48 fps selection; the other rates preserve compressed samples.

The new recorder behavior is gated by `Config.slowMotion`. Ordinary Video, Pro Video, Hyperlapse and photo/panorama paths retain their recorder settings. Slow-motion capability updates resnap only slow-motion settings, with a regression test protecting other modes' persisted choices.

Final validation: 556 unit tests passed. The installed build passed the complete 48/60/120/240 capture, review, resume and save matrix with an empty crash log. Saved 60/120/240 files were byte-identical to their review files; saved 48 fps output measured 10.33 Mbps HEVC and exact 30 fps timestamps. Comparison against the pre-change settings snapshot found no changes to another mode's saved settings. The compatibility guards for camera metadata on Android 8–10 were compiled but were not exercised on an older physical device.

## Device evidence

The instrumentation runner drives the real activity, view model, reducer, recorder and review flow. Capture/review passed at 48, 60, 120 and 240 fps. Separate runs exercised Save plus resume at all four rates, 0.6× capture, 3× with Single AF / +1 EV / torch, front-camera 60/120, and wheel zoom to 2×.

Camera result metadata confirmed auto anti-banding, the chosen zoom, AF mode, exposure compensation, torch, and optical stabilization when present. At 0.6×/60 fps the reported active physical camera was the ultrawide. The 48 fps save/resume sample retained 198 of 248 captured frames, decoded as HEVC Main, measured 11.05 Mbps, and had uniform 33.333 ms presentation intervals. Decoded samples contained I/P frames, without B-frames or exact duplicate frames in the sampled regions.

In static scene samples, settled central-crop luminance variation (standard deviation / mean) measured 1.14% in the original 120 fps build, 0.066% after correction, and 0.049% in the native 120 fps reference. Corrected 240 fps measured 0.120%; a separately verified native FHD 240 clip measured 0.148%. These are scene-specific stability measurements, not an optical sharpness benchmark or proof of parity under all lighting.

An early experiment with fixed-rate preview-only high-speed requests and a different encoder frame-rate declaration caused a Samsung camera-provider failure. It was removed. The later Samsung preview correction described below uses both configured targets with the prepared recorder suspended. The variable preview-only range remains the brief fallback while an old recorder drains. Normal 48/60 fps sessions keep their fixed 60 fps preview.

## Reproduce

### Native live reference session

Four user-recorded native clips were retained on September 3: two FHD240, one FHD120, and one UHD120. Encoder logs and file metadata agree on the selected rates. FHD uses a configured 10.2 Mbps HEVC budget; UHD uses 22 Mbps and produces 3840×2160 output. The UHD file contains 1,124 encoded frames at a measured 21.71 Mbps. Excluding the startup presentation interval, its slowed timestamps imply about 118.54 source fps, agreeing with our measured nominal-120 sensor cadence.

Samsung logs a 60 fps preview target at all three settings. This is not a measured display rate, and our actual preview cadence still needs direct comparison. Native writer-thread termination took 81–131 ms after the stop call; its recording state returned to idle in 209–320 ms. These are log-event intervals, not full gallery/display latency.

The user did not provide a stationary opening in the UHD clip; all references include camera motion. They support configuration, timing and moving-scene inspection, but do not establish matched flicker, sharpness or image-processing quality. UHD120 remains outside the verified public high-speed path. Detailed evidence and limitations are retained under `artifacts/native-live-20260903-140310/` and `artifacts/native-live-20260903-143114/`.

### Refinements after the native comparison

Slow-motion 60/120/240 now updates the timing tables in the finalized MP4 in place. `SlowMotionMp4Clock` validates a single-video, nonfragmented file, sample counts, clock precision, and edit lists before writing. It changes the `moov` metadata without reading or rewriting the compressed picture payload. Unsupported layouts retain the existing remux fallback. Codec headers, color information, keyframes, sample locations, and compressed frames remain intact. A real 14.37 MB capture was retimed in approximately 22 ms on the host, with an identical SHA-256 for its `mdat` payload and unchanged file length.

After a slow-motion stop, the finalized clip is handed to review before the next recorder is prepared on the worker thread. Immediate resume waits for that recorder, with generation checks preventing an obsolete preparation from attaching after a mode change. The ordinary Video/Pro Video/Hyperlapse recorder sequence is unchanged.

On the phone, the measured stop-to-review interval fell from 456 to 201 ms at FHD120 and from 854 to 411 ms at FHD240 in the comparison runs. These are single-run UI-state measurements, not a latency guarantee. Native recording-state-idle timings are a different endpoint and should not be treated as directly equivalent. The FHD48/60/120/240 matrix passed recording, immediate resume, playback, save and delete. Saved 60/120/240 clips were byte-identical to their review files and retained BT.709 primaries/matrix/transfer with limited range, matching the native references. Other modes' persisted settings matched the pre-change snapshot.

The current camera view had a very close foreground obstruction. No new optical-sharpness or flicker improvement is claimed from these performance tests; a matched, unobstructed scene remains necessary for that comparison. Samsung's private tuning keys were absent from the app's advertised capture-request keys. The implementation does not invent or force those private controls.

### Follow-up from the user's app pans

The user then repeated FHD120 and FHD240 pans in our app. Both retained files decoded completely, with 1,645 and 2,792 pictures respectively, no identical adjacent sampled frames, BT.709 limited-range color, and uniform 30 fps playback timestamps. Stop-to-review-file readiness measured approximately 148 and 369 ms. Uniform playback timestamps alone do not establish uniform capture: the 240 fps writer duration exposed fewer pictures than expected. Evidence is retained under `artifacts/our-live-20260903-152857/`.

A follow-up 8-second 240 fps capture, without camera-service snapshots during recording, exposed eight original timing gaps after frame 947. Five gaps spanned about nine frame periods and three about five periods (approximately 52 missing source-frame slots). The 240 fps sensor was still reporting a 4,166,666 ns period. The MP4 timing observer now retains a summary of original sample intervals before normalizing playback, so these losses cannot be concealed by evenly spaced output timestamps.

The native logs explicitly set `time-lapse-enable=1` and `time-lapse-fps=120/240` while keeping video frame rate 30. Our direct high-speed recorder omitted that capture-rate declaration. [Android's Stagefright recorder](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libmediaplayerservice/StagefrightRecorder.cpp) uses it to set the encoder's operating rate and temporal-layer configuration, as well as timestamp conversion. Slow Motion 120/240 now uses this contract, with the bitrate budget expressed on the 30 fps encoder clock. Extending it to the normal 60 fps session produced reordered samples and an approximately halved output bitrate on this phone, so 48/60 retain their validated real-time source contract.

In the corrected 8-second 240 fps run, 1,862 frames were retained, with no original timing interval above 1.5 frame periods. Review readiness fell from 510 to 202 ms in this before/after pair. Both files decoded completely and retained the intended color metadata. This resolves the reproduced mid-capture frame-loss pattern in this test; it does not prove every lighting, motion or thermal condition. The before/after records are `artifacts/slow-source-clock-baseline/` and `artifacts/slow-native-capture-rate/`.

Public stream-duration metadata also confirms UHD requires at least 33,333,333 ns per frame on this route, while FHD allows 16,666,666 ns. UHD60 is therefore not added from the vendor configuration table alone; UHD120 remains outside the verified public high-speed path.

The final installed build has 558 passing unit tests. Device validation covered 48/60/120/240 recording, immediate resume, and Save/Delete. The final 60 fps recheck produced 9.98 Mbps HEVC, no original timing gaps, byte-identical review/save files, and 204 ms review readiness. The 120/240 matrix measured 210/202 ms and no timing gaps, including resumed segments. Final saved files retained BT.709 limited-range color and even 30 fps presentation intervals; other modes' persisted settings matched the baseline. Use `slow-native-contract-60-final` for the final 60 fps evidence; the 60 fps row in `slow-native-contract-final` records the excluded experiment.

### Sluggish live preview after the final user pan

The user subsequently reported sluggish preview after Stop. The bounded monitor had already ended; the phone's retained app logs and published file were recovered directly. That pan contained 2,800 frames and no original capture-timing gaps. Finalization took 104 ms; the review file was ready approximately 147 ms after Stop. The affected surface was the live camera preview, not the saved slow-motion timeline. SurfaceFlinger presentation timestamps measured 7.47 fps while the sensor reported roughly 30 fps, a 33.33 ms exposure, and the variable `[30,240]` request range. These measurements are retained under `artifacts/slow-preview-sluggish-20260903-180402/`.

The Samsung-only slow-motion workaround keeps both configured high-speed surfaces targeted while MediaRecorder is prepared and suspended. It uses the lowest advertised fixed high-speed range (120 on this S24) for idle preview, then the selected fixed range for recording. The prepared recorder discards incoming buffers until Start; an observed pre-record dump confirmed zero encoded frames and zero encoded duration. During finalization the old recorder target is removed, and the stable preview request is restored once its replacement is prepared. This avoids recreating the camera session and retains the existing review/resume flow.

Actual live-preview presentation cadence measured 29.6–29.8 fps before capture and after Save at both FHD120 and FHD240, including immediate resume. Review readiness measured 207/202 ms. No mid-capture timing gaps occurred; one 240 fps run had a startup-only interval, which the existing playback-clock normalization removed. Evidence is in `artifacts/slow-preview-prepared-targets/` and `artifacts/slow-preview-final/`. This is a verified stable 30 fps public preview, not Samsung's separately logged private 60 fps preview target. Holding the sensor at the fixed preview range also changes its idle exposure/power behavior; sustained thermal and battery equivalence to the private native pipeline is not claimed.

### Instrumented app validation

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest '-PdeviceTestRunner=com.mobiledivecontrol.ui.camera.SlowMotionCaptureInstrumentation'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e rates 48,60,120,240 -e durationMs 4000 -e save true -e resume true com.mobiledivecontrol.test/com.mobiledivecontrol.ui.camera.SlowMotionCaptureInstrumentation
```

Reports and retained samples are under the app's external files directory, `slow-motion-validation/<timestamp>/`. Save tests copy the generated output and then delete only that test's MediaStore row. Optional arguments include `lens`, `exposure`, `torch`, `focus` (use `Single_AF`), and `zoom`. The default instrumentation runner remains the panorama runner unless the Gradle property is supplied.

## Limits

Automatic anti-banding cannot guarantee flicker-free 240 fps under every LED/PWM light. Exposure is limited by the sensor frame period, and lighting pulses can remain visible even in Samsung's app. Samsung's private denoising, autofocus and UHD processing are not exposed by the public constrained-session contract. These changes improve the public capture path; exact native-camera image-quality parity has not been established. Moving-subject, underwater and long-duration thermal validation remain separate from these desk-scene checks.

References: [Android constrained high-speed sessions](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession), [Samsung camera flicker guidance](https://www.samsung.com/us/support/troubleshooting/TSG01222442/).
