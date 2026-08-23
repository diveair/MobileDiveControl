---
type: "query"
date: "2026-08-23T23:11:04.557441+00:00"
question: "Improve gesture control recognition speed and accuracy so a pointing hand sets the heading faster."
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "DiveViewModel"]
---

# Q: Improve gesture control recognition speed and accuracy so a pointing hand sets the heading faster.

## Answer

Expanded from the original query via graph vocabulary: [camera, confidence, frame, gesture, media, speed, tracking]. CameraRuntimeController routes the existing RGBA analysis frame into the on-device PointingGestureRecognizer and then DiveViewModel converts the accepted image ray into a target heading. The recognizer was hard-capped at 5 fps and required a 400 ms stable hold. It now analyzes up to 12.5 fps, confirms the same three consistent frames in about 160 ms, requires a fresh three-frame candidate for every re-aim, tolerates brief detection dropout, lowers raw hand-detection thresholds only behind the existing first-principles finger-geometry and temporal gates, reuses its bitmap and row buffer, delegates rotation to MediaPipe, mirrors front-camera landmarks mathematically, and closes every MPImage wrapper. Focused and full Gradle tests pass; the exact build is installed and MediaPipe initializes on the connected S24 without inference or runtime errors. Physical pointing acceptance still requires manual camera input.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- DiveViewModel