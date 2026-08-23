---
type: "query"
date: "2026-08-23T21:30:58.649842+00:00"
question: "The Arrow needs to be a triangle like the standard Navigation triangle arrow. Also, check how stable you are holding the compass heading, it appears to drift and I cannot have that be the case. check all of your rotation code and the tilt code to make sure your perspective calculation is correct and intuitive"
contributor: "graphify"
outcome: "useful"
source_nodes: ["HeadingMath", "HeadingStabilizer", "CompassHeadingMonitor", "CameraHudOverlay"]
---

# Q: The Arrow needs to be a triangle like the standard Navigation triangle arrow. Also, check how stable you are holding the compass heading, it appears to drift and I cannot have that be the case. check all of your rotation code and the tilt code to make sure your perspective calculation is correct and intuitive

## Answer

Expanded from graph vocabulary: [arrow, camera, display, filter, filtered, matrix, sensor, target, vector]. Audited Android natural sensor axes, runtime landscape remap, back-camera -Z basis extraction, world-horizontal target placement, pinhole projection, pitch, roll, yaw and behind-camera direction. Replaced the seven-point dart with a three-vertex standard navigation triangle while retaining shallow perspective extrusion. Added HeadingStabilizer: rotation follows only while gyroscope-derived camera azimuth is changing, settles for 420 ms, then holds exactly against magnetic drift. Raw basis yaw is aligned to the same stable heading around world-up, preserving pitch, roll and orthonormality. Live installed build held 275 degrees W across repeated readings.

## Outcome

- Signal: useful

## Source Nodes

- HeadingMath
- HeadingStabilizer
- CompassHeadingMonitor
- CameraHudOverlay