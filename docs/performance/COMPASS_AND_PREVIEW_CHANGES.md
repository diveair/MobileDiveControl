# Compass and camera preview optimizations

This pass targets the work Nauticase does while the phone is left on a screen. The order below follows the [S24 baseline audit](OPTIMIZATION_RANKING.md).

## Changes shared by the camera modes

The HUD registers the compass while the activity is resumed and a heading is on screen. Leaving the readout unregisters the gravity or accelerometer, magnetic-field and gyroscope listeners owned by the compass. New readings after resume go through the existing warm-up. The camera’s independent autofocus motion sensor and the housing pressure monitor have their own lifetimes.

The numeric readout and navigation arrow now collect compass readings locally. Capture metadata and the Expert RAW Sky Guide receive the heading through a separate update on the camera controller. A change in heading alone used to rerun camera configuration, including lookups for lens, frame rate, resolution and capture format. Camera settings, location readiness, and each pressure or temperature input used by the controller still trigger configuration updates. Compass filtering still consumes the sensor samples at full rate. It now builds the corrected camera basis only for readings it publishes.

## Decisions in optimization order

| Baseline rank | Mode | Lowest-risk change delivered and feature preserved |
|---:|---|---|
| 1 | Portrait Video | Copy each sampled RGBA pixel in one four-byte operation. Crop coordinates, mask model, cadence, strength, and shader are the same. |
| 2 | Panorama | Cache each plane’s stride and buffer bounds during sampling, and read YUV components as primitives. The full-resolution stream, standby thumbnail cadence, first sweep frame and stitching inputs remain available. |
| 3 | Slow Motion | Remove heading-triggered camera configuration and duplicate autofocus luminance reads wherever the shared analyzer runs. Keep the selected high-speed stream continuously ready for the shutter. |
| 4 | Night | Apply the shared heading and focus changes. The night exposure and capture sequence use the existing settings. |
| 5 | Food | Apply the shared heading and focus changes. Radial blur, color temperature and the capture renderer use the existing settings. |
| 6 | Expert RAW | Deliver heading to the Sky Guide without recalculating the capture graph. RAW output, bracket frames and merge processing follow the existing path. |
| 7 | Pro | Apply the shared changes while retaining the manual focus, exposure and white balance request paths. |
| 8 | Photo | Apply the shared changes. Shutter readiness, the selected lens, resolution and save format continue to be determined by the camera settings. |
| 9 | Portrait | Decouple heading from camera configuration. Samsung Blur and pointing retain their selected lens, frame source and cadence. |
| 10 | Video | Apply the shared changes. Encoder configuration, stabilization, audio and recording transitions follow the existing path. |
| 11 | Hyperlapse | Apply the shared heading changes. Interval selection, exposure sampling and rate conversion follow the existing path. |
| 12 | Pro Video | Apply the shared heading changes. Manual controls, dynamic range and recorder selection continue to follow the user’s settings. |
| 13–21 | AR Zone, Bixby Vision, Macro, Burst, Director’s View, Night Video, Single Take, Super Slow Motion, Dual Record | These catalogue entries share the visibility and camera-state changes whenever their preview route runs. Their place in the selector and the availability of their named features follow the existing catalogue. |

The shared autofocus optimization reuses each adjacent luminance sample in the next difference. It computes the same integer differences, in the same order, across the same center region. The sparse pixel reader is also used by underwater white balance. Regression fixtures cover padded RGBA, planar and interleaved chroma strides, and truncated terminal rows.

## S24 verification

The updated debug build ran on the SM-S921W with the housing disconnected. Each mode used the saved camera settings with 3.7 seconds to settle and a 6.2-second sample. CPU is Nauticase process CPU and 100% means one busy core. The earlier ranking requested 1× and disabled optional metadata. Lens choice, scene and thermal conditions can change the readings, so these figures describe the updated build under the user's settings. They do not isolate savings per mode.

| Selectable mode | Preview fps | Nauticase CPU |
|---|---:|---:|
| Portrait Video | 30.0 | 94.5% |
| Panorama | 30.0 | 82.5% |
| Slow Motion | 59.8 | 48.8% |
| Night | 30.0 | 67.6% |
| Food | 30.0 | 69.0% |
| Expert RAW | 30.0 | 64.8% |
| Pro | 30.2 | 73.6% |
| Photo | 30.0 | 67.3% |
| Portrait | 30.2 | 45.4% |
| Video | 30.0 | 48.5% |
| Hyperlapse | 30.0 | 44.2% |
| Pro Video | 30.0 | 48.4% |

The unit suites passed 609 core and 276 Android app tests. The first physical run found a missing Compose lifecycle owner during startup. The corrected build receives the activity's lifecycle explicitly and passed the foreground, background and navigation checks. Tests observed the compass unregister and reacquire a fresh heading. Screens with a visible numeric heading included Safety and both phone-control screens. Android's sensor service also recorded the corresponding gravity, magnetometer and gyroscope registrations and removals.

With the new automatic compass policy, Gallery used **3.7%** of one core and Diagnostics used **1.9%** over 3.8 seconds each. The earlier audit measured 23.4% and 25.2% respectively with the same sampling duration. The nine catalogue-only IDs each delivered 30.0 fps with no reported camera runtime failure. For unsupported entries this verifies their fallback preview.

An on-device comparison isolated the Portrait Video pixel-copy loop. Both implementations read the same generated padded RGBA buffer, with the same crop, and produced **147,456 identical bytes**. After warm-up, eight alternating batches of 35 copies gave median calling-thread costs of **30.98 ms** for the previous loop and **8.44 ms** for the revised loop. This 73% reduction applies to input preparation. Model inference, GPU blur and recording are additional work.

The device checks covered live preview, navigation and heading lifecycle with the housing disconnected. Live shutter captures, audio/video recording, the physical pump and a long-duration temperature comparison were outside this run.

The final build also ignores a pointing result that arrives after the heading screen closes. A second run exercised that case and repeated all navigation checks, with Gallery at 3.5% and Diagnostics at 1.7%. Portrait Video delivered 30.0 fps and Portrait delivered 30.2 fps. All 276 Android app tests also passed in the release configuration. After testing, all seven camera preference fields matched the pre-test snapshot, including the original Portrait selection. The temporary instrumentation package was removed.
