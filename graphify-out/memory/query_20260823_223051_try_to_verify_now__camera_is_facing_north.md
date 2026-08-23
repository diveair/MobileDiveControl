---
type: "query"
date: "2026-08-23T22:30:51.983660+00:00"
question: "try to verify now, camera is facing north"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "Sensor"]
---

# Q: try to verify now, camera is facing north

## Answer

Expanded from original query via graph vocab: [camera, monitor, sensor, target]. CameraRuntimeController is the runtime integration path, but the graph predates CompassHeadingMonitor, so live device telemetry was authoritative. With the user holding the camera optical axis north, five UI samples read 357, 354, 356, 357, and 356 degrees; the latest three were 356, 357, 356. The calibrated AK09918 magnetometer reported Android accuracy 3. This verifies a stable north result within 3-6 degrees and does not justify adding a fixed calibration offset from one pose.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- Sensor