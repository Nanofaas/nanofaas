# P23 — final verification against the frozen P19 control

This directory is a new dossier. It references P19's accepted dossier by digest and never writes
inside `../p19`, as that dossier's README requires of P23.

## What was verified before measuring anything

P19's accepted dossier
`74a0d632f3373724032df4719945ba50e84f827e49773c46b94902b9c4809dc0` was checked first:

- all 653 entries of its `SHA256SUMS` verify;
- the sha256 of its `manifest.json` equals both the directory name and the `manifest_sha256` in
  `../p19/baseline.json`;
- its accepted control is arm **B** at `d93b68cdf1cca6e7af17e1c641c8e236c80d0fca`, status
  `DONE_WITH_CONCERNS`;
- the jars extracted from its `measured-jars.tar.xz` carry the exact sha256 values its own run
  records name, so arm B here is the binary P19 measured and not a rebuild of it;
- `P19Probe.java` recompiled from the archived source is byte-identical to the class P19 measured
  with (`95ca016f49ec0a390444ae4e592537a2f6f28c9c3dc7b4cbae466ef274a24054`).

The observation instrument and arm B's binary are therefore provably the same as P19's. Between the
two arms only the product jar differs.

## Arms and identity

| Arm | Source | Jar sha256 |
|---|---|---|
| B | `d93b68cdf1cca6e7af17e1c641c8e236c80d0fca` (P19's accepted control) | `a362b94ae8328a9e…` |
| C | `b2aed0a3` (P20b + P21 + P22 + the P22 condition fix) | `23d4dfc4310fbe96…` |

`campaign_bc.py` records the revision it reads from `git rev-parse HEAD` at launch, and this
campaign was launched before the fix was committed, so its `revision` field says `7083ca87`. The
authoritative identity is the jar digest: `C-none.jar` is byte-identical to a rebuild from the
committed `b2aed0a3` tree, which is what ties the measurement to the source. Read `jar_sha256`, not
`revision`.

## Protocol

P19's protocol v2 unchanged, driven by its own frozen `http_runner.py`: real configured core-only
HTTP SYNC EXTERNAL against a separate deterministic backend, no retries and no keys, 6000 warm-up
and 12000 measured offers at 200/s, outstanding bound 32, `-Xms256m -Xmx512m -XX:+UseG1GC`,
`taskset -c 5-8`, three repeats in fresh alternating processes. Same host and JDK 25.0.4 as P19.

Throughput is bounded by the offered 200/s by design, so `useful_successes_per_s` only proves no
work was lost; it is not a capacity measurement. Latency percentiles carry real run-to-run spread —
P19's own recorded B ranges from 1799 to 3122 µs at p95 — so they are compared only *within* this
fresh alternating pair, never against P19's recorded values. Allocated bytes per success is the
tight discriminator: its spread inside an arm here is under 0.35%.

## Result

| | useful/s | p50 | p95 | p99 | alloc/success |
|---|---|---|---|---|---|
| B | 200.00 | 1269.6 µs | 1817.4 µs | 2200.2 µs | 144 201 B (spread 0.19%) |
| C | 200.00 | 1216.8 µs | 1820.8 µs | 2106.8 µs | 143 084 B (spread 0.31%) |
| C vs B | +0.00% | −4.16% | +0.19% | −4.24% | **−0.77%** |

Every run in both arms is `valid`, with offered = admitted = unique terminal successes = 12000,
zero refusals, zero transport errors and zero unresolved. No metric breaches the plan's 5% gate,
and the two movements beyond 5% noise are improvements. The structural work of P20b, P21 and P22
costs nothing measurable on this path and allocates slightly less.

Fresh B measures 144 201 B/success against P19's recorded 143 728 B, a 0.33% difference, which is
what makes the C comparison credible: this environment reproduces P19's measurement of the same
binary.

`comparison.json` also carries P19's recorded A/B pair. A (`61d72e73`, the P00 diagnostic
comparator, known defective) allocated 138 212 B/success, so the whole campaign's correctness work
costs roughly +3.5% allocation against it — measured by P19 in its own paired campaign, not
inferred across sessions. That is a correctness cost, quantified rather than hidden.

## The defect this task found

An earlier C jar (`3d40c929…`, source `7083ca87`) was measured first and is not the final candidate.
A packaged run of it under `modules=none` showed the managed orchestration fully built — replica
snapshot, wake-up gate, wake-up coordinator — and no immediate readiness, contradicting what P22
claimed. `ManagedDeploymentOrchestration` was annotated `@Configuration` inside a
component-scanned package, so the scan registered it unconditionally and the `@ConditionalOnBean`
on the importing auto-configuration was never consulted. P22's isolated
`ApplicationContextRunner` test could not see this, because it performs no component scan.

The fix removes the stereotype; `ScannedContextDeploymentWiringTest` now asserts the invariant in a
real scanned context. `profiles/none` and `profiles/all` here are packaged runs after the fix:
without a provider there is no snapshot, no gate and no wake-up coordinator, and the pool-free
coordinator and immediate readiness are present instead; with a provider the full orchestration
exists and the unmanaged defaults stand down.

## Coverage and what was not run

`behaviour-matrix.txt` and `artifacts.txt` record the section-8 matrix, the four negative module
selectors with their exact rejection messages, the OpenAPI/Helm/Compose checks and both native
images including start, register, invoke, replay of the same idempotency key and shutdown.

Not run, and therefore not passed: the Go SDK suite. Its toolchain (`go1.24`) is not installed and
cannot be downloaded offline. This is an environment limitation, recorded as such.

Three repeats do not establish statistical equivalence, and none of this replaces the P24 soak.
