---
type: "query"
date: "2026-08-23T22:06:50.566493+00:00"
question: "When the phone camera faces down towards the floor and the screen faces up towards the sky I get no compass reading being displayed. As I tilt it up it goes from 245 to 262deg roughly (without rotating it away from north"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraRuntimeController"]
---

# Q: When the phone camera faces down towards the floor and the screen faces up towards the sky I get no compass reading being displayed. As I tilt it up it goes from 245 to 262deg roughly (without rotating it away from north

## Answer

Expanded query tokens: camera, sensor, monitor, vertical, arrow, display. Graph traversal located the camera runtime path but the recently added heading files were not yet indexed. Direct inspection found a vertical optical-axis azimuth singularity and a motion gate differentiating optical azimuth near that singularity. Implemented a continuous camera-frame heading that becomes the top-of-image bearing at straight down, changed turn detection to world-up gyro rate so pitch/roll cannot unlock yaw, and aligned the stabilized 3D basis with the same convention. Added vertical and pitch-sweep regression tests; 361 tests passed with zero failures. Built, installed, and launched on the connected Samsung.

## Outcome

- Signal: useful

## Source Nodes

- CameraRuntimeController