---
type: "query"
date: "2026-08-23T22:25:37.893325+00:00"
question: "I show 023 and the compass needle in real life is facing 000/360. use the gps to verify as well. The phone screen currently is facing up, the camera is facing down into the ground"
contributor: "graphify"
outcome: "corrected"
correction: "A stationary flat phone has no optical-axis azimuth. Define heading as the landscape preview's centre-to-top axis; GPS position can provide declination but GPS cannot verify that stationary axis. Do not calibrate from the compass needle alone or stack fixed offsets."
source_nodes: ["CameraRuntimeController"]
---

# Q: I show 023 and the compass needle in real life is facing 000/360. use the gps to verify as well. The phone screen currently is facing up, the camera is facing down into the ground

## Answer

Expanded from graph vocabulary: [calibration, camera, monitor, offset, target]. The graph's heading files were stale, so direct source and live ADB diagnostics were used. The new observation corrected the prior conclusion: do not stack a fixed offset. GPS was enabled but had no satellite fix or course, only a network position; stationary GNSS cannot verify device orientation. In the flat camera-down pose, Samsung's rotation-vector yaw reported no accuracy and disagreed with the high-accuracy AK09918 magnetometer. Replaced absolute yaw with gravity plus calibrated magnetic field, removed the minus-20 profile, added a one-second warm-up and time-based low-pass filter for camera-electronics spikes, passed 361 tests, installed, and confirmed no crash. A valid physical comparison must align the centre-to-top direction of the landscape preview with the compass's heading/index line; the needle alone always indicates magnetic north and does not specify the phone heading.

## Outcome

- Signal: corrected
- Correction: A stationary flat phone has no optical-axis azimuth. Define heading as the landscape preview's centre-to-top axis; GPS position can provide declination but GPS cannot verify that stationary axis. Do not calibrate from the compass needle alone or stack fixed offsets.

## Source Nodes

- CameraRuntimeController