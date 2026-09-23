# Scheduler switching — baseline (Task 0)

- **Date:** 2026-09-19
- **NanoFaaS revision:** `05f49dcb7b06f18682fb861e0688f7202d3fb8df` (branch
  `feat/208-manual-scheduler-switching`, working tree clean at the start of this task)
- **Host:** Linux aarch64, kernel `6.17.0-1032-nvidia`, 20 CPUs, 121 GiB RAM
- **Toolchain:** OpenJDK 25.0.4 (Temurin/Ubuntu build), Docker 29.2.1

## P24/P25 status this task inherits (not measured here)

The lifecycle-memory campaign's own status file
(`docs/experiments/lifecycle-memory-2026-09/STATO.md`, entry dated 2026-09-19) ends with:
**"Campaign incomplete."** The paired requalification it names as missing — a
`memory-soak-sync-container.yaml` run at `e35405ee` and at the candidate revision, evaluated
with the fixed harness — was never run. P24's candidate-only diagnostic (revision
`758d135b8c1b2e7483ae07c96173c51db2510a74`) measured `p24_qualified: false` for two JVM
rss-return criteria; the JavaScript defect it targeted is confirmed fixed.

**This plan proceeds anyway because the project operator declared P25 closed on 2026-09-19.**
That is an operator decision, not a measurement this task performed or can attest to. See
`STATO.md` in this directory for the full statement and its attribution limit. Nothing in this
document should be read as asserting the soak was run or that P24/P25 qualified — it was not,
and they did not.

## GitNexus index

`node .gitnexus/run.cjs analyze --index-only` was re-run. Result: 23,872 nodes, 63,993 edges,
764 clusters, 747 flows, in 33.0s. The run reported two structural limitations that apply to
every query below:

- **FTS/keyword search degraded this run** — the FTS index build failed ("buffer pool is full");
  graph and embedding analysis completed, but text/BM25 search was unavailable for this pass.
  `--repair-fts` was not re-attempted (out of scope for a documentation-only task; recorded as a
  limitation, not silently worked around).
- **Process-flow discovery is truncated**: 747 flows reported, but 1,743 of 1,943 candidate entry
  points were never ranked in, and 1,906 callees were dropped at the branching cap. An absent
  flow does not mean the code path does not exist. Every risk figure quoted below is a lower
  bound on the true blast radius, and is stated as such.

## Symbol analysis — callers, processes, risk

Every symbol name required by the brief is **ambiguous** in this index (multiple candidates:
production sources under `platform/`, decompiled snapshots under
`experiments/control-plane-staging/versions/...`, and constructors sharing the class name). Each
was disambiguated to the production `platform/` class before recording a verdict, per the
project's UID/file-path disambiguation rule.

| Symbol (production UID) | impactedCount | risk | epistemic | Real callers (direct) |
|---|---|---|---|---|
| `ExecutionLifecycle` (`controlplane/execution/ExecutionLifecycle.java`) | 12 | LOW | exact | `InvocationExecutionFactory` (constructor), `ExecutionStore.attachLifecycle` |
| `ExecutionStore` (`controlplane/execution/ExecutionStore.java`) | 27 | **CRITICAL** | exact | 8 direct dependents incl. `InvocationService.invokeSyncReactive`, `InvocationExecutionFactory.createClaimedRecord`; ambiguous match against the decompiled-snapshot class was excluded (13 total candidates, `experiments/control-plane-staging/...` copy scored max 49/HIGH but is not production code) |
| `FunctionCapacityRegistry` (`controlplane/capacity/FunctionCapacityRegistry.java`) | 61 | **CRITICAL** | exact | 26 direct dependents; unambiguous (single candidate) |
| `ExecutionCompletionHandler` (`controlplane/service/ExecutionCompletionHandler.java`) | 19 | MEDIUM | exact | implements `InvocationDispatch`; direct dependents incl. `InvocationService`, `ReactiveInvocationCoordinator` (typed-property references); ambiguous match against the decompiled snapshot excluded |
| `RuntimeConfigService.update` (`modules/runtimeconfig/RuntimeConfigService.java#update#3`) | 1 | LOW | **lower-bound** | `AdminRuntimeConfigController.patch` (the only production caller) |
| `Scheduler` (`modules/asyncqueue/Scheduler.java`) | 3 | LOW | exact | 2 direct dependents (unambiguous — no name collision) |
| `SyncScheduler` (`modules/syncqueue/scheduler/SyncScheduler.java`) | 5 | LOW | exact | 2 direct dependents (unambiguous — no name collision) |

**UNKNOWN resolved by text search.** `RuntimeConfigService.update` came back `epistemic:
lower-bound` with a boundary note: "21 call sites invoking `update` were dropped at index time
because the receiver's type could not be established." Per project rule, this was **not** taken
as a clean LOW. A text search (`grep -rn "\.update(" platform/modules/runtime-config/`) found
exactly those 21 sites: 1 production call
(`AdminRuntimeConfigController.java:66`, `service.update(request.expectedRevision(), ...)`) and
9 test-file call expressions across `RuntimeConfigServiceTest.java` and related tests, whose
local-variable receiver typing the indexer could not fully resolve (the remaining count is
accounted for by overloaded/chained `assertThatThrownBy(() -> service.update(...))` forms,
each contributing more than one dropped site). No second production caller exists. Verdict:
**one production caller**, matching the graph's direct-impact edge; the lower-bound label is
recorded rather than discarded.

**CRITICAL risk on `ExecutionStore` and `FunctionCapacityRegistry` is a real finding, not a
blocker for Task 0** (which changes no production code). It is recorded here as the baseline
warning for every later task in this plan that touches either class: both sit at the center of
the execution lifecycle and capacity contracts this ADR's lock order (§ ADR 0002) depends on.

## Baseline test runs (three profiles, run separately)

Run one at a time, `--rerun-tasks` to force a fresh execution rather than reuse a stale
`UP-TO-DATE` result from an earlier, differently-configured run. Nothing else touched this
checkout while each ran.

### Profile 1 — async-queue + runtime-config

```
./gradlew :control-plane:test :control-plane-modules:async-queue:test \
  -PcontrolPlaneModules=async-queue,runtime-config --no-parallel --console=plain --rerun-tasks
```

Result: **BUILD SUCCESSFUL** (1m 49s, 67/67 tasks executed).
Tests: **901 run, 0 failures, 0 errors, 5 skipped.**

All 5 skips are JUnit `Assumption` aborts, expected for this module combination (not container
gates, not failures):

- `P07ConfiguredHttpCalibrationTest.t1CoreOnlySyncUnkeyedThroughConfiguredHttpRuntime` — requires `-PcontrolPlaneModules=none`
- `CoreOnlyApiTest.disabledInvocationEnqueuerReportsUnavailable` — core-only assumption not met
- `CoreOnlyApiTest.asyncEnqueueReturns501WhenAsyncQueueModuleIsNotLoaded` — core-only assumption not met
- `SyncQueueBackpressureApiTest.syncInvokeReturns429WithRetryAfter` — requires sync-queue
- `OpenApiRouteCoverageTest.enabledModuleRoutesAreDocumented` — requires build-metadata **and** runtime-config both selected; only runtime-config is here

No Testcontainers/container-requiring gate exists in this profile's scope (verified: no
`Testcontainers`/`DockerClientFactory`/container tag under `platform/control-plane/src/test` or
`platform/modules/*/src/test`). Container-backed E2E is owned by NanoLab and out of this task's
scope per CLAUDE.md.

### Profile 2 — sync-queue + runtime-config

```
./gradlew :control-plane:test :control-plane-modules:sync-queue:test \
  -PcontrolPlaneModules=sync-queue,runtime-config --no-parallel --console=plain --rerun-tasks
```

Result: **BUILD SUCCESSFUL** (1m 41s, 67/67 tasks executed).
Tests: **954 run, 0 failures, 0 errors, 5 skipped** (all assumption-gated, symmetric to profile 1:
`P07ConfiguredHttpCalibrationTest.t2AsyncKeyedShapesThroughConfiguredQueueHttpRuntime`,
three `InvocationQuotaHttpTest` async-queue-only cases, `OpenApiRouteCoverageTest.enabledModuleRoutesAreDocumented`).
No container-requiring gates.

### Profile 3 — runtime-config only

```
./gradlew :control-plane:test -PcontrolPlaneModules=runtime-config --no-parallel --console=plain --rerun-tasks
```

Result: **BUILD SUCCESSFUL** (1m 30s, 58/58 tasks executed).
Tests: **835 run, 0 failures, 0 errors, 6 skipped** (all assumption-gated: the two queue-specific
`P07ConfiguredHttpCalibrationTest` variants, `OpenApiRouteCoverageTest.enabledModuleRoutesAreDocumented`,
three `InvocationQuotaHttpTest` async-queue-only cases, `SyncQueueBackpressureApiTest.syncInvokeReturns429WithRetryAfter`).
No container-requiring gates.

### Summary

| Profile | Modules | Result | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| 1 | async-queue, runtime-config | BUILD SUCCESSFUL | 901 | 0 | 0 | 5 |
| 2 | sync-queue, runtime-config | BUILD SUCCESSFUL | 954 | 0 | 0 | 5 |
| 3 | runtime-config only | BUILD SUCCESSFUL | 835 | 0 | 0 | 6 |

All three baseline profiles are green. This is a green baseline, not an assumed one — each was
force-rerun (`--rerun-tasks`) rather than read from Gradle's `UP-TO-DATE` cache. The two queue
modules were never run together (`async-queue` and `sync-queue` declare mutual `conflicts` in
their `module.properties`), matching the constraint stated in this task's brief.

## Budgets frozen for this campaign

See `budgets.json` in this directory — copied byte-for-byte (value for value) from the task
brief. No threshold was rounded, reordered, renamed, or adjusted. The admission count/byte caps
referenced by the budgets note are the existing `nanofaas.execution-store` defaults documented in
`CLAUDE.md` (`max-outcomes` 100000, `max-keys` 100000, `max-outcome-bytes` 0 meaning
`max-outcomes` × 116 B); this task does not change them, and no later task in this plan may raise
them to make a comparison pass.

## What this baseline does not establish

- No performance numbers (throughput, P99, CPU/completion, post-GC heap, switch pause) were
  measured in this task. `budgets.json` records the **thresholds**, not results; producing the
  results is later tasks' job, against this frozen host/revision pair.
- No production code was touched. `git diff --stat` for this commit is documentation and
  `docs/experiments/scheduler-switching-2026-09/*` plus `docs/architecture/0002-*` only.
