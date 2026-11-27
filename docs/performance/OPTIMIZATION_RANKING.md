# Nauticase optimization ranking

## November 26 housing thermal review

The Galaxy S25 dive included an 81.9-second Pro Video clip at 3840 × 1772 and 59.5 encoded fps. Its skin sensor reached 45.1°C at 12:57:47, followed one second later by DiveControl disconnecting the camera. The handset reported 45°C as its severe skin threshold. Earlier Galaxy S24 measurements below cover standby CPU. Neither dataset measures heat dissipation for every mode inside a housing. The following loads are attributed from the camera paths and those device measurements.

| Selectable mode | Main sources of sustained or burst work | Changes affecting this mode |
|---|---|---|
| Pro Video | Continuous sensor and ISP, focus/exposure preview, UHD encoder during recording | Reduce display-only GPU passes while warm. Skip diagnostic metadata formatting by default. Housing telemetry updates the selected depth-dependent colour and metadata without repeatedly resolving the camera graph. |
| Slow Motion | High sensor rate and prepared encoder surfaces. Samsung’s camera provider dominated the earlier S24 standby run | Use an advertised lower idle rate on compatible regular UHD sessions, and the existing separate preview and encoder targets during recording. Constrained high-speed sessions retain their required target combinations. |
| Hyperlapse | An open sensor session between output frames. Night trails accumulate source exposures on the GPU | Limit the warm night viewfinder to 15 fps. Accumulation and output timing follow the selected interval. |
| Video | Sensor, stabilization, encoder, and display composition. UHD and high frame rates increase sustained work | Reduce eligible preview work early. Keep encoded frames on their own path and finalize active clips through the normal recording stop path when cooling is required. |
| Portrait Video | Foreground segmentation plus background rendering on display and video surfaces | Reuse mask buffers after GPU upload, skip inference for zero-strength effects, and skip background shaders on fully foreground pixels. Reduce idle mask updates to 5 Hz when warm. Display throttling applies only to dedicated preview surfaces. |
| Photo | Live sensor, optional gesture inference, focus/exposure assists and still processing | Shared preview and telemetry reductions. Shutter timer and camera restart respect the cooling state. |
| Portrait | Samsung bokeh processing or app-based foreground tracking, mask conversion and preview composition | The app-based tracker reuses pixel storage and precomputed sample offsets and limits warm mask updates to 5 Hz. The Samsung extension controls its internal cadence. |
| Food | Live sensor, preview and still colour/effect processing | Apply shared preview and telemetry reductions. The final still uses its selected effects. |
| Night | Long exposures or vendor image processing during capture, plus a continuously running viewfinder | Reduce eligible display work and stop camera use if the handset approaches severe thermal pressure. |
| Expert RAW | RAW buffers, multi-frame capture or merge, and optional Sky Guide sensors | Shared reductions, cancellation of further sequence captures during cooling, and release of Sky Guide sensors while suspended. |
| Pro | Manual camera controls, per-frame focus and exposure feedback, and optional multi-frame processing | Remove diagnostic-only result reads from normal operation and avoid full camera configuration on ordinary pressure changes. |
| Panorama | Continuous high-resolution YUV, thumbnail conversion, motion tracking and stitching during a sweep | Limit warm idle thumbnails to four per second. An active sweep retains its processing cadence and receives a finish request on thermal suspension. |

Burst, Single Take, Dual Record and Night Video are catalogue-only entries. Their common live camera paths receive the same thermal policy. Super Slow Motion, Director’s View, Macro, Bixby Vision and AR Zone are unavailable legacy profiles. The older S24 readings for them exercised fallback preview. No named proprietary capture workload was demonstrated for those five.

The camera reduces optional work at Android’s light thermal level or a ten-second forecast of at least 0.85. It pauses at severe or a forecast of at least 1.0. Recovery requires 30 seconds below moderate with the forecast below 0.95 when available. Android 10 uses reported severity, and Android 8–9 use battery-temperature fallback thresholds of 39°C for reduced work and 42°C for suspension. Battery and skin readings measure different things. All thresholds govern app work. Water temperature, charging, the housing and other processes also influence the handset’s peak temperature. [Android thermal headroom reference](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int))

The in-place S25 update preserved the saved dive checkpoint checksum. The 301 Android and 640 core unit tests passed. An on-device fixture submitted 256 successive portrait masks while the consumer lagged and used two direct buffers. With 600 synthetic 60 fps input timestamps, the warm display scheduler selected 150 frames. The same handset passed the synthetic thermal-forecast pause and timed-recovery checks. These are allocation, scheduling and policy checks. Sustained in-housing peak temperature and battery draw require separate measurements under matched conditions.

Galaxy S24 SM-S921W · 23 November 2026 · Installed debug build · Disconnected housing

These measurements precede the first implementation pass. The [compass and preview follow-up](COMPASS_AND_PREVIEW_CHANGES.md) lists the changes delivered in ranked order and their verification.

**Portrait Video was the most CPU-intensive selectable mode in both runs.** Panorama and Slow Motion followed. Portrait Video used the most CPU inside Nauticase. Slow Motion was particularly expensive in Samsung’s camera service. The strongest opportunity shared by camera and non-camera screens is still the compass subscription at the root of the UI. In the previous controlled run, pausing that input reduced Diagnostics from 24.3% to 1.2% of one core and Gallery from 28.5–32.0% to 4.3%. Narrowing the subscription preserves the option to keep live heading at full precision where needed. [Controlled compass and pointing tests](../S24_PERFORMANCE_AUDIT.md)

| Deliverable | Coverage |
|---|---|
| [Function ranking](function-ranking.csv) | 5,913 explicitly ordered source entries, including 2,029 named declarations and 3,415 callbacks/lambdas. Each row has a priority, reason, trigger, source location and investigation. The workload columns contain attributable CPU measurements. |
| [Measured functions within each mode](functions-by-mode.csv) | Separate rankings for self and inclusive CPU inside each of the 21 mode entries and eight app destinations. The global candidate number links each measured row back to the full source inventory. |
| [Camera mode ranking](camera-mode-ranking.csv) | 21 mode IDs, with a product optimization order, a separate raw CPU order, repeat readings, frame delivery, thermal status and service breakdown. |
| [Camera control ranking](camera-setting-ranking.csv) | 194 mode/setting pairs, with both overall and within-mode ordering, S24-resolved status and values, cost mechanism and validation approach. Each setting was enumerated independently. Values of every slider were not individually benchmarked. |
| [Measurement evidence and audit code](mode-profile-evidence.zip) | Raw mode and screen measurements, sampled method traces, source census, device controls, benchmark harness and analysis scripts. |

## Selectable camera modes, highest to lowest standby workload

| Priority | Mode | Nauticase CPU | Samsung provider CPU | Four-process CPU | Two-run range | Preview fps |
|---|---|---|---|---|---|---|
| 1 | Portrait Video | 152.2% | 115.3% | 291.1% | 284.2%–298.1% | 30.0 |
| 2 | Panorama | 103.3% | 124.1% | 253.4% | 247.8%–259.1% | 29.9 |
| 3 | Slow Motion | 52.2% | 159.6% | 244.4% | 233.6%–255.2% | 60.0 |
| 4 | Night | 74.6% | 129.6% | 229.8% | 228.2%–231.4% | 30.0 |
| 5 | Food | 71.4% | 125.6% | 222.1% | 213.9%–230.3% | 30.0 |
| 6 | Expert RAW | 74.9% | 121.2% | 221.8% | 221.6%–222.0% | 29.9 |
| 7 | Pro | 81.9% | 114.0% | 221.8% | 220.4%–223.3% | 30.1 |
| 8 | Photo | 79.8% | 110.5% | 213.4% | 207.3%–219.6% | 29.9 |
| 9 | Portrait | 58.2% | 128.2% | 210.0% | 205.4%–214.6% | 29.7 |
| 10 | Video | 56.8% | 123.2% | 202.9% | 200.0%–205.8% | 30.1 |
| 11 | Hyperlapse | 44.6% | 127.1% | 195.4% | 191.2%–199.6% | 30.0 |
| 12 | Pro Video | 60.2% | 111.4% | 195.3% | 194.8%–195.8% | 30.0 |

100% is one CPU core in use. Four-process CPU sums Nauticase, Samsung’s camera provider, Android cameraserver and SurfaceFlinger. It captures substantial work outside the APK. The provider and compositor are shared system processes. GPU, ISP and display energy are outside this sum. Two observations per mode are enough to identify large differences on this handset. Treat small or overlapping differences as ties for implementation decisions.

## Every other camera mode in the code

| Priority | Mode | Nauticase CPU | Samsung provider CPU | Four-process CPU | Two-run range | Preview fps |
|---|---|---|---|---|---|---|
| 13 | AR Zone | 78.2% | 130.6% | 234.3% | 228.3%–240.3% | 30.0 |
| 14 | Bixby Vision | 73.3% | 132.9% | 231.7% | 224.5%–239.0% | 30.0 |
| 15 | Macro | 71.7% | 132.4% | 229.7% | 224.7%–234.7% | 30.0 |
| 16 | Burst | 72.9% | 131.2% | 229.2% | 229.1%–229.3% | 30.0 |
| 17 | Director's View | 70.0% | 127.9% | 222.6% | 217.9%–227.3% | 30.0 |
| 18 | Night Video | 76.6% | 117.8% | 217.8% | 216.9%–218.7% | 30.0 |
| 19 | Single Take | 69.1% | 121.7% | 214.7% | 209.9%–219.5% | 30.0 |
| 20 | Super Slow Motion | 62.7% | 124.0% | 211.3% | 207.6%–215.1% | 30.0 |
| 21 | Dual Record | 67.1% | 118.5% | 209.8% | 205.7%–213.9% | 30.1 |

Burst, Single Take, Dual Record and Night Video have settings in the catalogue but no place on the current selector. Super Slow Motion, Director’s View, Macro, Bixby Vision and AR Zone use an explicitly unavailable legacy profile. The diagnostic selected their state IDs to measure the viewfinder that actually runs. The readings for those five describe fallback preview work. They do not demonstrate the named proprietary feature. These nine are last in the product optimization order because ordinary housing input cannot select them. Their raw CPU position across all 21 is available in the mode CSV. [Mode catalogue](../../core/src/main/kotlin/com/mobiledivecontrol/core/CameraCatalog.kt#L111)

## Best intervention sites

| Order | Intervention site | Why it is high priority |
|---|---|---|
| 1 | MainActivity.onCreate · lambda in setContent | Compass StateFlow is collected above the routed screen. Gallery 28.5–32% to 4.3% app CPU and Diagnostics 24.3% to 1.2% when paused. |
| 2 | StateDrivenCameraPreview · lambda in LaunchedEffect | Heading and safety snapshots key a camera-state effect. The earlier Portrait compass A/B had a 16.4-point app CPU delta for the entire pathway. |
| 3 | PortraitSegmenter · analyze | Portrait Video samples 256×144 RGBA, runs segmentation and copies confidence values into a new native buffer. |
| 4 | CameraRuntimeController.bindCamera.analysis · lambda in setAnalyzer | The CameraX analyzer dispatches per-frame work. Panorama requests 4000×3000 YUV even before a sweep. |
| 5 | CameraRuntimeController · sampleVendorPortraitGesture | Samsung still Portrait uses a TextureView bitmap readback for pointing. Same-session pointing A/B saved 3–6 CPU points with pointing paused. |
| 6 | DiveControlScreen | Camera content can remain under a long-lived opaque intro or cap prompt. The router also encloses the heading-driven subtree. |
| 7 | CameraRuntimeController · bindCamera | Owns the preview, analysis and capture graph, including the ordinary RGBA analysis stream and preview effect. |
| 8 | CameraHudOverlay | Measured in both earlier CPU stacks, including 14.33% inclusive in Gallery. Shared with the root compass intervention. |
| 9 | CompassHeadingMonitor · onSensorChanged | Gravity and magnetic processing publish full basis changes. A/B includes sensor math and downstream UI together. |
| 10 | PointingGestureRecognizer · recognize | Eligible frames invoke the hand model even in an unattended viewfinder. GPU work is not included in method CPU. |

These are code changes to investigate. A caller that schedules work can be a better place to save power than a leaf with more CPU time. Several high-ranking rows belong to the same intervention, so their savings overlap.

Two source functions stood out during the live preview traces. In Portrait Video, `PortraitSegmenter.analyze` accounted for 61.1 ms of sampled self CPU per wall-clock second. In Panorama, `AnalysisPixelReader.component` accounted for 41.2 ms/s. Both led their respective mode in attributable self CPU. Slow Motion illustrates the limit of optimizing Kotlin alone. The Samsung provider used 159.6% CPU while Nauticase used 52.2%. The requested standby frame rate and active camera surfaces are the first places to investigate there.

1. **Contain compass updates.** Subscribe where the arrow and heading are drawn. Retain the latest full-resolution heading for capture metadata and hand pointing. Repeat the earlier Gallery, Diagnostics and Portrait on/off tests and verify navigation while rotating the handset.
2. **Reduce Portrait Video work per frame.** Inspect the RGBA downsample, confidence-to-byte conversion, native buffer lifetime and the separate preview/encoder draws. Compare blur edges on a moving person, actual preview frame timing and the encoded output before accepting a change.
3. **Reduce standby work in Panorama and Slow Motion.** Benchmark Panorama’s 4000 × 3000 analysis request and thumbnail cadence separately from the sweep. In Slow Motion, compare the active surfaces and delivered cadence before and after pressing record. Preserve the first sweep frame, first recorded frame, final dimensions and selected playback timing.
4. **Keep the housing path independent.** The benchmark ran with the housing disconnected. Proposed display and camera changes should be gated on real housing tests for pump commands, pressure freshness, both validation countdowns, PASSED/FAILED popups, reconnection and underwater transitions.

## Other destinations and capture processing

| App destination | Nauticase CPU |
|---|---|
| CameraLive | 89.1% |
| CameraAdjust | 84.2% |
| DiveSettings | 27.0% |
| Diagnostics | 25.2% |
| Gallery | 23.4% |
| PhoneTarget | 20.0% |
| Safety | 19.2% |
| PhoneCursor | 17.0% |

These destination readings used the Photo 1× camera graph for CameraLive and CameraAdjust, with one 3.8-second sample per destination and app CPU only. The two-pass camera tables include camera services. Phone control destinations showed their in-app placeholder. Gallery contained no albums.

| Production function | Generated input | Median calling-thread CPU per call |
|---|---|---|
| applyVirtualAperture | 1280x720 synthetic bitmap, F1.4, in-place | 263.0600 ms |
| combineJpegs | four 1280x720 JPEGs, average | 155.5086 ms |
| stitch | fallback, two 1280x720 synthetic frames, 0.17 rad | 123.0641 ms |
| ndlSeconds | 16 compartments at 15 m, air | 0.4772 ms |
| decodeWaterPressure | valid 3-byte packet | 0.0014 ms |

The kernel table measures the calling thread on the phone with generated fixtures and three measured batches after warmup. File compression used to prepare the fixture was outside the measurement. JPEG decoding inside the merge is included. The panorama case exercises the software fallback on synthetic frames. Its result depends on image content. The pressure parser benchmark also consumes the decoded result. Live shutter exposures, real panorama sweeps and microphone recordings were outside these preview measurements. The synthetic timings cover part of image processing. Capture-only settings have source-based priorities.

## How to read the complete function order

The census parsed `app/src/main`, `app/src/debug` and `core/src/main`. It includes named Kotlin functions, nested functions, source constructors, explicit accessors and initializer blocks, Kotlin callbacks, the two Java camera interface methods, identifiable embedded GPU function bodies and bundled DCTL functions. Compiler-emitted methods were indexed from the debug bytecode for symbol matching. Generated getters, synthetic bridges and external library internals have no independent handwritten intervention to rank. The archive includes their bytecode mapping where present in the app build. Unit tests, build scripts and the temporary profiler are outside the installed application.

The CSV orders **optimization candidates**. Tiers 1–2 contain individually reviewed, recurring intervention sites. Tier 3 adds expensive feature-specific processing and observed hot bodies. Tiers 4–6 cover other camera, I/O, graphics, sensor and repeated-work candidates. Tier 7 contains conditional UI work, setup, and callers with little established self cost. Tier 8 includes lower-evidence work and safety-sensitive logic. Tier 9 includes data construction and declarations with no implementation. Tier 10 contains debug and external/off-device paths. Within a tier, reviewed opportunities come first, followed by observed CPU and direct operations. Equal evidence uses stable file and source order. Adjacent low-evidence rows have equal practical priority.

Each implementation was examined for its directly owned calls and loops, with nested callbacks indexed separately. Conservative unique-name caller matches help identify helpers reachable from a hot callback. These matches are static clues. An unexecuted conditional branch has no measured cost in these runs.

ART sampled managed stacks at 5 ms intervals in separate 3-second camera windows and 2.4-second app-destination windows. CPU deltas came from each thread’s clock. JVM method descriptors, declaring class and source line tables were matched to unique source bodies. **258 entries resolved directly** across the 29 traces. The CSV reports milliseconds of sampled thread CPU per wall-clock second. An inclusive figure counts a body and its callees. Self time refers to that body. Blank cells mean no unique attribution. CPU in vendor services, native code or inlined lambdas cannot be assigned to every Kotlin function from these traces. The prior simpleperf columns use a different denominator, percent of sampled app userspace CPU. Synthetic per-call columns use milliseconds per operation.

## Measurement conditions

Camera CPU windows were 6.2 seconds after settling 3.7 seconds, in forward and reverse catalogue order. Each retained window had advancing frames and no new reported camera runtime failure. The final Photo window in the reverse pass lacked a bracketing service counter, so Photo was repeated. The raw data retains that incomplete reading. Measurements use the saved mode settings with 1× requested, timer off and optional metadata off. Modes lacking a lens control use the negotiated fallback. The control CSV records configuration-specific availability separately from catalogue defaults.

Nauticase CPU came from Android process CPU time. Service counters came from `/proc/<pid>/stat` sampled over ADB, using 100 ticks per second and interpolation at window boundaries. Sampling instrumentation was outside the CPU windows. The device was externally powered. It initially reported severe thermal status and a skin reading of 46.4 °C. The camera was backgrounded to cool. Accepted runs list the observed thermal status in the mode CSV, including a Portrait Video window that ended at severe status. The runner paused for cooldown before the next entry. Exact mode order will vary with scene, camera controls, device, brightness and thermal state. A reduction in watts or surface temperature requires longer matched energy measurements.

After profiling, the original camera preferences were restored and verified, the temporary test package was removed, and the phone was left on its Home screen with the camera closed.
