# cloud-itonami-isic-2591: Forging, pressing, stamping and roll-forming of metal; powder metallurgy

Open Business Blueprint for **ISIC Rev.5 2591**: forging, pressing, stamping and roll-forming of metal; powder metallurgy — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **forging/pressing/stamping/roll-forming and powder-metallurgy plant operations**: production-batch data logging (product-category/weight/defect-rate), forging-press/stamping-press/roll-forming-mill/powder-metallurgy-press-and-sinter-line maintenance scheduling, safety-concern flagging, and outbound forged/pressed/stamped/roll-formed/powder-metallurgy part shipment coordination.

This repository designs a forkable OSS business for forging/pressing/
stamping/roll-forming and powder-metallurgy plant operations: run by a
qualified operator so a plant keeps its own operating records instead
of renting a closed SaaS.

## Scope: the primary metal-forming process shop, not downstream products or surface treatment

ISIC 2591 covers the plant that forges, presses, stamps, roll-forms or
powder-sinters metal stock (billets, blanks, coil, metal powder) into
near-net-shape parts — typically a job shop/subcontractor supplying
downstream manufacturers. This is distinct from
`cloud-itonami-isic-2592` (Treatment and coating of metals), a
distinct surface-finishing/heat-treatment process; from
`cloud-itonami-isic-2593` (Manufacture of cutlery, hand tools and
general hardware), the specific downstream shop that forges/grinds/
heat-treats/finishes stock into a named consumer product family; and
from `cloud-itonami-isic-2599` (Manufacture of other fabricated metal
products n.e.c.), other specific downstream end-products not
elsewhere classified. This actor's own hazard profile centers on
forging-press/stamping-press pinch/crush hazard, high-temperature-
forging burn/radiant-heat exposure, roll-forming-mill entanglement
hazard, and powder-metallurgy combustible-metal-dust/inhalation
exposure.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — forging/pressing/stamping/roll-forming batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — forging-press/stamping-press/roll-forming-mill/powder-metallurgy-press-and-sinter-line maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety (press-pinch/crush, high-temperature-forging burn) concern (always escalates)
- `:coordinate-shipment` — outbound forged/pressed/stamped/roll-formed/powder-metallurgy part shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-relevant domain**
(forging-press/stamping-press pinch/crush hazard, high-temperature-
forging burn/radiant-heat exposure, roll-forming-mill entanglement
hazard, powder-metallurgy combustible-metal-dust/inhalation exposure):

- Does NOT control the forging press, stamping press, roll-forming mill or powder-metallurgy press-and-sinter line directly
- Does NOT make plant-safety or materials-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the forging press, stamping press, roll-forming mill or powder-metallurgy press-and-sinter line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`metalforming.operation/build`, a langgraph-clj StateGraph):
1. **`metalforming.advisor`** (sealed intelligence node, `MetalFormingAdvisor`): proposes decisions only, never commits
2. **`metalforming.governor`** (independent, `Metal Forming Plant Operations Governor`): validates against domain rules, re-derived from `metalforming.registry`'s pure functions and `metalforming.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct forging/pressing-line-equipment control)
     - Any proposal touching forging/pressing-line-equipment control is a hard, permanent block (`:actuate-forge-press-line? true` on a `:schedule-maintenance` proposal)
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`metalforming.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`metalforming.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

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

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
