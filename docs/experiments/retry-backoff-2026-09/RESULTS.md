# Retry backoff validation — 24 September 2026

The live replay recovered from callback saturation with backoff: **900/900 candidate invocations
succeeded**, versus **742/900 baseline**. A separate controlled upstream recorded **zero candidate
retry gaps below its one-second hint**, versus 892 baseline violations. These observations apply
to the recorded configuration, not arbitrary cold-start durations.

The final-code performance acceptance gate is **not passed**: unchanged shared-queue p99 is
**+8.06%** against the frozen +5% limit. All four CPU comparisons pass. The final-code campaign
below supersedes the earlier candidate numbers for acceptance; earlier evidence is retained. Switch, throughput, heap, identity and reservation checks passed. See the diagnostic
qualification below; the original failures are retained.

## Sources and measurement protocol

Baseline source: `a17289c2e974e280c1f299e2f871f034c0c632aa`, exported with `git archive` into
`/tmp/retry-backoff-baseline-a17289c2`; no branch switch or reset. Candidate: the same HEAD plus
the staged retry feature and the user's pre-existing dirty work. `raw/candidate-status.txt` records
the latter. No clean-HEAD claim is made for the candidate.

For the **local engine benchmark**, four dependency declaration files were copied to the baseline
export, and both executions used the candidate's dependency jars with their own compiled module
outputs. The complete classpaths, source SHA-256 manifest and runtime patch are under `raw/`.
The compiled source differences are confined to `SchedulerEngine`, `AttemptCoordinator`,
`DispatchResult`, `RetryScheduler` and the added `RetryBackoff`. The same updated harness runs
both revisions. Its default 0% workload retains the existing admission/deadline behavior.

Host: Linux 6.17.0-1032-nvidia, aarch64, 20 available processors; OpenJDK 25.0.4+7-1-24.04-Ubuntu.
Every measured JVM used `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`. Benchmarks ran sequentially before
VM provisioning. The host had other idle/resident services; the saved interval CPU sample was
about 97% idle. This is a shared host, not proof of an isolated latency laboratory.

The controlled check runs before timing for every requested backlog. It uses a fixed clock,
checks all ready work before eligibility, advances one second, switches back, and verifies every
ID completes once with zero remaining reservations. The 50% mix assigns `(sequence % 100) < 50`
to future eligibility; 100% leaves all initial work delayed. Queue deadlines follow eligibility.
Real timed runs use the wall clock and explicitly record the intentional 1,000 ms wait separately
from the unchanged 0% success-path comparison.

## Reproduction commands

Build each checkout's runtime test classpath and jars first; the runner does not compile production
sources. The baseline export's runner substitutes the recorded SHA because the export has no `.git`.
The baseline and candidate classpath files must point to their respective compiled outputs.

```bash
# Baseline export: identical harness/dependency declarations; original runtime sources.
/tmp/retry-backoff-baseline-a17289c2/docs/experiments/scheduler-switching-2026-09/run.sh \
  --label=retry-backoff-baseline-steady --repetitions=5 --delayed-percent=0 --profiles=low-load
# Candidate checkout:
docs/experiments/scheduler-switching-2026-09/run.sh \
  --label=retry-backoff-candidate-steady --repetitions=5 --delayed-percent=0 --profiles=low-load
docs/experiments/scheduler-switching-2026-09/run.sh \
  --label=retry-backoff-candidate-mixed --repetitions=5 --delayed-percent=50 --parts=backlog,switches
docs/experiments/scheduler-switching-2026-09/run.sh \
  --label=retry-backoff-candidate-delayed --repetitions=5 --delayed-percent=100 --parts=backlog,switches
python3 docs/experiments/scheduler-switching-2026-09/summarize.py \
  docs/experiments/retry-backoff-2026-09/raw/retry-backoff-candidate-steady.jsonl
python3 docs/experiments/retry-backoff-2026-09/raw/compare.py
```

Every campaign includes backlog sizes **0/100/1,000/10,000**, five backlog repetitions and a
**1,000-switch** run. Steady baseline/candidate each have five repetitions of all four arms,
8 s measured spans and the existing warm-up. Other workload profiles were not rerun; the
success-path comparison here is specifically `low-load`. Historical campaign labels were untouched.

## Original candidate performance budgets (superseded for acceptance)

Cross-revision medians for identical arms; steady latency/throughput use the existing final 2 s
window. CPU is summed live-thread CPU per useful completion; heap is the existing post-GC sample.
The raw comparison table includes absolute values.

| Arm | p99 change, limit +5% | throughput change, limit −5% | CPU change, limit +10% | heap change, limit +10% |
| --- | ---: | ---: | ---: | ---: |
| per-function unchanged | −1.86% | 0.00% | **+10.01% FAIL** | +0.01% |
| per-function → shared | −3.56% | 0.00% | −5.20% | +0.01% |
| shared unchanged | **+6.02% FAIL** | 0.00% | −0.57% | +0.01% |
| shared → per-function | −2.85% | 0.00% | **+10.86% FAIL** | +0.01% |

| Campaign | soak pause p99 ms, limit 100 | worst pause ms, limit 250 | committed switches |
| --- | ---: | ---: | ---: |
| baseline 0% | 0.234111 | 3.008548 | 1,000 |
| candidate 0% | 0.264734 | 3.583616 | 1,000 |
| candidate 50% | 0.315199 | 5.469352 | 1,000 |
| candidate 100% | 0.394734 | 5.590855 | 1,000 |

All campaigns observed at most two live indexes, returned to one, and conserved admitted work.
Every controlled drain completed the admitted population and ended with zero reservations.
The standing-backlog soak intentionally retains its offered backlog and reports it separately.
Preparation is bounded by the recorded total client switch elapsed time, which includes preparation
and the engine pause. Maxima were 3.030228/3.605855/5.491128/5.613607 ms for baseline/0%/50%/100%,
all below the 2,000 ms preparation budget (`raw/preparation-upper-bounds.json`).

The first bounded JFR diagnostic retained the same load but only yielded nine baseline and eleven
candidate execution samples. It could not attribute the failed CPU/p99 deltas. The original
per-function CPU samples were 346.4/442.2/412.0/368.9/353.1 μs versus
370.4/405.8/477.0/346.3/407.8 μs. Their spread is evidence of variability, not permission to discard
the failures. A subsequent focused diagnostic is recorded separately; its results do not replace
these five-repetition measurements or change the thresholds.

## Live NanoLab replay

Both revisions ran the existing `deployment-lifecycle-k8s.yaml` scenario in the sibling NanoLab
checkout with `--environment packages/nanolab/environments/multipass.yaml --keep`. Both lifecycle
runs passed; their built-in 12-VU/2 s queue-rejection load is **not** the 300-call evidence below.
The managed VM was `nanofaas-stack`, created from an empty Multipass inventory, with four vCPUs
and 12 GiB memory. No provisioning code was added to NanoFaaS.

An explicit NanoLab validation asset then registered one probe and submitted **300 invocations at
client concurrency 8**, immediately after registration, after ready EndpointSlices were observed,
and warm. The final matched settings were:

- function: `DEPLOYMENT`, concurrency 8, queueSize 300, timeoutMs 5,000, maxRetries 3;
- waiter: `X-Timeout-Ms: 10000`; client HTTP timeout 25 s;
- sync queue: enabled, maxDepth 512, maxQueueWait 30 s, maxEstimatedWait 10 min;
- `SYNC_QUEUE_ADMISSION_ENABLED=false` disables the estimated-wait heuristic only; the depth bound
  and queue ownership remain active. Before the first throughput sample the estimate can be
  infinite, so increasing a finite estimate budget did not remove unrelated admission refusals;
- Java callback defaults unchanged: two workers, 128 pending callbacks, 16 MiB pending bytes,
  2 MiB per callback. Deployment snapshots show no callback-capacity overrides.

Both revisions used the **same function runtime image**:
`127.0.0.1:5000/nanofaas/java-word-stats@sha256:983d5653823e16c4cee4661895c425c65b4a4468b1b21747e231e2048c8d1ffc`.
The control-plane image digests were baseline `26b5b53a1f5fa360b233594d36699e70b1d87625531ff9b9de2ccd32380cbea6`
and candidate `deed355971dbd5e6f5b1add849f0789a0c4c52f49056a2d9f6d6f15b36f468b6`.
Unlike the isolated engine benchmark, live control-plane builds also include pre-existing dependency
and source differences between HEAD and the dirty workspace; do not attribute every live latency
change solely to this feature.

| Phase | baseline success / error | candidate success / error | baseline retry delta | candidate retry delta |
| --- | ---: | ---: | ---: | ---: |
| immediately after registration | 266 / 34 | 300 / 0 | 351 | 26 |
| after ready endpoints | 239 / 61 | 300 / 0 | 412 | 11 |
| warm | 237 / 63 | 300 / 0 | 421 | 11 |

All **900 distinct execution IDs per revision** were retained, with no final-run API admission
429. Terminal success/error counter deltas match the envelopes. Errors in this matched run were
`EXTERNAL_ERROR` containing `RUNTIME_CALLBACK_SATURATED`. First ready observations were
15:26:31.058816 UTC baseline and 15:32:03.404133 UTC candidate.

Earlier diagnostic runs are preserved separately: the shipped depth-one profile refused calls
at admission; increasing depth still left `est_wait` refusals until that heuristic was disabled.
One of those runs also captured connection refusal before endpoints, distinct from callback
saturation after readiness. The first observer failed on `EndpointSlice.endpoints: null`; its
partial run was excluded, the bug was fixed, and fresh labels were used for all reruns.

## Actual retry-hint attempt timing

A separate NanoLab asset served an `EXTERNAL` upstream on the same VM. For each new execution it
returned unmarked HTTP 429 with `Retry-After: 1` until one second after its first received request,
then HTTP 200. It recorded `X-Execution-Id`, `X-Dispatch-Attempt`, idempotency key, monotonic arrival,
wall-clock arrival/completion and outcome for every actual upstream request. Both revisions ran
300 calls at concurrency 8 with the matched queue and waiter settings.

| Measurement | baseline | candidate |
| --- | ---: | ---: |
| distinct executions | 300 | 300 |
| upstream attempts | 1,195 | 600 |
| terminal success / error | 3 / 297 | 300 / 0 |
| adjacent retry gaps below 1 s | 892 | **0** |
| minimum retry gap | 0.000727 s | **1.000698 s** |
| median retry gap | 0.003307 s | 1.002181 s |
| maximum retry gap | 1.054072 s | 1.009121 s |

Every candidate execution had exactly attempts 1 and 2, with no duplicate attempt number.
These are server-observed request-arrival gaps, not inferred scheduler instants. They corroborate
the hint floor across the real HTTP path. Managed runtime logs expose execution IDs and handler
start timestamps, and candidate debug logs expose retry policy instants, but do not expose every
managed dispatch/failed-attempt completion timestamp. Complete per-attempt wire evidence is
therefore available for the controlled upstream, not for every managed-runtime attempt.

## Workflow integration, evidence and remaining gates

NanoLab now has an optional `retryBackoffBurst` setting and `retry-backoff-k8s.yaml`, with explicit
managed-burst and hint-recording steps. Default lifecycle behavior is unchanged. The extension
originally passed 92 affected tests and scoped Ruff checks. That first integration run was
evidence collection only: it did not make failed envelopes or early gaps fail the scenario.
The review correction adds explicit `--validate-candidate` to both optional scenario steps;
direct baseline commands omit this flag and retain evidence collection behavior. Candidate mode
requires 300 distinct successful admissions in each managed phase (900 distinct IDs overall),
300 successful hint executions, exactly attempts 1/refused and 2/success per execution with
matching execution IDs, and zero adjacent gaps below one second. Validation follows evidence
writes and uses the existing cleanup path. Regression tests reject failed envelopes, admission
refusals, duplicate IDs, missing/duplicate attempts, wrong identities/outcomes and early gaps.
The corrected code passes 94 affected tests and scoped Ruff checks. The standalone asset and integrated workflow use the same
probe implementation. The first integrated run exposed a sparse Helm environment array; the
recorded RED test reproduces it. The fixed integrated scenario passed both explicit steps
(managed burst 20.3 s; upstream hint 39.2 s). Full outputs were archived before deleting and
purging the task-owned VM; no other VM was present.

The JSONL benchmark outputs, comparison script/table, JFR recordings, live summaries, full
invocation/attempt evidence, Kubernetes snapshots, logs, metric snapshots and source identifiers
are under `raw/`. Run `raw/summarize_live.py` after extracting the matched live archives there.

GitNexus pre-edit impact: benchmark/main UNKNOWN, corroborated by the shell runner's explicit
Java entry point; header LOW and offer LOW with an unresolved receiver boundary. All-scope and
Task-0 comparisons report CRITICAL across the whole dirty workspace (94 files/255 symbols/120
flows at the recorded check). Compare-to-main includes prior #208 work and remains listing-capped
even after requesting limit 5,000; the index's already-known process truncation remains unresolved.
These are **not clean graph gates**. No commit was created, and unrelated staged/unstaged work was
preserved. The performance failures and incomplete managed-attempt trace remain explicit limits
on claiming complete acceptance.

### Live command details

From the sibling NanoLab checkout, the baseline and candidate lifecycle commands were:

```bash
NANOFAAS_ROOT=/tmp/retry-backoff-baseline-a17289c2 ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml --keep
NANOFAAS_ROOT=/home/michele/Documenti/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml --keep
# After setting the matched environment and waiting for rollout, for each revision:
multipass exec nanofaas-stack -- env KUBECONFIG=/home/ubuntu/.kube/config python3 /home/ubuntu/retry_backoff_burst.py --url http://10.43.67.166:8080 --image 127.0.0.1:5000/nanofaas/java-word-stats@sha256:983d5653823e16c4cee4661895c425c65b4a4468b1b21747e231e2048c8d1ffc --out /home/ubuntu/retry-backoff-REVISION-matched
multipass exec nanofaas-stack -- env KUBECONFIG=/home/ubuntu/.kube/config python3 /home/ubuntu/retry_hint_probe.py --url http://10.43.67.166:8080 --out /home/ubuntu/retry-hint-REVISION-matched
# Integrated candidate workflow verification:
NANOFAAS_ROOT=/home/michele/Documenti/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/retry-backoff-k8s.yaml --environment packages/nanolab/environments/multipass.yaml --keep
```

`REVISION` is `baseline` or `candidate`; directories must not already exist. The external
NanoLab change is staged in its own repository and preserved as `raw/nanolab-extension.patch.gz`.

### Bounded focused profile

After VM cleanup, `bash raw/retry-task7-high-profile.sh` ran the three failing arms sequentially
for baseline then candidate: 8 s warm-up and 30 s measurement per arm, 1 ms JFR execution
sampling and zero-threshold park events. `RetryBackoffProfile.java` invokes the existing harness;
`focused-findings.json` retains sampled stacks and diagnostic measurements. Export events with
`jfr print --json --events jdk.ExecutionSample,jdk.ThreadPark,jdk.CPULoad FILE.jfr` before running
`analyze-focused.py` (its temporary input paths are stated in the script).

There were 245 baseline / 185 candidate execution samples, including 117 / 94 scheduler samples.
The leading frames were the existing driver, enqueue, pass and index operations; this sample did
not identify a concentrated new retry hotspot. No scheduler `ThreadPark` events were recorded,
so those events cannot establish scheduler sleep overshoot. JFR machine CPU median was
1.95% / 2.09%, maximum 9.43% / 19.94%; interval `top` samples are retained. Host contention is
not established as the cause of the original failures.

Diagnostic CPU μs/completion for per-function/shared/shared→per-function was
684.227/653.512/585.346 baseline versus 623.986/607.891/561.522 candidate; whole-run p99 was
2.646677/2.641429/2.617157 ms versus 2.650693/2.644805/2.587429 ms. JFR overhead and the changed
measurement length prevent substituting these single samples for the frozen acceptance run.
The original three failures remain **failed/unresolved**; no speculative optimization was made.

Text log line endings/trailing spaces were normalized for review; patches and full VM evidence
are compressed without content changes. `raw/SHA256SUMS` identifies the retained artifacts.

The candidate validators also passed against the retained candidate archives and rejected both
baseline archives (`validation-retained-evidence.txt`). Retry identity is checked by execution ID
and dispatch attempt; retry dispatches currently omit the client idempotency key, which remains
recorded as evidence and is not treated as a new validation contract.

### Assertion-enabled scenario rerun

The fresh `retry-backoff-k8s.yaml` rerun exited 0 with candidate assertions enabled:
managed phases each had 300 successes (step 21.8 s), and the hint step had 300 successes
with exactly 600 upstream attempts (39.1 s). Full results, actual deployed validator source,
image/pod/metric/log snapshots and invocation records are in `candidate-validation-live.tar.gz`;
summary and run log have the same prefix. The hint validator was synchronized once after the
initial asset copy, before its step; its verified SHA-256 was
`265897dcdd75673e61efcb035bcbdc6f221b4ab8c393f0f250e457749fe15c90`.
The original matched baseline/candidate comparison is retained unchanged. The new run proves
that the scenario's candidate checks execute successfully, rather than only collecting outputs.
The owned VM was deleted/purged after archiving; final inventory was empty.

## Final-code steady campaign

After the reviewed `SchedulerEngine` hot-path correction, the candidate was rerun against the
original retained baseline, with the same harness SHA, JDK, dependency classpath, 1 GiB fixed
pre-touched heap and low-load settings. `final-candidate-source-sha256.json` records source and
compiled engine hashes; `final-candidate-source-changes.json` confirms only `SchedulerEngine.java`
changed among the previously recorded runtime sources. The compiled class timestamp followed
the final source edit; hashes still matched after the run. Dirty state, classpath and final runtime
patch are retained under the `final-candidate-` prefix.

```bash
docs/experiments/scheduler-switching-2026-09/run.sh \
  --label=retry-backoff-candidate-final-steady --repetitions=5 --delayed-percent=0 --profiles=low-load
```

All four backlog sizes completed five repetitions, all four steady arms completed five
repetitions, and all 1,000 switches committed. Correctness, identity, work conservation and
reservation checks passed. Soak pause p99 was 0.229071 ms; maximum pause across the campaign
was 3.129573 ms; maximum total client switch time was 3.151957 ms. At most two indexes were
live and the soak returned to one. All remain within their frozen limits.

| Arm | p99 change (limit +5%) | throughput change (limit −5%) | CPU change (limit +10%) | heap change (limit +10%) |
| --- | ---: | ---: | ---: | ---: |
| per-function unchanged | −7.42% | 0.00% | −6.89% | +0.01% |
| per-function → shared | −3.26% | 0.00% | −18.34% | +0.01% |
| shared unchanged | **+8.06% FAIL** | 0.00% | −7.30% | +0.01% |
| shared → per-function | −4.56% | 0.00% | −1.74% | +0.01% |

Absolute medians are in `raw/final-comparison.md`, produced with the unchanged `compare.py`
calculation and only its candidate input filename replaced by
`retry-backoff-candidate-final-steady.jsonl`. Shared unchanged trailing-window p99 samples
were baseline 3.330481/2.547653/2.754964/2.565908/2.571365 ms and final candidate
3.452164/2.605463/2.698278/2.779190/2.778582 ms. The median rose from 2.571365 to
2.778582 ms. This remains a failed acceptance budget; variability does not waive it.

No tests, builds or VM runs overlapped this campaign. Interval host activity is retained in
`final-candidate-host-top.txt`; it is a shared host, so those observations do not prove complete
isolation. A requested additional paired diagnostic was stopped at the user's request; its
partial results are excluded from acceptance. No further measurements or profiling were run.
The previous live validation remains retained; this final change was covered by the parent's
438 passing tests, and NanoLab was not rerun for this campaign.
