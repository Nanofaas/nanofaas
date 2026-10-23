# One-shot review remediation

> Execute inline with `superpowers:executing-plans`, as already authorized. The user requested fixes, not a campaign restart.

**Goal:** Correct the reviewed implementation without changing request ownership or misreporting bounded auctions as convergence.

**Architecture:** Route once before admission to a local queue. Dispatch queued work against the current physical allocation; epoch retirement never cancels accepted queued work. Physical in-flight leases survive transitions. Run the configured number of auction rounds on every participating node, then finalize feasible assignments. Use logical identities and monotonic deadlines for the clock-independent protocol.

**Spec:** The user's explicit queue rule and fixed-round choice supersede conflicting clauses in `../specs/2026-10-11-one-shot-clock-robustness-design-draft.md` and the previous clock-robustness plan. Existing memory/c1, generation, one-hop and physical-release invariants remain binding.

## Constraints and review focus

- No scientific campaign restart, push or merge in this remediation.
- No queued request is rerouted or rejected merely because its arrival epoch ended. Existing independent timeout/cancellation policies remain applicable.
- Already executing work retains its slot until positive physical release; new allocation cannot count that slot as free.
- Fixed round completion is not convergence. Missing phase closures and deadlines remain failures, never successful completed rounds.
- Preserve aggregate bid mathematics and the pinned Python fixtures.
- Graph impact before edits, RED/GREEN regression tests, complete graph change analysis before local commits.

## Task 1: Queue ownership and physical dispatch

Files: `InvocationTask`, `PlannedInvocationRoute`, `ExecutionRecord`, `ExecutionInputResources`, `ExternalDispatcher`, `ReplicaSlots`, `RoundRobinFunctionProxy`, their admission tests and `docs/one-shot-coordination.md`.

- [x] Add a failing actual HTTP regression: admit locally, retain in queue, rotate the same warm pool, dispatch once successfully using the current allocation. Verify no second routing decision, preserved input/dispatch ownership and no premature release of occupied slots.
- [x] Remove the recently added request-bound epoch token from local routes/tasks and HTTP dispatch. Keep receiver-issued quiesce/resume capabilities for allocation changes; they authorize management operations, not a queue lifetime.
- [x] Verify pending physical admission is closed during a transition and open against the current pool afterwards, regardless of request arrival epoch. Update conflicting tests/docs and run core/SPI/runtime/provider suites.

## Task 2: Fixed-round protocol

Files: `EpochCoordinator`, `EpochOutcome`, `ReplicaPlanActuator`, `OneShotOperations`, protocol/API tests and documentation.

- [x] Promote the failed two-triangle reproduction into a regression; add line and complete graph cases. Every healthy participant completes the configured rounds, including quiet nodes.
- [x] Remove local early termination. Finalize after the configured count and report explicit fixed-round completion distinct from convergence, deadline and missing messages; allow that finalized outcome to proceed to physical preparation.
- [x] Keep phase batching/idempotence and all identities; update timing qualification and result expectations without labeling a cutoff as measured convergence. Verify aggregate/unit reference parity and protocol fault tests.

## Task 3: Apply allocation changes at the current transition

Files: `ReplicaPlanActuator`, allocation/provider tests and transition documentation.

- [x] Replace the regression that approves a whole-epoch delay with one requiring the new allocation at its transition, even with the previous memory budget full.
- [x] Separate negotiation from physical transition. Close old incoming grants at retirement, drain only removed/reassigned physical replicas, then grow and install the current allocation. Preserve accepted queue contents and retained running work. Failed/slow drain is an explicit transition outcome, never fabricated release.
- [x] Verify the real provider boundary, unchanged warm pools, reductions before growth, failure paths and no double memory allocation.
- [x] User clarification: new original requests arriving during the physical allocation change select the local bounded queue if it has space, rather than falling back to cloud merely because routing is temporarily retired. Scope the exception to the live transition; retain ordinary input validation and queue limits.
- [x] User clarification: previous-epoch neighbor requests are eligible for ordinary local admission during and after transition when queue/resource conditions permit. Preserve metadata, current peer incarnation and calibrated workload validation; the expired grant/quota does not gate current admission. Dispatch waits for real current capacity; no second hop is introduced.

## Task 4: Complete clock-independent operation and integration

Continue the existing clock-robustness plan's logical session/epoch and monotonic timing work, incorporating the queue and fixed-round corrections above. Retain its RED tests until implemented; do not hide or remove them to obtain a green normal build.

- [x] Verify offset/jump independence through negotiation, activation, physical transitions and incoming grant validation, including manual and automatic triggers.
- [x] Update configuration/API/native reflection/docs and dependent NanoLab contracts before claiming the protocol is integrated. UTC remains diagnostic/correlation metadata.
- [x] Run relevant full suites, graph checks and a final whole-change review. Report software completion separately from the still-suspended scientific campaign.

## Decisions

- The Python reference at `71899f7` has a centralized simulation loop with global stopping predicates; that is not equivalent to the old Java neighbor-local early exit.
- The user explicitly chose a fixed number of rounds rather than introducing distributed global termination.
- The previous Task 1 completion remains historical verification of the old implementation; its queued epoch-permit semantics are superseded and must be corrected.

Execution update: queue ownership correction is committed at e6deeff0. Fixed-round protocol verified on two triangles, a line and a complete graph, with duplicate/loss and real P2P transport coverage. Ten-module regression: 1693 tests, zero failures/errors, three existing core skips. Two pending logical-epoch tests are excluded only by a temporary verification init script until Task 4 implements their APIs. No campaign qualification is implied.

Task 3/4 checkpoint: logical negotiation carries its monotonic timing into a
separate `NEGOTIATED`/explicit activation path. The actuator closes old routing,
pauses queued dispatch, drains removals before growth, obtains fresh pool
capabilities, then installs measured readiness before READY_ACK. New tests replace
the old whole-epoch-delay expectation, exercise real provider/physical-slot
boundaries and lost READY_ACK replies, and prevent quota refill on same-epoch
readiness updates. Public API, automatic initiation, NanoLab bindings, native
qualification and final review are still outstanding; the campaign stays paused.

Runtime checkpoint: v2 manual prepare/activate and explicit automatic session start are implemented. Monotonic neighbor initiation is tested on a line and two triangles, including duplicate/lost proposals, late-peer STATUS catch-up, stop/rearm and unproven physical drain. Full ten-module regression: 1720 tests, zero failures/errors, three existing core skips. NanoLab contracts/correlation, native qualification and the final whole-change review remain outstanding. An already-dispatched request racing proxy quiesce is still being checked; no campaign measurements have restarted.

Artifact clarification: the frozen campaign recipe is JVM SerialGC/-Xmx256m for the control-plane and Rust for the five functions. Preserve that recipe when qualifying protocol v2; inline/native execution is not permission to change compiler or GC. GraalVM compatibility and scientific JVM qualification must be reported separately.

Final software verification: fresh integrated review found a concurrent shrink
rejecting accepted dispatches and a missing terminal clock bracket in VM coverage.
Both have RED/GREEN regressions and fixes; parent-scope malformed probe accounting
is also corrected. Accepted transition waiters retain their original timeout and
acquire current capacity only after positive physical release. VM streams are
stopped and drained before terminal diagnostics and immutable coverage.
NanoFaaS: 1731 tests, zero failures/errors, three existing core skips.
NanoLab: 3911 general tests passed, 17 skipped; 637 real-checkout contracts and
51 TUI tests passed. Static/type/import checks and installed-wheel smoke passed.
This closes software remediation only. Scientific qualification, new frozen
campaign identity, observer overhead, pilot, measured runs and the final data
report remain outstanding; previous unqualified runs remain unchanged.
