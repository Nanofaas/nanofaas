# One-shot Clock Robustness Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` in the native mode already requested by the user. Follow the tasks in order. The architecture proposal requires review before runtime implementation; this plan is not evidence of qualification.

**Goal:** Remove synchronized UTC as a precondition for safe one-shot admission, allocation and epoch transitions, then complete the original scientific campaign on freshly qualified artifacts.

**Architecture:** Logical session/epoch identities drive neighbour negotiation. Local monotonic durations bound work; receiver-owned fenced admission and positive physical drain protect capacity across staggered transitions. UTC remains observable metadata, with separate measurement uncertainty.

**Tech stack:** Java 25/Spring Boot/Reactor/native GraalVM; existing container physical-slot machinery; Python 3.12 NanoLab, Multipass, k6 and existing observability. No new runtime service or external dependency.

**Spec:** [Architecture proposal](../specs/2026-10-11-one-shot-clock-robustness-design-draft.md). This is an extension of [the approved campaign plan](2026-10-10-one-shot-multifunction-multipass.md), whose Tasks6/7 remain outstanding.

## Global constraints

- Keep five edge nodes, one terminal cloud, two triangles, five Rust functions, strict64MiB/swap0/c1, nine360s load windows and all planned-original denominators.
- No invocation retries, invented completions, pooled independent runs or relabeled old profiles. Preserve every failed run and error/reject count.
- No node compares nominal UTC epoch labels with local wall time to authorize admission, release or capacity transfer in v2.
- Use explicit session, epoch, generation and incarnation fencing. A timeout does not prove physical release.
- Native inline execution; local commits only, no push/merge without request.
- Refresh graph before edits, upstream impact for each edited symbol, warn HIGH/CRITICAL; UNKNOWN needs caller investigation. Complete detect-changes before every NF commit.
- The current refreshed graph supports explicit-symbol walks; FTS repair failed, so concept-query limitations remain recorded.

## Review focus

- A queued route selected before retirement must not dispatch after capacity is reused (Task1).
- Late physical completion after a timeout must retain its slot until matching release proof (Task1).
- Lost READY_ACK and partial preparation must not expose an unreserved route or replenish quotas (Task3).
- A late/restarted peer must not resurrect a previous session, incarnation or retired grant (Task2/3).
- Staggered activation and clock jumps must not silently move requests between scientific denominators (Task4).

## Task 1: Fenced reusable physical quiesce

**Files:** SPI `platform/libs/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane/{registry/ManagedReplicaControl.java,registry/ReplicaQuiesceToken.java,deployment/ManagedDeploymentProvider.java,deployment/ReplicaAdmissionToken.java,offload/PlannedInvocationRoute.java,scheduler/InvocationTask.java}`; `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/{registry/ManagedDeploymentCoordinator.java,service/ReactiveInvocationCoordinator.java,dispatch/ExternalDispatcher.java}`; `platform/libs/container-deployment-runtime/src/main/java/it/unimib/datai/nanofaas/containerdeployment/{ManagedFunctionProxy.java,ReplicaSlots.java,RoundRobinFunctionProxy.java,LocalManagedDeploymentProvider.java}`.

**Interface contract:** A lease/generation-fenced `pauseReplicaAdmission(ReplicaControlLease, ManagedDeploymentTarget)` returns a `ReplicaQuiesceToken` after old physical admission is closed. Strict `quiesceReplicas(ReplicaControlLease, ManagedDeploymentTarget, Duration)` returns one only after all occupied slots are positively drained; timeout leaves admission closed and ownership retained. The registry wrapper binds exact lease/target to a deployment-layer `ReplicaAdmissionToken`, avoiding a deployment-to-registry package cycle. `resumeReplicas(ReplicaControlLease, ManagedDeploymentTarget, ReplicaQuiesceToken, String admissionEpochToken)` succeeds once for the same ownership/generation/incarnations. An explicit `resumeRetainedReplicas` variant may rotate admission for unchanged allocations while retaining every occupied physical lease; it never reports occupied capacity as released. Unsupported providers fail before preparation. The opaque admission epoch token is pinned on LOCAL planned routes and propagated into actual managed dispatch; it cannot be recovered from public client-supplied headers.

- [ ] Graph-impact all mutation boundaries; add RED tests to existing `ReplicaDrainTest`, provider/coordinator and reactive invocation test suites. Pause a local dispatch between routing and proxy slot acquisition, retire its epoch, resume the same warm backend under a new token, then assert the old dispatch cannot execute. Assert actual resource reassignment with an already occupied slot blocks until matching RELEASED proof, even after504. For unchanged allocations assert admission rotation preserves that occupied slot and uses only free slots.
- [ ] Implement strict quiesce/resume and unchanged-allocation admission rotation at the same lock boundary as slot acquisition. Preserve warm containers and their incarnations. Unknown physical state, timeout, stale lease/token, backend replacement or partial pause returns failure with admission closed and ownership retained; no rollback opens an uncertain backend.
- [ ] Verify no actual container recreation for unchanged replicas, no early lease release and correct error/handlerStarted accounting. Keep downscale removal after positive drain and release-only handling of expired ownership.
- [ ] Run relevant control-plane/SPI/container-runtime/provider tests, complete graph detect-changes and commit `Fence warm replica admission across epoch transitions`.

## Task 2: Logical epoch identity and monotonic neighbour initiation

**Files:** `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/{coordination/EpochCoordinator.java,coordination/EpochTiming.java,coordination/AuctionCodec.java,api/OneShotOperations.java,api/OneShotSettings.java,api/OneShotController.java}`, module `OffloadConfiguration.java`, `platform/modules/offload/openapi.yaml`, control-plane configuration and `docs/one-shot-coordination.md` and `docs/one-shot.md`.

**Interfaces:** `EpochTiming` holds monotonic preparation/activation/expiry budgets and canonical UTC labels separately. Configuration v2 supplies bounded sessionId; explicit session start arms epoch0. Manual prepare carries a relative activation delay; activate(sessionId,epoch) is idempotent. Neighbour PROPOSE/STATUS carries exact session, canonical labels, epoch and incarnation, on v2 topics. Local monotonic timers propose the next epoch; accepting one valid neighbour proposal starts/forwards it once. At most one current/retiring epoch and one preparation exists.

- [ ] RED independent-clock tests: offsets +/-30s and UTC jumps +/-1h during solver and receive have identical decisions for identical monotonic time/message traces. Exhausted monotonic budgets still stop work. A duplicate proposal must not create a second auction; a different session/period/label is rejected.
- [ ] Replace wall-time permission predicates using explicit logical state and injected monotonic time. Keep `startsAt`/`endsAt` as hash-bound trace labels. Pin input/config revisions and profile/generation eligibility exactly as before; no successful API return may precede necessary logical state installation.
- [ ] Implement bounded peer initiation/status catch-up. A peer behind multiple epochs must close/drain previous admission before joining, record skips and require fresh grants. Restart changes incarnation; no old message starts execution. Partition/unavailable peers produce bounded degraded outcomes rather than implicit clock-health failure.
- [ ] Test manual and automatic initiation on a line and the two-triangle graph, reordered/dropped/duplicate control messages, stop/start cancellation and mixed v1/v2 peers. Update OpenAPI/config/docs, run offload and API contract suites, graph check and commit `Drive one-shot epochs with logical identities and monotonic budgets`.

## Task 3: Receiver-ready grants and safe plan transitions

**Files:** existing offload `oneshot/actuation/{ReplicaPlanActuator.java,ActiveRoutingPlan.java}` and `oneshot/routing/{PlanRouter.java,AssignmentAdmission.java}`; existing actuator/coordinator/routing tests; new `oneshot/actuation/EpochTransitionTest.java`.

**Consumes:** Task1 quiesce/resume/admission token and Task2 logical session/EpochTiming. **Produces:** an inbound grant installed before seller READY; a buyer route enabled only after matching READY_ACK receipt plus local activation. States IDLE/PREPARING/SELLER_READY/ACTIVE/RETIRING/DRAINED are explicit. Physical leases remain owned through uncertain retirement.

- [ ] RED: advance only the seller, delay old buyer traffic, and assert old grants cannot spend new capacity; already admitted old execution survives until physical drain. Lost ACK, partial prepare and expiry never free announced capacity or create an outbound route.
- [ ] Quiesce old admission before resource changes, drain reductions before growth, preserve total strict memory budget, rotate retained warm replicas to the new admission token without releasing occupied leases; drain only resources actually removed or reassigned. Install seller inbound grants before READY; receiving buyer ACK is idempotent and checks all identities.
- [ ] Preserve quota objects through readiness updates. New assignment buckets start empty; per-function aggregate tokens are preserved/clamped on rate changes. Assert repeated READY and activation updates cannot replenish a burst. Keep c1 and one-hop/terminal-cloud constraints.
- [ ] Test stalled physical proofs, stale generations, lease expiry, failed downscale/upscale, peer incarnation change, malformed/replayed grants and UTC jumps in every transition state. Run offload plus dispatch/provider regression suites, graph check and commit `Activate receiver-owned grants without UTC synchronization`.

## Task 4: Scientific driver and independent temporal correlation

**NanoLab files:** `packages/nanolab/src/nanolab/{one_shot/client.py,config/one_shot.py,tasks/one_shot/clock.py,tasks/one_shot/qualification.py,tasks/one_shot/experiment.py,tasks/one_shot/resources.py,one_shot/report.py}` and `packages/nanolab/src/nanolab/one_shot/vm_resources.py` (reuse the existing sampler). Existing one-shot tests and docs/presets.

- [ ] RED: prepare/activate all edges by explicit session/epoch; a node UTC offset or jump does not abort protocol execution or change ORACLE epoch inputs. Delayed activation remains an observed reject/fallback, included in its original planned360s trace window; EWMA never receives future inputs.
- [ ] Replace the scientific monitor's synchronization prerequisite with retained UTC/monotonic correlation observations. Preserve raw guest UTC, monotonic time, all probe attempts, exceptions and uncertainty. Use host/guest monotonic timestamp brackets to align resource samples; never invent an exact offset from RTT. A interval crossing a trace boundary carries ambiguous-boundary status, not a silently assigned epoch.
- [ ] Retain nominal load epoch and actual native routing epoch separately; function/replica identity stays exact. Plot all nodes and container CPU/RAM/throttling with uncertainty/missing samples. Time correlation quality and protocol outcome are independent report fields.
- [ ] Verify1Hz coverage through warmup/nine windows, clock jumps, delayed probes, missing samples, failed runs, counts/percentages of every error/reject/unresolved original and lossless archive hashes. Run full CI scope with pinned source contracts, both workspace packages, all hooks and installed-wheel CLI/preset smoke. Commit `Correlate logical one-shot epochs with uncertain resource timestamps`.

## Task 5: Native integration, new freeze and original campaign completion

- [ ] Native-build the new protocol and perform multi-node real HTTP control/dispatch tests with injected wall-clock offsets and jumps. Prove physical stale-dispatch rejection and warm replica continuity, not only mocked actuator states. No host/VM system-clock mutation is required.
- [ ] Confirm no unsupported provider or legacy protocol qualifies v2. Archive commands, source/images/recipe/protocol and observer fingerprints, successful tests and failure originals. Quantify observer overhead including raw probe collection.
- [ ] Return to the original Task6 with three fresh independent VM observer pairs and logging pairs, strict/mixed physical calibration,450 uncensored timing samples, actual-trace observer pairs, six pilot triplets, failLoad and frozen main sample size. Prior v11 remains unqualified failed evidence.
- [ ] Complete original Task7 main blocks and report, original request accounting, horizontal/vertical offload proof, measured VM saturation versus allocation occupancy, raw/hash-verified retention, cleanup and final independent native review. Do not mark the campaign complete from software tests or a single successful gate.

## Self-review and execution state

All protocol changes are coupled by the admission fence and logical identity; no isolated UTC predicate removal is considered a completed task. Tasks1–3 preserve mathematical auction inputs/bid aggregation while changing operational timing/commitment semantics. Task4 preserves the scientific estimand and exposes transition outcomes. Task5 includes every remaining original gate and report requirement.

The diagnostic-only NanoLab change is complete locally at b66cc9d7fbb77fdf4d767f76a87d3511f984c528; it is not Task2/3 implementation. Runtime admission primitives, SPI/provider/core ownership bridge and LOCAL task/dispatch propagation are implemented locally. Full Task 1 verification is recorded in the native execution ledger; protocol v2 remains outstanding. Architecture transition rulings are recorded in the native execution ledger. This partial implementation does not qualify protocol v2 or the campaign.
