---
type: "architecture"
date: "2026-08-23T20:57:04.993468+00:00"
question: "How should the compass heading and target arrow adapt to landscape display rotation and phone tilt?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraHudOverlay", "CompassHeadingMonitor", "HeadingMath", "SealCheckIndicator", "DepthGauge"]
---

# Q: How should the compass heading and target arrow adapt to landscape display rotation and phone tilt?

## Answer

Expanded query tokens: arrow, camera, depth, direction, overlay, seal, status, target, temperature, vacuum. The HUD now uses a full back-camera basis remapped for the runtime display rotation. The horizontal target bearing is projected into screen right/up/forward coordinates, so pitch, roll, and landscape orientation rotate the navigation triangle consistently. Heading placement uses the measured seal-status height; the target triangle and depth/temperature group move between live and menu-safe bottom anchors.

## Outcome

- Signal: useful

## Source Nodes

- CameraHudOverlay
- CompassHeadingMonitor
- HeadingMath
- SealCheckIndicator
- DepthGauge