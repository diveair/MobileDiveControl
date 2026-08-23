---
type: "query"
date: "2026-08-23T14:54:26.242203+00:00"
question: "The gallery once inside a photo/video needs a easy way to navigate back to the gallery and needs a timeline that clearly shows where the video is playing/paused at. Currently there is no way after previewing a photo/video to navigate back to the gallery folder grid, or the folders grid or then to the camera"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "GalleryState", "GalleryScreen.kt", "SurfaceTexture"]
---

# Q: The gallery once inside a photo/video needs a easy way to navigate back to the gallery and needs a timeline that clearly shows where the video is playing/paused at. Currently there is no way after previewing a photo/video to navigate back to the gallery folder grid, or the folders grid or then to the camera

## Answer

Expanded from original query via graph vocab: [gallery, preview, back, navigation, video, progress, duration, pause, texture, reducer, input, media]. ControlReducer already owned a hidden Back hierarchy, while GalleryState and GalleryScreen owned selection and rendering. Implemented a seven-action preview rail with Back, a housing-focusable browser Back control for media grid -> albums -> camera, reset focus on gallery entry, and a MediaPlayer-sourced 250 ms timeline through the TextureView wrapper. Full Gradle tests/build passed; installed and verified on Galaxy S24 with housing simulation, including PLAYING/PAUSED timeline and all Back transitions; no fatal logs.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- GalleryState
- GalleryScreen.kt
- SurfaceTexture