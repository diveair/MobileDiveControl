---
type: "query"
date: "2026-08-23T21:57:59.565945+00:00"
question: "I think the compass points from the top of the phone because that top of the phone is facing West atm"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController"]
---

# Q: I think the compass points from the top of the phone because that top of the phone is facing West atm

## Answer

Expanded from original query via graph vocab: [camera, sensor, monitor, display]. Graph traversal confirmed CameraRuntimeController as camera integration context, while direct inspection showed CompassHeadingMonitor projects the back-camera -Z axis, not the device-top +Y axis. Connected S24 telemetry reported a rotation-vector solution whose back-camera axis is 259-262 degrees even though the physical camera is aligned north; the same quaternion puts device-top around 169 degrees, so the displayed west/top agreement is coincidental. Samsung provides no TYPE_HEADING or geomagnetic-rotation-vector sensor on this device, and rotation-vector heading accuracy is -1/unavailable. Recalibration in the final housing configuration is required before considering a multi-point app calibration.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController