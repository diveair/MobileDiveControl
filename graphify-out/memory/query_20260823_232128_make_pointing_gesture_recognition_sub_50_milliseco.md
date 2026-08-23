---
type: "query"
date: "2026-08-23T23:21:28.499136+00:00"
question: "Make pointing gesture recognition sub 50 milliseconds."
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController", "DiveViewModel"]
---

# Q: Make pointing gesture recognition sub 50 milliseconds.

## Answer

Expanded from the original query via graph vocabulary: [camera, confidence, frame, gesture, latency, media, speed, tracking]. Device measurement showed the prior CPU pipeline averaged 32 ms per analyzed frame but temporal confirmation caused the perceived delay. The implementation now uses MediaPipe's GPU delegate with automatic CPU fallback, consumes every latest CameraX analysis frame without queueing, and accepts a single frame only when strict independent finger geometry is high quality or corroborated by the Pointing_Up model; ambiguous poses retain three-frame confirmation. On the connected Samsung S24 the final installed build benchmarked 10 ms average and 25 ms maximum over 30 post-warmup frames. Observed high-confidence acceptances were 14-48 ms from frame arrival, with heading storage following in roughly 3-13 ms. The full Gradle suite passes, GPU teardown is queued on the thread-affine analyzer executor, and runtime logs show no inference or AndroidRuntime failures. This does not guarantee under 50 ms from real-world gesture onset because camera-frame arrival adds up to one frame period.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController
- DiveViewModel