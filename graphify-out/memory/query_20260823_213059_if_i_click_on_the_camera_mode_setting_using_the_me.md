---
type: "query"
date: "2026-08-23T21:30:59.897602+00:00"
question: "If I click on the camera mode setting using the Menu/OK button then click track heading, pressing left/right should keep me navigating inside the camera navbar but instead it forces me to navigate in the camera mode UI"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ControlReducer", "ControlCoreTest", "CameraUiZone"]
---

# Q: If I click on the camera mode setting using the Menu/OK button then click track heading, pressing left/right should keep me navigating inside the camera navbar but instead it forces me to navigate in the camera mode UI

## Answer

Expanded from graph vocabulary: [camera, control, focus, menu, mode, navigate, navigation, rail, setting, zone]. Track Heading incorrectly hardcoded focusedZone LiveView and discarded modeRailReturnZone. It now calls exitModeRail, returning to SettingsPanel when launched from the camera navbar and to LiveView when launched directly. A reducer regression test executes Navbar Confirm, Track Heading Confirm, then NavigateLeft and verifies focus and cursor remain in SettingsPanel.

## Outcome

- Signal: useful

## Source Nodes

- ControlReducer
- ControlCoreTest
- CameraUiZone