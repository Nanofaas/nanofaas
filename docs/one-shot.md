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

The base auction already sends aggregate bids containing price and integer flow
quantity, grouped by neighbor and function within a round. For example,
`price=0.01, quantity=2000` with `q=0.001` represents 2 requests/s; the seller can
accept a smaller quantity. All records for a neighbor share one batch per round
phase, including empty phase closures. Increasing flow units does not add bid
records or batches. Prices and quantities can change between rounds. See
[aggregate bids and round traffic](one-shot-coordination.md#aggregate-bids-and-round-traffic)
for grouping, message counts and batch bounds.

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
infrastructure failures where execution was not attributed. A failed handler can
still have an execution node: the managed proxy accepts only physical runtime
evidence matching execution ID, attempt and incarnation, with an explicit boolean
`handlerStarted: true`. Rust sets that flag after typed input validation, immediately
before invoking the handler. `ACTIVE` and `RELEASED` alone describe physical slot
occupancy; a released admission can free the slot without attributing execution.
Missing, false or nonboolean start evidence does not attribute execution. It strips a backend
`X-NanoFaaS-Handler-Executed` value before generating its own internal evidence;
that internal field is not an allowed handler response header. Admission refusals,
unknown observations and stale attempts do not supply execution attribution.

## Real local cluster gate

The JVM and native recipes select `offload`, `forecasting`, `p2p-discovery`,
`container-deployment-provider` and `build-metadata`. `controlPlaneModules=all`
selects the default Kubernetes provider and is a separate compatibility gate.
Build the Rust fixture image from the repository root, then run the process test:

```sh
docker build -f functions/rust/one-shot-workload/Dockerfile -t nanofaas-one-shot-workload .
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew :control-plane:nativeCompile -Precipe=recipes/one-shot-local-native.yaml -PnativeParallelism=2 -PnativeBuildMemory=4g
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-native.yaml -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"
```

Use JDK 25 for JVM execution and the GraalVM version pinned in
`gradle.properties` for native compilation. Docker and the fixture image are
required; unavailable prerequisites fail the test. Four real control planes
share one Docker daemon, using distinct `nanofaas.container-local.namespace`
values for creation, discovery, recovery and cleanup. The harness obtains the
immutable local image ID from Docker; raw `sha256:…` image IDs are eligible only
when inspection confirms that exact local image. Registry digest references
remain supported, and mutable image tags retain their normal pulling behavior.

The scenario loads synthetic profiles and oracle traces, executes the actual
auction, prepares physical replicas, then checks local/peer/cloud execution,
idempotent replay and total physical occupancy samples. The next epoch changes
arrivals, pauses one replica during readiness and removes another peer before
routing. Logs, plans, events, responses and runtime metrics are retained under
`platform/modules/offload/build/test-diagnostics/`. Its 120-second period and
5-second auction budget are diagnostic bounds, not timing qualification or a
scientific calibration. NanoLab is not needed for this gate.

Spring AOT resolves conditional beans at build time. Build the enabled native
recipe to include one-shot and forecasting. Runtime disable still removes the
router and returns HTTP 404 from their administrative endpoints, including on
the terminal cloud using the same binary. A baseline native composition built
without these features cannot enable them later through a runtime flag.

`assembleRecipe -Precipe=recipes/one-shot-local-native.yaml` uses the container
builder for a Linux image; an explicit `:control-plane:nativeCompile` compiles a
host executable for the process gate. The harness exercises Docker CLI and
Docker Java adapters in different nodes, with the same physical protocol.

Packaged one-shot recipes select the Docker Java adapter: distroless control-plane images do not contain the Docker CLI. Supply access to the Docker daemon and a runtime host/network configuration from which published function ports are reachable; the local process harness supplies its own explicit adapter and host settings.
