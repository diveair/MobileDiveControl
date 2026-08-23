---
type: "query"
date: "2026-08-23T21:45:43.191396+00:00"
question: "check your compass heading value, I am comparing with an actual compass and I get 332deg when you are showing 280"
contributor: "graphify"
outcome: "dead_end"
source_nodes: ["Sensor"]
---

# Q: check your compass heading value, I am comparing with an actual compass and I get 332deg when you are showing 280

## Answer

Expanded from original query via graph vocab: [sensor, monitor, display]. The graph traversal was stale and did not contain the new compass implementation. Direct source inspection and connected-device telemetry showed DiveControl correctly mapped Samsung's rotation-vector quaternion to a back-camera magnetic azimuth of about 281 degrees; the known north alignment means the sensor solution, not the app axis transform, is wrong. No guessed fixed offset was applied because observed errors were not constant.

## Outcome

- Signal: dead_end

## Source Nodes

- Sensor