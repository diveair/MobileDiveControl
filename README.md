# MobileDiveControl

Lean native-first control core for the `Claude.md` product spec.

This implementation does not pretend to ship a full mobile UI from an empty repo. It builds the part that matters first:

```text
Housing button -> BLE packet -> parser -> router -> reducer -> command/effect -> diagnostics
```

What is implemented:

- Kotlin control core with explicit state, commands, routing, and failure handling
- Bounded BLE button parsing with malformed-packet protection
- Input duplicate filtering
- Camera, phone-control, safety, and system command contracts
- BLE connection state machine and reconnect policy
- Vacuum workflow state machine
- In-memory ring-buffer diagnostics with JSONL export
- Scenario runner so you can run scripted tests locally and report exact results back
- Unit tests for parser hardening, routing, state machines, diagnostics, and safety behavior

What is deliberately not faked:

- Android BLE adapter
- Android camera adapter
- Android AccessibilityService adapter
- iOS adapter
- Unsupported protocol field decoding that is not actually specified in `Claude.md`

Those stay behind narrow interfaces so the core remains testable and honest.

## Layout

```text
src/main/kotlin/com/mobiledivecontrol/
  app/
    Main.kt
    ScenarioScriptRunner.kt
  core/
    BleConnectionMachine.kt
    ButtonEventNormalizer.kt
    Contracts.kt
    ControlCore.kt
    ControlReducer.kt
    DiagnosticsStore.kt
    InputRouter.kt
    JsonSupport.kt
    ProtocolParser.kt
    SafetyStateMachine.kt

src/test/kotlin/com/mobiledivecontrol/core/
  BleConnectionMachineTest.kt
  ControlCoreTest.kt
  DiagnosticsStoreTest.kt
  InputRouterTest.kt
  ProtocolParserTest.kt
  SafetyStateMachineTest.kt
```

## Running

You need a local JDK 17+.

Run the automated test suite:

```powershell
.\run-gradle.ps1 test
```

Run a scenario you control:

```powershell
.\run-gradle.ps1 run --args="scenarios/smoke.scenario"
```

Run a failure scenario:

```powershell
.\run-gradle.ps1 run --args="scenarios/malformed-packet.scenario"
```

Rebuild, reinstall, and launch the Android app on the connected USB phone:

```powershell
.\test.cmd
```

Useful options:

```powershell
.\test.cmd -Fresh
.\test.cmd -NoLaunch
.\test.cmd -Serial RFCX80XPC5P
```

## Reporting Results

Use the protocol in [TEST_PROTOCOL.md](TEST_PROTOCOL.md). It is written so you can send me:

- exact command
- exact scenario file
- exact output
- final state summary
- any unexpected effect or error

That is enough for me to iterate with you quickly.
