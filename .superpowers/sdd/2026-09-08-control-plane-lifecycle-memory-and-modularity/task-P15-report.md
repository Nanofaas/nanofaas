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
