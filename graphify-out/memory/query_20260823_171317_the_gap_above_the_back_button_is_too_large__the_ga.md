---
type: "implementation"
date: "2026-08-23T17:13:17.767057+00:00"
question: "The gap above the back button is too large, the gap below the back button is too large, reduce both by 75%"
contributor: "graphify"
outcome: "useful"
source_nodes: ["GalleryScreen.kt"]
---

# Q: The gap above the back button is too large, the gap below the back button is too large, reduce both by 75%

## Answer

Expanded from original query via graph vocabulary: [gallery, back, button, centered, direction, gaps, layout, screen, space]. Replaced edge-pinned chevrons with a symmetric vertical stack using explicit 1dp gaps above and below BACK, reducing the prior approximately 4dp gaps by 75%. The outer button remains 48dp. Full tests and assembleDebug passed; installed and visually verified selected/unselected states on the connected Galaxy S24; recent AndroidRuntime logcat was clean.

## Outcome

- Signal: useful

## Source Nodes

- GalleryScreen.kt