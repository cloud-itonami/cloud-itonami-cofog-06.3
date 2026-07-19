# cloud-itonami-cofog-06.3

Open COFOG Blueprint (implemented actor) for **COFOG 06.3**: Water
supply.

This repository publishes a forkable OSS business for an independent water
infrastructure leak-detection contractor: a pipe-crawler / acoustic
leak-detection robot performs distribution-network surveys under a
governor-gated actor, so a municipal water utility (or its contracted
inspector) keeps auditable leak and repair records instead of renting a
closed asset-management SaaS.

**Maturity: `:implemented`** — Survey Advisor ⊣ Water Infrastructure
Governor as a langgraph StateGraph (`intake → advise → govern → decide
→ commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-isic-3091`'s (motorcycle plant operations) dual
verified/registered-gate shape and `cloud-itonami-unspsc-27`'s
(`formation.telemetry`) sensor-grounding discipline: a leak-survey
CONFIRMED/NO-LEAK verdict must cite measured readings covering both
required metrics, never advisor self-attestation. 74 tests / 202
assertions green, `clj-kondo` 0 errors / 0 warnings. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an acoustic/pressure leak-sensing
robot performs the distribution-network survey under an actor that
proposes a leak-repair recommendation and an independent **Water
Infrastructure Governor** that gates it. The governor never dispatches
hardware itself; `:high`/`:safety-critical` actions (such as operating near
live pressurized mains, or a leak posing a water-safety risk) require
human sign-off.

## What this actor does

Proposes **survey/repair coordination**, not valve operation:
- `:log-pipe-condition` — pipe-segment material/install-year data logging (administrative, not an operational decision)
- `:leak-survey` — leak-survey verdict (CONFIRMED/NO-LEAK/NEEDS-MORE-DATA), grounded in cited sensor readings for CONFIRMED/NO-LEAK
- `:schedule-repair-recommendation` — repair-recommendation scheduling proposal against a segment with an on-file confirmed leak
- `:escalate-critical-leak` — surface an acute water-safety/pressure-loss concern (always escalates)

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY**:

- Does NOT actuate a valve or main directly (any live-pressurized-main operation stays under human water-utility authority) — a PERMANENT, unconditional block
- Does NOT self-issue a water-utility regulatory compliance filing (that is the utility's/regulator's own authority; this actor logs measured leak/flow-loss data only)
- Does NOT assert a leak CONFIRMED/NO-LEAK verdict without citing sensor readings that actually cover the required acoustic + pressure metric surface — an ungrounded condition claim is a HARD violation
- ONLY proposes/coordinates survey and repair-recommendation back-office work; all valve actuation requires explicit human authority

## Core Contract

```text
network segment survey + prior leak history
        |
        v
Survey Advisor -> Water Infrastructure Governor -> repair recommendation, or human sign-off
        |
        v
robot sensing actions (gated) + leak/repair record + audit ledger
```

No automated advisory can dispatch a robot action the governor would
refuse, suppress a leak record, or recommend deferring a
water-safety-critical repair without governor approval and audit
evidence.

## Architecture

Classic governed-actor pattern (`leaksurvey.operation/build`, a langgraph-clj StateGraph):
1. **`leaksurvey.advisor`** (sealed intelligence node, `Survey Advisor`): proposes decisions only, never commits
2. **`leaksurvey.governor`** (independent, `Water Infrastructure Governor`): validates against domain rules, re-derived from `leaksurvey.registry`'s pure functions, `leaksurvey.telemetry`'s sensor-grounding logic, and `leaksurvey.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Segment record must be independently verified/registered (`:verified?` AND `:registered?`) before any repair is recommended against it
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct valve/main control)
     - Directly actuating a valve/main (`:actuate-valve? true`) is a PERMANENT, unconditional block
     - A CONFIRMED/NO-LEAK leak-survey verdict must be grounded in cited sensor readings covering BOTH `:acoustic-signature` and `:pressure-differential` (`:needs-more-data` is exempt)
     - A repair recommendation must cite an on-file CONFIRMED leak record for the SAME segment
     - No double-recommending the same repair
     - No fabricated `:material` value on a pipe-condition patch
     - No physically implausible `:flow-loss-lpm` value on a leak-survey finding
   - ESCALATE (always human sign-off, overridable by a human):
     - `:escalate-critical-leak` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`leaksurvey.phase`** (Phase 0->3 rollout): `:leak-survey`/`:schedule-repair-recommendation`/`:escalate-critical-leak` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-pipe-condition` may auto-commit at phase 3 when clean
4. **`leaksurvey.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `06.3`). Required capabilities:

- :robotics
- :telemetry
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md),
[`docs/operator-guide.md`](docs/operator-guide.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## License

AGPL-3.0-or-later.
