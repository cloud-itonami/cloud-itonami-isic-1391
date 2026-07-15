# cloud-itonami-isic-1391: Manufacture of knitted and crocheted fabrics

Open Business Blueprint for **ISIC Rev.5 1391**: manufacture of knitted and crocheted fabrics — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office knitting/crocheting-mill **operations**: production-batch data logging (circular/flat-knitting or crocheting output, yardage, fabric weight, and output quality), circular/flat-knitting-machine or crocheting-line maintenance scheduling, materials-safety/equipment-safety concern flagging, and outbound fabric shipment coordination.

This repository designs a forkable OSS business for knitting/crocheting-mill
operations: run by a qualified operator so a knitting mill keeps its
own operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not machine operation:
- `:log-production-batch` — circular/flat-knitting or crocheting batch, yardage/fabric-weight, and output-quality (grade/defect-rate) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — circular/flat-knitting-machine or crocheting-line maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound fabric shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY** (circular knitting machines, flat/V-bed knitting machines, crocheting lines; materials-handling and equipment-safety hazards):

- Does NOT control circular-knitting, flat-knitting, or crocheting-line equipment directly
- Does NOT make mill-safety, labor-safety, or materials-safety decisions (that's the mill supervisor's exclusive human authority)
- Does NOT directly operate knitting/crocheting-line equipment under any proposal (permanently blocked, see Architecture)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`knittingops.operation/build`, a langgraph-clj StateGraph):
1. **`knittingops.advisor`** (sealed intelligence node, `KnittingAdvisor`): proposes decisions only, never commits
2. **`knittingops.governor`** (independent, `Knitting Mill Operations Governor`): validates against domain rules, re-derived from `knittingops.registry`'s pure functions and `knittingops.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Mill/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct knitting/crocheting-machine control)
     - Directly operating knitting/crocheting-line equipment (`:direct-operate? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped yardage past its own logged production yardage (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:quality-grade` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`knittingops.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`knittingops.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

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
