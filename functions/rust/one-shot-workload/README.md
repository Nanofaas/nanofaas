# One-shot calibration workload

Build from the repository root with `docker build -f functions/rust/one-shot-workload/Dockerfile -t nanofaas-one-shot-workload .`.
Run with `NANOFAAS_MAX_CONCURRENT_HANDLERS=1`, explicit container CPU and memory limits,
and a unique execution ID and dispatch-attempt header for each invocation.
Input is `{ "iterations": 1000000, "working_set_bytes": 1048576, "seed": 42 }`.
The deterministic integer checksum consumes CPU work and actual memory writes.
Limits are one billion iterations and 128 MiB working set; unknown fields fail validation.
Runtime, allocator and SDK memory are additional to the requested working set.
Warm up before measuring; use the per-execution physical occupancy sample, not HTTP
latency. The runtime exposes `GET /runtime/executions/{executionId}` with its process
incarnation and dispatch attempt. ACTIVE and UNKNOWN cannot authorize slot reuse.
Terminal records default to 10000 entries and ten minutes, configurable by
`NANOFAAS_OCCUPANCY_MAX_TERMINAL_RECORDS` and `NANOFAAS_OCCUPANCY_RETENTION` (milliseconds).
Prometheus exports physical occupancy without execution IDs as labels.
This workload supports NanoFaaS validation and later environment-specific calibration;
it is not itself a calibrated scientific service profile.
