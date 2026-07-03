# cloud-itonami-cofog-06.3

Open COFOG Blueprint for **COFOG 06.3**: Water supply.

This repository designs a forkable OSS business for an independent water
infrastructure leak-detection contractor: a pipe-crawler / acoustic
leak-detection robot performs distribution-network surveys under a
governor-gated actor, so a municipal water utility (or its contracted
inspector) keeps auditable leak and repair records instead of renting a
closed asset-management SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an acoustic/pressure leak-sensing
robot performs the distribution-network survey under an actor that
proposes a leak-repair recommendation and an independent **Water
Infrastructure Governor** that gates it. The governor never dispatches
hardware itself; `:high`/`:safety-critical` actions (such as operating near
live pressurized mains, or a leak posing a water-safety risk) require
human sign-off.

## Core Contract

```text
network segment survey + prior leak history
        |
        v
Leak Advisor -> Water Infrastructure Governor -> repair recommendation, or human sign-off
        |
        v
robot sensing actions (gated) + leak/repair record + audit ledger
```

No automated advisory can dispatch a robot action the governor refuses,
suppress a leak record, or recommend deferring a water-safety-critical
repair without governor approval and audit evidence.

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
