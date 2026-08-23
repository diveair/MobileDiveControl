---
type: "query"
date: "2026-08-23T14:22:59.005558+00:00"
question: "lets work on the gallery menu: albums grid, navigable media grid, full-screen preview controls, move, rename, and delete"
contributor: "graphify"
outcome: "useful"
source_nodes: ["GalleryRepository", "GalleryScreen.kt", "ControlReducer", "InputRouterTest"]
---

# Q: lets work on the gallery menu: albums grid, navigable media grid, full-screen preview controls, move, rename, and delete

## Answer

Expanded from original query via graph vocab: [gallery, folders, grid, media, image, video, preview, navigation, reducer, input, delete, exif]. The existing single gallery flow runs GalleryCommand through ControlReducer, executes MediaStore work in GalleryRepository via DiveViewModel, and renders GalleryScreen; the implementation extended those nodes rather than adding parallel state.

## Outcome

- Signal: useful

## Source Nodes

- GalleryRepository
- GalleryScreen.kt
- ControlReducer
- InputRouterTest