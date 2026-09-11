# ADR-0001: Survey Advisor ⊣ Water Infrastructure Governor architecture

## Status

Accepted. `cloud-itonami-cofog-06.3` promoted from `:blueprint` to
`:implemented`, following the verified fresh-scaffold protocol
established by prior actors in this fleet.

## Context

`cloud-itonami-cofog-06.3` publishes an OSS blueprint for an
independent water-infrastructure leak-detection contractor: a
pipe-crawler / acoustic leak-detection robot performs distribution-
network surveys under a governor-gated actor. Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest structural template is `cloud-itonami-isic-3091`
(motorcycle plant operations) for the dual verified/registered-gate
shape (segment + sensor unit here, batch + equipment there) and the
"one administrative logging op is phase-3 auto-eligible, everything
else always escalates" phase posture. The sensor-grounding discipline
-- a condition VERDICT must cite measured readings that actually cover
a required metric surface, never advisor self-attestation -- is
borrowed directly from `cloud-itonami-unspsc-27`'s `formation.
telemetry` (`grounds-verdict?`), adapted from a per-tool-class metric
surface to this domain's fixed acoustic+pressure survey surface (every
pipe segment, regardless of material, is surveyed the same way, unlike
a tool fleet's per-class sensor requirements).

This vertical has NO pre-existing `kotoba-lang/leaksurvey`-style
capability library to wrap (verified: no such repo exists). The domain
logic therefore lives as pure functions in `leaksurvey.registry` (and
the grounding logic in `leaksurvey.telemetry`), re-verified
independently by `leaksurvey.governor` -- the same "ground truth, not
self-report" discipline established across prior actors.

## Decision

### Decision 1: Self-contained domain logic (no external leak-detection capability library to wrap)

The segment/sensor-unit-verification, pipe-material-validity, and
flow-loss-plausibility functions live as pure functions in
`leaksurvey.registry`; the sensor-reading shape and grounding check
live in `leaksurvey.telemetry`. Both are re-verified independently by
`leaksurvey.governor`.

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of a water-
infrastructure leak-detection contractor's survey findings and repair
recommendations. It does NOT:
- Actuate a valve or main directly (any live-pressurized-main operation stays under human water-utility authority) -- a PERMANENT, unconditional block
- Make water-utility regulatory compliance determinations (that is the utility's/regulator's own authority; this actor logs measured leak/flow-loss data only)
- Assert a leak CONFIRMED/NO-LEAK verdict without citing sensor readings that actually cover the required metric surface -- an ungrounded condition claim is a HARD violation, the technical kernel of "never let an untrusted advisor hallucinate a leak finding"

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human survey-
coordinator approval.

### Decision 3: Sensor grounding for condition verdicts, exempting the honest "I don't know yet"

`:leak-survey` verdicts of `:confirmed`/`:no-leak` REQUIRE the
proposal's own cited `:sensor-basis` reading-ids to independently
resolve (via `leaksurvey.telemetry/grounds-verdict?`) to readings for
the SAME segment whose metrics cover BOTH `:acoustic-signature` AND
`:pressure-differential`. A `:needs-more-data` verdict is exempt --
requiring a basis for "I could not determine" would punish honesty,
the same posture `formation.telemetry`'s docstring establishes for
`:needs-data`.

### Decision 4: Repair recommendation requires an independently-verified confirmed leak, not the advisor's self-report

`:schedule-repair-recommendation` independently verifies TWO
ground-truth facts before it may commit: (a) the referenced
**segment**'s own `:verified?`/`:registered?` fields, and (b) that the
referenced leak-id's own COMMITTED `leaksurvey.store/leak-survey-of`
verdict is actually `:confirmed` for the SAME segment. A repair can
never be recommended against a leak that was never actually confirmed
on file -- the advisor's own claim that "the leak is confirmed" is
never trusted; only the store's own prior committed record counts.

### Decision 5: Critical-leak escalation -- always human sign-off, informed by (not claiming) Safe Drinking Water Act framing

`:escalate-critical-leak` ALWAYS escalates, never auto-commits. The
U.S. Safe Drinking Water Act (verified real via EPA's own published
program materials during this ADR's research) is the federal framework
governing drinking-water safety; a leak posing an acute risk (e.g.
negative-pressure contamination-ingress risk, or a major supply
disruption) is the kind of finding such a framework treats as urgent.
This governor's unconditional never-auto-resolved posture is the
software-side analog of routing that finding to the human water-safety
authority who actually holds compliance responsibility -- **not** a
claim that this software itself performs a Safe Drinking Water Act
determination, holds a utility operating license, or replaces the
utility's own engineering judgment.

### Decision 6: HARD invariants (no override)

Elaborated into ten concrete checks in `leaksurvey.governor`:
1. Segment record must be independently verified/registered before a repair is recommended against it
2. Proposals must be `:effect :propose` only (never direct valve control)
3. Direct valve/main control, or valve actuation, is permanently blocked
4. The op allowlist is closed -- `:log-pipe-condition`/`:leak-survey`/`:schedule-repair-recommendation`/`:escalate-critical-leak` only
5. A CONFIRMED/NO-LEAK leak-survey verdict must be grounded in cited sensor readings covering both required metrics
6. A repair recommendation must cite an on-file CONFIRMED leak record for the same segment
7. No fabricated `:material`, no physically implausible `:flow-loss-lpm`
8. No double-recommending the same repair

## Consequences

(+) Water-infrastructure leak-detection survey work now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world valve actuation requires human
sign-off, and no leak finding can ever be self-attested without
measured sensor grounding.

(+) Scope is bounded and verifiable: HARD invariants (elaborated into
ten concrete governor checks) protect against scope creep into
unauthorized valve actuation or an ungrounded/fabricated leak finding.
Critical-leak escalation is a circuit-breaker, not a threshold.

(-) Still a simulation/proposal layer, not a real SCADA/asset-
management control system. Valve actuation and utility compliance
filing remain human-/institution-controlled via external channels.

(-) No integration with real SCADA or utility-billing databases --
this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-cofog-06.3`: `kbb -M:test` green -- 74 tests, 202
  assertions, 0 failures, 0 errors (verified from a fresh checkout).
  Demo narrative (`kbb -M:dev:run`) exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, leak-verdict-ungrounded, segment-not-verified,
  leak-not-confirmed-on-file, valve-actuate-blocked,
  already-recommended, invalid-pipe-material, invalid-flow-loss).
- `kbb -M:lint` (clj-kondo): 0 errors, 0 warnings.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps`, so a bare `kbb -M:test` resolves offline
  inside the monorepo checkout.
