# P19 implementation report

Status: **DONE_WITH_CONCERNS**. Date: 2026-09-11. Sole implementer; no subagents.
Branch: `control-plane-lifecycle-memory`. P20b was not started.

## Commits and scope

- `b404e53b5ad78f8915ed4713f6e4670a32e92d74` — Freeze corrected P19 lifecycle baseline.
- The follow-up commit containing this report records the handoff and final graph
  evidence only; resolve its identity with `git log -1 --format=%H -- .superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P19-report.md`.

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
