# One-shot offload in NanoFaaS

This optional offload mode implements the base decentralized auction from the
pinned optimizer branch. There is no local search or hierarchical/PG variant.
NanoLab workflows, Multipass provisioning and scientific Azure experiments are
separate later phases. All measurements in local NanoFaaS tests are diagnostic.

Load the `offload`, `forecasting`, `p2p-discovery` and local deployment modules.
Enable one-shot explicitly with `nanofaas.offload.one-shot.enabled=true`.
Set bootstrap `auction-budget`, `peer-timeout`, `solver-budget`,
`preparation-budget`, `clock-threshold` and `clock-max-age` as durations. Configure
an explicit P2P invocation URI and the future external-arrival forecast provider.
Disabled one-shot preserves ordinary offload. Clock health comes from an external
UTC-offset measurement, never from network RTT.

Register eligible functions on the local container backend, using digest-pinned
images, explicit CPU and memory limits, HTTP runtime and physical SDK release
proof. Set `NANOFAAS_ONE_SHOT_PROFILE=true`, SDK
`NANOFAAS_MAX_CONCURRENT_HANDLERS=1`, and `STATIC_PER_POD` with
`targetInFlightPerPod=1`. The function-wide concurrency ceiling must cover every
possible replica count in the optimization budget. Keep nonselected workloads
outside this explicitly reserved function-pool memory budget; account OS,
control-plane and other workloads separately when assigning that budget.

Calibrate warm physical handler occupancy before experiments. Upload a version 1
service profile from `docs/contracts/one-shot/service-profile.schema.json` using
`PUT /v1/admin/offload/one-shot/profiles/{id}`, `If-Match: 0` on first installation
and `X-Content-SHA256: sha256:<hash of the exact uploaded bytes>`. The content hash
verifies upload integrity; image/input/environment hashes identify calibration
scope. Record sample statistics, cold-start exclusion, co-location, raw sample
hash and validated replica range. The current exact solver may choose any count
from one through floor(pool-memory/function-memory); the calibration range and
function-wide cap must cover that entire range. Unsupported partial ranges are
rejected instead of silently extrapolated.

Profiles must match image digest, CPU, memory, runtime/backend, fixed workload
input hash, environment fingerprint and purpose. A measured Multipass profile is
eligible only for compatible `workflow-validation` runs. Scientific experiments
require their own compatible measurement on the experiment target. Synthetic
profiles need `allowSynthetic=true` and are restricted to workflow validation;
no API converts synthetic evidence into real evidence. The synthetic example in
`docs/contracts/one-shot/examples` documents shape only and contains no usable
image or scientific measurement.

Install configuration through `PUT /v1/admin/offload/one-shot/config` with the
expected `If-Match` revision. It includes generation-scoped function identities,
cloud URI, pool MiB, flow quantum q, explicit period and lead time, preparation
budget, round/peer/queue/solver limits, utilization/utility coefficients and burst
limit. Rates are requests/s; only load and physical demand are converted to grid
units. Oracle input must lie exactly on the q grid. EWMA sub-grid residual goes to
cloud. Frozen inputs retain one configuration/profile revision and compatible
oracle revisions throughout an auction; updates apply to the next preparation.
An owned function cannot leave the managed group until positive drain/release.

Report clock offset and its original measurement time with
`PUT /v1/admin/offload/one-shot/clock-health`. Old reports cannot advance freshness;
future-dated reports are rejected. For deterministic tests use
`POST /v1/admin/offload/one-shot/epochs/{epoch}/prepare` with `{startsAt, endsAt}`
while `scheduled=false`. The half-open UTC window must match the configured
period, lie in the future and use monotonically increasing epoch IDs on all
participating nodes. Trigger every neighbor within the preparation window.

The full operational budget (auction plus physical preparation) must fit the lead
time and at most the configured small fraction of the period (maximum 0.2,
typically 0.1). There is no default one-minute period. Scheduled mode requires at
least 20 complete successful samples with twice the observed p99 below both the
fractional period allowance and lead time. Sampling is bounded to 128 runs and
qualified against calibration, function/flow/resource identities and neighbor
incarnations. These local qualification samples cannot choose an Azure period.
Scheduling uses the explicit UTC anchor and period; manual triggers conflict with
scheduled mode. Membership or profile changes invalidate qualification; unhealthy clock reports block runs.

`GET /v1/admin/offload/one-shot/status` exposes preparation state, clock health,
configuration revision and the active ready plan. `GET .../epochs/{epoch}/events`
accepts `after` cursor and `limit` (1..1000); retention is 10000 node-global events.
The envelope includes schema version and content hash. Each event follows the
version 1 event schema; page entries add cursor and full operational duration.
Solver, auction, qualified full operational durations and bounded status counters
are separate metrics. Deadline, round-limit, failure, cancellation and degraded
readiness never count as complete qualified duration samples. Node/epoch IDs
belong in events, never metric labels.

Ownership persists across lease expiry and uncertain preparation. After every
announced/active window ends, `POST .../drain-and-release` releases it only when the
runtime positively proves physical drain. Unknown execution status quarantines
capacity and does not free it on an HTTP timeout. See
[coordination and preparation](one-shot-coordination.md) for readiness protocol
and memory overlap behavior, and the phase-A handoff dossier for test commands.

## Native invocation routing

External arrivals receive a deterministic weighted LOCAL, confirmed peer or cloud
choice. Local and per-assignment token buckets have the configured finite burst.
An unavailable/changed peer or exhausted quota goes to terminal cloud before any
peer dispatch. Accepted quota is not refunded after a timeout. Each execution pins
its route once; local pressure and retry never choose another destination, and a
remote error after sending never produces a second cloud attempt.

The sender sets native HTTP headers `X-NanoFaaS-Offload-Version: 1`,
`X-NanoFaaS-Offload-Origin: node@incarnation`, `X-NanoFaaS-Offload-Epoch`,
`X-NanoFaaS-Offload-Assignment`, plus `X-NanoFaaS-Offload-Hop: 1`. The dedicated
cloud uses assignment `cloud`; it executes locally and never forwards again. A
peer accepts only its confirmed assignment, current buyer incarnation and epoch,
and its own remaining quota. Missing, duplicate or inconsistent native metadata
is rejected; any hop marker, including malformed legacy values, prevents another
hop. Function payload headers cannot supply these control-plane fields.

Calibration input hashes identify the exact JSON input, serialized with sorted
map keys and properties using the platform Jackson mapper. Selected functions
reject other inputs, so tests must send the calibrated fixed workload. Trusted
`X-NanoFaaS-Execution-Node` travels separately from handler-provided headers and is
preserved through remote completion and idempotent replay. It is absent on
infrastructure failures where execution was not attributed.
