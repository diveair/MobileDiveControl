---
type: "query"
date: "2026-08-23T22:47:48.073070+00:00"
question: "I think the butt end (ooposite of the tip) of the navigation arrow needs a pointy indent to match it closer to the standard navigation arrow"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraHudOverlay"]
---

# Q: I think the butt end (ooposite of the tip) of the navigation arrow needs a pointy indent to match it closer to the standard navigation arrow

## Answer

Expanded from original query via graph vocab: [arrow, camera, navigation, render, vector]. The navigation outline is now a four-vertex concave dart: tip, rear corner, centered inward tail-notch point, rear corner. Existing perspective projection and prism-wall rendering apply to all four edges. Geometry tests verify the notch is centered and points into the body while pitch, roll, extrusion, and directional behavior remain intact. Full tests pass and the build was installed.

## Outcome

- Signal: useful

## Source Nodes

- CameraHudOverlay