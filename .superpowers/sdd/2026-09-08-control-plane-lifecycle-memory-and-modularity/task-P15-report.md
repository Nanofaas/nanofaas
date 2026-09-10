# Task P15 report — catalog copy cost and persistence correctness

## Scope and revisions

- Original task start: `9f5c58db`.
- Final scope/review base: `779e14807ed9033117b9afc9aeee616759aa637f`
  (`Make the license identical across the projects: MIT`), created concurrently and
  preserved without modification.
- Scope: catalog register/update/remove and managed desired-replica persistence.
- Protected overload experiment files, GitNexus skill files, the untracked replica-status
  configuration test and the ignored controller ledger were not modified by P15.

## GitNexus pre-edit impact

The index was refreshed before impact analysis (19,416 nodes, 55,533 edges, 768 flows).
Its process layer warned that candidate/callee/walk budgets truncated flow discovery, so
reported blast radii are lower bounds.

- `FunctionRegistry.put(RegisteredFunction)`: HIGH; 66 impacted symbols, four direct
  callers, four processes and four modules. Twenty-one receiver sites were unresolved;
  exact text search enumerated service, coordinator and test call sites.
- `FunctionService.update`: MEDIUM; ten direct dependants, with five dropped receiver
  sites confirmed by exact `.update` searches.
- `ManagedDeploymentCoordinator.setReplicasLocked`: CRITICAL; 27 impacted symbols, two
  direct callers, five autoscaler/wakeup processes and three modules.
- `FunctionService.rollbackRemoval`: LOW; 18 impacted symbols and one direct caller.
- `providerFailureRestoresThePreviousDurableTarget`: UNKNOWN because tests are reached by
  JUnit discovery; exact text search found only the declaration before its rename.

HIGH and CRITICAL paths were explicitly audited through callers, focused integration tests
and the complete repository suite. UNKNOWN was not treated as safe.

## RED and GREEN evidence

Deterministic RED tests used real temporary-file catalogs, controlled providers and
barriers—never sleeps for ordering.

- Initial contract RED: equal `put`, same desired replicas and provider scale failure all
  violated the intended write/durability assertions.
- No-op PATCH RED: provider/listener side effects occurred despite an unchanged resolved
  record.
- Remove failure RED: restoring an unchanged snapshot performed a second write.
- Observation RED: a provider scale failure did not invalidate the volatile replica view.
- A later missing test-helper compile error was fixture construction, not counted as RED.

Production GREEN:

- Equal records return before registry copy/save; no-op PATCH returns before provider and
  listener work; same-target desired replica changes return before persistence/provider.
- Desired replicas are persisted intent. Persistence failure propagates before provider
  invocation and leaves both memory and reload state unchanged. Provider failure propagates
  while the persisted new target survives restart for reconciliation.
- Volatile observed replicas are invalidated after every attempted provider scale, including
  exceptional/partially applied attempts.
- Provider teardown failure restores the in-memory registration but does not rewrite the
  unchanged old snapshot. If teardown completed and durable deletion failed, provider
  reconciliation metadata is still persisted best-effort.
- Concurrent registry updates are serialized and both survive reload.

Existing controlled failure tests additionally cover register/PATCH/remove catalog save
failures and interrupted registration reload. The P15 contract adds explicit scale save
failure, PATCH/scale/remove provider errors, no-op and concurrent reload boundaries.

## Measurements

Environment: Linux aarch64 `6.17.0-1032-nvidia`, OpenJDK 25.0.4, 20 logical CPUs,
121 GiB RAM. The test warms each operation path, then measures one mutation using the JVM
thread allocation counter, `System.nanoTime`, final file size and a save counter. Duration
includes serialization and real temporary-file atomic replace/sync. These are diagnostic
single-operation values, not throughput benchmark claims.

| operation | catalog size | allocation B | serialized B | duration ns | writes |
| --- | ---: | ---: | ---: | ---: | ---: |
| register | 1 | 17,216 | 1,057 | 1,572,906 | 1 |
| update | 1 | 11,776 | 541 | 1,278,027 | 1 |
| remove | 1 | 53,544 | 34 | 1,771,480 | 1 |
| desired-replicas | 1 | 21,192 | 582 | 2,481,029 | 1 |
| register | 100 | 1,085,344 | 51,439 | 4,803,484 | 1 |
| update | 100 | 1,081,888 | 50,923 | 4,014,447 | 1 |
| remove | 100 | 1,079,552 | 50,415 | 5,482,281 | 1 |
| desired-replicas | 100 | 1,079,536 | 55,203 | 8,154,686 | 1 |
| register | 1,000 | 2,086,320 | 510,439 | 8,874,042 | 1 |
| update | 1,000 | 1,876,608 | 509,923 | 10,723,090 | 1 |
| remove | 1,000 | 1,747,240 | 509,415 | 5,741,592 | 1 |
| desired-replicas | 1,000 | 1,871,192 | 554,703 | 4,416,749 | 1 |

At size 1,000 the maximum duration is 10.723 ms (about 0.214% of the default
5,000 ms autoscaler loop), maximum allocation is 2,086,320 B (~1.99 MiB), maximum
serialized snapshot is 554,703 B (~542 KiB), and every actual mutation writes once.

Decision: snapshots are adequate at the declared maximum. The replacement hypothesis is
closed for P15. No ADR or copy-on-write persistence change is justified, and no journal or
coalescing mechanism was invented.

## Verification

- Focused registry/service/coordinator slice: passed, 78 actionable tasks.
- Measurement test: passed, 78 actionable tasks; emitted all twelve rows above.
- Autoscaler test plus control-plane bootJar and native-profile AOT generation/Java
  compilation: passed, 43 actionable tasks. The native linker was intentionally excluded;
  native-profile reachability/AOT sources compiled.
- Complete repository: `./gradlew test --rerun-tasks --no-parallel --continue
  --console=plain --offline` passed in 4 min 8 s, 190/190 actionable tasks executed.
- Post-review explicit scale-persistence failure regression: passed in the focused
  control-plane test run, 78 actionable tasks (3 executed, 75 up-to-date).

Final GitNexus 1.6.11 gates used the refreshed 19,474-node/55,818-edge/768-flow
index. All scope completed with 10 files/63 symbols, zero affected processes and LOW
risk; its two non-P15 files are the preserved dirty overload experiment files. Staged
scope completed against exactly the eight P15 files with 62 symbols, zero affected
processes and LOW risk. Neither detect-changes result reported `partial` or `truncated`.
The analyzer's separate process-discovery budget warning remains the index limitation
described above. The exact staged P15 diff passes `git diff --cached --check`.

## Changed P15 files

- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistry.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/ManagedDeploymentCoordinator.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/ManagedDeploymentCoordinatorTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogMutationContractTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogCostMeasurementTest.java`

This report itself is force-added from the normally ignored task scratch directory and
is included as the eighth P15 commit path, matching the brief's report deliverable.

## Remaining concerns

- Snapshot cost scales linearly with catalog size; if a future supported maximum or control
  SLO changes materially, repeat these measurements before reopening replacement.
- Provider operations are not transactional with the catalog. Durable-first desired state
  and restart reconciliation define recovery; no untested distributed rollback or journal
  semantics are claimed.
- GitNexus flow-index budget truncation limits graph completeness and is compensated by
  exact text searches and executable tests, not interpreted as a clean absence of callers.

## Fix round 1 — three Important findings addressed

Base implementation: `2c88d569000cfa24d8a54e6f9726285cd5a27436`; the external MIT commit
`779e14807ed9033117b9afc9aeee616759aa637f` remains an unchanged ancestor.

1. `FunctionRegistry` now owns bounded, volatile per-function application state separate
   from its durable catalog snapshot. A failed PATCH or desired-replica provider/listener
   application retains a marker. An identical retry reapplies provider/listener work while
   skipping the catalog write; the equality fast path resumes only after successful
   application. The state permits at most one PATCH, scale and unavailable marker per
   retained function name, caps reported provider data, and is never persisted.
2. A successful deprovision followed by catalog-delete and reconcile failures restores the
   durable record only as an unavailable recovery handle. Service get/list/invocation views
   exclude it and listeners are not replayed. In-process delete retry and restart through
   the existing catalog restorer/reconcile path recover it without claiming a live backend.
3. Manual scale now checks availability and looks up the function while holding the same
   per-function lock used by remove. The latch-driven partial-deprovision race returns the
   pending-removal conflict and performs no provider scale call.

The three Important findings are closed (3/3). The reviewer-requested benchmark-stability
Minor is intentionally deferred. The original catalog-size 1/100/1,000 snapshot matrix and
its decision are unchanged; no journal or replacement persistence mechanism was introduced.

### Fix-round RED/GREEN evidence

- Initial behavioral RED: six deterministic failures showed identical PATCH/scale retries
  were incorrectly short-circuited, triple failure republished/listener-replayed the
  function, restart advertised failed reconcile, and racing scale missed pending removal.
- Retention RED: removing the state diagnostics made the 1,000-function bound/zero-cleanup
  test fail compilation on the missing contract; restoring only those diagnostics made it
  GREEN.
- HTTP mapping RED: with the pending-state exception handler removed, its focused test
  failed compilation on the missing handler; restoring the 409 mapping made it GREEN.
- Fail-then-identical-retry GREEN writes are exactly two: registration plus one PATCH, or
  initial durable record plus one desired-replica update. Provider application is attempted
  twice and the third identical call is a true no-op.
- Triple-failure GREEN keeps the durable recovery record but exposes no live service view,
  replays no registration listener, and succeeds on delete retry. Failed startup reconcile
  stays unavailable; a later healthy restart publishes and replays once.
- At 1,000 durable names, 2,000 simultaneous PATCH/scale markers allocated 200,480 bytes in
  the diagnostic run. Cardinality plateaued at one table entry and two markers per name,
  then returned to zero after success/removal; reload/startup clears volatile markers.

### Fix-round verification

- Focused pending recovery and HTTP mapping checks: GREEN.
- Complete control-plane test task: GREEN, 78 actionable tasks in 1 min 44 s.
- Autoscaler and concurrency-control integration plus bootJar/native-profile AOT generation
  and Java compilation: GREEN, 46 actionable tasks in 32 s; native linking excluded.
- Final complete repository `test --rerun-tasks --no-parallel --continue`: GREEN, 190/190
  actionable tasks in 4 min 46 s.
- Final GitNexus all/staged gate details are recorded below.

### Fix-round GitNexus finalization

GitNexus 1.6.11 refreshed successfully to 19,559 nodes, 56,231 edges, 867 clusters
and 768 flows. Process discovery still reported its known independent budget truncation:
1,533 candidate entry points and 1,778 callees were omitted, with 48 walks cut. Therefore
flow counts remain lower bounds and are compensated by exact text searches, focused
failure/concurrency tests, module integration, and the complete repository suite.

The final complete all-scope gate reported 18 detected files, 126 symbols, nine affected
flows and HIGH risk. It includes the two preserved dirty overload experiment files in
addition to the 16 P15 paths. The complete staged-scope gate is
the authoritative commit-scope check: exactly 16 P15 files, 125 symbols, nine affected
flows and HIGH risk. Neither gate reported `partial: true` or `truncated: true`; the HIGH
risk flows are the explicitly reviewed rollback/unavailable recovery path plus the
pre-existing registry persistence flow. The exact staged diff passes `git diff --cached
--check`. The report is force-added and included in the commit.

### Fix-round changed files

- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- `.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P15-report.md`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/GlobalExceptionHandler.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionApplicationPendingException.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionApplicationState.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogRestorer.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistry.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/ManagedDeploymentCoordinator.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionApplicationPendingHttpMappingTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionApplicationPendingRecoveryTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogRestorerTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceConcurrencyTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServicePartialDeprovisionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceTest.java`

The report remains included in the fix-round commit from the ignored task scratch directory.

## Fix round 2 — atomic publication and removal marker retention

Base: `9f165ed1b588e0949703dd51eb45573877f5fa77`; the external MIT commit
`779e14807ed9033117b9afc9aeee616759aa637f` remains an unchanged ancestor.

Both open Important findings and the deterministic race-test Minor are addressed (3/3):

1. Removal start no longer clears pending PATCH or scale application markers. Provider
   deprovision failure restores the live record with those markers intact, so an identical
   PATCH/scale retry reapplies its side effects without another catalog write. Durable
   catalog deletion prunes all marker kinds; independently, startup recovery resets all
   volatile state and coordinator shutdown clears scale markers only.
2. `FunctionRegistry` now owns one volatile immutable snapshot containing separate recovery
   and public maps. Every public/service/invocation lookup and list reads only the public
   map; raw records are package-private to startup recovery. Managed deployments loaded
   from disk begin recovery-only, while LOCAL and EXTERNAL records remain public. Failed
   rollback restores only the recovery map and successful restore publishes availability
   atomically with the recovered catalog snapshot.
3. The removal/scale race test now observes the scale call at the shared lock boundary
   before allowing deprovision to finish. This deterministically traverses the old pre-lock
   window and proves scale returns conflict without invoking the provider.

Cleanup ownership is exact: `FunctionApplicationState` owns every volatile marker and
prunes all marker kinds when durable deletion publishes or startup recovery resets state.
`FunctionService` completes PATCH markers after provider/listener success.
`ManagedDeploymentCoordinator` completes scale markers after provider success and its
`close()` clears scale markers only; it does not own PATCH or unavailable-marker shutdown.
Registry replacement/reload discards the whole old volatile state because a new registry
instance owns a new table. No marker is serialized.

### Round-2 RED/GREEN evidence

- Deterministic RED produced four new failures: failed PATCH -> failed DELETE -> identical
  PATCH and failed scale -> failed DELETE -> identical scale each attempted the side effect
  only once; a rollback/read interleaving observed an unavailable record; and a precreated
  startup reader observed a managed record before reconcile. The strengthened lock-boundary
  race already passed the corrected lock placement and would time out on the old pre-lock
  return path.
- GREEN performs two provider attempts for each fail/delete/retry sequence while retaining
  exactly two catalog writes (registration plus PATCH/desired-replica persistence). A
  following identical call is a complete no-op. Durable deletion returns marker counts to
  zero.
- Latch-driven rollback/read and startup tests prove unavailable/recovery records never
  enter any public registry or service view. Failed reconcile retains the raw durable record
  and replays no listener; the existing restorer remains the sole restart recovery path.
- The 1,000-function retention diagnostic remains bounded at 1,000 entries/2,000 markers,
  allocated 200,480 bytes, and returned to zero on cleanup.

### Round-2 measurement rerun

The setup now explicitly completes startup publication before measuring managed desired
replicas. This is a functional correction, not the deferred multi-sample benchmark-stability
work. Each row is still one warmed diagnostic sample.

| operation | catalog size | allocation B | serialized B | duration ns | writes |
| --- | ---: | ---: | ---: | ---: | ---: |
| register | 1 | 18,328 | 1,057 | 1,599,866 | 1 |
| update | 1 | 12,376 | 541 | 1,390,907 | 1 |
| remove | 1 | 53,488 | 34 | 1,487,370 | 1 |
| desired-replicas | 1 | 16,736 | 582 | 1,252,123 | 1 |
| register | 100 | 1,103,912 | 51,439 | 4,963,631 | 1 |
| update | 100 | 1,099,704 | 50,923 | 4,612,656 | 1 |
| remove | 100 | 1,097,008 | 50,415 | 4,023,602 | 1 |
| desired-replicas | 100 | 290,664 | 55,203 | 2,749,174 | 1 |
| register | 1,000 | 2,256,016 | 510,439 | 8,415,955 | 1 |
| update | 1,000 | 2,077,432 | 509,923 | 6,487,897 | 1 |
| remove | 1,000 | 1,892,080 | 509,415 | 6,159,435 | 1 |
| desired-replicas | 1,000 | 2,011,392 | 554,703 | 4,336,769 | 1 |

The maximum duration is 8.416 ms, maximum allocation is 2,256,016 B, and maximum
serialized snapshot is 554,703 B. Every measured mutation writes once. Snapshots remain
adequate, the replacement hypothesis stays closed, and no journal was introduced.

### Round-2 verification

- Pending/recovery GREEN: 12 tests, zero failures/errors.
- Registry/restorer/service/coordinator slice: 103 tests, GREEN.
- HTTP/invocation focused checks: GREEN, 78 actionable tasks.
- Autoscaler and concurrency-control integration plus bootJar: GREEN, 43 actionable tasks.
- Native-profile AOT generation and Java compilation: GREEN, 37 actionable tasks; final
  native linking intentionally excluded.
- Complete repository suite from scratch: GREEN in 3 min 19 s, 190/190 actionable tasks.

### Round-2 GitNexus finalization

GitNexus 1.6.11 refreshed to 19,578 nodes, 56,344 edges, 872 clusters and 765
flows. Its independent process discovery again reported incomplete flow coverage (1,539
candidate entry points and 1,773 callees omitted; 47 walks cut), so flow counts remain lower
bounds compensated by exact text searches and executable tests.

The complete all-scope gate reported 13 files, 65 symbols, 13 affected flows and HIGH risk;
the two additional files are the preserved dirty overload experiment files. The complete
staged gate reported exactly 11 P15 round-2 files, 64 symbols, 13 affected flows and HIGH
risk. The affected flows are the audited registry publication, put-if-absent and removal
rollback/recovery paths. Neither detect-changes result reported partial or truncated output,
and `git diff --cached --check` passed.

The sole open review item is the requested single-sample benchmark-stability Minor (1 open,
3 addressed). It remains deferred exactly as requested.

## Fix round 3 — unavailable retry rollback stays recovery-only

Base: `7c80df411e7e5f23600d6e60514cf343da1e2a14`. The sole open Important
finding is addressed (1/1).

An ordinary deprovision failure during retry of an already unavailable removal no longer
republishes the detached record. `FunctionRegistry.restoreDetached` owns the decision under
its registry lock: when the current application state is unavailable/pending removal, it
restores the durable record to the recovery map only; otherwise it performs the ordinary
public rollback. No service-level check-then-act was introduced.

The deterministic RED had exactly two behavioral failures: public registry lookup observed
the unavailable record, and the real `DeploymentWakeUpGate` reached its coordinator for
that record. GREEN retains the raw recovery record and all three PATCH/scale/unavailable
markers after the failed retry, keeps public lookup/list empty, and makes wake-up return
`DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE` without touching its coordinator. A later successful
delete writes the fourth and final snapshot, removes the raw record, and returns retained
function/marker counts to zero. Existing no-extra-write and bounded-state behavior remains.

Verification: the exact RED/GREEN pair ran 7 tests; the focused recovery/wake-up/removal/
restorer/HTTP slice passed with 78/78 actionable tasks; autoscaler and concurrency-control
integration plus bootJar passed with 43 tasks; native-profile AOT generation/Java compilation
passed with 37 tasks; and the complete repository suite passed 190/190 tasks from scratch in
4 min 9 s.

GitNexus 1.6.11 refreshed to 19,590 nodes, 56,459 edges, 870 clusters and 767
flows. Process discovery still omitted 1,539 candidate entry points and 1,768 callees and
cut 46 walks, so flow absence remains a lower bound compensated by exact text search and
tests. The complete all-scope gate reported 7 files, 17 symbols, 2 affected flows and MEDIUM
risk; it includes the two preserved overload experiment files. The authoritative complete
staged gate included exactly all 5 P15 files, 16 symbols, the same 2 audited
`restoreDetached` flows and MEDIUM risk. Neither gate reported partial or truncated output;
the staged diff check passed.
