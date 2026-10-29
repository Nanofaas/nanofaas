# One-shot roulette validation

Routing probabilities normalize the active plan's local, ready-outbound and cloud
rates. Destinations with zero weight are excluded; draws use [0,1). The production
source is ThreadLocalRandom; tests inject explicit draws. There is no seed setting
and concurrent request order is not reproducible. A missing ready plan and missing
peer retain their former cloud fallback. Forecast zero retains cloud fallback.
Physical-allocation transitions retain the existing LOCAL queue route. Invalid
metadata, identities and future epochs retain rejection. Delayed known neighbors
retain current-allocation queue admission. No rejection causes another draw.

The sync admission now uses the per-function queueSize as well as the existing
global depth ceiling. This is an intentional change for all sync-queue users, not
only one-shot. No queue or scheduler is replaced.

## Evidence

NanoLab saves `queue-samples-NODE.jsonl` every second using independent per-node
workers. It preserves Prometheus labels, process start time, UTC/monotonic sample
times, lateness, missed slots and HTTP failures. A failed scrape has no samples.
The existing physical-proof collection and historical HTTP latency are unchanged.

Queue lifecycle records use logger `it.unimib.datai.nanofaas.execution.QueueObservation`.
Enable NanoLab `observability.logs` (JSON console output and the existing collector)
for per-attempt queue times. NanoLab explicitly enables this dedicated INFO logger
while keeping the root at WARN; the runtime defaults it to OFF. The changed logging
fingerprint requires matching calibration/qualification evidence. Without it, sampled metrics and HTTP/physical latency
remain available, but per-attempt queue residence is not exported. Never interpret
the historical `function_queue_wait_ms` timer as strict queue residence: it starts
at execution admission.

Events contain processId, executionId, originalId (trace ID), attempt, function
and monotonicNanos. QUEUED captures successful queue insertion before releasing
the scheduler gate; logging occurs outside it. DISPATCH marks handing work to the
dispatch adapter, not the remote handler's start. RETURNED marks input-backpressure
return; REMOVED/EXPIRED mark the outside-gate terminal notification. These latter
timestamps include the small delay between removal and notification. They must
not be presented as nanosecond-precise internal removal timestamps.

NanoLab exports the matching log stream with overlapping timestamp pagination to
`logging/NODE/queue-events.jsonl` and derives `queue-timings.jsonl`. Missing pairs
produce null durations. Process IDs separate restarts. Repeated queue intervals
for the same attempt are added; physical handler duration remains the independent
SDK occupancySeconds. A complete export is not proof that every event was delivered:
consult collector delivery/archive status and reconcile original/attempt identities.
No cross-host clock subtraction is used.

## Validation and comparison

Local tests cover roulette boundaries/zero weights, repeated local/peer routing
without quotas, inbound bypass of RNG, full queue rejection without cloud traffic,
per-function isolation, the global ceiling, delayed peers and plan transitions.
The execution-runtime suite covers deadline, cancellation, retries and capacity
ownership. Queue sampler tests cover labels, failed scrapes, restart markers,
log pagination and missing timing boundaries.

Before a scientific comparison, freeze both commits, trace/input hashes, resources,
function/global queue ceilings, wait/deadline settings and enabled logging budgets.
Baseline routing is NanoFaaS 45bd6dc239ddd7d51c0be1ee1ccd85872803e2da; apply the same
measurement instrumentation and queue-limit policy to the comparison baseline,
or report the queue-limit change as a separate factor. Retain the historical
artifacts unchanged. Start with a short ORACLE step run, then paired full step
ORACLE, continuous ORACLE and ARI runs. Measure selected destinations separately
from executed destinations and retain rejected/missing requests in denominators.

Use existing timeout/SLO bounds, require no double hop or capacity overrun and full
request accounting, and freeze latency/overhead acceptance margins before running.
The current local tests do not establish an improvement in cloud share or production
overhead. Long campaigns, physical-handler saturation and logging delivery on real
Linux VMs remain required before integration/deployment.
