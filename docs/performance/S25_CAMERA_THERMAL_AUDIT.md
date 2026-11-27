# Galaxy S25 camera thermal audit

The Galaxy S25 ran the twelve modes below in order on the original and optimized debug builds. The app uses the sensor, image processor and viewfinder even before recording. Pro Video adds the encoder at UHD 60. The earlier dive reached a skin-sensor reading of 45.1 °C immediately before the camera disconnected. In this bench test, Android entered moderate thermal stress during preview alone. [Earlier dive measurement](OPTIMIZATION_RANKING.md)

The comparison used the same rear 1× lens, a charging phone, the existing thermal policy and three 2.2-second windows per mode after the camera settled. The chart reports median app CPU as a percentage of one CPU core. Frame counts come from the app’s displayed-frame counter, or its direct-preview counter for Expert RAW. These counters measure different stages of the preview pipeline. Neither measures video encoding speed.

| Mode | Main sustained or capture-specific work | App CPU before → after | Preview fps before → after |
|---|---|---:|---:|
| 1. Pro Video | UHD 60 sensor and ISP, manual focus and exposure feedback, stabilization and encoding during capture | 71.8 → 68.5% | 15.0 → 15.0 |
| 2. Slow Motion | High-speed Camera2 session, high-rate sensor delivery and encoding. Independent playback timing can add file processing | 48.4 → 46.2% | 15.0 → 15.0 |
| 3. Hyperlapse | Sensor and preview stay live between saved frames. The Night setting adds GPU frame accumulation | 38.0 → 38.1% | 15.0 → 15.0 |
| 4. Video | UHD 30 sensor and ISP in the test. Recording adds encoding, optional audio and selected stabilization | 44.6 → 44.3% | 15.0 → 15.0 |
| 5. Portrait Video | Subject segmentation, mask upload and background compositing. Recording can require separate display and encoder draws | 72.1 → 69.6% | 15.0 → 15.0 |
| 6. Photo | Continuous preview, enabled gesture and focus processing. Pressing the shutter adds still encoding | 64.1 → 62.4% | 15.0 → 15.0 |
| 7. Portrait | The S25 selected Samsung bokeh at 1×. Other supported settings can select software tracking and compositing | 55.4 → 48.9% | 30.0 → 30.0 |
| 8. Food | Normal preview plus colour-temperature requests. The radial-blur control was unavailable in this configuration | 59.7 → 60.3% | 15.0 → 15.0 |
| 9. Night | Continuous preview before the shutter. Night capture can add longer exposure and device processing | 62.0 → 64.2% | 15.0 → 15.4 |
| 10. Expert RAW | RAW and JPEG outputs, optional multiframe blending and aperture processing. Sky Guide adds sensor work when enabled | 66.2 → 55.9% | 30.0 → 30.0 |
| 11. Pro Photo | Continuous manual-control feedback. Output format, aperture and enabled capture metadata add work at the shutter | 62.8 → 57.9% | 15.0 → 15.0 |
| 12. Panorama | High-resolution YUV, viewfinder thumbnail conversion and tone mapping. An active sweep adds motion tracking and stitching | 75.8 → 81.3% | 15.0 → 15.0 |

Both preview passes reported zero camera runtime failures in their measurement windows. The initial comparison includes the first settings optimization and the Expert RAW change. The Panorama-specific optimization and the dial refinement were made after that comparison. A 2.2-second sample at 15 fps has approximately 33 frames. A one-frame difference changes the reported rate by 0.45 fps. The initial Pro Video pass started at moderate thermal stress on the original build and light stress on the optimized build. The short measurements establish workload and frame delivery. They cannot establish thermal equilibrium inside a housing.

## Settings and image processing

The reducer, camera callbacks and HUD requested the full settings catalog on routine meter updates. Building it filters the ISO ladder, applies lens and frame-rate constraints, and resolves pipeline compatibility. The optimized code retains up to eight recently resolved lists. Each match checks the mode, device variant, lens inventory, hardware capabilities and the selections that govern control limits. A focus movement can reuse the list and still read the newly selected focus value. Frequent individual camera setting lookups now use a suffix index. Lookups that accept several legacy names retain catalog order.

Both foreground passes produced the same 178 control records, including selected value, availability and ordered options. An on-device fixture also replayed each of the 3,689 enumerated options one at a time through the original and updated catalog code. The fixture used the app’s default capability inputs and the settings observed on the S25. All twelve mode checksums matched across the resolved control descriptions and effective selections. Physical camera tests used the configurations in the inventory. They do not cover the Cartesian product of all setting combinations. [Complete setting inventory and workload by control](S25_CAMERA_SETTING_THERMAL_AUDIT.csv)

| Operation on the S25 | Original CPU time | Optimized CPU time | Output check |
|---|---:|---:|---|
| Resolve unchanged control limits on a meter update | 1.09–10.92 µs per call, depending on mode | 0.043–0.265 µs | Identical settings replay checksums |
| Resolve limits and read a changed dial value in each mode | 1.16–9.34 µs per change | 0.20–1.51 µs | Same values at every tested dial position |
| Merge three 640 × 480 JPEGs, Add | 40.53 ms | 10.06 ms | Identical decoded pixel checksum |
| Same merge, Average | 36.15 ms | 10.30 ms | Identical decoded pixel checksum |
| Same merge, Bright | 38.60 ms | 10.25 ms | Identical decoded pixel checksum |
| Same merge, Dark | 31.93 ms | 10.26 ms | Identical decoded pixel checksum |
| Panorama HDR tone mapping, 209,088 pixels | 4.90 ms | 3.37 ms | Identical checksum across all 16,777,216 RGB inputs |
| Panorama Log tone mapping, same input count | 4.88 ms | 3.49 ms | Identical checksum across all 16,777,216 RGB inputs |

The JPEG merger now writes packed colour values directly and reuses its two row buffers throughout a capture. With 8-bit channels, the weighted average at any history weight of 255 or higher rounds to the same value as weight 255. Bounding the weight prevents multiplication overflow. An independent calculation checked 589,824 channel and weight combinations, including the maximum possible frame index. The Panorama code builds a 256-entry transfer table for each active colour profile. Off still passes the original pixel through.

The operation timings are medians of seven trials for the meter lookup, eleven for dial changes, and five for JPEG merges. For Panorama, the original and final APKs ran in interleaved order four times each, with nine trials per invocation. The Off path took 0.83 ms on both. These are CPU measurements on the handset. They exclude sensor and ISP power, heat transfer through the housing, and charging power.

## Physical capture verification

The original-build capture run published four videos before USB monitoring disconnected and interrupted the run. Android’s media extractor read the following sample timings directly from the temporary files. Presentation rate reflects the output timeline. Hyperlapse deliberately creates a shorter timeline.

| Mode | Encoded format | Frames | Presentation fps |
|---|---|---:|---:|
| Pro Video | AVC, 3840 × 1772 | 371 | 59.98 |
| Slow Motion | HEVC, 1920 × 1080 | 360 | 59.81 |
| Hyperlapse | HEVC, 1920 × 1080 | 13 | 30.00 |
| Video | AVC, 3840 × 1772 | 184 | 30.00 |

The final APK passed the settings and image-processing comparisons on the handset. Repeating the live preview and capture comparisons on that exact APK requires an unlocked handset. In particular, the first whole-app pass measured higher CPU in Food, Night and Panorama, and approximately the same CPU in Hyperlapse. The component measurements alone cannot establish a whole-app reduction or a lower peak temperature for these modes. [Device measurements and benchmark fixtures](S25_CAMERA_BENCHMARK_EVIDENCE.zip)
