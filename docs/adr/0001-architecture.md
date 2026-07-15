# ADR-0001: KnittingAdvisor ⊣ Knitting Mill Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1391` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1391` publishes an OSS blueprint for knitting/
crocheting-mill **operations coordination** (production-batch
circular/flat-knitting or crocheting output and yardage/fabric-weight/
defect-rate data logging, circular/flat-knitting-machine or
crocheting-line maintenance scheduling, materials-safety/equipment-
safety concern flagging, and outbound fabric shipment coordination).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1520` (Manufacture of
footwear): both are back-office plant-operations-coordination actors
with real physical-safety-relevant equipment (circular/flat-knitting
machines and crocheting lines vs. cutting presses/sewing machines/
sole-molding presses), production-batch tracking with quality/grade
data, and equipment maintenance scheduling with a permanent block on
directly operating that equipment. This build mirrors 1520's module
shape (advisor ⊣ governor ⊣ phase ⊣ store, four ops, `MemStore`-only
backend) closely, substituting knitting/crocheting-specific ground
truth: `volume-yards` in place of `volume-pairs`, `fabric-weight-gsm`
in place of an equivalent footwear finish-quality field, and a
permanent `:direct-operate?` block in place of 1520's own
`:direct-operate?` block -- both are a proposal-level field that, if
set true, attempts to bypass "propose/schedule a DRAFT" and reach
actual equipment operation. `:flag-safety-concern` (materials-safety/
equipment-safety) replaces 1520's `:flag-quality-concern` (materials-
defect/labor-safety/labeling-compliance) -- a narrower, but equally
always-escalating, concern-flagging surface as specified for this
vertical.

This vertical has NO pre-existing `kotoba-lang/knitting`-style
capability library to wrap (verified: no such repo exists). This
build therefore uses self-contained domain logic -- pure functions in
`knittingops.registry` (equipment/batch verification, shipment-yardage
recompute, quality-grade validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-1520`'s `footwearops.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:knitting-mill-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "knitting-mill-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

Regulatory context (informational, not enforced in code): knitted and
crocheted fabric manufacturing is subject to fiber/textile-content and
labor-standards regimes in multiple jurisdictions -- e.g. the US
Textile Fiber Products Identification Act (fiber-content labelling),
EU Regulation (EU) No 1007/2011 (textile fibre names and related
labelling), and Japan's 家庭用品品質表示法 (Household Goods Quality
Labeling Act) covering textile products. `:flag-safety-concern`'s
`:materials-safety`/`:equipment-safety` concern-types exist to surface
exactly this class of issue to a human for review -- this actor does
not itself adjudicate compliance.

## Decision

### Decision 1: Self-contained domain logic (no external knitting capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
knitting/crocheting vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-yardage / quality-
grade / defect-rate validation functions live as pure functions in
`knittingops.registry` and are re-verified independently by
`knittingops.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1520`'s `footwearops.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of knitting/
crocheting-mill operations. It does NOT:
- Control circular-knitting, flat-knitting, or crocheting-line equipment directly
- Make mill-safety, labor-safety, or materials-safety decisions (exclusive to the human mill supervisor)
- Directly operate knitting/crocheting-line equipment under any proposal

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human mill-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: knitting/crocheting-mill manufacturing
carries real physical-safety and labor-standards dimensions (needle/
cylinder injury risk on circular and flat-knitting machines, moving-
yarn and tension-mechanism entanglement risk on crocheting lines,
repetitive-strain labor conditions, materials-defect and equipment-
safety risk). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (materials-safety or equipment-safety concern)
ALWAYS escalates, never auto-commits. This is not a "low-stakes
proposal" — it is a circuit-breaker that must reach human authority,
deliberately broad enough to cover both a defective-yarn/fabric
materials finding and a machine-condition equipment finding
simultaneously.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Unlike a single-entity-gated vertical, this vertical has TWO entity
kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "mill/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date yardage plus
the proposal's own claimed yardage would exceed the batch's own
recorded production yardage -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`knittingops.governor`, mirroring `cloud-itonami-isic-1520`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Mill/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's yardage must independently recompute within the batch's own logged production yardage
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct knitting/crocheting-line-equipment control (`:direct-operate? true`) is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Knitting/crocheting-mill operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human mill-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation. Safety concerns are a
circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real mill-operations
control system. Equipment actuation remains human-controlled via
external channels.

(-) No integration with real mill-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1391`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded, direct-operate-
  blocked, already-scheduled, invalid-grade, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
