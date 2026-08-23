---
type: "query"
date: "2026-08-23T21:45:43.097350+00:00"
question: "Improve gesture recognition for the point"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "DiveViewModel"]
---

# Q: Improve gesture recognition for the point

## Answer

Expanded from original query via graph vocab: [gesture, point, camera, image, analysis, frame, confidence, tracking, target, pipeline]. Traversal identified CameraRuntimeController as the CameraX analysis integration and DiveViewModel as the image-ray-to-heading consumer. PointingGestureRecognizer now corroborates MediaPipe Pointing_Up with 3D world-landmark geometry, rejects a second extended finger, extrapolates the index ray, and uses a 400 ms dropout-tolerant stability gate. Full build and 360 test executions passed; APK installed on RFCX80XPC5P.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- DiveViewModel