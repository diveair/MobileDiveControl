---
type: "architecture"
date: "2026-08-23T20:31:44.761564+00:00"
question: "How should DiveControl distinguish Bluetooth-off from housing-disconnected, render the off banner edge-to-edge, and reconnect automatically?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["BluetoothAdapter", "DiveViewModel", "MainActivity", "HousingLinkService", "HousingLinkBanner"]
---

# Q: How should DiveControl distinguish Bluetooth-off from housing-disconnected, render the off banner edge-to-edge, and reconnect automatically?

## Answer

Observe BluetoothAdapter state through ACTION_STATE_CHANGED, resample the adapter state, stop HousingLinkService while the radio is off, request enable through ACTION_REQUEST_ENABLE, and restart the existing idempotent reconnecting HousingLinkService when the radio reaches ON. Render only the Bluetooth-off banner with full width and square corners. Android target 35 does not permit silent Bluetooth enabling for an ordinary app.

## Outcome

- Signal: useful

## Source Nodes

- BluetoothAdapter
- DiveViewModel
- MainActivity
- HousingLinkService
- HousingLinkBanner