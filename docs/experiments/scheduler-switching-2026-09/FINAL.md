# Manual scheduler switching — final report

Task 13c / issue #208. This is the campaign's consuntivo: what shipped, what was measured, what it
does **not** claim, and what is still owed.

**The campaign is not closed.** One criterion of the plan is not met and is not waived: the
≥60-minute soak has **not yet been run and is pending** (§7.1).

---

## 1. What shipped, and at which revision

Branch `feat/208-manual-scheduler-switching`, **54 commits** on top of the Task 0 baseline
`05f49dcb`. The last commit that changes production code or behaviour is

```
6b09b21d Restore the native executable and tombstone the retired old-loop harness
```

and that is the revision every figure below was measured or verified at. The two commits this file
is part of change no code, manifest or test.

The JVM-suite, `bootJar`, build-plugin and both chart rows were measured at `429df11f`, the parent of
the fix commit; the native rows, the two E2E rows and the switch procedure are at `6b09b21d`.
`6b09b21d` touches exactly four files — `platform/control-plane/build.gradle`,
`docs/control-plane.md`, `docs/experiments/scheduler-switching-2026-09/run-old.sh` and `OLD-VS-NEW.md`
— and the only production line among them is a Gradle setting that reaches `nativeCompile` and
nothing else. The gate was re-run at `6b09b21d` anyway, and it says exactly that: `BUILD FAILED in
14s`, `193 actionable tasks: 3 executed, 190 up-to-date`, with the two pre-existing failures and
their per-task counts reproduced unchanged (`AutoscalerConfigurationTest`: `73 tests completed, 1
failed`; `GeneratedBuildMetadataTest`: `22 tests completed, 1 failed`) and **every other test task
UP-TO-DATE** — including `:control-plane:test`, whose inputs the fix never touched. So the table's
figures are current at the final revision rather than carried by assumption; they were not re-executed
because Gradle determined there was nothing new to execute.

The change itself: one mandatory `execution-runtime` owns the pending work, and a single engine loop
selects through one of the two preserved algorithms (`per-function`, `shared-queue`). The strategy
is chosen at startup (`NANOFAAS_SCHEDULER_STRATEGY`, Helm `controlPlane.scheduler.strategy`,
Compose passthrough) and can be replaced at runtime by
`PATCH /v1/admin/runtime-config/scheduler`, in both directions, without a restart and without
draining the queue. The superseded per-module loops (`asyncqueue.Scheduler`,
`syncqueue.scheduler.SyncScheduler`) are gone, with the consumers migrated.

## 2. Tests: what ran, and what was skipped

Run; the paragraph after the table gives the revision each group of rows was measured at.

| what | command | result |
|---|---|---|
| JVM suite, both queue modules | `./gradlew test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --continue` | **283 classes, 1640 tests, 2 failures** |
| of which `control-plane` | (same run) | 119 classes, 712 tests, 0 failures, **4 skipped** |
| journal + OpenAPI artifact | `./gradlew :control-plane:bootJar :control-plane:composeControlPlaneOpenApi -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --rerun-tasks` | BUILD SUCCESSFUL; `app.jar` sha256 `7f1553edee29b45ab80c79eafc06fe56ba11e0e78f2a5d38b1fc72d288bd89d1` (31,879,592 bytes) |
| build plugin | `./gradlew -p platform/gradle-plugin test` | BUILD SUCCESSFUL, 7 classes / 46 tests |
| chart | `helm lint deploy/helm/nanofaas` | 1 chart linted, 0 failed |
| chart rendering | `helm template nanofaas deploy/helm/nanofaas --set controlPlane.scheduler.strategy=shared-queue` | exit 0, `NANOFAAS_SCHEDULER_STRATEGY` rendered explicitly; the default renders `value: ""` |
| native image | `./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=async-queue,sync-queue,runtime-config` (repository's pinned GraalVM) | BUILD SUCCESSFUL in 1m 5s, 31/31 tasks, `Generating 'control-plane' (executable)`, **ELF 64-bit LSB pie executable**, sha256 `684ec9aa…`; the `scripts/native-java-image.sh` `cp` target exists and the artifact boots and serves `GET /v1/functions` 200 |
| native black-box, both directions | admin-enabled build sha256 `d0caaf48…`, PIDs 3269205 / 3269553 | both directions switch, ids and payloads preserved, unknown id `422`, each process starting on its configured strategy |
| E2E, container | `nanolab run …/deployment-lifecycle-container.yaml` | **11/11 tasks passed, exit 0** |
| E2E, k8s on Multipass | `nanolab run …/deployment-lifecycle-k8s.yaml --environment …/multipass.yaml` | **21/21 tasks passed, exit 0** (VM created, bootstrapped, k3s in it, released) |
| the switch procedure itself | executed against the container stack (see `NANOLAB.md`) | 6 committed switches under live load, 248/248 async ids admitted and resolvable, 163/163 sync `200`, `409`/`422` paths, restart restores the startup selection; and on a second pass 16/16 **distinct** payloads enqueued across a switch each returned their own result |

**The 2 JVM failures are pre-existing and not this change's**: `AutoscalerConfigurationTest`
(`No qualifying bean of type 'DeploymentWakeUpControl'` — the gate's module selection has no
deployment provider) and `GeneratedBuildMetadataTest` (`nanofaas.selectedControlPlaneModules` omits
`build-metadata`). Both reproduce on the stashed tree with the same command.

**The 4 skips are pre-existing and none is this change's**: `CoreOnlyApiTest` (2, "run with few
modules"), `OpenApiRouteCoverageTest` (1, build-metadata + runtime-config),
`P07ConfiguredHttpCalibrationTest` (1, "run with `-PcontrolPlaneModules=none`"). Each is a
profile-gated assumption rather than a disabled check, and CI runs the core-only and sync-queue
selections as their own jobs (`:control-plane:test -PcontrolPlaneModules=none`,
`…concurrency-control:test :…sync-queue:test -PcontrolPlaneModules=sync-queue,concurrency-control,runtime-config`).

**Not run:** the ≥60-minute soak (§7.1). No end-to-end run of the native release image (§7.2).

## 3. Values against the frozen budgets

Budgets are frozen in `budgets.json`; the harness echoes them into every artifact header, so no
figure below can have been compared against a different threshold. All eleven budgeted values, from
`RESULTS.md`:

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSwitchPauseMs` | 250 ms | **3.984 ms** (max over 1 140 measured switches) | PASS |
| `maxSwitchPauseP99Ms` | 100 ms | **0.266 ms** (p99 over the 1 000-switch phase) | PASS |
| `maxSwitchPreparationMs` | 2 000 ms | 3.984 ms (total switch duration, an upper bound) | PASS |
| `maxLiveStrategyIndexes` | 2 | 2 | PASS |
| `switchesInSoak` | 1 000 | 1 000 committed, 0 refused — **the switch count, not the soak** (§7.1) | PASS |
| `repetitions` | 5 | 5 per (workload, arm), all 48 pairs | PASS |
| `switchBacklogSizes` | [0, 100, 1000, 10000] | all four swept, 5 repetitions each | PASS |
| `maxSteadyP99RegressionPercent` | 5 % | worst +9.51 %; 4 of 22 comparisons over, **0 separable** | over budget — **no regression established; the test cannot resolve 5 %** |
| `maxUsefulThroughputRegressionPercent` | 5 % | worst +2.09 % | PASS |
| `maxCpuPerCompletionRegressionPercent` | 10 % | worst +22.06 %; 3 of 24 over, **0 separable** | over budget — **no regression established; the test cannot resolve 10 %** |
| `maxPostGcHeapRegressionPercent` | 10 % | worst +0.01 % | PASS |

The two "over budget" rows are reported as they are rather than smoothed: the paired design's own
resolving power is worse than the budget on those two metrics, so the correct reading is *not
measured*, not *regressed*. The mechanism budgets — the pause, the preparation, the index count,
the switch count — pass by two to three orders of magnitude.

`churn-drain`'s 4 steady-window rows are not measurable by construction (the workload stops its own
traffic), and its 4 whole-run rows pass; that is recorded in `RESULTS.md` rather than hidden by an
average.

## 4. Compatibility

- **Both strategies remain supported and selectable**, and the artifact that ships contains both.
  An artifact built with one module publishes only the ids it has; `available` is the honest list.
- **Capability, caps and admission policy are untouched by a switch.** SYNC/ASYNC behaviour, the
  per-function limits, the sync profile's `:enqueue` `501` and the queue cap are the same before and
  after; the switch moves the selection, nothing else.
- **An empty `NANOFAAS_SCHEDULER_STRATEGY` is not a third strategy**: it means "no explicit
  selection" and derives the legacy mapping (both queue modules or `async-queue` alone →
  `per-function`; `sync-queue` alone → `shared-queue`). Verified on the container stack: with the
  variable empty and both modules built in, the process starts on `per-function`.
- **An unknown strategy id prevents startup** rather than falling back — `SchedulerEngine`'s
  constructor calls `StrategyRegistry.require`, so a typo is a crash loop that names the available
  ids. Verified by the JVM test and by the container PATCH path's `422`.
- **No authentication was added and none was removed.** The admin API stays explicitly enabled
  (`nanofaas.admin.runtime-config.enabled`, default **off**), and its default is unchanged. In a
  **native** image that flag is an AOT-time decision: an image compiled without it has no admin
  route at all (`404` however it is started). This is now documented in `docs/control-plane.md`.
- **No new production dependency.** Java 25, the existing Spring Boot composition, Micrometer and
  the existing Caffeine cache.
- The two properties the plan placed under `nanofaas.scheduler.*` (`max-switch-preparation`,
  `max-switch-pause`) were **deleted**: nothing read them, and the engine's preparation budget is
  the compiled `SchedulerEngine.SWITCH_BUDGET_MS` (50 ms), not a setting. A key nobody reads is
  read by everyone as a supported control.

## 5. The pause, and rolling a release back

**What bounds the pause.** A switch is not free: it rebuilds the destination index from the pending
work the engine owns, under the engine's own short gate, and the loop does not select during it. The
bound is structural — the gate is brief and does no I/O, no store call, no listener call and no
meter registration — and it is *measured*, not asserted: worst case 3.984 ms against a 250 ms
budget, over 1 140 switches, with the pause recorded by the engine's own observer on a real clock.
Two indexes exist at most during a transition, never a third.

**What that does not cover.** The pause was measured in the harness, not in production under
sustained multi-function churn; the ≥60-minute soak is where that belongs and has not run (§7.1).
The preparation budget is a compiled constant with no configuration knob: an operator cannot shorten
or lengthen it at runtime, and the harness figures are the only consumption figures that exist.

**Rolling a release back.** Nothing about this change persists scheduler state: the selection is
memory-only, so the admin API can be ignored entirely by a rollback, and there is no stored
migration to reverse. A release is an immutable image tag (`docs/operations/image-releases.md`;
`latest` is not part of the release contract), and rolling back is re-deploying the previous tag
with the previous chart values. The one manifest value this change adds is
`controlPlane.scheduler.strategy`, defaulting to `""`, and the empty string means "no explicit
selection" — so an older chart rendering, or a values file that never sets it, is a supported
configuration rather than a broken one. In Compose the same variable defaults to empty. The admin
enabling flag is **not** exposed by either shipped manifest, so no rollback has to consider it, and
no rollback can leave the switch API accidentally reachable.

## 6. What does not survive a restart

**A committed switch is an override for the life of the process, and nothing more.** Restarting the
control plane restores the strategy configured at startup and resets the runtime-config revision to
`0`. This is stated by the namespace's own `persistence: "restart"` and was verified twice on the
container stack: after a switch to `shared-queue`, a restart answered `per-function` (the derived
startup selection, revision `0`); with `NANOFAAS_SCHEDULER_STRATEGY=shared-queue` and a switch to
`per-function`, a restart answered `shared-queue`.

**No part of this change promises that pending work survives a restart.** It does not: pending
invocations live in the process's memory, and a restart loses them exactly as it did before this
campaign. The switch itself is the opposite case — it is guaranteed *not* to lose pending work — and
that difference is deliberate, but it does not extend to a restart. Persistence for pending work
was explicitly out of scope (the plan forbids durable storage), and nothing here should be read as
promising it.

## 7. Residual limits

### 7.1 The ≥60-minute soak has not yet been run, and is pending

The plan's acceptance criteria require a soak of **at least 60 minutes with 1 000 manual switches**
sent by the harness, two duration classes and function churn, observing through drain to the maximum
configured retention: live records, physical input, leases, waiters, timers, indexes and meters,
with cache-inside-TTL distinguished from a leak and heap distinguished from RSS.

**It was not executed.** By operator decision it is deferred to when the machine can be dedicated to
it. It is not "skipped", it is not "accepted as a limit", and it is not satisfied by any other
figure in this report. Specifically:

- `budgets.json`'s `switchesInSoak: 1000` **PASS** above is Task 12c's harness driving 1 000
  switches inside a short measurement window. It is the switch *count* and the return-to-baseline
  check. It is **not** the soak and must not be read as one.
- Nothing else in this campaign ran for 60 minutes.

Until it runs, the campaign is open. Its precondition, recorded so the next session does not have
to rediscover it: **the host must be quiet** — no Gradle daemon, no JVM, no other sampling run —
because a soak that measures a host measuring something else measures the wrong thing. State of that
precondition at the end of this task, measured rather than assumed:

- The two idle Gradle daemons the campaign inherited (PIDs 2888262 and 2888753, started 21:19 by
  another session) are **gone**: a full `ps` at the start of this task found no Gradle, JVM or
  `java` process at all. Nothing in this task stopped them.
- One Gradle daemon **is** running now — PID 252783, VmRSS ≈ 563 MB, started 07:24:38 by this task's
  own re-run of the JVM gate (§2). It is idle and it is this session's, but stopping Gradle daemons
  was not permitted in this part, so it was left alone and is named here instead. **It must be
  stopped (`./gradlew --stop`) before the soak starts.**

### 7.2 The native release path has never been executed end to end

`scripts/native-java-image.sh control-plane` — the Docker/GraalVM image build the release uses — has
**not been run**. What was verified natively is `nativeCompile` and the resulting executable
(§2), and the black-box contract against it. The image build that wraps it, and the release
workflow that publishes it, are unverified by this campaign. Anyone closing this out should run it.

### 7.3 Nothing in CI verifies that a native artifact is an executable

`native-build-tools`' `NativeImagePlugin` turns `java-library` into
`options.getSharedLibrary().convention(true)`: a module that applies both `java-library` and the
GraalVM plugin produces a **shared library**, silently, and `nativeCompile` still exits 0. That is
what broke this repository's release path when `d456915f` (Task 10 of this campaign) added
`java-library` to `:control-plane`, and it stayed broken until `6b09b21d` fixed it — **33 commits and
a day later**, with no commit in between touching `.github/` or either native script, because **no job
anywhere compiles a native image**: `.github/workflows/gitops.yml` runs `./gradlew test` in several module
selections, the SDK/example tests and the watchdog tests, and the only other workflow is the CodeQL
security scan — neither builds an image with the GraalVM plugin, so neither can notice what the
artifact is. The failure surfaced only because this campaign compiled natively by hand and looked at
the file.

Fixed here by `sharedLibrary.set(false)` on the `main` binary, with the chain written into the
comment beside it. The gap remains, and the cheapest correction is small: a CI job that runs
`scripts/native-build.sh` (or `:control-plane:nativeCompile`) and asserts the artifact's type with
`file` — a check that would have failed at `d456915f` and would fail today on **`sdks/java-lite`**,
which applies `java-library` (line 2) and `org.graalvm.buildtools.native` and sets no `sharedLibrary`
either, so its `nanofaas-lite-runtime` binary is the same latent `.so`.

### 7.4 The switch API is unreachable in both shipped manifests

`deploy/compose/compose.yaml` forwards no variable that enables
`nanofaas.admin.runtime-config.enabled`, and `deploy/helm/nanofaas/values.yaml` does not expose it
either. So as shipped, an operator cannot reach the hot-switch endpoint without editing a manifest —
and the compose file's own comment already describes the PATCH endpoint. The default staying off is
correct and unchanged; the gap is that there is no supported way to turn it on. The one-line
passthrough in each manifest is proposed in `NANOLAB.md` §4. This also means the procedure had to be
executed with a local compose overlay, which is why `NANOLAB.md` records an execution rather than
naming a supported entry point.

### 7.5 Six always-empty series, and a deny-list that still binds

The removed per-module loops took their observability with them: four tests were deleted, and
`QueueManager`'s six `function_scheduler_*` recorders have **no caller** and **zero test
references** — but their meters are still registered per function (`QueueManager.java:97-115`), so
`/actuator/prometheus` carries six permanently empty series. `MetricsProfileConfiguration.java:88,
:92, :93` still deny-lists them, which is **not inert**: the entries are what classifies those
series as `advanced` and keeps them out of `basic`. Leaving them is harmless, and re-wiring is
deferred cleanup for whoever next touches the metrics profile — but it is live classification of
dead series, not a no-op, and it is recorded here so it is not re-described as inert.

### 7.6 The switch procedure is not yet a NanoLab workflow

It was executed by hand against the container stack and recorded in `NANOLAB.md`. Its durable home
is a NanoLab scenario — a task module, a workflow builder and a `scenarios-v2/scheduler-switch-*.yaml`
— which does not exist yet and was not written, because the NanoLab checkout is a separate repository
that this task was not authorised to modify. The same document carries the proposal that would make
the container scenario able to serve it.

## 8. Publication

The consuntivo and the pull request are linked to **issue #208** when the execution session
authorises publication. Neither is published by this report.

## 9. Where the evidence is

| what | where |
|---|---|
| the switch's cost, with the command behind every table | `RESULTS.md`, artifacts in `raw/` |
| the refactor's own cost, old loop against the new engine | `OLD-VS-NEW.md`, `raw/old-vs-new.jsonl` |
| the frozen thresholds | `budgets.json` |
| the task-by-task record, SHAs and limits | `STATO.md` |
| the E2E and the executed switch procedure | `NANOLAB.md` |
| the switch's contract for a deployment reader | `docs/control-plane.md`, `docs/architecture/0002-manual-scheduler-switching.md` |
