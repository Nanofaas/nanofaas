# SLO and Performance Targets

## Targets

Targets are **indicative, not contractual**: the platform is a research
codebase and performance depends on the pinned Azure profile. The numbers
below are the observed medians of the last releases on that profile (see
`docs/performance/history.md` for the full series and `docs/performance/releases/`
for raw records).

| Version | Throughput (req/s) | Error rate | p95 (ms) | Peak replicas |
| --- | ---: | ---: | ---: | ---: |
| 0.17.0 | 241.9 | 0% | 6.5 | 3 |
| 0.17.1 | 269.1 | 0% | 5.8 | 3 |
| v0.18.1 | 239.1 | 0% | 6.6 | 4 |
| v0.18.2 | 249.3 | 0% | 5.1 | 3 |
| v0.18.3 | 232.4 | 0% | 5.1 | 4 |

Working targets, all on `azure-d8s-v5+d2s-v5-amd64-native-loadtest-v1`
(AMD64-native control plane + function, three k6 benchmark runs per release):

- **Error rate: 0%** on the load-test suite (hard gate in the release workflow).
- **p95 latency ≤ 10 ms** for the reference word-stats workload at the
  released load stage.
- **Throughput ≥ 200 req/s** sustained on the pinned profile.
- **Cold start**: track the cold-start distribution (`function_cold_start_ms`)
  rather than a fixed budget; the native control plane + warm function
  replicas keep p95 well under the 10 ms budget.

Any change that regresses the structural hot-path guarantees (replay reuse,
sync-queue forward progress, async fairness) fails review even if absolute
numbers look fine — see `docs/observability.md` → Perf Regression Coverage.

## Metrics mapping

| Concern | Metric |
|---|---|
| Latency (what the caller experienced) | `function_e2e_latency_ms{function}` |
| Latency (runtime service time per attempt) | `function_latency_ms{function}` |
| Cold start | `function_cold_start_ms{function}` |
| Queue depth | `function_queue_depth{function}` |
| In-flight / concurrency | `function_inFlight{function}`, `function_effective_concurrency{function}` |
| Success/error | `function_success_total{function}`, `function_error_total{function}` |
| Retry | `function_retry_total{function}` |

An SLO on latency should read `function_e2e_latency_ms`: it covers the whole
invocation including retries and their waits, and it has a sample for every
admitted invocation. `function_latency_ms` measures a single attempt and omits
timed-out and expired ones entirely, which biases it optimistically exactly
under the overload an SLO exists to catch. See `docs/observability.md` →
"What the three duration timers actually sample".

## How to measure

The release workflow (see `docs/operations/image-releases.md`) runs three k6
load-test benchmarks per version and compares medians against the newest
record of the same profile in `docs/performance/releases/`. For an ad-hoc
run, use the `loadtest.yaml` scenario (see `docs/e2e-tutorial.md`).
