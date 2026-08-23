---
type: "architecture"
date: "2026-08-23T20:57:06.398559+00:00"
question: "Why did continuous autofocus pulse and how should it hold focus without becoming unresponsive?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "CaptureRequest", "AutofocusHoldPolicy"]
---

# Q: Why did continuous autofocus pulse and how should it hold focus without becoming unresponsive?

## Answer

Expanded query tokens: auto, autofocus, camera, capture, focus, lens, request, state. The previous repeating continuous-video AF kept hunting. The controller now lets continuous AF settle, sends CONTROL_AF_TRIGGER_START for exactly one capture, and retains the resulting locked AF state in repeating requests. Gyroscope motion keeps the current plane locked while moving and requests one new focus cycle after motion settles; sustained sharpness loss can also request a single refocus. Live Samsung telemetry held lpActual at 1972 for over 20 seconds.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- CaptureRequest
- AutofocusHoldPolicy