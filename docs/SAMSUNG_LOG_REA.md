# Samsung LOG investigation with REA

Investigation date: 2026-10-11. The original transfer-tag and large framing mismatches are fixed.
The qualified configuration is SM-S921W / S921WVLS6DZH3, rear main camera, FHD or UHD at30fps.
Use **Standard stabilization in both apps** when comparing stabilized framing; Off intentionally
retains the wider field of view. DiveControl now honors this setting in native LOG.

## Final qualification and fresh comparison

The user moved the phone during testing. Comparisons across that move were excluded. A new
Samsung reference (`20261011_204817.mp4`) and DiveControl pair were captured at the new position:
UHD30, ISO800,1/30s,3400K, locked focus5.49450541diopters, Standard stabilization. DiveControl used
its production native Kelvin control, **not copied gains or a fitted color transform**.

`stationary-comparison.json` reports240 registration inliers, scale0.993085 (0.69% residual
scale difference), and approximately0.23pixel center displacement at960x540. The original
23.3% field-of-view mismatch is absent. Direct smoothed low-gradient errors **without geometric
registration** are Y/U/V1.682/4.113/1.763 ten-bit codes. Registered luma MAE is1.114codes.
No grading LUT, brightness normalization, fitted correction, or synthetic zoom was applied.
This close-up is a bounded scene comparison, not a calibrated full-range/color-chart qualification.

Both encodes independently decode as HEVC Main10,10-bit, BT.2020 primaries/matrix, limited range,
and transfer_characteristics2 (unspecified). Android MediaExtractor maps that native transfer
to0. The old mismatched BT.2020_10 tag mapped to3 and is now rejected at finalization, as are
HLG/PQ, missing color metadata, full-range tags and non-Main10/8-bit output.

The fixes are based on traced native calls and verified device behavior:

- Recorder author0 and recording mode27 are set before source initialization; profile2 uses
  automatic level0. This fixes encoded transfer signaling at acquisition; no file retagging.
- ProVideo shooting mode35, native VDIS parameters and public EIS ON activate matching stabilized
  framing on public camera0. Samsung's private camera56 uses vendor VDIS1 with public EIS OFF;
  copying only its public OFF value was the cause of the large framing mismatch.
- Manual Kelvin uses AWB101 plus `samsung.android.control.colorTemperature`; the HAL calculates
  gains and matrix. Auto uses AWB1 and the existing public AWB-lock behavior.
- The persistent recording surface is retained across takes. Stop waits for Samsung onInfo905
  once per configured track, keeps camera targets active until MediaRecorder.stop drains, then
  sends recordingTrigger1 before returning to preview. This fixes immediate-stop failures,
  missing stabilized tail frames, A/V end skew and failed rearming.
- Request serials prevent queued callbacks from the previous repeating request from marking
  the next take ready. Finalized duration comes from the encoded video track.

Hardware checks passed: four rapid FHD takes, four rapid UHD takes, four rapid silent UHD takes,
two sustained silent UHD takes, live1x/2x zoom while recording, and finishing a take when leaving
the preview. The final serial guard was followed by four more rapid FHD takes and two sustained
silent UHD takes. `tools/verify_native_log_clips.py` independently decoded12 retained clips:
all passed format, every indexed frame, increasing timestamps, bounded frame gaps and A/V-end
checks. Two further final-build clips (sustained silent and stabilization Off) also passed,
bringing the independently decoded total to14. All12 focused unit tests passed, covering actual Main10 headers and rejection of the former transfer
tag as well as CameraX fallback policy. See `qualified-stream-validation.json`, the `qualified-*`
and `final-*` device reports under `artifacts/rea-samsung-log/`. The final APK is installed.
Samsung Camera was left in Pro Video LOG/UHD30 with its original automatic ISO, shutter and
white balance restored; the temporary AF lock and camera-service watcher are no longer active.

Remaining qualification is limited to full-range chart/clipping/color tests, editor transforms,
other lenses/frame rates/firmware and underwater/filter looks. It is not a remaining HLG fallback
or metadata defect. The experimental label remains appropriate for this bounded device support.

## Earlier scene tests (before framing/metadata fixes)

Samsung Camera was already open in Pro Video LOG. Its initial automatic shutter was1/50s;
the user warned that the lighting was flickering. Recorded UHD30 references at manual ISO800,
1/60s and1/30s, WB2800K. Camera-service monitoring verified Samsung request5/result6, actual
exposure, color gains/matrix and focus. DiveControl used the production recorder with matching
exposure, the measured Samsung color gains/matrix, and the observed focus distance. Its measured
ISO was795. This isolates LOG transport/tonal response; it does not test whether the two apps'
independent Kelvin-to-gain algorithms match.

Comparison used decoded10-bit YUV, temporal averages after settling, SIFT/RANSAC similarity
registration, and smoothed low-gradient regions. No grading LUT, brightness normalization,
or fitted tone correction was applied to obtain these errors. Regression is diagnostic only.

| Corrected production capture | Luma mean absolute error (10-bit codes) | Luma RMSE | Chroma U/V mean absolute error | Registration inliers |
|---|---:|---:|---:|---:|
| ISO800,1/60s | 3.456 | 6.345 | 2.891 / 1.184 | 483 |
| ISO800,1/30s | 3.201 | 5.114 | 1.906 / 0.884 | 528 |

Luma regression slopes were0.9909 and1.0043. These are close scene-level results, not a
calibrated full-range transfer-function measurement. Residual flicker remains: at1/60s the
reference median per-pixel temporal standard deviation was7.112 codes after downsampling;
at1/30s it was3.835. This includes noise and motion, not illumination alone. The tested tonal
range does not establish clipping latitude, all colors, or performance under other lighting.

The test exposed and corrected a real ISP mismatch: Samsung requests FAST noise reduction,
FAST edge processing and optical stabilization ON. The initial public EIS OFF observation omitted
Samsung's separate vendor VDIS control, subsequently traced and fixed above. DiveControl
had inherited MINIMAL/OFF from the previous HLG implementation. `SamsungNativeLogPipeline`
now applies the observed native settings; the corrected APK was built, tested and installed.
Hardware results confirm FAST/FAST. The original minimal-processing comparison is retained
as `scene-comparison-iso800.json`; do not confuse it with the corrected captures above.

Differences observed at that earlier stage (resolved above):

- Samsung's image is cropped about1.233x relative to DiveControl's1x image. Registration removes
  this geometric difference only for the historical measurement.
- Samsung writes HEVC transfer2 (unspecified); DiveControl writes transfer14 (BT.2020_10).
  Both decode to Main10 BT.2020. No metadata retagging was performed. Static recorder differences
  worth separate controlled tests include author0, automatic codec level, captureRate30 and a
  persistent input surface. Subsequent author0/level0 initialization fixed the tag.
- Those early scene tests copied measured gains/matrix. The final fresh pair uses native Kelvin.

Reproduction: `tools/measure_log_scene.py` diagnoses temporal variation;
`tools/compare_log_scene.py` performs the registered comparison. Full clips, telemetry and
JSON/PNG comparisons are in `artifacts/rea-samsung-log/`, particularly
`scene-comparison-fast60.json` and `scene-comparison-30.json`. Native clips created by this test
are Samsung Camera files `20261011_193543.mp4` and `20261011_194212.mp4` and were retained.
Samsung Camera was reopened with LOG/UHD30 intact, and its original automatic ISO, shutter and
white balance restored. Camera-service monitoring was stopped.

## Native implementation and runtime evidence

The earlier assumption that third-party native access was impossible was too broad. REA/JADX
traced the following sequence, which runs under DiveControl's own UID10425 without impersonating
Samsung Camera or requesting a privileged camera ID:

1. Before session creation, load `/system/framework/scamera_sdk_util.jar` and call
   `CustomInterfaceHelper.setSamsungParameter` with recording_dr_mode=log10, shootingmode=35,
   operation_mode=none, sw-vdis matching the selected stabilization, sw-super_vdis=false,
   recording-fps=30 and stream_type=0.
2. Set integer request/session key `samsung.android.control.recordingDrMode` to5. The enum value
   and actual request setter were both traced (`native-dr-setter-source.txt`).
3. Use STANDARD output dynamic range and Samsung recorder streamUseCase65536 (surface option1
   shifted16 bits); do not select CameraX HLG.
4. Initialize author0/mode27 before recorder sources, then HEVC Main10/automatic level0 at100Mbps.

Historical runtime `native-recorder-key5.json/.mp4`:120 frames,1920x1080,30fps,physical camera5, result mode6,
HEVC Main10,10-bit BT.2020. PyAV reports transfer14 (BT.2020_10); MediaExtractor reports transfer3.
This is not an HLG recording. Result6 is an observed HAL result, not assumed equal to SDK enum5.
The same recorder with only the pre-session parameter, omitting request5, emitted PQ-tagged output
(`native-recorder-probe.mp4`), so the pre-session call alone is insufficient.

Production routes Pro Video LOG through `PreparedCamera2Recorder` and `SamsungNativeLogPipeline`.
CameraX HLG can no longer satisfy the LOG recording contract. The route is deliberately restricted
to the tested model/firmware and rear FHD/UHD30 configurations. Recorder failures do not fall back
to HLG/SDR. Legacy HLG exposure compensation and input transforms do not apply to native files.

The code preserves manual ISO/shutter, focus, EV, zoom and white-balance requests. Output checks
are transport checks, not proof of curve fidelity. No proprietary code was copied into the app;
the implementation invokes the device's installed firmware API. Remaining work: matched chart,
highlight/clipping/noise comparisons, editor input-transform validation, and broader firmware/lens
qualification. Native output is therefore labeled experimental.

### Production recorder checks

The production `PreparedCamera2Recorder` was exercised on the phone using the debug-only surface
host and `SamsungLogAccessInstrumentation -e preparedProbe true` under UID10425. Two consecutive
FHD30 clips passed readiness, finalization, Main10 SPS validation and session recreation. The UHD30
repeat (`-e preparedUhd true`) also passed twice with manual exposure: requested ISO100 and
8,333,333ns; observed ISO99 and8,333,333ns. Reports: `native-production-prepared.json` and
`native-production-uhd.json`. An independent PyAV decode of the second UHD clip confirms3840x2160,
HEVC Main10, `yuv420p10le`, BT.2020, transfer14, approximately30fps, and one audio track.

Focused tests: seven CameraX fallback/ISP policy tests, four real-header SPS tests, and seven
Samsung transfer-function tests passed. Debug APK builds successfully. The debug probe activity
is not included in release builds. These checks do not substitute for matched-scene color testing.

## Earlier investigation notes

The sections below retain the initial evidence and hypotheses; the runtime findings above supersede
earlier statements that native access was unknown or necessarily privileged.

## Installed tools

- REA `rea-agents@6.4.0`, installed locally in `.codex-tmp/rea-tool`.
- JADX headless MCP `0.7.1`, official release SHA-256
  `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`.
- Existing Microsoft JDK `17.0.20.1`; no replacement Java installation.
- Codex registration in the user's `.codex/config.toml`, with REA and Android-engine
  setup backups. The REA skill is installed in `.agents/skills/reverse-engineer-anything`.
  Restart Codex to load the new MCP registration; the CLI works now.
- Use `tools/rea.ps1` from this checkout, for example:

```powershell
.\tools\rea.ps1 --version
.\tools\rea.ps1 inspect-android-package artifacts/rea-samsung-log/SamsungCamera-current.apk --json
```

Keep `.codex-tmp/rea-tool`: the local launcher and Codex registration refer to it.
Downloaded tools and proprietary APKs remain in ignored directories, outside the Android build.

Upstream: [REA](https://github.com/morluto/rea),
[Android provider](https://github.com/morluto/rea/blob/main/docs/android-analysis.md),
[JADX release](https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1).

## Exact target

USB-authorized Galaxy S24 **SM-S921W**, Android 16, One UI property `80500` (8.5),
firmware build `S921WVLS6DZH3`. Samsung Camera reports `16.5.02.36`, version code
`1650236000`, min/target SDK 36.

The current `/system/priv-app/SamsungCamera/SamsungCamera.apk` was pulled and its
SHA-256 verified both on the phone and locally:
`555311163016fa96d61ff0596c86d9ab7d5a2559e725103ee7286662d88257a7`.
The earlier saved APK has SHA-256
`baa19918dc5964b11722576425796f964c82a5bdf4749b9dc8b082282ee48124`.
ZIP-member comparison found only four changed entries: `META-INF/CERT.RSA`,
`META-INF/CERT.SF`, `META-INF/MANIFEST.MF`, and `SEC-INF/buildinfo.xml`.
All executable DEX entries match byte-for-byte. This comparison does not verify signatures.

Full local evidence: `artifacts/rea-samsung-log/`; `apk-comparison.json` records the
member hashes. REA's current package evidence is
`ev_29e40bc1e22c738f21015fbc527210429df8efead6769e5f3319a3f6fe287b6a`.
It reports 17,281 classes and 6,212 resources, and retains the decoded manifest.

## Observed static behavior

These are decompiled APK observations, not proof of successful calls from DiveControl.

| Entry point | Observed behavior | Evidence file |
| --- | --- | --- |
| `RecordingManagerImpl.isLogVideoAvailable` | Requires shooting-mode/facing LOG support and `LOG_VIDEO == 1`; excludes APV HLG/LOG branches. | `current-is-log-available.json` |
| `CapabilityImpl.isLogVideoSupported` | Requires the current camera ID to occur in Samsung's `LOG_VIDEO_AVAILABLE_PHYSICAL_IDS` capability list. | `current-log-capability.json` |
| `MediaRecorderProfile.getVideoCodecProfile` | Non-APV LOG selects profile integer `2`; HLG also selects `2`, so codec profile alone cannot distinguish them. | `getVideoCodecProfile.json` |
| `MediaRecorderProfile.getVideoOption` | LOG selects `HIGH_BITRATE` irrespective of the separate high-bitrate UI preference. | `getVideoOption.json` |
| `MediaRecorderProfile.updateProfileForProVideo` | Non-APV LOG sets recording mode `27`; HDR10+ uses `25`. APV LOG uses a separate branch and does not establish S24 APV support. | `updateProfileForProVideo.json` |
| `RecordingSession.prepareMediaRecorder` | Applies Samsung `semSetRecordingMode`, profile/level, bitrate and input surface before preparing the recorder. | `prepare-media-recorder.json` |
| `MakerConnectionInfo.setRecordingDrMode` | Looks up a mode in `mRecordingDrModeMap` and applies the mapped value to `DeviceConfiguration.Parameters`, establishing a separate session-configuration path. | `capture-set-dr-mode.json` |
| `MakerConnectionInfo` constructor, overload 0 | Maps `RecordingDrMode.LOG` to `MODE_LOG10`, separately from `HLG` to `MODE_HLG`. Also maps preview-LUT output spaces to default `0`, BT.709 `1`, BT.2020 HLG `2`, and configures Samsung client-mode enums. None of these enum names proves permission to use them from DiveControl. | `capture-connection-map.json` |
| `VideoMakerBase.createMakerRequestBuilder` | Creates preview with template `1` and picture/record with template `3`. This method contains no LOG-specific setting. | `capture-request-builder.json` |
| Manifest | Declares `android.permission.SYSTEM_CAMERA`. This declaration does not by itself identify the LOG authorization check. | `current-package.json` |

REA returned complete method text for these methods. The old-APK method observations
above are applicable to the current APK's identical DEX bytes.

Broad `search-android-classes` failed REA's duplicate/count inventory validation on
this APK. Its partial producer output is preserved in `log-classes.json` and was used
only to identify candidate class names. Direct class/method inspection succeeded;
the failed search must not be described as complete coverage.
The existing full DEX inventory also names a distinct `MODE_LOG10` alongside `MODE_HLG`
in `DeviceConfiguration.Parameters.RecordingDrMode`. The initial nested enum lookup failed;
dotted nested selectors and full-class JADX extraction subsequently resolved it. See the
native trace ledger for the exact setter and framework helper evidence.

## Legacy implementation and transfer helper

`SamsungLogProfile.kt` contains the Samsung piecewise transfer function and inverse.
The previous `VideoDynamicRangePolicy.kt` requested public 10-bit HLG at100Mbps, with a
measured SM-S921 1x -1.5EV automatic exposure offset. This has been replaced by the native
route above. Packaged DCTLs remain useful for legacy HLG files only. See
[COLOR_WORKFLOW.md](COLOR_WORKFLOW.md).

This pipeline does not establish native Samsung ISP equivalence. A matching transfer
curve and HEVC profile do not establish matching highlight recovery, noise processing,
sensor gain, gamut mapping, white balance, lens processing, or metadata.

One verified mathematical correction was made during this investigation:
`decode(y)` now returns `-0.05` for `y < 0`, matching the inverse's below-black branch
shown on cached white-paper page 5. Previously it extrapolated the logarithmic toe.
The focused `SamsungLogProfileTest` suite passed: 7 tests, zero failures/errors.
That mathematical correction is independent of the new native recorder.

## Source integrity and remaining work

The old `.codex-tmp/samsung-log-atomic/Samsung_Log_White_Paper_En.pdf` and
`Samsung_Log_to_Linear_1DLUT_v1.0.cube` are actually Samsung sign-in HTML, not valid
PDF/LUT downloads. Do not use them as numerical reference data. Separately cached
rendered pages exist under `tmp/pdfs/samsung-log-whitepaper/`; page 5 visibly contains
the coefficients and the below-black inverse branch, but no source PDF digest is
available for those cached images in this pass.
Page 6 separately labels the reference values as **full-range** 10-bit codes:
black 128, 18% gray 540, 90% white 725, ceiling 1023. Do not confuse those values
with the `64 + 876 * signal` legal-range helper or a decoded clip's range metadata.

The [official Samsung LOG page](https://developer.samsung.com/mobile/samsung-log-video.html)
provides the white paper and LUT links. Obtain genuine bytes and validate their file
format before future LUT comparisons. Existing calibration clips and their measurement
JSONs are historical evidence, not new on-device validation.

Remaining qualification requirements:

1. Compare controlled exposure ramps, neutral/color patches, clipping and noise against
   Samsung Camera. Transport and encoded sample depth are verified; curve equivalence is not.
2. Qualify additional lenses, frame rates and firmware before broadening the device gate.
3. Validate editor input transforms and a display LUT for the native output.

The final debug APK was installed with existing app data preserved. The final production
recorder check (`native-final-device-check.json`) passed two more FHD clips after enabling
strict BT.2020 validation. No root or firmware modification was used.
