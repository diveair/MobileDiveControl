---
type: "query"
date: "2026-08-23T22:13:55.362264+00:00"
question: "we are off by 15 degrees (at 360 deg) the actual measured compass verified angle is 340 deg"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController"]
---

# Q: we are off by 15 degrees (at 360 deg) the actual measured compass verified angle is 340 deg

## Answer

Expanded from graph vocabulary: [calibration, calibrated, camera, monitor, offset, sensor, target]. The graph located CameraRuntimeController but the new compass files were not indexed, so direct source inspection and live ADB telemetry were required. The supplied exact pair 360 to 340 requires a minus-20-degree correction, despite the verbal estimate of 15 degrees. Added a correction scoped to Samsung SM-S921W, kept the calibrated HUD heading separate from the stable internal navigation bearing so existing target arrows do not jump, added three regression tests, passed 364 tests with zero failures, and installed the build on the connected phone.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController