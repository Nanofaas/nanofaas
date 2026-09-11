# P19 implementation report

Status: **BLOCKED after fix round 1**. Date: 2026-09-11. Sole implementer; no subagents.
Branch: `control-plane-lifecycle-memory`. P20b was not started.

The original report below is historical. The appended fix-round section supersedes
its acceptance, native and short-profile claims. No accepted P23 control is declared.

## Commits and scope

- `b404e53b5ad78f8915ed4713f6e4670a32e92d74` — Freeze corrected P19 lifecycle baseline.
- `85abb78ef9c1d6edb503a89271f401a829169103` — Record P19 verification and handoff.

Production/build source B is exactly `6d08303371d803f44187ec5f4e37827d54fec597`.
A is `61d72e73528db62cf8ca465c6a037981d7ec13b0`, the known-defective P00 diagnostic
comparator. No runtime implementation, dependency or build behavior was changed.
P23 must use B, not A, as its immutable pre-structural-movement control.

Read first: P19 brief, controller matrix inventory, performance protocol, section-8
requirements and preflight/rulings at the end of progress.md. Executing-plans and
verification-before-completion governed the existing approved plan. TDD and
systematic-debugging governed measurement defects; no owning runtime regression
failed and no new retained runtime owner was established. Self-review was performed
locally under the explicit no-subagents instruction, not claimed as independent review.
The final user timebox stopped additional framework and auxiliary campaign work.

## Artifact and files

Artifact path:
`docs/experiments/lifecycle-memory-2026-09/p19/dossiers/49f98dabf572a260125d77989db7802d7a0020c11595a9ea78250cb729e27380/`

Manifest SHA-256: `49f98dabf572a260125d77989db7802d7a0020c11595a9ea78250cb729e27380`.
Schema: `nanofaas-p19-dossier-v1`. Accepted control: B. 714 hashed payload files,
52,472,766 payload bytes, approximately 50 MiB. `baseline.json` points to this digest.

New files are confined to `docs/experiments/lifecycle-memory-2026-09/p19/`:

- `P19Probe.java`, `http_runner.py`, `campaign.py`: configured real HTTP measurement
  with a separate backend and generator; reflective observation only, no bean replacement.
- `contract.py`, `test_contract.py`, `test_http_status.py`, `test_dossier.py`:
  unique-terminal-success accounting, wire-state classification, pair identity,
  percentile and archive-integrity tests.
- `verification.py`, `verification.init.gradle`, `extra_gates.py`, `sdk_gates.py`,
  `package_smoke.py`, `native_smoke.py`, `summarize_evidence.py`: fresh existing gates
  and structured result collection. The init script excludes the protected untracked test.
- `dossier.py`, `README.md`, `protocol.json`, `observer-dictionary.json`, `.gitignore`,
  `baseline.json`: freeze/reverify, method and owner-observation semantics.
- `auxiliary.py`: preserved authored overhead experiment, **not run**, no acceptance credit.
- Content-addressed dossier, and separate `final-graph-gates.json.gz` for the
  precommit all/staged result that necessarily follows artifact freezing.

Modified ledger: `docs/experiments/lifecycle-memory-2026-09/STATO.md`.
This report is explicitly force-added because its planning directory is ignored.
All other source and protected paths are outside the commit scope.

The dossier contains exact source/build/environment/config/workload identities,
raw per-request JSONL gzip, per-run records, full sampled Prometheus, owner snapshots,
four verified-GC checkpoints/run, histograms, role-separated /proc observations,
raw test methods/XML/logs, comparison, hashes and failed exploratory attempts.
Only the three measured JVM jars are archived; all eight built jar entry identities
are recorded. Production/build source archives omit historical experiment prose;
the exact complete Git tree identity is recorded separately. Native image identity
is retained, not the entire container image/layers.

`executed-common-harness.tar.gz` preserves the identical source used by all six
SYNC runs. `harness.tar.gz` preserves the final ASYNC refinement and dossier tools.
The SYNC branch is unchanged. P23 must remeasure B if changing harness or protocol,
then alternate B/C contemporaneously; historical measurements alone do not establish
neutrality. Content hashes detect edits, not physically enforce write-once storage.

## Fresh verification commands and results

Work/evidence collection root: `/tmp/nanofaas-p19.AitJMN`; accepted evidence is copied
into the repository dossier and does not depend on that temporary path to inspect.
Exact argv, cwd, timestamps, durations and exit codes are in each `result.json`.

`python3 .../p19/verification.py /tmp/nanofaas-p19.AitJMN/verification G1 ... G18`
executes the exact controller inventory filters with `--rerun-tasks`,
`--offline --no-parallel --console=plain` and the protected-test exclusion init script.
Each group exit 0. G1–G18: **752 passed, 0 failed, 0 skipped**.

| Group | Passed | Group | Passed | Group | Passed |
|---|---:|---|---:|---|---:|
| G1 | 7 | G7 | 13 | G13 | 12 |
| G2 | 68 | G8 | 1 | G14 | 68 |
| G3 | 96 | G9 | 21 | G15 | 54 |
| G4 | 44 | G10 | 40 | G16 | 28 |
| G5 | 33 | G11 | 85 | G17 | 38 |
| G6 | 73 | G12 | 25 | G18 | 46 |

All R1–R8 are freshly green: R1/R2/R3/R4/R7 and both R8 methods in G1,
R5 in G6 and R6 in G14. `regression-map.json` names all nine methods. No known-red
exception is carried forward. Earlier transient commentary totals were corrected;
the method-derived 752 is authoritative.

Supplemental Java pass counts: sync-alone 73; offload-async 21; offload-sync 21;
scaler-combined 25; governor-combined 12; configured-T1 1; configured-T2 5;
contract-default 16; contract-all 16; catalog-cost 1; offload-http 6; metadata 22;
full JVM SDK 237 (Java 143, Java-lite 81, warm-echo 13); selector 30. Total 486.
Actual Docker adapter lifecycle: 2. Thus **1,240 Java test invocations**, including
intentional repeated profile selections, not 1,240 distinct test methods.

SDK gates: Python 139, JavaScript 80, Go normal 116 and race 116 including subtests,
shared validator 26; all exit 0, no failed/skipped tests. Go uses the cached supported
1.24 toolchain, fresh `-count=1` JSON and `-race`; vet exits 0 (no test-case count).
Python emits one deprecation warning. Non-JUnit/native command counts are explicitly
not_applicable rather than invented zeros. SDK ownership assertions execute real
runtime owners/callbacks/stop; no standalone numerical SDK-heap claim is made.

Four invalid selections (unknown, none+offload, async+sync, k8s+container providers)
exit the expected 1 with selector diagnostics, not dependency errors. Seven packaged
JVM selections build and pass health/OpenAPI/start-stop: none, async-queue, sync-queue,
sync-queue+runtime-config, container-deployment-provider, all, default.

T3–T9 use existing focused deterministic and loopback tests, covering divergent
waiters/offload, physical cancellation, retries, churn/fences, blocked replica provider,
wake-up, input/proxy/callback bounds, bounded stop and catalog mutation. Consumer
selections are exercised separately and together. P15 mutation cost at 1/100/1000
functions is fresh. P14 retention tests are fresh; its previously accepted standalone
measurement decision is retained, not freshly remeasured. No second cross-hop framework
or full integrated numerical profile was invented.

## Measured run table

Real configured SYNC EXTERNAL common surface: 6,000 warm-up offers then 12,000
measured offers at fixed 200/s, max 32 outstanding; separate deterministic backend
echoes unique IDs. All six run exits 0 and `valid=true`; each offered/admitted/unique
success count is 12,000, refused/terminal-failed/transport-error/unresolved all zero.
Each backend records exactly 18,000 warm-up+measured attempts. Order is
A1/B1/A2/B2/A3/B3, fresh processes, no competing build in the sequence.

| Run | Useful unique successes/s | p50 ms | p95 ms | p99 ms | Allocated bytes/success | Post-policy heap bytes |
|---|---:|---:|---:|---:|---:|---:|
| A1 | 199.979 | 1.742 | 3.369 | 4.027 | 138143.041 | 40522672 |
| B1 | 199.945 | 1.671 | 3.428 | 4.161 | 144013.697 | 41444160 |
| A2 | 199.964 | 1.714 | 3.407 | 4.022 | 138906.887 | 40310696 |
| B2 | 199.955 | 1.634 | 3.304 | 3.920 | 144009.636 | 41334320 |
| A3 | 199.961 | 2.071 | 3.375 | 4.036 | 138335.490 | 40419896 |
| B3 | 199.994 | 1.695 | 3.350 | 3.956 | 143947.032 | 41139496 |
| B-async-1 | 50.150 | 12.487 | 22.933 | 26.891 | 1136868.200 | 25646960 |
| B-async-2 | 50.197 | 11.476 | 20.634 | 27.089 | 1137968.533 | 25504008 |
| B-async-3 | 50.064 | 15.209 | 22.937 | 25.130 | 1145169.867 | 25494544 |

Three ASYNC runs have 20 warm-up and 120 offered/admitted/unique successes each,
fixed 50/s, four flat/deep20/wide4096/large262144 shapes, 1 MiB archive budget,
2 s TTL/3 s maxLifetime and 6 s post-policy wait. Ordinary ASYNC latency includes
bounded terminal-observer polling and the initial archive visibility check; admission
acknowledgement and server terminal timestamp are also stored separately.

R1 intentionally declines deep/wide archive outcomes. The best-effort existing
terminal hook records scalar identity/state/output-id/time only, not payload/record,
bounded at 4096 entries and zero at quiescence. Recent declined deep output stays 404;
still-protected key replay returns 410 without backend redispatch. This is **declined
output replay**, not proof of previously-visible capacity eviction; fresh R2 owns
the actual eviction race. Nonvalid archive-polling/expiry pilots are preserved.

Separate fresh P07 configured T2 saturation: large offered 100/admitted 63/refused 37
on input quota; other shapes 100/100. The supporting record separates refusal and
admission. Its old mixed-latency/per-admission allocation semantics and P07 historical
microbenchmark/fixed-duration numbers are not reused as performance acceptance.

Paired B-vs-A p95 changes: +1.769%, −3.035%, −0.747%; p99: +3.349%, −2.546%,
−1.963%; allocation/success: +4.250%, +3.674%, +4.056%. Useful-rate deltas remain
within ±0.017% because offered work is fixed. These are diagnostic corrected-owner
costs, **not** capacity, equivalence or a P23 neutral-move verdict.

JDK 25.0.4, aarch64 Linux, affinity cores 5–8 (four Cortex-X925), G1,
`-Xms256m -Xmx512m -XX:ActiveProcessorCount=4`; affinity inherited by all three
roles. Full host/version/process/environment records are frozen. Affinity is not
exclusive reservation; unrelated host services exist. Latency uses all successful
raw samples and nearest-rank percentiles, with intended-arrival lag separately.
Warm-up, explicit GC, histograms, policy wait, registration/removal/stop are outside
ordinary latency windows. Six 2,000-sample segments/run expose within-run variation.

Allocation uses process-total `getTotalThreadAllocatedBytes`, not a heap delta or
enumeration of surviving threads. Escaping 64 MiB validation observed 67,155,880
bytes after platform thread exit and 67,430,424 after virtual thread exit: both valid.
Delta through cohort drain is divided by unique terminal successes; includes probe
and failed-attempt costs if present, excludes separate backend/generator/Gradle.
Separate observer-overhead passes were not run under the timebox: cost is included
and unquantified, not assumed zero.

All B quiescent checkpoints have zero live records, execution/input/copy/waiter
reservations and terminal-observer events. Function capacity/name/registered-meter/
retiring-offload owners reach zero on removal. Raw keys/outcomes, weighted archive
bytes, queue/executor/pool state, buffers, thread/socket counts, full metric labels,
process RSS/PSS and every practically accessible field are retained. Missing future,
raw-reference, timer/subscriber or external/unselected owner observations are explicitly
unavailable/not_applicable in the observer dictionary, supported separately by focused
tests where possible; aliases and cumulative sequences must not be summed as owners.

Cache size accessors invoke existing maintenance before `System.gc`; GC count must
increase before used heap is sampled. These are observer-assisted post-policy
checkpoints, not untouched-idle cleanup proof. Small pool scan residuals can remain
until policy scan/context close. Histograms use `-all` after GC and can include new
observer garbage; they are not dominator evidence. Warm-idle to measured-drain
framework/Prometheus heap growth is visible, not silently subtracted or classified
as a proven new retainer. Stable repeated post-policy heaps and owner drain are a
short control; P24 soak remains necessary. Server termination is observed as process
absence (SIGTERM exit 143), not fictitious post-exit zero heap.

## Limitations, failures and smallest valid fallbacks

- Sandbox Gradle cache/socket and Docker permission failures were retried with approved
  escalation. Docker 29.2.1 was genuinely available; two real lifecycle tests passed.
  Temporary test containers/network were cleaned; no existing resources were deleted.
- Selector offline dependencies were missing (JUnit 5.10.3/ByteBuddy 1.14.18); online
  resolution succeeded and all 30 selector tests passed. Rejected invocation logs
  (including a duplicated Gradle console option) remain diagnostic only.
- Minimal native: `CONTROL_PLANE_MODULES=none NATIVE_BUILD_MEMORY=4g
  NATIVE_PARALLELISM=4 timeout 900 scripts/native-java-image.sh control-plane
  nanofaas/control-plane:p19-6d083033-none`, from exact B archive, exit 0. Native
  health/OpenAPI/start-stop smoke passed. Image:
  `sha256:e5cd87f4e0d6c0a12c9a8a9563e0cb4d138682ef38b1b52c35774b48910d76af`.
- Same native build with `CONTROL_PLANE_MODULES=k8s-deployment-provider` failed at
  414.4 s with compiler Java heap OOM under `-Xmx4g`: native-image exit 3, outer exit 1.
  **Not green**, not absent Docker and not a discovered product-runtime defect.
  No indefinite larger-memory redesign/retry: fresh JVM G11/G16 and all/default
  packaged startup are the user-directed bounded fallback.
- `multipass list --format json` exit 0 returned no VMs; host kubectl/native-image
  absent. No provisioned cluster/NanoLab Kubernetes lifecycle run and no native managed
  performance claim. Full NanoLab multi-runtime container scenario was not run;
  real Docker adapter, per-hop loopback and all five SDK ownership suites are the
  proportional fallback, not an integrated deployment claim.
- `helm lint deploy/helm/nanofaas` exit 0: one chart, zero failed, icon recommendation.
  `helm template p19 deploy/helm/nanofaas` exit 0, 22,591 rendered bytes.
  `docker compose -f deploy/compose/compose.yaml config --quiet` exit 0. Initial empty
  Helm capture was explicitly rechecked; these validate configuration, not deployments.
- Initial dossier freeze exited 1 on checksum-list order (Path tuple order differs
  from JSON sorted string order). The entries and hashes were identical as sets;
  sorted checksum emission fixed the packaging defect. Full re-freeze/verify and
  nine contracts are GREEN. The rejected dossier was moved recoverably to
  `/tmp/nanofaas-p19-rejected-dossier-2b827c5b`, not accepted or staged.

## GitNexus, self-review and preservation audit

Cached GitNexus 1.6.11 CLI was used when the repository wrapper attempted unavailable
npm resolution. Graph context checks precede consumption of named runtime symbols
(application/controller/store/capacity/dispatch/waiter/key classes). Upstream checks
cover edited probe and runner symbols and dossier/verification functions. LOW/UNKNOWN
results are preserved under artifact `graph/`; UNKNOWN/not-found Python and dynamic
entrypoint edges were resolved with `rg`, never equated to zero callers. No pre-edit
HIGH/CRITICAL verdict was observed. One measurement-only `P19Probe.main` refinement
had its exact main impact run retrospectively after prechecking snapshot/inspect:
this is an explicit workflow caveat, not claimed perfect pre-edit compliance.

Index-only refresh completed in 24.4 s: 21,948 nodes, 62,669 edges, 762 flows.
Architectural context can still carry receiver-typing/dispatch/process-enumeration
boundaries; no full-architecture absence-of-impact claim is made. Final backend
`detect_changes` all and staged (also CLI `detect-changes --scope ... --limit 20000`)
were inspected completely, with no error/partial/truncated/UNKNOWN result:

- all: 261 textual changed files, 79 symbols, four flows, MEDIUM;
- staged: 259 textual changed files, 78 symbols, four flows, MEDIUM.

These are graph/text counts, not all 738 committed paths: compressed/raw binaries
and unindexed dossier records do not become program symbols. Text/manual review
supplemented those gaps, the new final harness and final STATO section. Four flows
are probe observation/helpers and runner request encoding; no runtime code changed.
Raw gate payload: `p19/final-graph-gates.json.gz`, SHA-256
`d7bf2747341910312051e2655cec46dd10eaca28dbde44a046d241ae0bb031d1`.
The report-only follow-up was separately checked all/staged after a 19.2 s refresh
(22,316 nodes, 63,006 edges). All: three textual files/one symbol/zero flows, LOW;
staged: one textual file/zero symbols/zero flows, LOW; both exit 0, no error,
partial or truncated flags. The ignored planning-report path is not mapped to graph
symbols despite explicit staging: its zero is an index gap, not evidence of no
effect. `git diff --cached` and text review confirm only this report and compressed
graph evidence, with no function/class/method changes. The final report-gate raw
payload is also preserved as `p19/final-report-graph-gates.json.gz`.

Self-review checked unique IDs/terminal denominators, refused/failed/unresolved
separation, exact A/B workload/config identity and run order, observer limitations,
rejected pilot exclusion, allocation validation, GC windows, source/jar identities,
content checksum tamper handling and P23 retrieval instructions. No independent
review is claimed. `git diff --cached --check` returned 2 only for the verbatim
copied requirements' pre-existing terminal blank lines (brief and inventory).
`git -c core.whitespace=-blank-at-eof diff --cached --check` exited 0; immutable
requirements were not rewritten merely to suppress whitespace diagnostics.

`sha256sum -c /tmp/nanofaas-p19.AitJMN/protected.sha256` passed for both overload-path
dirty files, untracked `ReplicaStatusSnapshotConfigurationTest.java` and all six
GitNexus skill directories. Protected files never staged. Original protected patch
is retained only in temporary workspace, not in the dossier. `git diff B -- platform
sdks services openapi deploy` is empty. Commit scope is measurement/docs only;
no checkout/reset, external push, P20b work or subagent was performed.

Final verification, both exit 0:

```bash
python3 -m unittest discover -s docs/experiments/lifecycle-memory-2026-09/p19 -p 'test_*.py'
python3 docs/experiments/lifecycle-memory-2026-09/p19/dossier.py verify docs/experiments/lifecycle-memory-2026-09/p19/dossiers/49f98dabf572a260125d77989db7802d7a0020c11595a9ea78250cb729e27380
```

Results: 9 tests, 0 failures/skips; VERIFIED manifest digest and all 714 payload
files, including replayable raw common-run accounting. Concerns are bounded-method
and environment/workflow limitations above, not an R1–R8 known-red exception.

## Fix round 1 — 2026-09-11 — BLOCKED

Latest user timebox was honored: no further harness features after the second
timebox; current contracts, profiles and native checks executed, evidence frozen.
Review findings 3/4 are closed; 1 is partial and 2 remains open with a concrete
production-native failure. Correctness acceptance is not inferred from an integrity
check. Exact B remains unchanged. Fixing native runtime hints requires owning-task
TDD and reconciliation of the immutable exact-B requirement, not an unrecorded
production change in this measurement round.

Commit: the commit containing this section (`git log -1 --format=%H --
.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P19-report.md`).
Previous commits are listed above; this round is measurement/docs only.

### Changes and red-green evidence

Receiving-code-review, systematic-debugging and test-driven-development were used
to reproduce concrete defects before fixing them. No subagents were used.

- `contract.py`, `dossier.py`, `test_revision_identity.py`, `test_dossier.py`:
  exact A/B revision-to-jar bindings, stable distinct artifacts, rehashed actual
  config/workload documents, raw command/function/workload/latency schedule checks,
  boot/PID/start-tick uniqueness and chronological whole-process non-overlap.
  Identity RED: 8 tests, 7 failures; binding RED: 4 tests, 1 failure, each exit 1.
  Final combined portable suite: 20 tests, 0 failures/errors/skips, exit 0.
- `http_runner.py`, `campaign.py`, `supervision.py`, `test_process_cleanup.py`:
  first child starts inside cleanup protection; timeout owns a process session,
  sends TERM, waits bounded grace, then KILL; retained supervisor/failure records
  and bounded no-live-member verification. RED: both cleanup tests failed, exit 1.
  GREEN includes second-spawn failure and a grandchild ignoring TERM. Nine current
  campaign supervisor records report exit 0 and no remaining live group members.
- `P19Probe.java`, `P19ProbeContract.java`: direct live-store completion-future
  census; RED missing observer (NoSuchMethodException, exit 1), GREEN real futures
  retained=2/open=1 then open=0, exit 0. External aliases remain unavailable.
- `short_profiles.py`: bounded existing HTTP/probe fixtures exercise partial
  T3/T4/T5/T7/T9; no second integration stack. Rejected initial assumption that a
  logical timeout is HTTP 504 is preserved: actual wire is HTTP 200 with timeout
  body. Expectations corrected without runtime changes.
- `native_smoke.py`, `native_integration.py`: smoke now requires invocation,
  replay identity and shutdown, with minimal EXTERNAL and managed Docker fixture.
  The separate native integration wrapper is not included in portable test
  discovery and was not independently counted; actual smoke commands below fail.
- `test_contract.py`, protocol, observer dictionary, README, baseline pointer and
  ledger describe the strengthened evidence and remaining gaps. Old dossier is
  immutable and preserved. Current strict verifier targets new metadata; use the
  archived old verifier when inspecting the historical v1 artifact.

### Exact executed commands and outcomes

All paths below are from repository root; W abbreviates
`/tmp/nanofaas-p19-r1.9kgN9P`, H abbreviates
`docs/experiments/lifecycle-memory-2026-09/p19`. Raw commands, timestamps and results
are retained in the dossier. No unavailable external service was waited on.

```bash
python3 -m unittest discover -s docs/experiments/lifecycle-memory-2026-09/p19 -p 'test_*.py'
java -cp /tmp/nanofaas-p19-r1.9kgN9P/probe P19ProbeContract
java -cp /tmp/nanofaas-p19-r1.9kgN9P/probe P19Probe validate-allocation
taskset -c 5-8 python3 docs/experiments/lifecycle-memory-2026-09/p19/campaign.py /tmp/nanofaas-p19-r1.9kgN9P
python3 docs/experiments/lifecycle-memory-2026-09/p19/native_smoke.py /tmp/nanofaas-p19-r1.9kgN9P none
python3 docs/experiments/lifecycle-memory-2026-09/p19/native_smoke.py /tmp/nanofaas-p19-r1.9kgN9P container
python3 docs/experiments/lifecycle-memory-2026-09/p19/dossier.py freeze /tmp/nanofaas-p19-r1.9kgN9P
python3 docs/experiments/lifecycle-memory-2026-09/p19/dossier.py verify docs/experiments/lifecycle-memory-2026-09/p19/dossiers/70ea3494d86e6435ac2de1cbb207311079135f129c573af8f45d09968bfd66d0
```

Exits respectively: 0, 0, 0, 0, **1, 1**, 0, 0. Final contracts output:
`Ran 20 tests in 0.816s / OK`; Java observer output:
`P19ProbeContract PASS: 2 retained futures, 1 open, then 0 open`.
Collector validation: 67,108,864 escaped payload bytes each; platform observed
67,155,880, virtual observed 67,430,808, both valid after thread exit.
Short profiles invoked current `short_profiles.py W SELECTION` through the same
`supervise(..., timeout=120)` helper for `none`, `all`, `sync-queue,runtime-config`;
all three exit 0. Their exact commands and processes are archived.
Campaign is six alternating fresh common processes plus three ASYNC processes;
aggregate campaign exceeded ten minutes by construction, no individual run
exceeded its 300s/120s bound or required unavailable infrastructure.
Already-proven G1–G18/R1–R8, SDK and JVM packaging evidence is inherited from the
previous dossier with original timestamps, NOT rerun or represented as fresh in
this round. No known-red native gate is excepted into green acceptance.

### Fresh run table

Each common run: 6,000 warm-up offers, 12,000 measured offers/admissions/unique
terminal successes at 200 offers/s, no refusal/unresolved work. Each ASYNC run:
20 warm-up and 120 measured offers/admissions/successes at 50 offers/s, no refusal.
Common actual config digest:
`2c6df920400cc83eef6842ce17881278453687982963f2a6a4d34b881cde04eb`.
Latencies below are milliseconds; allocation is process bytes per unique success,
including observer allocations; heap is post-policy post-GC used bytes, outside
ordinary latency windows. Raw exact nanoseconds and every checkpoint are archived.

| Run | Useful successes/s | p50 | p95 | p99 | Allocation B/success | Post-policy heap B |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | 199.995905 | 1.310748 | 3.045463 | 3.664533 | 138309.295 | 40211280 |
| B1 | 199.965826 | 1.508395 | 3.064343 | 3.711573 | 144070.911 | 41287432 |
| A2 | 199.995604 | 1.532859 | 2.948183 | 3.709205 | 137954.645 | 40372576 |
| B2 | 199.962545 | 1.493803 | 3.301638 | 3.868932 | 143852.758 | 41490280 |
| A3 | 199.994624 | 1.483228 | 3.256278 | 3.819796 | 137572.721 | 40400296 |
| B3 | 199.973984 | 1.727323 | 3.184199 | 3.781540 | 143690.369 | 41269160 |
| B-async-1 | 50.206860 | 12.436730 | 22.558171 | 27.502284 | 1138873.067 | 25661720 |
| B-async-2 | 50.138949 | 11.909852 | 22.228092 | 27.358140 | 1137680.200 | 25422288 |
| B-async-3 | 50.216336 | 11.524925 | 20.546625 | 24.858820 | 1145156.533 | 25397944 |

Paired deltas and segmented latency distributions are in `comparison.json`.
A is diagnostic only; these are not maximum-throughput/equivalence results.
Brief native diagnostic smoke overlapped A2, although native compilation did not;
host affinity is not exclusive reservation. Observer-overhead paired passes were
not run under timebox; attribution remains unavailable. These limits prevent an
unqualified causal attribution of small A/B differences.

### Short profiles: finding 1 PARTIAL, not closed

| Selection | Cases | Snapshots | Duration seconds | Exit |
| --- | ---: | ---: | ---: | ---: |
| none | 7 | 331 | 19.098379 | 0 |
| all | 8 | 343 | 20.467105 | 0 |
| sync-queue,runtime-config | 8 | 329 | 18.878098 | 0 |

23 case records / 1,003 snapshots, sampled every 50ms plus collection cost.
T3 exercises divergent short/long waiters on one key, shared terminal success and
replay identity, including offload false/true on all. T4 exercises retry (two
physical attempts), administrative expiry while the physical backend remains
active, and sync queue toggle during work. T5 removes/re-registers three names
while old HTTP responses are suspended and verifies new-generation replay after
late completion. T7 uses 256KiB payload/output plus >1MiB refusal (413). T9 checks
physical backend drain after each case and final post-policy removal/GC.
Observed maxima per selection: live=1, keys=3, outcomes=6, reservations=1,
canonical/copy bytes=524802 each, waiters=2, live-store open futures=1.
Final public live/reservation/input/copy/waiter/key/outcome populations and observed
live-store open futures are zero; physical backend active=0. Raw owner_fields and
Prometheus snapshots retain practically mapped owners without summing aliases.
This does NOT establish zero independent future aliases/timer/subscriber handles.

Still missing: T6 blocked replica-provider/shared wake-up profile, T8 each SDK
process, complete proxy/callback/slow-client numerical ownership and full
multi-destination churn. These are **open evidence findings**, not unavailable
infrastructure and not closed by inherited focused tests. Unselected owners remain
not_applicable to that process; selected but unobservable owners are unavailable.

### Native: finding 2 OPEN, concrete correctness blocker

Managed build from exact B archive at `/tmp/nanofaas-p19.AitJMN/native-source`:

```bash
CONTROL_PLANE_MODULES=container-deployment-provider NATIVE_BUILD_MEMORY=8g NATIVE_PARALLELISM=4 timeout 600 scripts/native-java-image.sh control-plane nanofaas/control-plane:p19-6d083033-container
```

Exit 0; suitable local Docker route exists. Managed image
`sha256:9d3dc1671abff05834223bdd47c69be5e4630beadb9cc134f5dfd19be658fe1c`;
minimal image `sha256:e5cd87f4e0d6c0a12c9a8a9563e0cb4d138682ef38b1b52c35774b48910d76af`.
An initial managed attempt used the CLI adapter, unavailable inside Distroless;
that rejection is preserved separately. Selecting the existing
`--nanofaas.container-local.runtime-adapter=docker-java` completes actual managed
Docker provisioning (201), but invocation still fails with HTTP 500 in both
minimal and managed selections. Logs identify:

`UnsupportedFeatureError: Record components not available for record class
it.unimib.datai.nanofaas.common.model.InvocationResponse`.

The controller's erased response type does not supply that DTO's required native
record reflection metadata. Shutdown also logs `MissingReflectionRegistrationError`
for `java.util.concurrent.ThreadPoolExecutor.shutdown()` on
`deploymentWakeUpTimeoutScheduler`. Neither is an infrastructure exception.
Replay is unreached after invocation fails, so no native replay success claimed.
Both final smoke commands exit 1, preserve `valid:false` failure records and logs;
container stop/removal exit 0, but application shutdown correctness is still red.
Scoped `docker ps -a --filter name=nanofaas-p19-smoke` is empty after cleanup.
No runtime hint patch, P20b work or external deployment was performed. The old
k8s 4GiB OOM/absent-cluster limitation remains historical, not a substitute green.

### Artifact and preservation

New schema `nanofaas-p19-dossier-v2`, status BLOCKED, `accepted_control:null`:
`docs/experiments/lifecycle-memory-2026-09/p19/dossiers/70ea3494d86e6435ac2de1cbb207311079135f129c573af8f45d09968bfd66d0`.
Manifest SHA-256 `70ea3494d86e6435ac2de1cbb207311079135f129c573af8f45d09968bfd66d0`.
Freeze/verify output: `VERIFIED` with that digest and **608 payload files**.
Contains raw runs/profiles/native failures, exact sources/build entry hashes,
measured binaries, compiled observer, config/workload/environment identities,
red-green logs, inherited regression results, checksums and `fix-round.json`.
The previous `49f98d…` immutable dossier is unchanged and superseded, not erased.

Protected SHA-256 audit passed for both overload-path dirty files, all six
untracked GitNexus skill directories and the untracked snapshot test. No protected
path is staged. `git diff 6d083033… -- platform sdks services openapi deploy` is
empty. Self-review covered new diff, raw native failures, real profile scope,
paired identity rejection and child lifetime records. No new runtime retainer was
proven, and no claim of full numerical ownership coverage is made.

### GitNexus fix-round evidence

All existing edited symbols were checked upstream before edits; new consumers had
graph context checks. Raw exact-symbol checks live under dossier `graph/`:
validate_pairs, run/proc_snapshot, snapshot/liveOwners consumption, smoke,
profiles, supervise, verify/freeze and affected test fixtures. LOW/UNKNOWN results;
UNKNOWN/not-found/dynamic edges were resolved with `rg`, not treated as unused.
No HIGH/CRITICAL result occurred. Prior retrospective P19 probe-main impact debt
remains disclosed above; this round does not erase it.
Final index-only refresh: exit 0, 24.9s, 22,372 nodes, 63,147 edges, 763 flows.
Final complete all/staged detect-changes and preservation results are recorded in
the precommit gate addendum below; compressed raw gates are outside the immutable
dossier to avoid self-referential hashing. Text review supplements ignored report,
new Python/dynamic boundaries and compressed artifacts not mapped as symbols.

Precommit gate addendum: complete LocalBackend `detect_changes` scope all and
staged, explicitly bound to `/home/michele/Documenti/nanofaas`, both exit 0.
All: 205 textual files, 62 symbols, five flows, MEDIUM. Staged: 203 textual files,
61 symbols, five flows, MEDIUM. No error/partial/truncated/UNKNOWN verdict in raw
payload. An oversized terminal rendering was truncated; complete compact field
and symbol output was reread, so that rendering was not accepted as the gate.
Five affected flows are Profiles→Encoded, Main→Call/Bean/GcCount, Run→Encoded,
all measurement helpers. Text review confirms campaign top-level calls and ignored
planning report are covered despite absent graph symbols. Compressed binaries/raw
records are not represented by these textual-file counts.
Raw payload: `p19/fix-round-1-graph-gates.json.gz`. Final report/addendum and binary
gate payload are restaged and both scopes rechecked immediately before commit.
Whitespace check exit 2 is only pre-existing final blank lines in the verbatim
copied brief/inventory; `git -c core.whitespace=-blank-at-eof diff --cached --check`
exit 0. Protected staged-path query and old-dossier staged query both empty.
Final fresh portable recheck: 20 tests, 0 failures/skips, exit 0 (0.847s); full new
dossier verifier: exit 0, VERIFIED 608 payload files. BLOCKED status is unchanged.

## Fix round 2 — native diagnosis and product fix (in progress)

The user explicitly authorizes advancing B from 6d083033 to the final product-fix
commit. A remains 61d72e73528db62cf8ca465c6a037981d7ec13b0, diagnostic only.
Work directory `/tmp/nanofaas-p19-r2.wEbAOQ`; no subagents or P20b.

Systematic debugging phase 1: reread both complete minimal/managed native traces
from round 1 and the actual InvocationController, response model, wake-up bean and
hint import boundaries. Both reproductions fail after actual terminal completion,
inside AbstractJacksonEncoder→ObjectWriter→RecordUtil→Class.getRecordComponents:
this is response SERIALIZATION, not failed request deserialization. Shutdown is
DisposableBeanAdapter.invokeCustomDestroyMethod→Method.invoke, targeting native
ThreadPoolExecutor.shutdown. The factory declares ScheduledExecutorService but
creates ScheduledThreadPoolExecutor. Recent history: adc272b4 wake-up ownership,
4483034a catalog record hints, bcbbf22a timeout bound. No scheduler ownership policy
change is needed to repair missing metadata.

Phase 2: compared complete FunctionCatalogRuntimeHints and its three tests (same
erased-object Jackson record pattern), ProcessorMetricsRuntimeHints (explicit
reflective JDK target) and ReplicaStatusSnapshotConfiguration (owned close).
Phase 3 hypotheses tested independently: (a) erased ResponseEntity<Object> hides
InvocationResponse binding metadata; (b) scheduler interface return type does not
register the concrete shutdown targets used reflectively at native destruction.

Phase 4 TDD, before production changes: new InvocationNativeHintsTest discovers
the application's actual imported registrars and checks every record accessor and
the real scheduler's shutdown Method with RuntimeHintsPredicates.
Command for each run below (console=plain, protected snapshot test excluded):
`./gradlew :control-plane:test -PcontrolPlaneModules=none -I
docs/experiments/lifecycle-memory-2026-09/p19/verification.init.gradle --tests
'*InvocationNativeHintsTest' --console=plain`.

- Initial `--rerun-tasks`: exit1, 7 tests/2 failures (the two new regressions);
  missing executionId accessor and concrete ScheduledThreadPoolExecutor.shutdown.
- Add only InvocationResponse binding registrar/import: exit1, 7 tests/1 failure;
  response regression GREEN, scheduler still RED.
- Extend scheduler regression to the exact ThreadPoolExecutor.shutdown method in
  the native trace: exit1, expected missing parent-method metadata. Native and JVM
  reflection expose different concrete targets when override metadata is absent;
  both observed methods, not all executor methods, are registered explicitly.
- Add only no-argument shutdown hints for ScheduledThreadPoolExecutor and its
  ThreadPoolExecutor parent. Same command plus `--tests '*FunctionCatalogRuntimeHintsTest'
  --tests '*DeploymentWakeUpGateTest'`: exit0, BUILD SUCCESSFUL in 9s. Exact XML and
  RED/GREEN logs saved before subsequent build reuse.

Production changes are only new InvocationLifecycleRuntimeHints plus its import in
ControlPlaneApplication; runtime algorithms, scheduler instance/policies and JVM
execution behavior are unchanged. Native images still must be rebuilt and exercised;
JVM hint predicates alone are not declared native GREEN. B will identify this
product commit; later measurement/report-only commits do not alter its runtime.

GitNexus exact upstream checks preceded the application class annotation edit and
new registrar method refinement. UNKNOWN class/dynamic-entrypoint results were
resolved with rg: application bootstrap, two injected scheduler consumers and
focused tests identified; no HIGH/CRITICAL result. New test consumed graph-checked
application/model/scheduler symbols. Protected SHA-256 audit remains green.

### Fix round 2 — final execution and re-review handoff

Status **BLOCKED**, `accepted_control: null`. Finding 2 is closed by actual minimal
and managed native execution. All missing finding-1 surfaces now have numerical
HTTP attempts, but strict multi-destination pool drain fails. Findings 3/4 remain
closed. Per final user timebox, no further code/design: send the failure to re-review.
No subagents, no P20b.

Product B: **b4770d6675adc99f53444260dcf0200f10b517cf**, `Register native invocation
and scheduler lifecycle hints`, explicitly superseding 6d083033. A remains
61d72e73528db62cf8ca465c6a037981d7ec13b0, diagnostic only. Subsequent changes are
measurement/docs only: diff against B for platform/sdks/services/build files is
empty. Measurement commit and new dossier digest appear in the closing addendum.

W=`/tmp/nanofaas-p19-r2.wEbAOQ`; H=`docs/experiments/lifecycle-memory-2026-09/p19`.
All command logs, structured supervision, XML and per-run raw records are retained.

#### Exact verification and native RED/GREEN

- Preserved XML: initial RED 7 tests/2 failures/0 skips; response-only GREEN with
  scheduler RED 7/1/0; final GREEN **46/0/0** (2 invocation hints, 36 wake-up gate,
  3 catalog hints, 5 architecture). Additional exact native parent-method RED is
  native-parent-hint-red.log/XML. Commands and hypotheses are above.
- `./gradlew :control-plane:bootJar -PcontrolPlaneModules=<selection>
  -PnanofaasBuildRevision=b4770d6675adc99f53444260dcf0200f10b517cf
  -PnanofaasBuildDirty=false --console=plain`: none, async-queue, all,
  sync-queue,runtime-config, container-deployment-provider; **all five exit 0**.
- `python3 H/verification.py W/verification G1`: exit 0, **7 passed/0 failed/0
  skipped**, R1/R2/R3/R4/R5/R7/R8. `... G14 G11`: exit 0; **G14 68/0/0** includes
  R6; **G11 85/0/0**. All R1–R8 fresh green on B. Other unchanged groups explicitly
  inherited from round 1 via inherited-evidence.json, not claimed fresh on B.
- Native source exported from committed B, excluding dirty checkout files.
  `CONTROL_PLANE_MODULES=none NATIVE_BUILD_MEMORY=8g NATIVE_PARALLELISM=4 timeout
  600 W/native-source/scripts/native-java-image.sh control-plane
  nanofaas/control-plane:p19-b4770d66-none`: exit 0. Same command with
  container-deployment-provider/tag suffix container: exit 0. Both under 3 minutes.
- `python3 H/native_smoke.py W none container`: final repeat **exit 0**, exact
  output `native-none True` and `native-container True`. Both check health/OpenAPI,
  actual invocation, same-key/same-execution replay, removal/no owned function
  container, stop/remove exit 0, empty application_errors. Prior green checks are
  preserved under aborted/native-first-green (superseded, not failed).
  Minimal image sha256:6c81f6f25a54a17beb3cf598ec63af1930cc0a581c259716db76d104104ab40e;
  managed sha256:ebafd45e0e6ce6eb3457b94922f2329a64aea311c1c37c157da8aec0dbe8d7c3.
  Existing Docker docker-java adapter used; no Kubernetes/native-cluster claim.
- `python3 -m unittest discover -s H -p 'test_*.py'`: final **exit 0, 27 tests,
  no failures/errors/skips**, contracts-timebox-final.log. New native scanner
  tests were RED for missing helper then GREEN. Census HTML regression RED
  ValueError parsing `-`, then GREEN. Acceptance regressions RED 2 AttributeErrors
  before validator, then GREEN. Earlier accounting/census RED/GREEN logs retained.

#### Final corrected-B campaign

`taskset -c 5-8 python3 H/campaign.py W`: **exit 0**. Actual fresh alternating
A1/B1/A2/B2/A3/B3 generator/server/backend identities and chronological non-overlap
validated, then three B ASYNC processes. No native builds/smokes or SDK/profile
load overlapped this final sequence. Each common run: 6000 warm-up, 12000 measured
offered/admitted/unique terminal successes, fixed 200/s, no refusal/unresolved.
Each ASYNC: 120 offered/admitted/completed, four payload shapes. Raw requests,
allocation validation and post-GC checkpoints retained; GC outside ordinary windows.

| Run | useful successes/s | p50 ms | p95 ms | p99 ms | bytes allocated/success |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1 | 199.9838 | 1.668732 | 3.468505 | 4.853174 | 138075.864 |
| B1 | 199.9768 | 1.575036 | 3.202664 | 3.754150 | 143898.151 |
| A2 | 199.9616 | 1.686027 | 3.225911 | 3.781141 | 137960.619 |
| B2 | 199.9543 | 2.140777 | 3.326997 | 3.872692 | 143952.312 |
| A3 | 199.9803 | 1.412603 | 3.043894 | 3.636852 | 137933.339 |
| B3 | 199.9573 | 1.443883 | 3.149173 | 3.759858 | 144274.245 |
| B async 1 | 50.1613 | 12.181059 | 20.765715 | 24.615492 | 1137578.400 |
| B async 2 | 50.0851 | 12.236642 | 20.960322 | 27.911768 | 1144352.400 |
| B async 3 | 49.9283 | 11.552068 | 23.347128 | 27.506665 | 1147779.467 |

#### Actual bounded owner profiles

Java fixture builds: `./gradlew :sdks:java:p19Classpath :sdks:java-lite:p19Classpath
-I H/profiles.init.gradle -Dp19.sources=<absolute H> --console=plain`, exit 0 after
correcting a fixture Gradle closure lookup error. Same init/properties with
`:control-plane:p19Classpath :control-plane-modules:container-deployment-provider:p19Classpath
-PcontrolPlaneModules=all`, exit 0. Go fixture copied only to isolated B SDK source:
`go test -c -o W/sdk-go ./nanofaas`, exit 0. JavaScript `npm run build`, exit 0.
Python uses existing SDK venv without resolving the executable symlink out of it.
Exact commands, classpath hashes and environments: *-commands.json and
profile-build-identities.json. Fixtures use real SDKs/provider/proxy seams, not
replacement runtime logic. Loopback observation endpoints are not deployable.

Execution via existing `supervise(..., timeout=150)`: `sdk_profiles.py W <language>`,
`owner_profiles.py W managed|proxy`, `short_profiles.py W <selection>`, serial
owned groups. All remaining_live_pids arrays empty, including the failed profile.

| Profile | Ordinary offers/admitted/refused; result | Sampled peak → drain |
| --- | --- | --- |
| Short none/all/sync+runtime-config | exit 0; 7/8/8 cases T3/T4/T5/T7/T9 | live/futures/waiters/input drain; post-policy GC/raw owners |
| T6 managed | 10/10/0: 6 blocked terminal errors, 4 healthy successes | physical provider reads 1→0; gate/timers 2→0 including healthy owner; release/removal/no resurrection |
| Multi-destination | prior complete records: 12 names, 3 destinations, 12 successes + 12 protected replays | **latest strict gate fails: empty pool registry 3→3**; connections/acquisitions/function mappings/backend work 0 |
| Actual proxy | 11/9/2: 8 successes, 1 output-limit error; exit 0 | exchanges 2→0; bytes 12591169→0 below 16MiB; deadlines 2→0; owned close flags observed |
| Java SDK, each of 2 generations | 19/6/13: 2 successes, 4 errors; 14 callbacks total | handler permits fully returned; callbacks 2→0, reservation bytes 16777216→0, executor active/queue→0 |
| Java-lite, each generation | 19/6/13: 1 success, 5 errors; 16 callbacks total | active handlers/set 2→0, callback reservation 16777216→0 |
| Python, each generation | 19/10/9: 1 sync success, 7 errors, 2 callback acknowledgements; 4 callbacks total | handlers/callbacks 2→0; callback bytes 524428→0; active task sets drain |
| JavaScript, each generation | 19/10/9: 1 sync success, 7 errors, 2 acknowledgements; 4 callbacks total | handlers/callbacks 2→0; reservation 16777216→0; output 4194336→0 |
| Go, each generation | 19/6/13: 1 success, 5 errors; 14 callbacks total | handlers/callbacks 2→0; reservation 16777216→0; output 4194337→0 |

Each SDK generation and proxy also offers 2 separately recorded slow clients.
Java/lite/Go return synchronous output plus callback side effects; Python/JS
acknowledgements are not counted as terminal successes. Callback records keep
hashes/byte counts, not repeated payloads. SDK child exit codes: Java/lite 143
after SIGTERM, Python -15, JavaScript/Go 0; all supervisors exit 0 and verify
process absence. Do not relabel all child exits 0. Java-lite incomplete-body
headers are unavailable at client deadline; proxy closes without headers. Their
admission/refusal are unavailable, not zero; completed useful wire bodies=0.

Initial launcher defects (Java/Go environment callback URL missing, Python venv
symlink resolution, JSON Accept on a Prometheus endpoint) were corrected and rerun;
failed/superseded evidence retained under aborted. Some earliest failures predate
failure-populations preservation; no data is invented to fill that gap.

Remaining failure: repeated managed runs retain 2–3 empty destination entries.
Latest managed-policy-drain.log ends **exit 1** after 4s plus bounded 10s extra wait;
registry=3, active/idle/allocated/pending connections and backend work=0. Source
trace reaches DispatchConnectionPool.disposeInactivePools→disposeDestination→
delegate.disposeWhen; **root cause not established**. Do not call it a proved
product retainer or fixture-only issue yet. Latest failure preserves raw populations,
backend identities, first T6 case and assertion point; final combined rows/census
were not reached. Earlier complete attempts supply full accounting but do not
waive the strict failure. Per timebox, no more code: finding 1 enters re-review.

Observer dictionary updated. Unobservable Netty aliases/kernel buffers, JS private
closures, JDK internal queues and unselected owners stay unavailable/not_applicable.
Numeric limits/cumulative IDs/observer handles are not retained workload; aliases
must not be summed. Pool drain is not inferred from process exit. Observer overhead
unquantified; moderate-load costs are not peak capacity/equivalence/P07 acceptance;
short profiles do not replace P24 soak.

#### Graph, self-review and preservation

Native production edits had exact upstream impact first; no HIGH/CRITICAL result.
UNKNOWN bootstrap/dynamic symbols resolved with rg. Fixtures consumed graph-checked
SDK/provider/proxy/wake-up symbols. Initially absent new census/profile symbols
were checked with exact-file impact plus text search; ambiguous profiles/profile
were not treated as clean. W/graph contains raw evidence. Completed refresh:
28.1s, 22787 nodes/63835 edges/763 flows. The final diagnostic tool call was
interrupted before execution; no pool production edit followed. Retrospective debt
from earlier rounds remains disclosed. Final complete all/staged gates below.

Self-review preserves refusal/admission/replay distinctions and failed evidence;
does not claim process exit proves in-process owner policy, or immutable integrity
proves acceptance. SHA-256 audit green for both overload-path dirty files, the
untracked ReplicaStatusSnapshotConfigurationTest.java and all six skill files.
None is staged. Unrelated dirty/untracked state is preserved. Files added are P19
SDK/provider/proxy fixtures, process drivers and focused contracts; modified files
are revision bindings, native error scanner, dossier/protocol/observer documentation,
README, baseline pointer, STATO and this report. Full file list is in the commit.

#### Closing artifact and pre-commit checks

Dossier: `docs/experiments/lifecycle-memory-2026-09/p19/dossiers/cc5e971f0e354b36ca7238aef1b5831c2b994e9a95eceb31b3770f267ac14a4d`.
Manifest SHA-256 **cc5e971f0e354b36ca7238aef1b5831c2b994e9a95eceb31b3770f267ac14a4d**,
schema nanofaas-p19-dossier-v2, 698 payload files/63,768,471 bytes, status BLOCKED,
accepted_control null, source B b4770d6675adc99f53444260dcf0200f10b517cf.

`python3 H/dossier.py freeze W`: exit 0; separate `... verify <dossier>` exit 0,
`VERIFIED cc5e971f...7ac14a4d 698 payload files`. Independent `sha256sum -c
SHA256SUMS` exit 0, **699 checks**. All three archived measured jar hashes match
build-identities.json; Git archive pax comments match exact A and B revisions.
Frozen harness tests: 27/0/0 exit 0 when extracted under the repository's
docs/experiments/lifecycle-memory-2026-09/p19 layout. A shallow /tmp extraction
first failed with 2 import errors (HERE.parents[3]); preserved log, no code change.
Reproduction must preserve that layout. This is a packaging limitation, not a
waiver of failed profile acceptance.

Complete raw all/staged GitNexus checks saved before freeze in graph/pre-freeze-gates.json:
all 27 files/82 symbols/11 flows; staged 25/81/11; **HIGH aggregate risk** reported
to user, attributable to new fixture/provider/profile links (not waived as LOW).
After staging the dossier, final raw checks at /tmp/nanofaas-p19-r2-final-gates.json:
all 245 indexed/text files/82 symbols/11 flows; staged 243/81/11, same HIGH risk,
same symbol/flow sets, no partial/truncated/UNKNOWN verdict. Binary/unindexed
artifact files were supplemented by exact manifest/staging enumeration: **726 staged
files including all 700 artifact files**, protected targets absent. Main source
diff against B empty. Raw evidence is complete; long console transport output was
read back via a lossless field comparison/complete symbol and flow listing.

`git diff --cached --check` exit 2 only for preserved raw RED XML assertion trailing
spaces and original requirements' final blank lines inside the frozen dossier.
The same check excluding immutable dossiers exits 0. Do not alter raw failure or
requirements evidence to cosmetically erase those warnings. Final 19 owned process
groups currently empty; `docker ps -a --filter name=nanofaas-p19-smoke` empty/exit 0.
Protected SHA-256 audit remains green. Final report/digest additions are prose only.
