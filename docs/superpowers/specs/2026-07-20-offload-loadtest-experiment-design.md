# Offload load-test experiment (`offload-loadtest`)

**Goal:** a realistic experiment proving the offload strategy under load: a k6
load generator drives two functions with different offload policies against an
"edge" control plane; part of the offloadable function's traffic must land on
a "cloud" control plane on another VM, with client-observed headers and the
internal control-plane metrics agreeing with each other.

Decisions (2026-07-20, with the user): Multipass with three VMs for v1;
k3s + Helm deployment on both control-plane VMs (same ritual as the existing
loadtest); second function is a **control group** with offload disabled;
autoscaler off (fixed replicas) so pressure is controllable and the
conservation checks are interpretable.

## Topology

Environment `environments/multipass-offload.yaml`, provider `multipass`:

| Role | VM | Runs |
|---|---|---|
| `stack` (edge) | nanofaas-edge | k3s + Helm control plane, offload module active, `nanofaas.offload.target-url=http://<cloud-ip>:30080` |
| `cloud` | nanofaas-cloud | k3s + Helm control plane, same image, no offload target |
| `loadgen` | nanofaas-loadgen | k6 |

`cloud` is a **new role**: `EnvironmentConfig`, role provisioning, and the
connectivity strategy currently know only `stack`/`loadgen`. Extending them is
the widest plumbing task and comes first. Azure/Proxmox variants later are
environment files only.

Both control-plane VMs get the standard base provisioning and the standard
k8s deployment flow; the edge Helm values add the offload target. One
control-plane image built once (modules include `offload`, `async-queue`,
`sync-queue`, `k8s-deployment-provider`) and distributed to both VMs.

## Functions and policies

- `word-stats-java` — offloadable under pressure (no `offload` block =
  pressure default with the module active). Registered on **edge and cloud**.
- `json-transform-java` — control group, `offload: {enabled: false}`.
  Registered **only on the edge**: an accidental offload hits a remote 404 and
  surfaces as a visible 502 instead of a silent false negative.

Low per-function `concurrency`/`queueSize` and fixed single replicas so the
k6 rates saturate local capacity; sync-queue admission enabled (both `depth`
and `est_wait` triggers are legitimate).

## Load

One k6 script with two constant-arrival-rate scenarios (one per function),
both sized above local capacity. The script:

- counts responses carrying `X-NanoFaaS-Offloaded` in a custom
  `offloaded_requests` counter tagged by function;
- tags request durations with `offloaded:yes|no` so the summary separates the
  local and offloaded latency populations;
- treats 200 as success for both functions and 429 as an **expected** outcome
  for the control function under saturation (counted, not failed).

## Verification — conservation chain

Three independent observers (k6, edge Prometheus, cloud Prometheus) must
agree, within a small in-flight tolerance:

1. `k6 200(word-stats)` == edge `function_success_total{word-stats}` (the
   edge records offloaded completions as its own successes).
2. `k6 offloaded(word-stats)` == edge
   `nanofaas_offload_total{word-stats, trigger∈{depth,est_wait}}` == cloud
   `function_success_total{word-stats}` — the same number measured at the
   client, the edge, and the cloud.
3. Control group: `k6 200 + 429 (json-transform)` == its request total; edge
   `nanofaas_offload_total{json-transform}` absent; **no json-transform meter
   exists on the cloud at all**.
4. Zero everywhere: `nanofaas_offload_failure_total`, `function_retry_total`.

Report: JSON in the run directory with the raw counters, the conservation
deltas, and the local-vs-offloaded latency comparison (informative, not a
gate).

## Out of scope (v1)

Autoscaling during the experiment; eager-mode functions under load; Azure and
Proxmox runs (environment files later); more than two functions; performance
regression gating (this experiment validates correctness of the strategy and
of the metrics, not a performance baseline).

## Results (v1)

Ran end-to-end on real Multipass infrastructure (3 VMs: `nanofaas-edge`
2cpu/6G, `nanofaas-cloud` 4cpu/6G, `nanofaas-loadgen` 2cpu/2G — sized down
from the role defaults to fit an 18G/11-core host) 10 times while
implementing and tuning this experiment. The full pipeline — provision,
build+push images to each VM's own registry, deploy k3s+Helm on both
clusters, register the mixed-policy functions, run k6, fetch results,
evaluate — completes reliably. **`passed: false` on every run**; documented
here as a known v1 limitation rather than pushed further.

**What's confirmed working:** offloading genuinely happens and the three
observers agree on it. Best runs:

- k6 200s for `word-stats-java` (599) vs edge `function_success_total` (597) —
  within tolerance.
- k6 `offloaded_requests` (14) vs edge `nanofaas_offload_total` (16) vs cloud
  `function_success_total` for the offloaded function (12) — all within
  tolerance; one run matched exactly (25 vs 25).
- Control function (`json-transform-java`) never leaked to the cloud, and
  edge never offloaded it — check #3 always passed.

**What doesn't pass:** check #4, `nanofaas_offload_failure_total` must be
absent on the edge. It's never absent — every run showed a nonzero share of
offload attempts failing (best run: 4/16 ≈ 25%; worst: 18/43 ≈ 42%; no
downward trend despite retuning).

**Root cause (best-supported hypothesis, not proven with tracing):** the
pressure trigger doesn't spread offload decisions smoothly across the test
duration — it correlates with the control plane's `SYNC_QUEUE_THROUGHPUT_WINDOW`
(hardcoded 10s), so it fires in bursts. A direct 40-way concurrent curl burst
from edge to cloud's NodePort succeeded at 92% (37/40, 3× 429) — noticeably
better than the in-experiment ratio — meaning the gap isn't raw network
reachability or a fixed capacity shortfall; it's burst timing coinciding with
momentary admission pressure on the cloud side, which by design also runs
its own sync-queue.

**Fixes applied during this validation** (beyond the plan's original code):
correcting a Helm `extraEnv[]` index collision that would have silently
overwritten `NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND`; widening
`provision_environment`'s `dedicated_loadgen` check (was hardcoded to the
`loadtest` workflow, so the loadgen VM — and k6 — would never have been
provisioned for `offload-loadtest`); correcting the k6 `--summary-export`
parser (counters are flat `{"count": N}`, not `{"values": {"count": N}}`, and
k6 only emits a tag submetric for combinations referenced by a threshold —
added an explicit `offloadable_requests` counter instead of relying on a
`http_reqs{function:...}` submetric that never materializes); registering the
cloud copy of the offloadable function with generous capacity
(`concurrency=20`, `queueSize=100`) instead of inheriting the edge's
pressure-inducing tight settings; raising the offloadable function's
`timeoutMs` to 15000 (it doubles as the offload gateway's remote-call
budget); lowering `OFFLOADABLE_RATE` to 10 (from 20) to shrink burst size.

**Follow-ups, not done:** trace/log the specific `OffloadFailedException`
reason (timeout vs 429 vs other) per failure — today
`nanofaas_offload_failure_total` has no reason label and
`DefaultOffloadGateway` only logs the *unclassified* error branch, so the
common cases are silently counted with no diagnostic trail; consider a
token-bucket or jitter on the pressure trigger so decisions spread instead of
bursting on the throughput-window boundary; the redundant control-plane/
warm-echo image builds (each built once per VM, twice per run, though
identical) could be built once and pushed to both registries instead of
rebuilt — orthogonal to the conservation gap but was noticed while iterating.
Azure/Proxmox environment files for this scenario are still out of scope.
