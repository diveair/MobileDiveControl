# Galaxy S24 Portrait preview

Inspected on the SM-S921W with Samsung Camera 16.5.02.36. The offsets below are hexadecimal byte offsets within the named, uncompressed DEX files. Android SDK Build Tools 35.0.0 `dexdump -d -n` produced the disassembly.

| Package and DEX | Offset | Observation |
| --- | --- | --- |
| Samsung Camera, `classes4.dex` | `0x3f4744` | `PortraitPresenter.onBokehInfoChanged` receives a state and two maps. The presenter checks whether to show the face guide. This method handles UI feedback. |
| Samsung camera extensions service, `classes.dex` | `0x136d10` | `BokehProcessor.initializeLocked` creates and initializes a single-bokeh node and retains it for subsequent frames. |
| Extensions service | `0x136b74` | `processPreviewLocked` receives an image and its capture result. It reads the sensor crop and detected faces and, when present, sends both with the camera reference rectangle to `setPreviewHwFaceInfo` at `0x136bf2`. It passes the current image into the node at `0x136c00`. |
| Extensions service | `0x136e18` | The orientation callback forwards physical device orientation to the same node. |
| Extensions service | `0x13708c` | The strength setter skips a repeated value, then maps a requested percentage to an integer level using `floor(8 × percentage / 101)`. Its default percentage is 64, which resolves to level 5. |
| Samsung Camera, `classes3.dex` | `0x2d2e52` | Samsung’s node invokes its native hardware-face command. The service and app expose inputs and outputs at this boundary. Android denied shell access to the vendor preview library containing the underlying image processing. |

The public extension and stock Camera have distinct entry points. The extension trace establishes its level mapping and inputs. Samsung’s private segmentation weights, temporal filter, and blur kernel remain unverified.

## Nauticase routing

For Portrait stills with **Blur** at levels 1–7, Nauticase requests the public Samsung CameraX BOKEH extension on a compatible lens. One extension session supplies the preview and JPEG. The housing dial changes the extension strength in place. Zero selects an unblurred image. Other effects and lenses without a compatible extension use Nauticase’s on-device renderer. That path estimates the person outline, aligns consecutive masks using face metadata when available, and composites each live frame on the GPU.

The S24 reported Samsung BOKEH for rear 0.6× (camera 2), rear 1× (camera 0), rear 3× (logical camera 0 at 3×), and front (camera 1). Samsung’s own Camera app showed rear Portrait, Ready, Blur level 5 and held camera ID 23 during this check. The public API gives Nauticase Samsung processing. The different session identifiers leave exact visual parity with stock Camera unverified.

## Reproducibility

The inspected Samsung Camera APK has SHA-256 `555311163016fa96d61ff0596c86d9ab7d5a2559e725103ee7286662d88257a7`. The camera extensions APK has SHA-256 `0852875594ae0d1eb2022eeb2e249fe3289255b4ded4746a08f77509d3eb447c`. Its extracted `classes.dex` has SHA-256 `1cdd36827f0f2c523e56d42eaaa26c4271caa7cb04aaa69900a65ada3d79b300`.

The device regression feeds a moving portrait through the production camera texture and GL compositor, omits one person detection, and measures face pixels and background edge contrast independently. It also takes a real Nauticase JPEG and a public Samsung BOKEH reference exposure. The controlled movement uses James S. Davis’s 1984 Navy portrait of Grace Hopper. [Navy archive](https://www.history.navy.mil/content/history/nhhc/our-collections/photography/numerical-list-of-images/nhhc-series/nh-series/NH-96000/NH-96919-KN.html) · [Matplotlib test image](https://github.com/matplotlib/matplotlib/blob/main/lib/matplotlib/mpl-data/sample_data/grace_hopper.jpg)

The earlier independent-renderer regression retained at most 28.25% of the original flag edge contrast across 12 moving frames at strength 4. The largest frame-average change in the face patch was 0.00043 on the 0–255 green-channel scale. The independent compositor delivered 30.06 fps with a 30 fps input and a 95th-percentile frame interval of 35.75 ms. This controlled test did not establish the quality of a moving person's outline in a live camera scene.

The production Samsung path saved a 4080 × 1883 Full-frame JPEG and changed from level 4 to 5 without rebinding. A separate public Samsung reference session returned a 4080 × 3060 exposure. On this handset, the public extension does not advertise an ImageAnalysis stream. Nauticase samples a small copy of the displayed preview for compass pointing gestures during the extension session.

On the S24’s rear 1× camera at level 4, the Samsung path delivered 29.95 preview frames per second over 3.2 seconds. The source timestamps on frames presented by the TextureView were 33.33 ms apart at the median and 95th percentile. A moving person’s hair and outline still need a side-by-side visual comparison with stock Camera.
