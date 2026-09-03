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
