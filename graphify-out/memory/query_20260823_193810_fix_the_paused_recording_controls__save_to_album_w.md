---
type: "implementation"
date: "2026-08-23T19:38:10.720088+00:00"
question: "Fix the paused recording controls, SAVE TO album workflow, album ordering, empty-grid Back focus, and gallery Back visuals"
contributor: "graphify"
outcome: "useful"
source_nodes: ["RecordingPausedChooser", "GalleryRepository", "ControlReducer", "RecordingSaveLocation", "GalleryBackActionButton"]
---

# Q: Fix the paused recording controls, SAVE TO album workflow, album ordering, empty-grid Back focus, and gallery Back visuals

## Answer

RecordingPausedChooser now renders one rounded rectangle with PAUSED above it, action rail centered, Preview/Resume/Stop/Delete ordering, Resume default, and white Top/Bottom labels. SAVE TO defaults to DCIM/Camera, uses a housing-following LazyVerticalGrid, and album activation opens a separate Back/Confirm decision; Back returns to focused SAVE TO. GalleryRepository suppresses root-level synthetic Other albums and persists newest-first created albums. ControlReducer focuses Back for empty grids and preserves the two-stage save-location invariants. Full unit suites and APK assembly passed; installed device verification confirmed scrolling, Camera first/no Other, Back/Confirm, and focus return.

## Outcome

- Signal: useful

## Source Nodes

- RecordingPausedChooser
- GalleryRepository
- ControlReducer
- RecordingSaveLocation
- GalleryBackActionButton