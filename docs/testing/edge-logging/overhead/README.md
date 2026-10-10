# Six-VM logging interference pilot

This pilot tests the observation stack, not one-shot algorithm benefits or the
scientific campaign. Five heterogeneous edges and one cloud receive identical
CPU-bound input. It uses one replica/node, external dispatch and no P2P. Collector
budget is 0.10 CPU / 64 MiB / 256 MiB output buffer per node. Backend is on the
operator host with 1.5 CPU / 768 MiB in total.

The first complete series, [v1 fixed-phase](v1-fixed-phase/receipt.json), **failed**:
median goodput change +0.1105%, p95 change −12.2236%. All 5,433 requests completed,
with no drops/deficit, and all 18 collector exports completed. Narrow edge-1
latencies within some windows shifted 25–50 ms between windows, making fixed
arrival/quota-phase alignment a plausible confound. This is not evidence that
logging improves latency.

Protocol v2 uses identical deterministic 0–100 ms offsets in every window to
spread arrivals across CPU quota phases. Images, input, average rates and logging
budgets are unchanged. The real native gateway, Rust workload and k6 preflight
passes, including the successful u64 checksum without JavaScript precision loss.
Three warmup failures are retained under `failed-warmups/`; none produced measured
pairs. Every failed attempt has an empty owned-VM cleanup inventory.

The original v1/v2 CPU/RAM/I/O data are post-load Docker snapshots and do not
measure workload CPU cost. Their legacy collector `complete` status means the
bounded query/export succeeded; it was not a checked buffer-drain/archive claim.
Those original receipts and raw files are preserved unchanged. The supplemental
resource series described below supplies cumulative counters during the load and
verified stopped-collector state archives under the corrected finalization policy.
Normal
WARN/lifecycle logging has low volume; this does not qualify verbose DEBUG logs.
Collector recovery/overflow qualification is separate in the parent dossier.
The full frozen campaign stack, topology, replica counts and arrival trace must
repeat the interference gate before calibration. No profiles were published.

Each series retains raw k6 data as `raw.jsonl.gz`. Decompress those files before
checking the original `sha256.json`; the archive manifest hashes compressed
bytes. Collector queries are bounded and have separate complete/partial status.
The collector's own logs use Docker `local`, preventing recursive ingestion.

The [complete v2 series](v2-phase-coverage/receipt.json) passes the predeclared
median 5% gate: goodput change 0.1106%, p95 change 0.8230%. All three pairs are
retained. All 5,427 actual requests completed, no generator deficit/drop, all 18
collector exports completed and all six owned VMs were removed. This qualifies
only this technology pilot and its low-volume logging configuration.

The [failed finalization series](failed-finalization/disposition.json) retains
all six resource-measured windows and all 18 transferred state archives. Every
request completed, but the already-loaded earlier helper failed to remove a
root-owned temporary file in sticky `/tmp`; workflow exit was 1. It is **not** a
successful final-stack qualification. Final source tests/builds overlapped that
failed series. The final helper uses a private directory and marks successful
owned deletion so compensation cannot repeat completed cleanup after an archive
error. The repeat uses a freshly built static Rust workload containing the
reviewed SDK fixes; previous image tags/digests and data remain intact.

The [final runtime/resource series](v2-final-runtime-resources/receipt.json)
**passes** the same three-pair 5% gate: median goodput −0.1105%, p95 +0.8286%.
All **5,428** actual requests completed with no deficit/dropped iterations.
All **18** collector finalizations have complete bounded queries, drained delivery
counters with zero known drops, and hash-verified stopped-state archives; all six
owned VMs and the owned backend were removed. The preexisting `nanofaas-stack`
was preserved. Source snapshots/commits and actual runtime image IDs are retained.

[Cumulative CPU/block-I/O and endpoint RAM summary](v2-final-runtime-resources/resource-summary.json)
includes the six nodes and owned Loki/Grafana. Largest per-node collector median
mean CPU is **0.00077 cores** and median endpoint memory **4.85 MiB** for this
seven-record/node/window workload. Owned backend median mean CPU totals **0.01237
cores** and endpoint memory **303.68 MiB**. These are observations, not capacity
limits or peaks. Counters bracket the load including sequential capture/generator
overhead; they do not attribute Docker-daemon/host log-driver I/O. No unrelated
builds/tests ran during this final series. Retention/query completeness and
scientific campaign qualification remain separate claims.

To reproduce using the matching local image archive from a NanoLab checkout:

```sh
PYTHONPATH=packages/nanolab/src /path/to/nanolab-venv/bin/python \
  scripts/one-shot-logging-overhead.py \
  --nanofaas /path/to/nanofaas \
  --images /path/to/images-final.tar \
  --host-address 10.83.80.1 --output /tmp/logging-overhead-new
```

Use the private VM-facing address of your host. The archive must contain the
control-plane `20261010` and workload `20261010-final` tags referenced by this
script, or adjust and freeze those explicit tags before a new campaign. This
final technology pilot still has one replica per node, P2P disabled and low log
volume; repeat the complete frozen-stack gate before scientific calibration.
