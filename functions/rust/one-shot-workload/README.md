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

For a managed container function, set `NANOFAAS_ONE_SHOT_PROFILE=true` in its environment,
the function concurrency cap high enough for all ready replicas, use
`STATIC_PER_POD` with `targetInFlightPerPod=1`, and set
`NANOFAAS_MAX_CONCURRENT_HANDLERS=1` in each replica.
The managed proxy checks `/runtime/status` before admitting a backend. It retains
occupied slots after response timeout or disconnect and polls bounded per-execution
proofs. Uncertain/expired observations quarantine the slot. Downscale first excludes
the backend from dispatch, then waits for physical drain; failure retains the container.
Recovery refuses to adopt or delete a one-shot container whose physical idleness
cannot be positively established.

The same image also provides five distinct handlers, selected explicitly by
`FUNCTION_HANDLER`: `one-shot-arithmetic`, `one-shot-sort`, `one-shot-memory`,
`one-shot-matrix`, and `one-shot-mixed`. Without that variable the original
`one-shot-workload` handler and its input remain available. Each new catalogue
entry selects its own typed handler; inputs for another handler are rejected.

New inputs use unsigned integers and reject unknown fields:

| Handler | Fields in addition to `seed` | Bounds |
| --- | --- | --- |
| arithmetic | `iterations` | at most 1 billion |
| sort | `items`, `passes` | items and passes at most 1 million; product at most 100 million |
| memory | `working_set_bytes`, `passes` | at most 64 MiB, 1 million passes; product at most 1 billion |
| matrix | `dimension`, `repetitions` | dimension at most 256, repetitions at most 1 million; cubic work at most 1 billion |
| mixed | `arithmetic_iterations`, `sort_items`, `sort_passes`, `working_set_bytes`, `memory_passes`, `matrix_dimension`, `matrix_repetitions` | all four component bounds checked before work |

Arithmetic evaluates an integer recurrence; sort permutes integer values between
sorts; memory repeatedly updates and checksums every byte; matrix multiplies
integer matrices and updates the left operand between repetitions. Mixed executes
all four components sequentially and combines their checksums. All arithmetic
wraps at 64 bits, and runtime work uses `spawn_blocking`. Independent Python
reference vectors cover seeds 0, 7 and `u64::MAX`. Runtime overhead counts towards
the strict memory limit; the input bounds do not guarantee a given container fits.
