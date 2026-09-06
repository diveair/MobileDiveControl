# Samsung panorama integration

Panorama uses the implementation in the installed Samsung Camera APK for frame selection,
gyro input, stitching, the scan mosaic, and JPEG encoding. No Samsung binaries are bundled.
The integration was verified on a Galaxy S24 with Samsung panorama interface 2025.6.30.0
and ArcSoft engine 5.1.12523.109; compatibility with other Samsung APK versions is unverified.

`SamsungPanoramaEngine` loads Samsung's `Interface` through a shared class loader. CameraX's
bundled native YUV converter packs 4000×3000 camera frames into reusable NV21 storage.
The native engine receives the actual row stride and padded height. JPEG output format is `0`.

The controller displays the engine's growing mosaic during capture. After stop, it stages the
completed JPEG unchanged and opens Save/Delete review. Only Save publishes the image to the
gallery. Review input is briefly disarmed so the stop gesture cannot also select Save.
Stopping before a frame is selected cancels quietly.

Direction reversal uses the installed selector's `0x7011` (trace direction back) result,
the same condition handled by Samsung's `PanoramaStateCapture`. The adapter checks it before
discarding unselected frames, stops accepting input, and requests the normal completed-image
Save/Delete review. Ordinary skipped frames and movement warnings continue capture. This uses
Samsung's own reversal detection rather than the retired Kotlin gyro thresholds.

The full-screen viewfinder clears its camera-switch still image when the replacement
`PreviewView` TextureView consumes a frame. The callback is scoped to the camera binding and
surface request so an old producer cannot reveal an unready replacement. This avoids a stale
full-screen cover when CameraX's stream state does not deliver an IDLE transition, even though
the independent ImageAnalysis mini preview continues updating.

## Callback compatibility

Samsung's higher-level `PanoramaNode` accesses a private `ImageReader` field and cannot process
frames in this third-party app. The direct-buffer `Interface` avoids that wrapper.

The JNI progress callback requires a concrete implementation of `(I)V`. A reflective Java
proxy caused ART CheckJNI to interpret progress `50` as an object reference and abort.
The handwritten `PanoCallbackInterface` and `ResultParam` declarations preserve that ABI;
they contain no vendor implementation.

## Validation

Run the unit suites and build with:

```text
gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

After installing the app and test APKs, the device regression can run without opening an activity:

```text
adb shell am instrument -w -e exerciseSamsungPipeline true com.mobiledivecontrol.test/com.mobiledivecontrol.ui.camera.PanoramaPipelineInstrumentation
```

It feeds real YUV Images with synthetic horizontal and vertical sweeps through the production
adapter, checks empty-capture cancellation, receives scan mosaics, and validates native JPEG
completion and decoding. Both directions passed on the S24.

Two subsequent physical captures on September 3, 2026 returned native JPEGs 116 ms and 103 ms
after stop. The controller opened completed-image review at 213 ms and 181 ms respectively.
Both review images were deleted through the app, with no crash. These measurements establish
capture and review behavior for those runs, not exhaustive image-quality or device compatibility.

The reversal regression additionally feeds forward travel, a stationary pause, then reverse
travel through the production adapter and the installed native selector on both axes and in
both signs:

```text
adb shell am instrument -w -e exerciseSamsungPipeline true -e exerciseSamsungReversal true com.mobiledivecontrol.test/com.mobiledivecontrol.ui.camera.PanoramaPipelineInstrumentation
```

It checks that reversal requests Stop exactly once, later input is ignored, and the completed
JPEG still decodes. This is a native pipeline test with synthetic image motion, not a physical
phone-pan or activity review-screen test.
All four reversal cases and both forward-only controls passed on the connected S24 on
September 3, 2026; results are retained in `artifacts/panorama-reversal-device-results.txt`.
