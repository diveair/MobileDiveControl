---
type: "query"
date: "2026-08-23T16:15:10.349333+00:00"
question: "move back to where delete is and delete where back is"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "GalleryScreen.kt", "Contracts.kt"]
---

# Q: move back to where delete is and delete where back is

## Answer

Expanded from original query via graph vocab: [gallery, preview, back, delete, reducer, control, screen]. Swapped only the GalleryPreviewAction enum positions so the rendered rail is Delete, Back, Options, Previous, Play/Pause, Next, Details. Updated the ordering/navigation test. Full Gradle tests and APK assembly passed, the APK was installed on the connected Galaxy S24, the order was visually verified, and AndroidRuntime had no fatal logs.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- GalleryScreen.kt
- Contracts.kt