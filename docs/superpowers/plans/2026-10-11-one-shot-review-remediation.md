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

- [ ] Add a failing actual HTTP regression: admit locally, retain in queue, rotate the same warm pool, dispatch once successfully using the current allocation. Verify no second routing decision, preserved input/dispatch ownership and no premature release of occupied slots.
- [ ] Remove the recently added request-bound epoch token from local routes/tasks and HTTP dispatch. Keep receiver-issued quiesce/resume capabilities for allocation changes; they authorize management operations, not a queue lifetime.
- [ ] Verify pending physical admission is closed during a transition and open against the current pool afterwards, regardless of request arrival epoch. Update conflicting tests/docs and run core/SPI/runtime/provider suites.

## Task 2: Fixed-round protocol

Files: `EpochCoordinator`, `EpochOutcome`, `ReplicaPlanActuator`, `OneShotOperations`, protocol/API tests and documentation.

- [ ] Promote the failed two-triangle reproduction into a regression; add line and complete graph cases. Every healthy participant completes the configured rounds, including quiet nodes.
- [ ] Remove local early termination. Finalize after the configured count and report explicit fixed-round completion distinct from convergence, deadline and missing messages; allow that finalized outcome to proceed to physical preparation.
- [ ] Keep phase batching/idempotence and all identities; update timing qualification and result expectations without labeling a cutoff as measured convergence. Verify aggregate/unit reference parity and protocol fault tests.

## Task 3: Apply allocation changes at the current transition

Files: `ReplicaPlanActuator`, allocation/provider tests and transition documentation.

- [ ] Replace the regression that approves a whole-epoch delay with one requiring the new allocation at its transition, even with the previous memory budget full.
- [ ] Separate negotiation from physical transition. Close old incoming grants at retirement, drain only removed/reassigned physical replicas, then grow and install the current allocation. Preserve accepted queue contents and retained running work. Failed/slow drain is an explicit transition outcome, never fabricated release.
- [ ] Verify the real provider boundary, unchanged warm pools, reductions before growth, failure paths and no double memory allocation.

## Task 4: Complete clock-independent operation and integration

Continue the existing clock-robustness plan's logical session/epoch and monotonic timing work, incorporating the queue and fixed-round corrections above. Retain its RED tests until implemented; do not hide or remove them to obtain a green normal build.

- [ ] Verify offset/jump independence through negotiation, activation, physical transitions and incoming grant validation, including manual and automatic triggers.
- [ ] Update configuration/API/native reflection/docs and dependent NanoLab contracts before claiming the protocol is integrated. UTC remains diagnostic/correlation metadata.
- [ ] Run relevant full suites, graph checks and a final whole-change review. Report software completion separately from the still-suspended scientific campaign.

## Decisions

- The Python reference at `71899f7` has a centralized simulation loop with global stopping predicates; that is not equivalent to the old Java neighbor-local early exit.
- The user explicitly chose a fixed number of rounds rather than introducing distributed global termination.
- The previous Task 1 completion remains historical verification of the old implementation; its queued epoch-permit semantics are superseded and must be corrected.
