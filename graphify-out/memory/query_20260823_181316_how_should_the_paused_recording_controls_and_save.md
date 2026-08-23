---
type: "query"
date: "2026-08-23T18:13:16.706467+00:00"
question: "How should the paused recording controls and Save To album chooser work?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "CameraShellScreen", "CameraHudOverlay", "GalleryRepository", "CameraRuntimeController", "RecordingSessionMuxer"]
---

# Q: How should the paused recording controls and Save To album chooser work?

## Answer

The paused action rail is independently centered on the screen. SAVE TO sits above a centered PAUSED badge; when SAVE TO owns focus, no Resume/Preview/Stop/Delete action is highlighted. Opening SAVE TO shows a true modal, three-column, vertically scrollable album grid with two visible rows. Tile activation only highlights a pending destination; Back cancels it and Confirm applies it. Housing vertical navigation moves by three entries and horizontal navigation by one. The explanatory footer was removed. The final S24 device test selected DCIM/Canada Docs and Stop published a valid 2.966-second, 4,599,670-byte MP4 there.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- CameraShellScreen
- CameraHudOverlay
- GalleryRepository
- CameraRuntimeController
- RecordingSessionMuxer