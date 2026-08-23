---
type: "query"
date: "2026-08-23T22:56:30.874466+00:00"
question: "Autofocus no longer adjusts smoothly, rapidly, or responsively; check the changes against the repository baseline."
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "CaptureRequest", "AutofocusHoldPolicy"]
---

# Q: Autofocus no longer adjusts smoothly, rapidly, or responsively; check the changes against the repository baseline.

## Answer

Expanded from the original query via graph vocabulary: [autofocus, camera, capture, controller, focus, hold, lens, policy, request, runtime, sensor]. The repository baseline handed focus back to continuous-video AF after landing, whereas the newer anti-pulse implementation kept CONTROL_AF_STATE locked throughout camera motion and waited until motion ended before releasing it. Live telemetry confirmed AF mode 3 with focused-locked state. The surgical correction releases the lock on the first deliberate gyroscope movement, leaves the native continuous AF request running throughout the reframe, coalesces later motion into the relock deadline, and gives stationary subject-change detection a tested nominal budget of 280 ms (actual wall time remains frame-scheduling dependent). The full Gradle test suite passes and the APK was installed on the connected phone.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- CaptureRequest
- AutofocusHoldPolicy
