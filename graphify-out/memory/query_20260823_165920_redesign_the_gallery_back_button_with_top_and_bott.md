---
type: "implementation"
date: "2026-08-23T16:59:20.875135+00:00"
question: "Redesign the gallery Back button with TOP and BOTTOM chevrons and centered BACK; center video Play/Pause and move Delete after Details."
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "GalleryCommand", "GalleryRepository", "Contracts.kt"]
---

# Q: Redesign the gallery Back button with TOP and BOTTOM chevrons and centered BACK; center video Play/Pause and move Delete after Details.

## Answer

Implemented a dedicated 94dp Back control with legible TOP/BOTTOM chevrons and geometrically centered BACK. Video preview actions are Back, Options, Previous, Play/Pause, Next, Details, Delete; photos omit Play/Pause. Updated reducer-facing action ordering and tests, passed the full Gradle test/assembleDebug build, installed on the connected Galaxy S24, and visually verified both layouts.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- GalleryCommand
- GalleryRepository
- Contracts.kt