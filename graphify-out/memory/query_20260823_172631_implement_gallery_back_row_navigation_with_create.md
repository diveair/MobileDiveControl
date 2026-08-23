---
type: "query"
date: "2026-08-23T17:26:31.635427+00:00"
question: "Implement gallery Back-row navigation with Create Album as the intermediate Right target, add the compact Back arrow and TOP/BOTTOM chevrons, and prevent video autoplay on preview or Next/Previous"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "GalleryState", "GalleryScreen.kt", "GalleryMenuFlowTest"]
---

# Q: Implement gallery Back-row navigation with Create Album as the intermediate Right target, add the compact Back arrow and TOP/BOTTOM chevrons, and prevent video autoplay on preview or Next/Previous

## Answer

Implemented reducer-level anchored-row edge selection using selectedIndex and the 4/6 column contracts. Left from Back selects the current row's left edge. In albums, Right selects Create Album and a second Right selects the current row's right edge; in media grids Right selects the row edge directly. Video preview entry and every Previous/Next transition now set videoPlaying false; Play/Pause is the only transition that starts playback. The shared Back control is 44dp high with a Back arrow and compact TOP/BOTTOM chevrons. Added regression tests, completed all project tests and assembleDebug, installed on the Galaxy S24, and verified both .mp4 files remain paused across Next.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- GalleryState
- GalleryScreen.kt
- GalleryMenuFlowTest