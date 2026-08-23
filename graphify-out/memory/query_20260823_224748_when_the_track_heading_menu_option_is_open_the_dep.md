---
type: "query"
date: "2026-08-23T22:47:48.115489+00:00"
question: "When the TRACK HEADING menu option is open the Depth/heading/arrow/temperature are higher up than when it is closed. I need that fixed. Only menu's that actually interfere with these UI bounding boxes should move them up and just out of the way of the menu's while they are open and then move back when they are closed. The side menu where TRACK HEADING is located does not interfere at all"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CameraHudOverlay", "CameraUiZone", "CameraState"]
---

# Q: When the TRACK HEADING menu option is open the Depth/heading/arrow/temperature are higher up than when it is closed. I need that fixed. Only menu's that actually interfere with these UI bounding boxes should move them up and just out of the way of the menu's while they are open and then move back when they are closed. The side menu where TRACK HEADING is located does not interfere at all

## Answer

Expanded from original query via graph vocab: [camera, depth, hud, layout, menu, mode, navigation, overlay, rail, settings, zone]. CameraHudOverlay incorrectly treated CameraUiZone.ModeRail as a bottom collision. The right rail is edge-confined; only CameraState.settingsEditing expands the full-width bottom tray. cameraReadoutBottomPadding now keeps ModeRail at 54 dp, lifts only the bottom editor to 86 dp, and restores 54 dp when it closes. Regression tests cover all three states; full debug and release tests pass and the build was installed.

## Outcome

- Signal: useful

## Source Nodes

- CameraHudOverlay
- CameraUiZone
- CameraState