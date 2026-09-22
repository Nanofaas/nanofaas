# Manual scheduler switching — final report

Task 13c / issue #208. This is the campaign's consuntivo: what shipped, what was measured, what it
does **not** claim, and what is still owed.

**The campaign is not closed.** One criterion of the plan is not met and is not waived: the
≥60-minute soak has **not yet been run and is pending** (§7.1).

---

## 1. What shipped, and at which revision

Branch `feat/208-manual-scheduler-switching`, **68 commits** on top of the Task 0 baseline
`05f49dcb`. The revision every figure below was measured or verified at is

```
6b09b21d Restore the native executable and tombstone the retired old-loop harness
```

— the branch's 54th commit. It is this file's **measurement anchor, not the branch's last code
commit**, and the two were the same only until the fix wave: `79b55bad` (the retry path routed
through the helper it claims to use) and `98e9d76d` (the live selection in the fourth switch helper,
each removal fence pinned) change `src/main` after it, and so does the histogram pair `117e8303` /
`6fa6d969` recorded in §5.

The JVM-suite, `bootJar`, build-plugin and both chart rows were measured at `429df11f`, the parent of
the fix commit; the native rows, the two E2E rows and the switch procedure are at `6b09b21d`.
`6b09b21d` touches exactly four files — `platform/control-plane/build.gradle`,
`docs/control-plane.md`, `docs/experiments/scheduler-switching-2026-09/run-old.sh` and `OLD-VS-NEW.md`
— and the only production line among them is a Gradle setting that reaches `nativeCompile` and
nothing else. The gate was re-run at `6b09b21d` anyway. **Be precise about what that re-run is**:
it says `BUILD FAILED in 14s`, `193 actionable tasks: 3 executed, 190 up-to-date`, with the two
pre-existing failures and their per-task counts reproduced unchanged
(`AutoscalerConfigurationTest`: `73 tests completed, 1 failed`; `GeneratedBuildMetadataTest`: `22
tests completed, 1 failed`) and **every other test task UP-TO-DATE** — including
`:control-plane:test`, whose inputs the fix never touched. It is therefore **not a second
execution of the whole gate**: Gradle re-ran the tasks it considered out of date (the two that had
failed, which Gradle never leaves up-to-date) and took its own up-to-date verdict for the other
190, so the totals in §2 come from the earlier run at `429df11f` and are carried by that verdict
rather than re-measured. The independent reason the verdict is sound is mechanical and is stated
here instead of being inferred from the numbers: `6b09b21d` changes no JVM source, no JVM test, and
no build input of any JVM test task — its only production line is a Gradle setting that reaches
`nativeCompile` and nothing else, so no JVM test task's inputs changed and Gradle's UP-TO-DATE is
the correct answer for every one of them. The fix wave did run the gate with `--rerun-tasks` at its
own revision; those re-executed totals are in §2.

The change itself: one mandatory `execution-runtime` owns the pending work, and a single engine loop
selects through one of the two preserved algorithms (`per-function`, `shared-queue`). The strategy
is chosen at startup (`NANOFAAS_SCHEDULER_STRATEGY`, Helm `controlPlane.scheduler.strategy`,
Compose passthrough) and can be replaced at runtime by
`PATCH /v1/admin/runtime-config/scheduler`, in both directions, without a restart and without
draining the queue. The superseded per-module loops (`asyncqueue.Scheduler`,
`syncqueue.scheduler.SyncScheduler`) are gone, with the consumers migrated.

## 2. Tests: what ran, and what was skipped

The two JVM-suite rows below are **re-executed, not carried**: the gate was run again at the
fix-wave revision `2dad4e9e` with `--rerun-tasks`, so no UP-TO-DATE verdict was accepted and all
193 tasks executed (`193 actionable tasks: 193 executed`, `BUILD FAILED in 4m 39s` — the two
pre-existing failures are what fails it). Every other row keeps the revision §1 records for it.

| what | command | result |
|---|---|---|
| JVM suite, both queue modules (re-executed at the fix-wave revision, `--rerun-tasks`) | `./gradlew test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --continue --rerun-tasks` | **375 classes, 2070 tests, 2 failures, 7 skipped** over the modules of this Gradle build — every project of the root `settings.gradle` except the included build `platform/gradle-plugin`, which the "build plugin" row below covers instead — of which the `platform/*` and `platform/modules/*` projects this campaign's code lives in are **277 classes, 1598 tests**, and row 2 breaks the largest of them out; see the scope note below row 1 of the table |
| of which `control-plane` | (same run) | 120 classes, 714 tests, 0 failures, **4 skipped** |
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
The gate's other 3 skips are `concurrency-control`'s, gated on `nanofaas.queue.provider`, for the
same reason and with the same CI job.

**The scope of row 1 is corrected here, and the earlier figure did not trace.** As Task 13c wrote
it, that row read "283 classes, 1640 tests". Those numbers cannot be reproduced from the JUnit XMLs
the gate actually writes (`platform/*/build/test-results/test/TEST-*.xml` **and**
`platform/modules/*/build/test-results/test/TEST-*.xml` — the second glob is where nine of the
gate's module rows land, and a sum that names only the first lands on neither number, because it
both omits and adds: it misses those nine projects' 114 classes and picks up `platform/gradle-plugin`'s
7, which the gate does not run, for a net 107 below the 277 — 114 − 7): they counted
in `platform/gradle-plugin`'s 7 classes / 46 tests, which the gate does **not** run — it is an
included build with its own `settings.gradle`, and its own command is the "build plugin" row below —
and left out `:nanofaas-cli`'s 24 classes / 192 tests, which the gate **does** run. The first
correction of that figure then repeated the defect one module-set further out: it labelled
277 / 1598 "over the modules of this Gradle build" when the same invocation also executed
`:sdks:java` (37 / 145), `:sdks:java-lite` (23 / 81), `:services:java:warm-echo` (4 / 13) and the ten
`functions:java:*` modules (10 / 41) — every one of them a project of the same root `settings.gradle`,
in the same contiguous window of that one run. Row 1 now carries that build's own total, **375 classes
/ 2070 tests**, and states the 277 / 1598 subset it is a total of; 351 / 1878 is the same total
without `:nanofaas-cli`. Every figure here is re-summed from the XMLs of the one `--rerun-tasks`
invocation, module by module, rather than carried from a prior list.

**The one figure in this section that is a hypothesis, and is labelled as one.** Reconstructing the
Task 13c number for the 277 / 1598 scope at `429df11f` gives 1594 tests (1640 − 46) — but that is an
*explanation* of the old figure's scope, not a measurement: the XMLs of that run have been
overwritten by later ones, so nothing on disk can confirm it. What is measured is this run's 1598 in
that scope, four more than the reconstruction, accounted for by this campaign's fix wave (2 in
`SchedulerSwitchContractGateTest`, 2 removal-fence tests in `SyncQueueRuntimeLifecycleTest`). The 2
failures and the 7 skips are identical in every one of these scopes, so nothing else in this section
moves.

**Not run:** the ≥60-minute soak (§7.1). No end-to-end run of the native release image (§7.2).

**One test in these counts is structurally racy and was not fixed** —
`actuatorPrometheus_exposesFunctionCountersAndLatencyTimer`, in `:control-plane:test`. Its green
here is a race won, not a check passed; the mechanism and the counts are in §7.8.

## 3. Values against the frozen budgets

Budgets are frozen in `budgets.json`; the harness echoes them into every artifact header, so no
figure below can have been compared against a different threshold. All eleven budgeted values, from
`RESULTS.md`:

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSwitchPauseMs` | 250 ms | **3.984 ms** (max over 1 140 measured switches) | PASS |
| `maxSwitchPauseP99Ms` | 100 ms | **0.266 ms** (p99 over the 1 000-switch phase — the harness's own in-process figure, not the soak's; §5) | PASS |
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

**The p99 budget was unexpressible, not unobservable.** `maxSwitchPauseP99Ms` is frozen at 100 ms,
and until `117e8303` the timer carrying the pause — `scheduler_switch_duration` — published no
buckets under any profile, so no criterion could read a percentile from it and no soak could have
bound the budget however long it ran. That commit put the timer into the set a non-basic profile
gives percentile histograms to (`FUNCTION_TIMERS` → `HISTOGRAM_TIMERS`), and `6fa6d969` records what
that costs: the default bucket set is **69 `le` classes**, so this timer goes from 3 series to
**72 per non-basic process** — flat, because it is registered with no `function` tag and there is
one engine per process. NanoLab then added a `percentile` operation that derives a p99 from such a
family the way Prometheus' `histogram_quantile` does, reading the family's growth across the
declared window rather than its level, and the switch policy now carries the budget as a criterion
on `scheduler_switch_duration_seconds_bucket` (`quantile: 0.99`, threshold `0.1` s — the same frozen
100 ms in the unit the meter serves).

**The arithmetic was checked on real data, and what that is worth.** The new criterion was replayed
over a saved run's own `_bucket` family — `function_latency_ms_seconds_bucket`, role
`control-plane`, a 900 s steady window with 89 000 observations per label set — and agreed with an
independent re-derivation to the digit: p99 = **0.00203613 s** for `word-stats-java` and
**0.000994436 s** for `word-stats-javascript` (the level-based reading, for contrast, would have
been 0.00209497 / 0.0009951). Two limits on that: the family is the *function latency* timer's, not
the switch timer's — the image this host can start predates `117e8303`, so the switch timer's own
buckets have never been scraped by this campaign — and the **budget itself remains unmeasured by a
soak**, which is the whole of §7.1. What `117e8303` buys is that the platform's own series now
exists, so a soak can read the p99 independently of the harness's observer.

**The budget was never without a measurement.** `RESULTS.md:146` carries a harness figure of
**0.266 ms** p99 over the 1 000-switch phase, computed in-process by the benchmark from the same
`enginePauseNanos` its own switch observer took (`SchedulerSwitchBenchmark.java:232`), and it did
not need Prometheus to exist. So the correct reading of the histogram work is *the platform now
publishes what only the harness could measure* — a cross-check between two instruments, not the
first number where there had been none.

**Rolling a release back.** Nothing about this change persists scheduler state: the selection is
memory-only, so the admin API can be ignored entirely by a rollback, and there is no stored
migration to reverse. A release is an immutable image tag (`docs/operations/image-releases.md`;
`latest` is not part of the release contract), and rolling back is re-deploying the previous tag
with the previous chart values. The one manifest value this change adds is
`controlPlane.scheduler.strategy`, defaulting to `""`, and the empty string means "no explicit
selection" — so an older chart rendering, or a values file that never sets it, is a supported
configuration rather than a broken one. In Compose the same variable defaults to empty. The admin
enabling flag is not a chart field this change added — it is reached through the chart's generic
`controlPlane.extraEnv` (§7.4) — so a rollback that restores the previous chart and values removes
nothing this change introduced, and a cluster that never set it is unaffected. What a chart-only
rollback does **not** do is clear an `extraEnv` entry the operator added themselves: that entry lives
in their values, so turning the switch API back off after a rollback is an explicit operator step
rather than something the chart undoes.

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
- **A second 1 000-switch run happened after this file was written, and it is not the soak either.**
  NanoLab's switch step was run against a live control plane for 200 s, and it satisfies
  `switchesInSoak: 1000` as well (§7.6). That is the point rather than an aside: the budget is a
  *count*, it carries no duration, and **`switchesInSoak` alone is therefore never soak evidence**.
  Nothing in this report may quote those 1 000 switches without their window.
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

### 7.3 The native artifact type: closed for `:control-plane`, still open for `sdks/java-lite`

`native-build-tools`' `NativeImagePlugin` turns `java-library` into
`options.getSharedLibrary().convention(true)`: a module that applies both `java-library` and the
GraalVM plugin produces a **shared library**, silently, and `nativeCompile` still exits 0. That is
what broke this repository's release path when `d456915f` (Task 10 of this campaign) added
`java-library` to `:control-plane`, and it stayed broken until `6b09b21d` fixed it — **33 commits and
a day later**, with no commit in between touching `.github/` or either native script, because **no job
anywhere compiled a native image**: `.github/workflows/gitops.yml` runs `./gradlew test` in several module
selections, the SDK/example tests and the watchdog tests, and the only other workflow is the CodeQL
security scan — neither built an image with the GraalVM plugin, so neither could notice what the
artifact was. The failure surfaced only because this campaign compiled natively by hand and looked at
the file.

Fixed in `6b09b21d` by `sharedLibrary.set(false)` on the `main` binary, with the chain written into
the comment beside it — and the CI half is fixed by this wave: the `test-native-artifact` job in
`.github/workflows/gitops.yml` compiles `:control-plane:nativeCompile` on the repository's pinned
GraalVM and runs `scripts/assert-native-executable.sh` on the result, which is exactly the `file`
assertion described above and **would have failed at `d456915f`**, the day the breakage arrived.

**The limit on that half, stated here rather than left in scratch.** The job **could not have passed
as first committed**: it called `scripts/install-graalvm.sh community <release> <java_version>
<arch>`, but the script is positional — `<architecture> <release> <java_version> [distribution]` —
so it built the case string `amd64:25.2.4:25.0.4:community`, matched no arm and exited 1 on the `*)`
branch. That is repaired (arch first, `community` last), and the repair is verified by replaying the
script's own parsing offline, not by a CI run:

```
$ sh scripts/install-graalvm.sh community 25.2.4 25.0.4 amd64      # as first committed
Unsupported GraalVM: amd64/25.2.4/25.0.4/community
exit=1
$ sh scripts/install-graalvm.sh amd64 25.2.4 25.0.4 community      # as repaired
<the script proceeds past the parse to fetch the distribution>
```

**And the `test-native-artifact` job has never executed** — not before the repair and not after it.
No GraalVM is installed on this machine and a native compile is not something this campaign could
run, so what is verified is the parse and the task graph, not a green job. Two things follow that a
reader should not have to infer: the first thing CI does with this job is its first-ever run of it,
and the part the replay above cannot show is the one that comes *after* the parse: the corrected
order is shown to select the `community:25.2.4:25.0.4:<arch>` arm and reach the fetch, and the fetch
itself is unexercised — which is why the replay block stops where it does rather than at a completed
download. The risks that remain for that first run are named in §7.2's spirit rather
than hidden here: the download and a cold native compile inside the 45-minute timeout, and the
runner's toolchain satisfying `native-image`.

What remains open is one artifact, deliberately: **`sdks/java-lite`** applies `java-library` (line 2)
and `org.graalvm.buildtools.native` and sets no `sharedLibrary` either, so its `nanofaas-lite-runtime`
binary is the same latent `.so` — and the assertion would fail on it today. The job is scoped to
`:control-plane`, the binary the release path and `scripts/native-java-image.sh` ship, because
nothing in the release path builds or consumes java-lite's image and what kind of artifact that SDK
should produce (a library for consumers, or an executable of its own) is unresolved; deciding it is
a change to a published SDK's build semantics rather than a fix at a release gate. It is named here
as the follow-up it is, and `scripts/assert-native-executable.sh` takes any number of paths, so
adding it once decided is one argument.

### 7.4 Enabling the switch API is a per-manifest operator action, and only Compose lacks one

`nanofaas.admin.runtime-config.enabled` defaults to **off**, deliberately and unchanged: the plan
requires the admin enablement to stay explicit, and nothing here changes it. What this section
records is what an operator must do in each shipped path, which is **not** the same in all three.
(This section's first version claimed the flag was unreachable "in both shipped manifests" and
attributed a PATCH-related comment to `deploy/compose/compose.yaml`; both were wrong and are
corrected here. That compose file contains no `PATCH` and no `runtime-config` at all — the comment
is the chart's, `deploy/helm/nanofaas/values.yaml:13-17`.)

- **Helm: supported, with a values override.** `templates/control-plane-deployment.yaml:78` renders
  `controlPlane.extraEnv` as a generic passthrough, and `values.yaml:13-17` — added by `28a460ae`,
  Task 13a of this campaign — tells the operator to use exactly that for exactly this flag. Verified
  by rendering the chart, not by reading it:

  ```
  $ helm template nanofaas deploy/helm/nanofaas \
      --set controlPlane.extraEnv[0].name=NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED \
      --set controlPlane.extraEnv[0].value=true --set controlPlane.scheduler.strategy=shared-queue
            - name: "NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED"
              value: "true"
            - name: NANOFAAS_SCHEDULER_STRATEGY
              value: "shared-queue"
  ```

  So the operator's action is two values: `controlPlane.extraEnv` to enable the API, and
  `controlPlane.scheduler.strategy` to pin the startup selection. No manifest edit.
- **Compose: no shipped path.** `deploy/compose/compose.yaml` lists the control plane's environment
  explicitly and names no such variable, so nothing exported from outside reaches the container. The
  operator must add the variable to the file or supply an override — which is what this task did, with
  the one-file overlay in `NANOLAB.md` §4 — and even then the image must be built with a queue module.
  That second condition is independent of the flag and was measured separately: an image built with
  `container-deployment-provider,runtime-config` (no queue module) and started with the flag **on**
  serves `/v1/admin/runtime-config` **200** — whose envelope's `namespaces` contains `control-plane`
  and nothing else — while `/v1/admin/runtime-config/scheduler` answers **404**. No engine, no
  `SchedulerControl`, no namespace, flag or no flag. The one-line passthrough proposed in `NANOLAB.md`
  §4 is a proposal about **this file only**.
- **Native: a build prerequisite, not a runtime setting.** In a native image the flag is evaluated
  when the image is built (§4), so no values override and no runtime environment change reaches it:
  the flag must be set before `nativeCompile`, and an image compiled without it has no admin route
  however it is started.

The published flag name is worth stating precisely, because two spellings circulate and a reader
copying the wrong one would get a route that never appears. Both work — measured, not argued. Two
containers from the same image, one spelling each, and three controls:

| environment | `/v1/admin/runtime-config` | `…/scheduler` |
|---|---|---|
| `NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED=true` (dash removed) | **200** | **200** |
| `NANOFAAS_ADMIN_RUNTIME_CONFIG_ENABLED=true` (chart's spelling) | **200** | **200** |
| *(no flag)* | 404 | 404 |
| `NANOFAAS_ADMIN_RUNTIMECONF_ENABLED=true` (wrong name) | 404 | 404 |
| `NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED=false` | 404 | 404 |

The controls are what make the two 200s mean something: the route is absent by default, absent under
a name that differs by one letter, and absent when the same variable is `false`. So the chart's
comment is correct as it stands and is left alone — it names a spelling that binds — and the compose
proposal in `NANOLAB.md` §4 names the other, which binds too. The mechanism behind that (relaxed
binding accepting both the dash-removed and the dash-as-underscore form of `runtime-config`) is an
inference from two measurements rather than something this task read out of Spring; the observable an
operator needs is the table, and it says either spelling turns the route on, and a misspelling does
not.

### 7.5 Six always-empty series, and a deny-list that still binds

The removed per-module loops took their observability with them: four tests were deleted, and
`QueueManager`'s six `function_scheduler_*` recorders have **no caller** and **zero test
references** — but their meters are still registered per function (`QueueManager.java:97-115`), so
`/actuator/prometheus` carries six permanently empty series. `MetricsProfileConfiguration.java:88,
:92, :93` still deny-lists them, which is **not inert**: the entries are what classifies those
series as `advanced` and keeps them out of `basic`. Leaving them is harmless, and re-wiring is
deferred cleanup for whoever next touches the metrics profile — but it is live classification of
dead series, not a no-op, and it is recorded here so it is not re-described as inert.

### 7.6 The soak driver now exists in NanoLab, and its 1 000-switch validation is not the soak

The procedure was executed by hand against the container stack and recorded in `NANOLAB.md`. It now
also has a durable home — in the **NanoLab repository**, on its own branch `feat/scheduler-switch-soak`
(not in this repository, and not on NanoLab's default branch):

- scenario `scenarios-v2/memory-soak-scheduler-switch-container.yaml`: warmup 120 s, baseline drain
  2 100 s, **steady 5 400 s**, drain 2 100 s, with `function-name-churn` among its required coverage;
- policy `scenarios-v2/scheduler-switch-soak-policy.yaml`, which binds the budgets as criteria;
- a step that reads the engine's own three meters — `scheduler_active`, `scheduler_switch_total`,
  `scheduler_switch_duration_seconds_max` — and asserts them, rather than trusting the driver's own
  tally;
- the switch receipt reaching the run's manifest (`acceptance-manifest.json`, wired by `f410fa9`),
  carrying **`window_s` and `elapsed_s` beside the count**.

**What was actually run, and the one reading it forbids.** The step was executed against a live
control plane for **200 s**: **1 000 switches committed, 0 stale, 0 refused**, over **199.786 s**
from the first to the last switch line of that run's log, worst pause **0.3178 ms** against the
frozen 250 ms, **2** live indexes, and the run ended on the strategy it started on
(`initial` = `final` = `per-function`). That validates the **step** — that the harness can drive hot
switches against a real control plane and assert what it drove. **It is not the soak, it is not an
approximation of the soak, and no reading of it is acceptance evidence.** The trap is exact and it
does not announce itself: the step's target count *is* `budgets.json`'s `switchesInSoak`, and so that
run **satisfies `switchesInSoak: 1000`** while running for three minutes. A count with no duration
cannot distinguish them, which is precisely why the receipt carries the window — so that "1 000
switches" cannot be read without "over 200 s" beside it. Its evidence is also **scratch, not an
artifact of record**: the log (`/tmp/cpcheck/drive.log`) and the driver's stdout receipt are not
committed anywhere, on either side.

**Correction to the "nothing floors the steady phase" claim, because the short version is false.**
`budgets.json` floors nothing — the frozen budgets carry no duration at all, which is the half that
matters and the reason for the receipt's window. But NanoLab's *configuration* does floor it, twice,
for this scenario: the policy's steady criteria declare `window_s: 5400` and `config/soak.py:437`
refuses any criterion whose window extends beyond its phase, while for `purpose: p24`
`validate_schedule` (`config/soak.py:57`) refuses a `steady_s` below three times the longest
retention — 3 × 1800 = 5400 s for this scenario's own retention policy. So `steady_s: 200` is
**refused** here rather than silently passing, and the campaign's 5 400 s is the minimum its
retention config and these declared windows jointly allow. The residual limit is the first half, and
it is the one that bites: no *budget* ties the 1 000-switch count to any duration.

**The ≥60-minute soak is still the pending step.** §7.1 is unchanged by any of the above: the driver
exists, the criterion is expressible, and the run that would exercise them for an hour has not been
made.

### 7.7 The retired queue facades are dead code that still ships

Nine classes the old per-module loops drove are still in `src/main` and run nothing:
`QueueManager`, `FunctionQueueState`, `QueueBackedEnqueuer`, `WorkSignaler`,
`AsyncQueueWorkloadMetricsSource` (all `async-queue`), `SyncQueueService`,
`SyncQueueInvocationEnqueuer`, `SyncQueueWorkloadMetricsSource` (all `sync-queue`) and the
`QueuedDispatchCapacity` SPI they implement (`control-plane-spi`). **None of them is referenced by
any live bean or constructor in `src/main`** — verified by grepping each name over
`platform/*/src/main` and `platform/modules/*/src/main` and reading the non-comment hits, which all
land inside the nine themselves: no `@Bean` produces one, no `new` of any of them exists outside the
cluster, and no live bean takes one as a constructor parameter or field. `SyncQueueWorkloadMetricsSource`,
which wraps `SyncQueueService`, is constructed by nobody in `src/main` at all — its only constructor
calls are in `SyncQueueWorkloadMetricsTest`. ADR 0002 §1 carries the same statement with the same
inventory.

**Why this is stated rather than done.** The plan allowed keeping facades that are *still
consumed*; none of these is, so the allowance does not cover them and the honest description is
"dead code still shipped" rather than "facades retained on purpose". They are not deleted here
because the fix wave sits at the release gate and a nine-class deletion with its tests and its
benchmark seam is a change of its own, with its own impact census and review. **Named follow-up:
delete all nine in one dedicated pass** — `SyncQueueConfiguration`'s javadoc already asks for the
census — and take the six `function_scheduler_*` recorders and the deny-list entries of §7.5 with
them. Until that happens, a reader of `src/main` should read these nine as deletion candidates,
not as a surviving code path.

### 7.8 One test in the suite is structurally racy, and was left as it is

`PrometheusEndpointTest.actuatorPrometheus_exposesFunctionCountersAndLatencyTimer` POSTs an
invocation and then asserts, on the next line,
`function_latency_ms{function="echo"}.count() >= 1`. That timer is not recorded on the thread that
serves the POST: it is recorded by the `AttemptObserver`'s `completed` callback through
`bestEffort`, on whichever thread concludes the invocation
(`ExecutionCompletionHandler.java:174` — the record call is inside a `bestEffort` lambda in
`completed`). So the assertion can run before the sample it counts exists. The race is
**structural**, not a slow host.

Observed, with the counts kept visible because they are small: red in **2 of 5** runs when a second
HTTP request on the management port was added to the same class, against **0 of 4** with that
addition absent and **0 of 5** with the registry-level version of the new pin. The agent who found it
declines — correctly — to claim that the extra request *is* the cause; what is claimed is the
mechanism above, and that the assertion has no wait in either version.

**It was not fixed here**, and the honest consequence belongs in this file rather than in scratch:
the test is in `:control-plane:test`, which the campaign's gate ran (§2). §2 records exactly two
failures and neither is this test — but a green there is a race **won**, not a check shown to hold,
and no one should read its presence in that suite as evidence that the assertion is sound. A fix is
a wait or a poll on the meter, and it is a change to a test outside this campaign's own work.

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
