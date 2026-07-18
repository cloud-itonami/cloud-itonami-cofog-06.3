# cloud-itonami-cofog-06.3

Open COFOG Blueprint for **COFOG 06.3**: Water supply.

This repository designs a forkable OSS business for an independent water
infrastructure leak-detection contractor: a pipe-crawler / acoustic
leak-detection robot performs distribution-network surveys under a
governor-gated actor, so a municipal water utility (or its contracted
inspector) keeps auditable leak and repair records instead of renting a
closed asset-management SaaS.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the Leak
Advisor and Water Infrastructure Governor described below do not exist
in code. It is not (yet) a governed Advisor⊣Governor actuation actor;
the Core Contract section specifies what that pipeline is intended to
enforce once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an acoustic/pressure leak-sensing
robot performs the distribution-network survey under an actor that
proposes a leak-repair recommendation and an independent **Water
Infrastructure Governor** that gates it. The governor never dispatches
hardware itself; `:high`/`:safety-critical` actions (such as operating near
live pressurized mains, or a leak posing a water-safety risk) require
human sign-off.

## Core Contract (design intent — not yet implemented)

```text
network segment survey + prior leak history
        |
        v
Leak Advisor -> Water Infrastructure Governor -> repair recommendation, or human sign-off
        |
        v
robot sensing actions (gated) + leak/repair record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated advisory will be able to dispatch a robot action the
governor refuses, suppress a leak record, or recommend deferring a
water-safety-critical repair without governor approval and audit
evidence — but none of that is enforced today.

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `06.3`). Required capabilities:

- :robotics
- :telemetry
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
