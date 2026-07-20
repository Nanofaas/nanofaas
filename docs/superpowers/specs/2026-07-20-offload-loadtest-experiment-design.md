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
