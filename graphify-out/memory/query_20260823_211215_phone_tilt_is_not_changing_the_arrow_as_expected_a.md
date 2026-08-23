---
type: "query"
date: "2026-08-23T21:12:15.434121+00:00"
question: "phone tilt is not changing the arrow as expected and it should. The arrow should be 3D so that when the phone is perpendicular to the ground and facing the correct heading the arrow points along the camera's perspective towards the heading. I also want the heading moved and placed between the Depth and the temp in the center of their UI and give it a unique colour"
contributor: "graphify"
outcome: "corrected"
correction: "Use a perspective-projected horizontal 3D mesh; a single 2D arrow rotation cannot represent optical-axis depth or phone tilt. Place heading in the geometrically centred depth/temperature row."
source_nodes: ["HeadingMath", "CameraHudOverlay", "CompassHeadingMonitor", "DepthGauge"]
---

# Q: phone tilt is not changing the arrow as expected and it should. The arrow should be 3D so that when the phone is perpendicular to the ground and facing the correct heading the arrow points along the camera's perspective towards the heading. I also want the heading moved and placed between the Depth and the temp in the center of their UI and give it a unique colour

## Answer

Expanded from graph vocabulary: [arrow, camera, controller, display, matrix, sensor, target, vector]. The earlier implementation reduced the world target to a 2D angle and collapsed when the heading aligned with the optical axis. It was replaced by a perspective-projected 3D prism on a virtual world-horizontal plane. The live camera basis projects its top, bottom, and side faces, making pitch and roll change foreshortening and vanishing direction. Display rotation is read from DisplayManager. The heading moved into an equal-side-width DEPTH | HEADING | TEMP row and uses HeadingViolet.

## Outcome

- Signal: corrected
- Correction: Use a perspective-projected horizontal 3D mesh; a single 2D arrow rotation cannot represent optical-axis depth or phone tilt. Place heading in the geometrically centred depth/temperature row.

## Source Nodes

- HeadingMath
- CameraHudOverlay
- CompassHeadingMonitor
- DepthGauge