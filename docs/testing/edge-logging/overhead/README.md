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

CPU/RAM/I/O data are Docker snapshots, not continuous peak/mean telemetry. Normal
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
