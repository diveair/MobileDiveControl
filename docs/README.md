# MobileDiveControl — Documentation Index

All documentation lives in `docs/` and is derived from the product spec (`Claude.md`) and the actual code implementation. Documentation is kept in sync with the code — the [TRACEABILITY.md](TRACEABILITY.md) document maps every code element to its spec requirement.

---

## Documentation Map

| Document | Description | Spec Sections |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture, module layout, data flow, security zones, state model, platform strategy, implementation status | §6, §7, §8, §9, §10 |
| [BLE_PROTOCOL.md](BLE_PROTOCOL.md) | BLE services, characteristics, button mapping, sensor encoding, connection state machine, identity trust model | §5, §18, §9.3 |
| [COMMANDS.md](COMMANDS.md) | All command contracts, input routing table, mode-aware button mapping | §11, §12 |
| [SAFETY.md](SAFETY.md) | Safety states, vacuum workflow, alert priorities, crash-safe defaults, sensor freshness, failure recovery | §17 |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | Ring buffers, JSONL log format, diagnostic screen, export bundle, privacy constraints, performance telemetry | §22, §23, §19 |
| [PRIVACY.md](PRIVACY.md) | Data protection, what is stored/never stored, Transparent Phone Mode privacy, telemetry policy, Google Play considerations | §2, §3.5, §22 |
| [SECURITY.md](SECURITY.md) | BLE trust model, high-risk command safeguards, parser hardening, OTA policy, release hardening, kill switches | §9 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Camera tiers, Android version matrix, BLE stability, OEM quirks, firmware compatibility, thermal behavior | §14, §24 |
| [SCENARIOS.md](SCENARIOS.md) | Scenario scripting language reference, available scenarios, writing guide, acceptance focus | §25, §29 |
| [TRACEABILITY.md](TRACEABILITY.md) | Code-to-spec mapping, deviation analysis, implementation status tracking | All sections |

---

## Key Reference Files

| File | Purpose |
|---|---|
| `Claude.md` | Product specification — the source of truth for requirements |
| `README.md` | Project overview, layout, running instructions |
| `TEST_PROTOCOL.md` | Test execution protocol and report format |
| `run-gradle.ps1` | PowerShell helper — auto-detects JDK and invokes Gradle wrapper |
| `scenarios/*.scenario` | Deterministic test scripts |

---

## Documentation Maintenance Rules

1. **Code changes → doc updates.** When code changes, update the relevant doc and the TRACEABILITY.md.
2. **Docs do not own behavior.** The code is the source of truth for how the system works. Docs describe and explain.
3. **TRACEABILITY.md is the audit trail.** It maps every code element to its spec requirement and flags deviations.
4. **No sensitive data in docs.** Follow the same privacy rules as the app — no passwords, screen text, etc.
5. **Date stamp updates.** Each document has a "Last updated" footer.

---

*Last updated: 2026-05-26*
