# P19 matrix inventory — read-only assessment

Date: 2026-09-11. Repository: /home/michele/Documenti/nanofaas.
Inspected HEAD: `6d08303371d803f44187ec5f4e37827d54fec597`.
Status: INVENTORY COMPLETE; P19 acceptance NOT established by this inventory.

Only this requested report is written. No test suite, build, load test, reindex, container/VM lifecycle operation, or subagent was run. Commands below are a reviewable execution catalog, not new green results. “Covered” means an existing test exercises the property; a fresh run with its actual counts/results is still required.

## Evidence and scope

Read the P19 brief first, then plan Section 8 and relevant prior task-report evidence: P00; P02–P08 including P07b–e; the lowercase `task-p09-report.md`; P10–P18 including P16a/P16b; P20a; the campaign progress ledger and lifecycle experiment STATO. Also inspected test declarations, selected test bodies, module descriptors and build configuration. P09's lowercase report is a partial slice: its old open completion-generation concern is subsequently closed in lifecycle STATO and `CompletionMetricsGenerationFenceRegressionTest`; offload generation coverage is strengthened by P07c. Do not carry those superseded concerns forward as known bugs.

P20a is explicitly a prerequisite. Its generation primitives and tests exist. P20b/P21/P22 and their final architecture acceptance are later work; P19 must freeze the corrected pre-SPI behavior, not require a future SPI artifact.

Prior reports record green corrections for every original R regression. P16b reports Java 143, Java-lite 81, warm-echo 6, Python 139, JavaScript 80, Go normal/race/vet and runtime-backed corpus verification. These counts belong to their recorded runs and cannot be asserted as current P19 totals. Earlier R6/R8 known-red exceptions are historical only and are forbidden at P19.

GitNexus: the repository wrapper attempted npm resolution and failed with EAI_AGAIN. Its bounded context fallback produced no result before timeout. The already cached 1.6.11 CLI successfully queried lifecycle ownership, identifying queue-to-capacity flows, generation lifecycle tests, ResourceOwner and offload meter owners. It reports two commits behind HEAD (index revision `7f8f4c2e`). No reindex was run because this task is read-only. Pre-write impact on this new report returned UNKNOWN/target not found: callers/processes are unresolved, not zero. Filesystem/text checks confirmed the report did not already exist and no existing report references it; no function/class/method is edited. This is not a production blast-radius claim.

Protected pre-existing state: modified overload-path STATO.md and run-queue-2.out; six untracked GitNexus skill directories; untracked `ReplicaStatusSnapshotConfigurationTest.java`. That untracked test is deliberately not baseline evidence. Any later build from this checkout must record dirt and ensure the frozen artifact/test manifest does not silently incorporate uncommitted test additions.

## R1–R8 exact regression matrix

Java packages actually begin with `it.unimib.datai.nanofaas`, despite the generic repository guideline's com.nanofaas example. Exact FQCNs and source paths are in the command catalog below.

| Requirement | Existing primary regression and exact method | Additional evidence / required profile |
|---|---|---|
| R1 conservative outcome weight/count/byte cap | execution.R1OutcomeByteBudgetRegressionTest.deeplyNestedAndWidePayloadsDoNotSilentlyFitTheOutcomeByteBudget | G1 + G2: OutcomeWeigher, OutcomeWeightBudget, ExecutionStoreEviction; T2 configured HTTP adds shapes/replay. |
| R2 archive/key/replay publication race | service.R2ArchiveEvictionReplayRegressionTest.replayDuringOutcomeEvictionAndArchiveDoesNotBecomeANewExecution | G1 + G2: terminal transition, retention and key lifetime; no redispatch after eviction. |
| R3 isolated waiter deadline | service.R3WaiterTimeoutSharedOutcomeRegressionTest.shortWaiterTimeoutDoesNotTurnTheSharedSuccessIntoATimeoutForReplays | G1 + G3 + G9: P05WaiterTimeoutSharedOutcome, P07d divergent deadlines, real offload waiter-budget tests. |
| R4 offload terminal completion | service.R4OffloadCompletionAfterWaiterTimeoutRegressionTest.offloadedResultArrivingAfterAShortWaiterTimeoutStillConcludesTheLocalAttempt | G1 + G2 + G3 + G9: terminal error/metric failure, synchronous offload failure, physical remote drain. |
| R5 no release of an unowned sync slot | syncqueue.R5DirectCompletionUnownedSlotRegressionTest | G6 with sync selected; both modules' QueueLeaseGenerationTest plus G3 prove old/new generation and retry ownership. |
| R6 recoverable partial deprovision | containerdeploymentprovider.R6DeprovisionFailureOwnershipRegressionTest.failedDeprovisionDoesNotLeakAnUntrackedProxy | G14 + G15: partial removal, retry/discovery, proxy close, pending-removal HTTP and persistent recovery. Real Docker lifecycle remains a separate prerequisite. |
| R7 atomic maxKeys | execution.R7KeyBudgetAtomicityRegressionTest.concurrentDistinctClaimsNeverExceedTheConfiguredKeyBudget | G1 + G2: IdempotencyKeyBudget/Lifetime and admission rollback. |
| R8 bounded historical names/replica entries and offload meters | service.R8HistoryCleanupRegressionTest.removedFunctionNamesDoNotAccumulateAfterChurn and .invalidatedReplicaTargetsAreRemovedNotJustCleared | G1 + G3 + G5 + G6 + G9 + G11: CompletionMetricsGenerationFence, P07c late offload success/failure, queue meter cleanup, refresh fencing. R8 alone does not cover all meter owners. |

## T1–T9: what exists and what it actually establishes

| Scenario | Existing executable evidence | Remaining P19 evidence |
|---|---|---|
| T1 fixed-function SYNC unkeyed | P07ConfiguredHttpCalibrationTest.t1CoreOnlySyncUnkeyedThroughConfiguredHttpRuntime, profile none; G3/G4 admission and accounting | Real configured HTTP exists (100 warm-up, 1,000 measured requests), with mocked LOCAL downstream. Existing output is p50/p95 and process-wide allocation deltas; not p99, post-GC heap, standalone runtime cost or repeated comparative production baseline. |
| T2 keyed ASYNC shapes | P07ConfiguredHttpCalibrationTest.t2AsyncKeyedShapesThroughConfiguredQueueHttpRuntime, async-queue; G1/G2/G5 | Flat/deep/wide/large, refusal resources, sampled populations, drain and no replay redispatch exist. Need useful completion throughput, separate rejected/accepted latency distributions, p99 and repeat/heap protocol. Its latency array includes refused requests and allocations are per admitted request, not necessarily a general per-success measurement. |
| T3 same key / divergent waiters / offload | G1/G3/G9, especially P07dWaiterAdmissionTest.divergentDeadlinesDetachOnlyTheShortWaiter and .massivePendingReplaySaturatesWaitersWithoutRedispatchOrRetainedRejections | Combine offload-only and compatible queue selections; archive, shared future, waiter and physical owner evidence must be captured together in finite HTTP profiles. |
| T4 backend slow/error, retry, toggle, expiry | G3/G5/G6/G7/G8; DispatchLifecycleAndCancellationTest; ReviewLifecycleGateTest | Existing real HTTP subscription cancellation and event-controlled LOCAL worker tests are stronger than isCancelled alone. Need a common short-profile result with counters before fault, during fault and after physical drain. |
| T5 churn / suspended old callbacks | G3/G5/G6/G9/G10/G14/G15; R8 | Many deterministic churn checks exist. Need one recorded configured process timeline for names, retiring generations, meter cardinality, endpoint pools and proxies, including recovery policy. |
| T6 blocked replica provider / wake-ups | G11/G12/G13/G16 | Bounded refresh and independent-loop tests exist. Repeat consumer selections separately and together; mock Kubernetes evidence does not establish real cluster lifecycle. |
| T7 large bodies/callbacks and slow clients | G4/G10/G14/G17/G18 + Python/Go/JS commands | Real per-hop ingress/proxy/SDK tests exist. Need integrated cross-hop short-profile measurements and attribution; no claim of bounding remote RSS. |
| T8 repeated runtime timeout / start-stop | G17/G18 + runtime-backed SDK corpus and Python/Go/JS suites | Existing physical-handler ownership, stop/restart, owned-versus-injected executor/client tests. Require function-process resource evidence separated from Gradle/control plane; keep noncooperative work visible after bounded stop. |
| T9 drain after every case | G2–G18 owner assertions, configured T1/T2 cleanup, SDK corpus final counters | No single existing command proves every population for every T1–T8 process profile. Capture complete before/peak/drain/post-retention snapshots and explain permitted residual caches/containers. |

The P07Calibration.java factory/store harness is historical microbenchmark evidence. Its reported throughput -35.1%, p50 +103.0%, p95 +42.1% and allocation/op +129.4% are a disclosed concern, explicitly marked non-comparable to HTTP in the fix report. Do not relabel those values as current HTTP regressions or acceptance thresholds. P19 still owns the measured decision.

P14's exact-estimator/bucket decision has a standalone reproducible harness and output; buckets were rejected because the boundary changed admission. P15's FunctionCatalogCostMeasurementTest measures real temporary-file snapshot operations at catalog sizes 1/100/1,000. Snapshot persistence was retained by the task's measured decision. Its single-operation/multi-sample stability limitation is a measurement concern, not an unimplemented persistence redesign.

## Population ownership checklist

The following populations must be collected separately. A zero live-record count does not imply physical drain.

| Population / unit | Ownership and release rule | Existing assertion sources |
|---|---|---|
| Live records and shared futures / count | admission publishes; the single terminal owner settles future, key and record; failures roll back | G1/G2; ReviewLifecycleGateTest.metricFailureMustNotLeaveATerminalExecutionLiveWithPendingFuture |
| Protected keys incl. pending claims/tombstones / count | atomic claim; abandonment or expiry releases; outcome eviction never authorizes redispatch | G1/G2 |
| Outcomes / count and conservative bytes | bounded weight/cache policy; expiry/eviction independent of protected key | G1/G2, configured T2 |
| Logical execution reservations / global and per-generation count | retained admission owner; retry/replay do not create duplicate execution ownership | G3, InvocationQuotaLifecycleIntegrationTest.replayAndRetryReuseTheExistingCanonicalReservation |
| Canonical input / bytes | retained by actual logical/queue/physical owners; administrative terminal alone cannot free it | G3, nonCooperativeLocalWorkKeepsInputChargedAfterAdministrativeTerminalUntilRawDrain and administrativelySettledQueueEntryKeepsCanonicalInputUntilTheEntryIsRemoved |
| Physical input copies / bytes | attempt/offload/deployment work retains until raw completion, including cancellation before wake-up/handle publication | G3 P07cReviewFixTest; G6 P07cInputBackpressureTest |
| Waiters, waiter timers/subscriptions / count | each attachment reserves; timeout/cancel releases that waiter only, including replay | G3 P07dWaiterAdmissionTest, G9 |
| Dispatch slots and physical workers / count | acquired attempt/generation lease; release on actual work exit; retry acquires new lease | G3/G5/G6; ReviewLifecycleGateTest administrative-expiry methods |
| Queued entries / count and input ownership | enqueuer transfers, scheduler/removal drains; terminal queued item can still own bytes | G3/G5/G6 |
| Active/retiring generation owners / count | retirement blocks admission; last retained resource closes generation exactly once | G3 FunctionCapacityRegistryTest, GenerationLifecycleTest, ResourceQuotaTest |
| Meter owners and historical function labels / count | generation-fenced writes; removed names do not recreate meters; legacy offload owners drain on final subscription | R8, G3/G5/G6/G9 |
| Replica snapshot entries, refresh workers and pending refreshes / count | generation/target fence; bounded independent refresh work; invalidate/remove/close | G11/G12/G13 |
| Wake-up state, poll timers, continuations / count | shared generation owner; cancellation/removal/stop cancels owned timers; caller detach cannot cancel shared readiness | G11; publication/removal/rollback tests |
| Destination pools, allocated/active/idle connections, pending acquires / count | finite idle/lifetime policy; consumed/cancelled transport releases; owner closes provider | G10: 16-destination churn, physical sequence-number reuse checks, pending-acquire cancellation; expected residual may be <=1 during scan race, not unconditional immediate zero |
| Container proxy requests, retained buffers, timers, client/executor / count and bytes | request graph holds reservations through caller write; each hop deadline has its own owner; close ends proxy resources | G14 P13BoundedProxyTest, especially requestGraphOwnersRemainReservedWhileCallerResponseWriteIsBlocked |
| Container/deprovision recovery resources / count and durable identities | retain traceable failed cleanup; context stop closes local proxy/client but preserves recoverable containers | G14/G15; real Docker/NanoLab separate |
| WaitEstimator timestamps, function states and maintenance rotation / count | exact bounded history; owned time-driven cleanup and removal; 8,193rd live function rejects at 8,192 ceiling | G6 WaitEstimatorRetentionTest and SyncQueueRuntimeLifecycleTest; P14 harness |
| Catalog snapshots, pending removal/application markers, operation locks / entries, bytes, writes | atomic durable mutation and recovery; markers not lost on partial failure; snapshot cost decision retained | G15 plus FunctionCatalogCostMeasurementTest |
| SDK input/output/callback bytes and callback count / per-process units | finite reservation before handler work; callback handoff/retry owns bytes until completion or reported exhaustion | G17/G18 plus five-language corpus/tests |
| SDK physical handlers, callback workers/queues, HTTP listeners/sockets, owned executors/clients / count | real completion releases; bounded stop can report incomplete drain; injected resources remain externally owned | G17/G18, Python test_runtime/test_p16b_regressions, Go shutdown/race tests, JS runtime limits/callback corpus |

## Local feasibility and prerequisites

Observed with read-only probes:

- OpenJDK 25.0.4, Node on the v22.23.2 installation, Python .venv 3.14.7; JavaScript node_modules and Python .venv exist. Presence is not a fresh dependency integrity check.
- PATH Go is 1.22.2, below go.mod's 1.24. A cached Go 1.24.0 binary exists at `/home/michele/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.24.0.linux-arm64/bin/go` (also under /tmp/nanofaas-p16b-gopath). Use the explicit supported binary; do not conclude Go is unavailable merely from PATH.
- Docker CLI exists, but `docker info` returned permission denied on /var/run/docker.sock. Actual container tests cannot run under the current access. No container was created or removed.
- NanoLab checkout and both lifecycle scenarios plus multipass.yaml exist at /home/michele/Documenti/nanolab. Multipass executable exists. No VM/cluster health was queried or provisioning attempted. kubectl and native-image were not found on PATH; this does not establish absence inside a VM or native builder image.
- Restricted networking caused npm registry lookup failure. Gradle caches/toolchain resolution and local loopback socket permission remain execution prerequisites, not freshly verified. P16b reports a sandbox asyncio wakeup-socket EPERM that passed outside that sandbox; recognize it as environmental if reproduced.
- Tests/builds write build outputs/caches and some test catalogs. They are non-destructive to product data when using their test fixtures, but are not literal read-only commands. Preserve existing reports/artifacts before reruns. None ran here.

G1–G18 are candidates for local, non-destructive verification using fixtures, temp files and loopback servers; they do not require provisioning existing infrastructure. Full module/core test tasks should not be assumed equally safe/light: the core task depends on several bootJars, and the full container module includes real Docker lifecycle tests that create/remove containers/networks and pull busybox:1.36.

## Exact Java command catalog

Commands are source-resolved proposals, not execution-validated at this revision. Run one group at a time and save exit code, selected module list, XML counts/failures/skips and log identity before running another profile (Gradle reuses report paths). Options avoid network and parallel competition; dependency caches and allowed socket/cache access are required. Use a finite outer timeout appropriate to cached/cold build cost when executing. For fresh evidence, add `--rerun-tasks` deliberately; it also reruns dependent build tasks.

Each test filter below is an exact FQCN. Its source is the corresponding project directory plus `src/test/java/` plus FQCN with dots replaced by slashes plus `.java`. Thus G1's first source is `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/R1OutcomeByteBudgetRegressionTest.java`. Module source roots are `platform/modules/<id>`; SDK roots are `sdks/java` and `sdks/java-lite`.

### G1 — R1–R8 core regressions

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.R1OutcomeByteBudgetRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.R2ArchiveEvictionReplayRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.R3WaiterTimeoutSharedOutcomeRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.R4OffloadCompletionAfterWaiterTimeoutRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.R7KeyBudgetAtomicityRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.R8HistoryCleanupRegressionTest' \
  --offline --no-parallel --console=plain
```

### G2 — Outcome/key/lifecycle support

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeigherTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.OutcomeWeightBudgetTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.IdempotencyKeyBudgetTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.IdempotencyKeyLifetimeTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.IdempotencyOutlivesExecutionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.IdempotentRetentionContractTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.ExecutionLifecycleTerminalTransitionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.ReviewLifecycleGateTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreAdministrativeExpiryTest' \
  --offline --no-parallel --console=plain
```

### G3 — Physical ownership and aggregate admission

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.DirectAdmissionWithoutQueueBoundedRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.DispatchLifecycleAndCancellationTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistryTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.FunctionGenerationTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.GenerationLifecycleTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuotaTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuotaRuntimeLimitsTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacityTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacityTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationQuotaLifecycleIntegrationTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.P07cReviewFixTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.P07dWaiterAdmissionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.P05WaiterTimeoutSharedOutcomeTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.CompletionMetricsGenerationFenceRegressionTest' \
  --offline --no-parallel --console=plain
```

### G4 — Ingress, accounting and minimum wiring

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none \
  --tests 'it.unimib.datai.nanofaas.controlplane.CoreOnlyApiTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.api.IngressBodyLimitWebFilterTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.api.IngressBodyLimitHttpTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.api.IngressCodecAlignmentTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimatorTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInputTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacityPropertiesTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacityConfigurationTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationPathAccountingTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceCoreRetryTest' \
  --offline --no-parallel --console=plain
```

### G5 — Async shared SYNC/ASYNC ownership

```bash
./gradlew :control-plane-modules:async-queue:test -PcontrolPlaneModules=async-queue \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueInvokeEnqueueContractRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.QueueLeaseGenerationTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.QueueBackedEnqueuerRetryIntegrationTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.QueueManagerGaugeCleanupTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.QueueManagerTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.SchedulerResilienceTest' \
  --tests 'it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueContextTest' \
  --offline --no-parallel --console=plain
```

### G6 — Sync ownership and R5

```bash
./gradlew :control-plane-modules:sync-queue:test -PcontrolPlaneModules=sync-queue,runtime-config \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.R5DirectCompletionUnownedSlotRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.QueueLeaseGenerationTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeActivationRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeLifecycleTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueInvocationEnqueuerRetryIntegrationTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncSchedulerDispatchExceptionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncSchedulerLifecycleTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.scheduler.P07cInputBackpressureTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueServiceTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueContextTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.sync.WaitEstimatorRetentionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.sync.WaitEstimatorTest' \
  --tests 'it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueAdmissionControllerTest' \
  --offline --no-parallel --console=plain
```

### G7 — Runtime limit changes

```bash
./gradlew :control-plane-modules:runtime-config:test -PcontrolPlaneModules=sync-queue,runtime-config \
  --tests 'it.unimib.datai.nanofaas.modules.runtimeconfig.ControlPlaneRuntimeConfigExtensionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigServiceTest' \
  --tests 'it.unimib.datai.nanofaas.modules.runtimeconfig.AdminRuntimeConfigIntegrationTest' \
  --offline --no-parallel --console=plain
```

### G8 — HTTP sync activation/backpressure

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=sync-queue,runtime-config \
  --tests 'it.unimib.datai.nanofaas.controlplane.SyncQueueBackpressureApiTest' \
  --offline --no-parallel --console=plain
```

### G9 — Offload results, waiter isolation and meter cleanup

```bash
./gradlew :control-plane-modules:offload:test -PcontrolPlaneModules=offload \
  --tests 'it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGatewayTest' \
  --tests 'it.unimib.datai.nanofaas.modules.offload.ReviewOffloadWaiterBudgetTest' \
  --offline --no-parallel --console=plain
```

### G10 — HTTP destination pools and drain

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none \
  --tests 'it.unimib.datai.nanofaas.controlplane.config.DispatchConnectionPoolTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.config.HttpClientPoolTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.config.HttpClientPropertiesTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.P12CrossHostAdmissionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcherTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcherTimeoutTest' \
  --offline --no-parallel --console=plain
```

### G11 — Replica refresh, wake-up and generations

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=k8s-deployment-provider \
  --tests 'it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshotTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.config.ManagementLoopResourcesTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinatorTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.DeploymentWakeUpGateTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.DeploymentWakeUpRemovalRollbackTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinatorTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.service.UnavailableFunctionWakeUpVisibilityTest' \
  --offline --no-parallel --console=plain
```

### G12 — Independent autoscaler progress

```bash
./gradlew :control-plane-modules:autoscaler:test -PcontrolPlaneModules=async-queue,autoscaler \
  --tests 'it.unimib.datai.nanofaas.modules.autoscaler.InternalScalerResilienceTest' \
  --tests 'it.unimib.datai.nanofaas.modules.autoscaler.InternalScalerTest' \
  --tests 'it.unimib.datai.nanofaas.modules.autoscaler.InternalScalerDesiredVsReadyRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.autoscaler.InternalScalerTargetAwareScalingTest' \
  --offline --no-parallel --console=plain
```

### G13 — Independent governor progress

```bash
./gradlew :control-plane-modules:concurrency-control:test -PcontrolPlaneModules=async-queue,concurrency-control \
  --tests 'it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencyGovernorTest' \
  --tests 'it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencyGovernorE2eTest' \
  --offline --no-parallel --console=plain
```

### G14 — R6/proxy recovery and bounded buffers

```bash
./gradlew :control-plane-modules:container-deployment-provider:test -PcontrolPlaneModules=container-deployment-provider \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.R6DeprovisionFailureOwnershipRegressionTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerLocalDeprovisionRecoveryTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerLocalDeploymentProviderTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.RoundRobinFunctionProxyTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.P13BoundedProxyTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.P13ProxyConfigurationTest' \
  --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerDeploymentProviderConfigurationTest' \
  --offline --no-parallel --console=plain
```

### G15 — Persistent registry/removal recovery

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=container-deployment-provider \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionServicePartialDeprovisionTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.PendingRemovalInvocationApiTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogMutationContractTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistryPersistenceTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogRestorerTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionApplicationPendingRecoveryTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocksTest' \
  --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionServiceConcurrencyTest' \
  --offline --no-parallel --console=plain
```

### G16 — Kubernetes adapter contract (mock provider/server)

```bash
./gradlew :control-plane-modules:k8s-deployment-provider:test -PcontrolPlaneModules=k8s-deployment-provider \
  --tests 'it.unimib.datai.nanofaas.modules.k8s.deployment.KubernetesManagedDeploymentProviderTest' \
  --tests 'it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManagerTest' \
  --tests 'it.unimib.datai.nanofaas.modules.k8s.dispatch.MockK8sDeploymentReplicaSetFlowTest' \
  --tests 'it.unimib.datai.nanofaas.modules.k8s.K8sDeploymentProviderContextTest' \
  --offline --no-parallel --console=plain
```

### G17 — Java physical runtime and callback ownership

```bash
./gradlew :sdks:java:test \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.SharedSaturationWireCorpusTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.SaturationRuntimeObservationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.SaturationAdapterMutationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.HandlerExecutorLimitsTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.CallbackDispatcherLimitsTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.CallbackDispatcherBoundedSerializationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.RuntimePayloadLimitsBoundedTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.RuntimePayloadLimitFilterDeadlineTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.CallbackClientDeadlineTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.InvokeLimitsWireTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.runtime.HttpClientConfigOwnershipTest' \
  --offline --no-parallel --console=plain
```

### G18 — Java-lite physical runtime and callback ownership

```bash
./gradlew :sdks:java-lite:test \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.SharedSaturationWireCorpusTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.SaturationRuntimeObservationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.SaturationAdapterMutationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntimeOwnershipTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandlerOwnershipTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClientOwnershipTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandlerBodyDeadlineTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandlerLimitsWireTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClientBoundedSerializationTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClientDeadlineTest' \
  --tests 'it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandlerInterruptedCallbackShutdownTest' \
  --offline --no-parallel --console=plain
```

## Additional exact profiles and commands

### Configured T1/T2 and quota HTTP

Run these as separate invocations, selecting only the matching method to avoid counting the other profile's assumption skip as coverage:

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none --tests 'it.unimib.datai.nanofaas.controlplane.P07ConfiguredHttpCalibrationTest.t1CoreOnlySyncUnkeyedThroughConfiguredHttpRuntime' --offline --no-parallel --console=plain
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue --tests 'it.unimib.datai.nanofaas.controlplane.P07ConfiguredHttpCalibrationTest.t2AsyncKeyedShapesThroughConfiguredQueueHttpRuntime' --offline --no-parallel --console=plain
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue --tests 'it.unimib.datai.nanofaas.controlplane.InvocationQuotaHttpTest' --offline --no-parallel --console=plain
```

These calibration tests are modest compared with the complete suite, but still boot Spring and send real HTTP traffic; not executed during inventory.

### Selection/wiring matrix

| Selection | Existing tests and execution |
|---|---|
| none | G1–G4; CoreOnlyApiTest plus exact T1 method. Add minimum bootJar/start check for absence of unused managed beans. The protected untracked ReplicaStatusSnapshotConfigurationTest cannot close this requirement. |
| async-queue | G5 + T2 + InvocationQuotaHttpTest; SYNC and ASYNC share the same function capacity/queue. |
| sync-queue alone | Repeat G6 with selector changed to sync-queue and build/start the packaged artifact. SyncQueueContextTest is enabled when this selection is present, but sync module testImplementation always adds runtime-config. That test cannot prove the extension is absent from the actual runtime classpath. |
| sync-queue,runtime-config | G6/G7/G8, including live toggles and runtime decreases. |
| offload | G9; repeat with async-queue,offload and sync-queue,offload (or sync-queue,runtime-config,offload for toggle integration). Do not use none,offload: none combined with any module is invalid. |
| async-queue,autoscaler | G12. Autoscaler requires one queue. |
| async-queue,concurrency-control | G13. Governor requires one queue. |
| async-queue,autoscaler,concurrency-control | Repeat G12 and G13 separately with this selector; check shared observations and independent progress through G11. Optional consumers must not be called “independent” using only a combined default build. |
| container-deployment-provider | G14/G15. Excludes default Kubernetes provider; actual Docker lifecycle is a separate gate. |
| k8s-deployment-provider | G11/G16 plus external NanoLab lifecycle. Mock provider/server tests are local. |
| default and all | OpenAPI, metadata, architecture and wiring selection; neither proves excluded sync/container profiles. |

For local offload HTTP integration, these existing loopback tests complement G9 (multiple Spring contexts; run as a deliberate bounded integration group):

```bash
./gradlew :control-plane-modules:offload:test -PcontrolPlaneModules=sync-queue,offload --tests 'it.unimib.datai.nanofaas.modules.offload.OffloadPressureE2eTest' --tests 'it.unimib.datai.nanofaas.modules.offload.OffloadHeaderLossE2eTest' --tests 'it.unimib.datai.nanofaas.modules.offload.OffloadHopGuardE2eTest' --offline --no-parallel --console=plain
```

Discovery and profile packaging are normal local build actions, not read-only inventory operations:

```bash
./gradlew projects -PcontrolPlaneModules=all --offline --console=plain
./gradlew tasks --all -PcontrolPlaneModules=sync-queue,runtime-config --offline --console=plain
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none --offline
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue --offline
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue --offline
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config --offline
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider --offline
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all --offline
./gradlew :control-plane:bootJar --offline
```

Every bootJar writes the same platform/control-plane/build/libs/app.jar. Archive/hash each selected artifact before the next invocation. Existing profile bootJar evidence from prior reports is not proof of fresh HTTP start, replay, shutdown or native linking.

Core contract checks, separately under default and all:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.api.OpenApiRouteCoverageTest' --tests 'it.unimib.datai.nanofaas.controlplane.IssueCoverageTest' --tests 'it.unimib.datai.nanofaas.controlplane.ControlPlaneAutoConfigurationImportsTest' --tests 'it.unimib.datai.nanofaas.controlplane.architecture.CoreArchitectureTest' --offline --console=plain
./gradlew :control-plane:test -PcontrolPlaneModules=all --tests 'it.unimib.datai.nanofaas.controlplane.api.OpenApiRouteCoverageTest' --tests 'it.unimib.datai.nanofaas.controlplane.IssueCoverageTest' --tests 'it.unimib.datai.nanofaas.controlplane.ControlPlaneAutoConfigurationImportsTest' --tests 'it.unimib.datai.nanofaas.controlplane.architecture.CoreArchitectureTest' --offline --console=plain
./gradlew :control-plane-modules:build-metadata:test -PcontrolPlaneModules=all --offline --console=plain
```

Selector negative coverage already exists in the included build's ControlPlaneModulesPluginTest, ModuleConstraintResolverTest and RepositoryModuleDescriptorsTest. Plugin tests use Gradle TestKit, so they are not a trivial unit-only run:

```bash
./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.ControlPlaneModulesPluginTest' --tests 'it.unimib.datai.nanofaas.gradle.ModuleConstraintResolverTest' --tests 'it.unimib.datai.nanofaas.gradle.RepositoryModuleDescriptorsTest' --offline --no-parallel --console=plain
```

These real-repository configuration-only commands must fail for the stated reason; save each nonzero exit and diagnostic independently:

```bash
./gradlew help -PcontrolPlaneModules=async-queue,sync-queue --offline
./gradlew help -PcontrolPlaneModules=k8s-deployment-provider,container-deployment-provider --offline
./gradlew help -PcontrolPlaneModules=none,offload --offline
./gradlew help -PcontrolPlaneModules=unknown --offline
```

Do not accept a dependency/network error as a successful selector negative test.

### SDK and shared corpus commands

Run from the indicated directory; no dependency installation was attempted. These are existing suites, not permission to run them during inventory.

```bash
# Repo root: full JVM SDK gate when appropriate (broader than G17/G18).
./gradlew :sdks:java:test :sdks:java-lite:test :services:java:warm-echo:test --offline --no-parallel --console=plain

# cwd sdks/python; existing Python 3.14 virtualenv
.venv/bin/python -m pytest tests -q
# Focused physical ownership/common contract:
.venv/bin/python -m pytest tests/test_runtime.py tests/test_p16b_regressions.py tests/test_saturation_wire_corpus.py -q

# cwd sdks/javascript; node_modules is already present
npm test

# cwd sdks/go; avoid unsupported PATH Go 1.22
/home/michele/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.24.0.linux-arm64/bin/go test ./...
/home/michele/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.24.0.linux-arm64/bin/go test -race ./...
/home/michele/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.24.0.linux-arm64/bin/go vet ./...

# Repo root: shared corpus validator tests (stdlib unittest)
sdks/python/.venv/bin/python -m unittest discover -s sdks/runtime-contract -p 'test_*.py'
```

Go race mode additionally requires compatible cgo/C tooling. Cache presence and prior reports do not prove that dependency resolution/race compilation succeeds now.

Exact non-JVM test files relevant to T7/T8/T9:

- Python: sdks/python/tests/test_runtime.py, test_p16b_regressions.py, test_saturation_wire_corpus.py; runtime-backed adapters and pre-start cancellation, timeout and physical drain assertions.
- Go: sdks/go/nanofaas/runtime_limits_test.go, http_invoke_limits_test.go, http_invoke_admission_test.go, callback_dispatcher_test.go, callback_dispatcher_shutdown_test.go, saturation_runtime_adapter_test.go, saturation_wire_corpus_test.go, bounded_json_test.go, runtime_settings_test.go.
- JavaScript: sdks/javascript/test/runtime.limits.test.ts, runtime.callback.test.ts, runtime-saturation-corpus.test.ts, saturation-wire-corpus.test.ts, runtime.request-validation.test.ts, runtime.observability.test.ts. npm test compiles TypeScript through build:test before node --test; invoking the Java harness alone does not test this runtime.

Shared structural validation complements, but cannot replace, runtime-backed scenarios and mutation tests. SDK maximum RSS numbers reported in P16b were command-tree observations; JVM figures include Gradle workers and are not comparable to direct Node/Python/precompiled Go processes.

### P14/P15 measurement commands

After compiling the selected sync module, the existing P14 standalone harness writes only fresh temporary class files:

```bash
measure_dir=$(mktemp -d /tmp/nanofaas-p14-measurement.XXXXXX)
javac -cp platform/modules/sync-queue/build/classes/java/main -d "$measure_dir" docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.java
java -cp "$measure_dir":platform/modules/sync-queue/build/classes/java/main it.unimib.datai.nanofaas.modules.syncqueue.sync.P14WaitEstimatorMeasurement
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogCostMeasurementTest' --offline --no-parallel --console=plain
```

P14 output is already retained at docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.out. Both are component measurements, not an HTTP latency baseline.

### External/heavy gates: catalogued, not run

- Real container adapter: `./gradlew :control-plane-modules:container-deployment-provider:test -PcontrolPlaneModules=container-deployment-provider --tests 'it.unimib.datai.nanofaas.modules.containerdeploymentprovider.DockerJavaContainerRuntimeAdapterIntegrationTest'`. Requires working Docker socket, images and available ports; creates/removes uniquely named containers/networks. Current socket access is denied. Its availability assumption can skip instead of fail.
- In /home/michele/Documenti/nanolab: `NANOFAAS_ROOT=/home/michele/Documenti/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml`.
- In that checkout: `NANOFAAS_ROOT=/home/michele/Documenti/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml`. VM/k3s provisioning and lifecycle assertions are external mutations, outside this read-only inventory; checkout existence is established, environment readiness is not.
- Native minimum: `CONTROL_PLANE_MODULES=none scripts/native-java-image.sh control-plane nanofaas/control-plane:p19-none-native`; managed: `CONTROL_PLANE_MODULES=k8s-deployment-provider scripts/native-java-image.sh control-plane nanofaas/control-plane:p19-k8s-native`. The script uses a builder image/toolchain and Docker; native-image absence on host PATH alone is not its blocker, but Docker denial is. Record the selected GraalVM/GC/monitoring configuration and immutable image digest. These commands build images; native HTTP invocation/replay and stop remain additional runtime checks.
- Prior `processAot`/compileAotJava with nativeCompile excluded does not prove native linking or startup. Ordinary JVM builds disable main AOT.
- `./gradlew build` and `./gradlew test` are the broad Section 8 gates, deliberately not run. A full Java build does not automatically replace the Python/Go/JavaScript commands.

## Genuine gaps and acceptance conditions

1. No fresh R1–R8 green dossier at the inspected P19 revision was produced here. Existing tests are concrete and prior task reports claim their fixes; current status remains unexecuted. Run all named regressions and report method-level failures/skips. P19 cannot inherit the old R6/R8 exceptions.
2. Existing configured T1/T2 output is insufficient for the requested performance freeze: missing p99, post-GC heap, complete cross-component populations, comparable allocations per useful success, and at least three alternating revision repeats with identical environment/offered and admitted work. Warm-up and GC checkpoints must be outside ordinary latency windows. T2 submission latency is not execution completion latency.
3. T3–T9 have substantial deterministic/component/loopback coverage, but no single discovered existing harness records the complete Section 8 short HTTP profile and ownership population dossier across all selected processes. This is a measurement/integration gap, not evidence that the underlying fixes are absent.
4. Sync without runtime-config needs an actual packaged runtime/start check because module tests inject runtime-config on the test classpath. Profile assumptions can otherwise hide coverage: CoreOnlyApiTest, configured T1/T2, InvocationQuotaHttpTest, SyncQueueBackpressureApiTest, SyncQueueContextTest and OpenApiRouteCoverageTest have gating. Record method-level skips explicitly.
5. Minimum-profile absence of unused managed beans needs committed reproducible evidence. Do not count the protected untracked ReplicaStatusSnapshotConfigurationTest. Packaging/HTTP smoke on minimum and managed JVM/native profiles is a separate obligation.
6. External Docker access is denied. NanoLab is available as source but VM/cluster readiness is unverified. Native image builds and real provider lifecycle are not proven by mocks or AOT compilation. These are environment/execution prerequisites, not reasons to remove tests.
7. Freeze an immutable corrected artifact with revision, dirt/source manifest, module selection, hash/image digest, toolchain, limits, offered/admitted/refused/useful-success counts, timings, GC policy and population snapshots. Existing app.jar/report paths are overwritten by subsequent profile builds. Preserve the defective historical baseline separately from the corrected P19 control and future P23 SPI artifact.
8. P07e's microbenchmark concern and P14/P15 measured choices require accurate interpretation. No fresh evidence here establishes a new retainer or correctness defect. Noncooperative physical work retained after timeout, finite caches after traffic stops and persistent recoverable containers after context stop are intentional ownership outcomes, provided they remain bounded/attributed and drain according to policy.

Inventory handoff: command groups and source names are verified to exist; execution feasibility is conditional as stated. Only completion of the inventory is claimed.

