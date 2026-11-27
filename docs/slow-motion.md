# Slow-motion capture and validation

## Galaxy S24 UHD 120 in Slow Motion (November 26, 2026)

On the SM-S921W running firmware `S921WVLS6DZH3`, select **Slow Motion → 0.6× or 1× → UHD 4K → 120fps**. Select **Playback FPS → 30fps** for nominal four-times slow motion. **Options → Test UHD capture rates** provides an optional moving-subject check on this firmware. Other eligible firmware builds enable the rate after that check passes for the selected lens.

On the SM-S921W, Nauticase opens public logical camera 0 with Samsung shooting mode 13 in a regular Camera2 session. Optical zoom selects physical sensor 2 at 0.6× and sensor 5 at 1×. Both lenses use a 1280 × 720 viewfinder at 30 FPS in standby. During recording, every request targets the 3840 × 2160 HEVC recorder and one in four also feeds the display. Under moderate thermal pressure the idle preview requests an advertised fixed 24 FPS. During an ongoing take, one request in five feeds the display while the recorder continues at its requested 120 FPS. The lower viewfinder rate stays in effect through the existing cooldown. The optional qualifier still measures every capture result.

The following production-recorder measurements were collected on firmware `S921WVLS6DZH3` with audio enabled. File FPS uses the first and last video presentation timestamps. These two takes passed the lens, cadence and audio checks. Scene movement was insufficient for the decoded-frame test.

| Lens | Sensor FPS | UHD HEVC frames | File FPS | Video duration | AAC duration |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0.6× | 118.51 | 525 | 118.51 | 4.430 s | 4.428 s |
| 1× | 117.79 | 521 | 117.86 | 4.421 s | 4.420 s |

In a seven-second comparison on the same handset, the earlier 1× build averaged 117.17 FPS with 10 long intervals. Two takes with the updated viewfinder each averaged 118.18 FPS with three long intervals. The updated 0.6× build averaged 118.37 FPS with one. These are video-track measurements from first to last presentation timestamp. A long interval exceeds 1.5 times the nominal 8.33 ms frame period.

On-device tests switched the viewfinder from one display update per four capture requests to one per five during a single recording on each lens. Reduced standby measured 24.03 FPS at 1× and 24.00 FPS at 0.6×. Both returned to 30 FPS after recovery. The UHD HEVC files averaged 118.33 FPS at 1× and 118.75 FPS at 0.6×, each with an AAC track. These tests exercised the app's reduced-load path. Temperature rise and sustained runtime in a housing remain unmeasured.

## UHD capture rates and thermal handling

UHD means an encoded 3840 × 2160 frame at 16:9. Video, Pro Video and Slow Motion discover rates for the selected lens. On the tested SM-S921W, Video and Pro Video offer UHD 60 on the 0.6×, 1×, 3× and front cameras. All three rear lenses delivered nominal 60 FPS in saved files with audio. The front camera accepts the setting but its delivered rate varied.

| S24 lens | Video at UHD 60 | Pro Video at UHD 60 |
| --- | ---: | ---: |
| 0.6× ultrawide | 59.96 FPS | 59.96 FPS |
| 1× main | 59.57 FPS | 60.00 FPS |
| 3× telephoto | 59.65 FPS | 59.65 FPS |
| Front | 50.98 FPS | 51.28 FPS |

The ultrawide's regular video configuration table advertises UHD 60 while its default stream duration suggests 30. The original filter discarded the advertised rate. At 3×, a logical-camera request produced 30 FPS and could select the main sensor. Nauticase now pins the preview and recorder to the telephoto, sets the fixed rate at session creation and records UHD alongside a 1920 × 1080 viewfinder. The capture results identified physical camera 6 at 7.0 mm.

The front camera reported 16.67 ms frame duration even when it skipped intervals. In an independent 5.2-second test with only a UHD encoder attached, successive camera timestamps averaged 53.77 FPS and encoded samples averaged 53.76 FPS. This reproduced the shortfall upstream of the app's preview and housing controls.

With 60 FPS capture and 30 FPS playback selected separately on the 3× lens, Pro Video saved 269 UHD frames at 29.83 playback FPS. The video lasted 9.131 s and the retimed audio lasted 9.152 s.

1. **Discover candidate sessions.** Preserve existing UHD 60 discovery and recording. For additional rates, query the regular session's fixed AE ranges, recorder output sizes and per-size minimum duration. Separately query fixed high-speed ranges for the exact UHD size. Check a surface-input hardware encoder for the same size, rate and MIME type. A private physical camera can participate in a regular pinned session. An independently openable sensor can use its own public camera ID.
2. **Verify through the actual recorder.** On a qualifying lens, open **Options → Test UHD capture rates**, select a rate and aim at a moving subject in good light. Nauticase records about 4.5 seconds using the production preview topology, sensor route and encoder. It checks sensor timestamps, encoded frame count and sample timing, wall-clock capture duration, exact encoded size and codec, and AAC duration when microphone permission is present. Small clock deviations are accepted. The temporary clip is deleted. A successful test enables that rate on return to the camera. A change of firmware invalidates the result. Tests are explicit and stay out of app startup.
3. **Record at the qualified rate.** Use the same regular or constrained session that passed. Preserve the source frame clock and apply the chosen playback timing to video and audio after recording. A regular camera advertising 100 FPS and a constrained camera advertising 120 FPS are separate capabilities.
4. **Handle thermal pressure.** On Android 10 and later, moderate thermal pressure reduces viewfinder work on supported UHD 60–120 FPS paths. The encoder keeps its selected cadence. Severe pressure suspends UHD 100/120 FPS. UHD 60 FPS remains available until Android reports critical pressure. At the applicable limit, an active take goes through the normal save path. Capture becomes available as soon as the status falls below its limit. Viewfinder load stays reduced for 30 seconds after moderate pressure clears. The optional rate-qualification test still requires a cool handset.

For release qualification, exercise each supported route on a physical handset. Check moving-subject frame detail, independent capture and playback timing with sound, housing shutter controls, pause/resume/save, camera ownership on mode changes, and heat recovery in the actual enclosure. Android thermal status and energy use during idle preview and sustained recording are separate measurements. Testing on a handset that exposes only UHD 60 cannot establish positive hardware results for UHD 100 or 120.

## Current recording behavior (November 24, 2026)

Video, Pro Video and Slow Motion discover capture rates for the selected lens and resolution. Selecting a new capture rate sets Playback FPS to Match capture. An explicit playback override changes the timing of every recorded frame. When audio recording is on, the audio changes speed with the picture. Matching speeds use the original recording directly.

On the Galaxy S24 SM-S921W rear 1× camera, test clips confirmed 8K at 24/30, UHD at 24/30/60, and FHD at nominal 120 and 240 FPS. At FHD 120, the sensor reported 8.436 ms per frame. A 120→30 clip had an 8.546 s video track and an 8.597 s audio track. At FHD 240, the sensor reported 4.167 ms per frame. For UHD 120 the public high-speed size query rejected 3840×2160. An independent HEVC recording targeting a fixed 120 through a regular Camera2 session measured 16.663 ms between sensor frames and 59.71 FPS in the file. This phone therefore offers UHD up to 60 FPS in Nauticase. A phone that advertises a constrained UHD 120 session with a compatible encoder can offer UHD 120.

The base Galaxy S24 limits the selectable capture menu separately for Video/Pro Video and Slow Motion. Video/Pro Video allow HD 30, FHD 24/30/60/120, UHD 24/30/60 and 8K 24/30. Slow Motion allows FHD 120/240 and UHD 120 wherever the selected lens exposes that pair. If a saved lens supports neither Slow Motion rate, Nauticase selects the 1× lens when available. Playback FPS remains independently adjustable.

A further UHD test pinned the recording output to the main physical sensor through logical camera 0, used the video recording stream use case, disabled auto exposure, and requested a 4 ms exposure and 8.333 ms frame duration. The camera delivered the 4 ms exposure but reported a 16.667 ms frame duration. The UHD HEVC file measured 59.63 FPS. The native Samsung UHD clip described below measured approximately 118.54 source FPS.

The same manual UHD 120 request on the independently openable 0.6× camera (ID 2) produced 160 HEVC frames at 59.60 FPS, with a median sensor timestamp interval of 16.673 ms. CameraX 1.7.0 also rejected strict UHD at fixed 120 on cameras 0 and 2 with slow motion explicitly enabled, using both capability sources and both preview configurations. In-app UHD 120 on these two lenses remains unverified on this firmware.

## S24 UHD 120 session rejection (November 25, 2026)

The [November 26 follow-up](../artifacts/S24-UHD120-follow-up.txt) covers two prototype UHD files measuring approximately 117 FPS, the fresh true-size camera test, and independent HEVC/AAC timing. On supported devices the standard constrained recorder is tried before a single full-size texture feeding the encoder and viewfinder. The app checks sensor and encoded cadence, lens evidence, the audio/video presentation windows and decoded frame differences before accepting a new UHD high-rate qualification. Public camera IDs 0 and 2 on this S24 firmware still omit UHD from their high-speed sizes.

The SM-S921W ran Android 16 and Samsung Camera 16.5.02.36 (1650236000). The installed SamsungCamera.apk and the APK used for disassembly had the same SHA-256, `555311163016fa96d61ff0596c86d9ab7d5a2559e725103ee7286662d88257a7`. The DEX files were checked against the entries in that APK.

- With UHD and 120 both selected in Samsung Camera's Slow Motion UI, Android's camera service reported `com.sec.android.app.camera` as the active client of camera ID **56**. This was a live preview observation. The separate native clip measurement is described below.
- In the installed bytecode, `SlowMotionPresenter.onConnectMakerPrepared` passes the selected FPS as both ends of the range. `SlowMotionVideoMakerBase.connectCamDevice` prepares the recording surface and dispatches to `CamDevice.i`. The implementation selects `CamDevice.SessionMode.HIGH_SPEED` and calls `CameraDevice.createCaptureSession(SessionConfiguration)`.
- An ordinary app on the same phone enumerated IDs **0, 1, 2, 3**. Across their standard and available maximum-resolution stream maps, the largest advertised high-speed size was 1920×1080. Each advertised regular AE ranges up to 60 FPS. Nauticase previously received `Unknown camera ID 56` when querying characteristics through CameraManager. Samsung's vendor metadata for camera 56 does contain the UHD 120 pair.
- An isolated Android app configured a real HEVC recording surface at 3840×2160, requested a fixed 120 FPS and called the public high-speed session constructor on rear camera 0. Session creation threw `IllegalArgumentException` in `SurfaceUtils.checkConstrainedHighSpeedSurfaces` (One UI line 288), called from `CameraDeviceImpl.createCaptureSessionInternal` (line 1018). Its accepted size list contained 1280×720, 1920×1080 and 1920×824. The failure happened before capture requests could run. The earlier regular-session tests measured about 60 actual frames per second.
- These observations establish a difference in device access and stream validation. Samsung's internal access decision remains unverified. The blocker for native capture on this firmware is access to an app-visible camera session that accepts the UHD surface and delivers nominal 120 FPS.
- On public ultrawide camera 2, an ordinary app could read a Samsung high-speed metadata row for UHD at fixed 120. The Qualcomm hardware HEVC encoder advertised a UHD 120 performance point and configured successfully. Three public constrained sessions on camera 2 rejected the UHD recording surface before the first capture, with the encoder alone, with an FHD preview, and with a UHD preview and fixed 120 FPS session parameters. Android reported thermal status 0 throughout. The [device evidence ZIP](../artifacts/Nauticase-S24-UHD120-Lens-Evidence.zip) contains the B0 inventory and B1–B3 exceptions.

The [S24 capture matrix](../artifacts/Nauticase-S24-capture-matrix.csv) lists the menu, active lens and measured timing for 60 Video, Pro Video and Slow Motion combinations. The [measurement excerpts](../artifacts/Nauticase-S24-capture-matrix-evidence.txt) cover configuration failures and recording-state transitions as well as saved clips. At FHD 120, one main-camera test yielded 596 distinct callback timestamps across 149 frame-number anchors. The frame number advanced by 296 over that interval, giving 118.54 FPS, in agreement with the saved file. Counting every derived callback timestamp substantially overestimated the rate on this handset. Android documents how high-speed capture requests are batched. [Android high-speed session reference](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession)

The Android high-speed contract requires a size and range from the selected camera's published stream map. [Android StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getHighSpeedVideoSizes())

### Native lens selection and request timing

On November 25, the selected controls in Samsung Camera's Slow Motion size picker and Android's active camera client identified these routes on the SM-S921W:

| Native selection confirmed in the picker | Camera service ID | Corresponding app-facing rear route |
| --- | ---: | --- |
| UHD, 120 FPS, 0.6× | 58 | Public camera 2 or logical camera 0 with physical output 2 |
| UHD, 120 FPS, 1× | 56 | Public camera 0, main physical sensor 5 |

The observation was made in preview. The same native IDs were active at FHD 120. The size-picker selection is therefore necessary to identify UHD. The separate stock HEVC clip measured 118.54 FPS after startup. It establishes the stock file cadence for one recording.

We also tested the public logical-camera route to the ultrawide. The preview and UHD HEVC encoder were both pinned to physical camera 2 through camera 0. A fixed 120 FPS request with a 4 ms exposure produced 277 physical capture results reporting a 2.2 mm focal length. Sensor timestamps averaged **59.54 FPS** and 267 encoded UHD samples averaged **59.53 FPS**. The median sensor interval was 16.673 ms.

The installed camera app's `SlowMotionPresenter.onConnectMakerPrepared` supplies a fixed FPS range. Its slow-motion maker prepares the recording surface. The `CamDeviceImpl` high-speed branch resolves to Android session type 1 (`SESSION_HIGH_SPEED`) and supplies the output configurations to `CameraDevice.createCaptureSession`. At record time, `SlowMotionVideoMakerBase.startRecordRepeating` passes the upper FPS bound to `calculateSlowMotionRecordRepeatingCnt`. That method schedules the recording and preview surfaces at separate rates using the capture-to-preview FPS ratio. This explains how the viewfinder can update more slowly than the encoder. The traces do not measure the instantaneous preview FPS or identify the internal Samsung driver rule that enables UHD.

The camera service dump contains separate Android and Samsung high-speed tables. For both 56 and 58, Samsung's table includes 3840 × 2160 at a fixed 120 while the Android high-speed table tops out at FHD. Camera 2 also has the Samsung UHD tuple. The ordinary app inventory exposes IDs 0–3, and logical camera 0 identifies 2, 5 and 6 as physical children. The fixed 120 high-speed session on public camera 0 failed during Android's surface-size validation, before repeating requests or encoding began. Matching Samsung's preview schedule cannot change that earlier result. The public rear cameras also report session-configuration query version 34. Android documents the newer `CameraDeviceSetup` query for cameras reporting a value greater than 34. [Android CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager#isCameraDeviceSetupSupported(java.lang.String))

### CameraX high-speed and Feature Group tests on the SM-S921W

On November 25, 2026, an isolated on-device test used CameraX 1.5.3 (the previous Nauticase dependency) and stable 1.7.0. On both versions, `Recorder.getHighSpeedVideoCapabilities` returned null on IDs 0, 1, 2 and 3, and Android reported no high-speed 2160p camcorder profile on any of them. The test requested only `Quality.UHD` with a fixed [120,120] range. Both versions rejected high-speed binding on the default rear camera with preview and with the recorder alone. CameraX 1.7.0 also rejected both configurations when the recorder used its codec capability source. Each attempt failed before recording with “No supported quality on the device for high-speed capture.”

On 1.7.0, `CameraInfo.isSessionConfigSupported` accepted a required `UHD_RECORDING` feature group with preview and video. The session's advertised fixed rates were 15, 24, 26, 27 and 30 FPS. It rejected both the required UHD and `FPS_60` combination and the required UHD group with an explicit [120,120] frame-rate range. A regular session with a strict UHD quality selector and the same explicit range also returned false. The published 1.7.0 API supplies a groupable `FPS_60` feature and has no corresponding `FPS_120` feature. The 30 FPS ceiling here applies to this CameraX preview/record configuration. The in-app Camera2 recorder independently demonstrated UHD 60 on this phone.

An actionable firmware change would be to expose 3840×2160 with a fixed [120,120] high-speed range on an app-visible rear camera and provide the corresponding high-speed encoder profile. An OEM-supported in-app capture API that delivers the same frames would also meet the requirement. [CameraX Feature Group API](https://developer.android.com/reference/androidx/camera/core/featuregroup/GroupableFeature) · [CameraX high-speed capture and device support](https://developer.android.com/blog/posts/high-speed-capture-and-slow-motion-video-with-camera-x-1-5)

### Nauticase CameraX 1.7.0 upgrade verification

Nauticase binds its preview, recording and analysis use cases with the stable SessionConfig API. Its cached per-lens probe requests a required recording quality together with preview through Feature Group, with the existing quality-selector fallback for providers that cannot answer the group query. Direct recording continues to use its separate camera and encoder capability checks. SDR recording now selects AVC or HEVC through CameraX's video MIME API.

On the SM-S921W, ordinary Video recorded 1920×1080 at 29.58 FPS and Pro Video recorded 3840×2160 at 59.58 FPS. Slow Motion captured FHD at a measured 8.436 ms sensor interval and saved 262 frames at 29.64 FPS with an explicit 30 FPS playback setting. The resulting video and AAC tracks lasted 8.840 and 8.909 seconds. A late lens capability refresh now preserves the playback override, and the audio mux uses track indices to detect end of stream when AAC starts with encoder priming.

During validation, the Android camera service identified the outgoing and incoming clients as the same Nauticase test process and rejected their combined resource cost of 117 against the device maximum of 100. The app now waits for release of the outgoing CameraX device before opening a direct recorder. The Expert RAW → Hyperlapse → Slow Motion transition and Portrait → Video transition passed on the phone. The app and core suites passed 917 tests.

### Samsung Camera capture-for-result comparison

On the same S24, an isolated app requested a video from Samsung Camera with Android's capture-for-result intent and a private output URI. It requested Samsung's Slow motion mode, and Samsung's UI showed UHD and 120 selected. The phone used camera 56. After a four-second test and confirmation in Samsung's review screen, the caller received `RESULT_OK` and a 39,115,858-byte MP4 directly in its cache. The video track contained 458 HEVC frames at 3840×2160 over 3.909 seconds. After the startup interval, the sample timing measured 118.54 FPS. The AAC track lasted 3.904 seconds. The MediaStore check found no new Gallery entry, and uninstalling the test app deleted the private clip. This measurement used Samsung Camera as the foreground recorder. Nauticase requires recording in its own UI with the housing controls available.

### Samsung-supported in-app access and APV

Samsung lists the Galaxy Camera SDK among its retired developer resources. Samsung’s CamCyclopedia describes UniHAL+ integrations for supported S24 social apps, including video stabilization and low-light processing. It also describes feature-specific SDK libraries. The article gives no UHD 120 contract or eligibility criteria for other apps. I found no published application procedure or SDK contract granting independent apps S24 UHD 120 capture. The availability and terms of any private partner interface require confirmation from Samsung. [Retired Galaxy SDKs](https://developer.samsung.com/retired-content) · [Samsung CamCyclopedia on UniHAL+ and in-app camera integrations](https://community.samsung.com/my/conversations/camera-camcyclopedia/social-app-camera/r1_27414927)

Samsung documents APV recording through UHD 120 on the Galaxy S26 Ultra. The SM-S921W on firmware `BP4A.251205.006.S921WVLS6DZH3` registered 113 Android media codec entries. The only APV encoder was `c2.android.apv.encoder` (software), and its `areSizeAndRateSupported(3840, 2160, 120)` returned false. The camera stream and encoder each need to support the requested rate. The native S24 clip above used HEVC. [Samsung APV model and recording specifications](https://developer.samsung.com/mobile/apv.html)

#### Technical inquiry ready for Samsung Developer 1:1 Support

**Subject — Galaxy S24 SM-S921W public or partner API for in-app 3840×2160 at 120 FPS**

Nauticase is an Android camera app for a Bluetooth underwater housing. Users operate the camera through physical housing buttons and need our live dive overlay and recording controls in the foreground. We need a rear 1× 3840×2160 sensor stream at nominal 120 FPS that can feed an Android encoder surface in our own activity. HEVC is sufficient.

On an SM-S921W running Android 16, firmware `BP4A.251205.006.S921WVLS6DZH3`, Samsung Camera 16.5.02.36 delivered about 118.54 FPS in a 3840×2160 HEVC clip. Public Camera2 enumerates cameras 0 through 3. The rear camera's constrained high-speed session rejects 3840×2160, and a regular recording requested at 120 delivers about 60 FPS. CameraX 1.7.0 reports no high-speed UHD profile and rejects the session both with and without preview.

Does Samsung provide a supported in-app SDK, session configuration or Galaxy Camera partner integration (including UniHAL+ where applicable) for this combination on the base Canadian S24? If so, please share the API or onboarding contact, eligible models and firmware, signing and distribution requirements, and recording restrictions. If this is a camera-provider capability reporting issue, could Galaxy Camera engineering confirm the affected builds and whether a firmware release exposes the UHD 120 size/rate pair and matching encoder profile to third-party apps?

The 1:1 form requires a Samsung account. Relevant attachments are the camera capability summary, high-speed rejection and measured clip metadata above. [Samsung Developer 1:1 Support](https://developer.samsung.com/dashboard/support)

## September 3, 2026 recording contract and historical measurements

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
