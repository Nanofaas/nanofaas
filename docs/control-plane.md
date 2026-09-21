# Control-plane operation

The Java control plane is built directly with Gradle and deployed to Kubernetes
with Helm:

```bash
./gradlew :control-plane:bootJar
helm upgrade --install nanofaas deploy/helm/nanofaas
```

The nanolab tool coordinates validation and experiments without owning task
implementations. Scenarios under `packages/nanolab/scenarios-v2` (in the nanolab
checkout) compose tasks from `workflow-tasks`; environment files bind host,
stack, and load-generator roles.

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh inspect packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

Remote commands run through SSH and assume the repository at `<home>/nanofaas`.
VM provisioning remains an explicit Ansible/provider concern.

## Control-plane modules

The control plane has a minimal core and optional modules under
`platform/modules`. Each module publishes its entry-point configuration through
Spring Boot's `AutoConfiguration.imports` convention.

Select modules at build time with Gradle:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
# Invalid: the deployment providers are mutually exclusive.
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider,container-deployment-provider
```

The settings plugin `it.unimib.datai.nanofaas.control-plane-modules` discovers
the immediate Gradle projects under `platform/modules`, reads the required
`module.properties` descriptor, and validates selection before any task runs.

### What a module compiles against

Optional modules compile against `:control-plane-spi` — a mandatory contract library at
`platform/control-plane-spi`, not an optional module, and therefore never selected or
deselected. It holds the dispatch, admission, capacity, lifecycle-event, observation,
provider and replica contracts, and nothing else: no store, no record, no mutable capacity,
no registry, no controller, no executor and no autoconfiguration, all of which stay in the
core.

No module depends on `:control-plane` at compile time. That is enforced by the build graph
rather than by review: the contract library itself compiles only against `:common`,
`reactor-core` and `slf4j-api`, so a contract cannot name a core implementation, and a module
that reached into one would not compile either. A module's *test* sources may use the core,
because module integration tests legitimately run against the consumer.

### What a profile without a managed provider does not build

Managed deployment orchestration exists exactly when a managed deployment provider bean does,
which happens only when a provider module is selected. A LOCAL/EXTERNAL control plane therefore
builds no replica-refresh pools, no wake-up executor, no wake-up timeout scheduler and no wake-up
gate: none of them could ever have a provider to call, so each would be threads idling for the
process's lifetime. The function catalog is unaffected, and the deployment coordinator is still
available — built on a replica snapshot that owns no pool, so a replica reading in that profile is
reported as unavailable rather than as zero replicas.

The dispatch path asks a `DeploymentReadiness` port before a DEPLOYMENT attempt. With a provider
that port is the wake-up gate; without one it is an immediate implementation, and the path
dispatches directly rather than through the wake-up wrapper.

Where a module needs something a contract cannot express — the function catalog, replica
control, wake-up state, offload counters, hot admission limits — it consumes a narrow port
(`FunctionCatalogView`, `ManagedReplicaControl`, `DeploymentWakeUpControl`, `OffloadMeters`,
`AdmissionLimitsControl`) that the core implements. Each carries only the operations its
consumers use, so a control loop that reads the catalog cannot mutate it, and an offload
gateway that counts attempts cannot register meters.
The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`;
the project property `-PcontrolPlaneModules=...` has precedence. `none` cannot
be combined with other values. `all` selects every compatible module, preferring
`defaultEnabled=true` when a default and a non-default module conflict; conflicts
between modules with equal `defaultEnabled` values are ambiguous and fail the
build. Unknown names and other constraint violations also fail the build. The
default selects only descriptors whose `defaultEnabled=true`; this keeps
`async-queue` enabled and `sync-queue` disabled.

`k8s-deployment-provider` and `container-deployment-provider` are mutually
exclusive. Kubernetes is default-enabled, so `all` selects
`k8s-deployment-provider` and excludes the local container provider. Select
`container-deployment-provider` explicitly for local Docker workflows; selecting
both providers explicitly fails the build.

Each descriptor uses this format:

```properties
schemaVersion=1
id=sync-queue
defaultEnabled=false
requires.strong=
requires.weak=runtime-config
conflicts=async-queue
```

`requires.strong` must be selected (including transitively), while
`requires.weak` is optional. `conflicts` rejects a selected incompatible pair.
Selections are exposed to project builds as the immutable sorted Gradle extra
property `nanofaasSelectedControlPlaneModules`.

Every invocation uses the descriptor defaults unless a selector is supplied;
module selection is resolved at settings configuration time and cannot vary
within one build.

Current modules:

- `async-queue` — per-function scheduling strategy for the composed engine
- `sync-queue` — shared-queue scheduling strategy + sync admission/backpressure
- `async-queue` and `sync-queue` may both be selected: each contributes a
  `SchedulingStrategy` to one shared `SchedulerEngine` (issue #208's manual
  scheduler switching), not a separate worker; with both present the active
  strategy is chosen at startup (`nanofaas.scheduler.strategy`, defaulting to
  `per-function`) and can be hot-switched afterwards through
  `/v1/admin/runtime-config/scheduler` — see "Workload metrics" below
- `autoscaler` — internal replica scaler and scaling metrics integration
- `concurrency-control` — per-function concurrency governor (`FIXED`,
  `STATIC_PER_POD`, `ADAPTIVE_PER_POD`, `BUDGETED`, `SOJOURN`); **requires
  one of `async-queue` or `sync-queue`**
- `runtime-config` — hot runtime config service and namespaced admin API; modules
  contribute their editable parameters through the runtime-config extension SPI
- `build-metadata` — `/modules/build-metadata` diagnostics endpoint
- `k8s-deployment-provider` — default-enabled Kubernetes managed deployment
  backend; mutually exclusive with `container-deployment-provider`
- `container-deployment-provider` — local Docker-compatible deployment backend;
  mutually exclusive with `k8s-deployment-provider`
- `offload` — conditional transparent proxy of sync invocations to a remote
  instance

### Modules that need other modules

`concurrency-control` consumes two contracts published once by the composed
engine: `WorkloadMetricsSource` for queue depth and in-flight observations, and
`WorkloadCapacityController` for publishing the computed limits that enforce
concurrency. The module declares `requires.oneOf=async-queue,sync-queue`, so
at least one strategy module must be selected; it **refuses to start** when
neither is present (silently — no bean satisfies its
`@ConditionalOnBean(WorkloadMetricsSource.class)` — which is why the context
tests for both queue modules assert this bean's presence directly).

`autoscaler` requires one of `async-queue` or `sync-queue`; its workload metrics
source is the same single, engine-backed bean. The Gradle module selector
rejects an autoscaler selection without a queue provider.

### Workload metrics

Whichever strategy module(s) are selected, `SchedulerConfiguration` publishes
exactly one `WorkloadMetricsSource` (`EngineWorkloadMetricsSource`, backed by
the engine's own per-function reservation counters and `DispatchCapacity`'s
per-function state — never a backlog scan) and binds it through
`WorkloadMetricsBinder` to the per-function gauges `function_queue_depth`,
`function_inFlight`, `function_effective_concurrency` and
`function_dispatchable_backlog`. These gauges retire when a removed
function's last physically active attempt finishes draining, not at the
moment it is removed — see `SchedulerConfiguration.schedulerCapacityGenerationListener`.

Two additional gauges, `scheduler_active{strategy=...}` (one per built-in
strategy the artifact was actually assembled with — cardinality bounded by
that count, never by execution/ticket/generation identity) and a
`scheduler_switch_total{outcome=committed|noop|refused}` counter plus a
`scheduler_switch_duration` timer, observe `SchedulerEngine.switchTo` from
outside its own correctness transaction: a throwing observer can never turn a
committed switch into a reported failure or vice versa.

Image validation is **not** a standalone module: each deployment provider owns
its validator (`KubernetesImageValidator`, `DockerImageValidator`) and
activates it when selected as the deployment backend.

Each module owns its tests and explicit `@Bean` registrations; module packages
are not discovered through application component scanning.

## OpenAPI contract

The API contract is split between `openapi/core.yaml` (the always-present
routes) and a per-module `platform/modules/<id>/openapi.yaml` fragment. A
Gradle-time task (`OpenApiComposer` in `platform/gradle-plugin`) merges the
core document with the fragments of whatever modules were actually selected
for the build, and packages the result as `META-INF/resources/openapi.yaml`
inside the jar. There is no static `openapi.yaml` at the repository root
anymore: the contract is generated per artifact, so a route whose module was
not selected is absent from both the build and the published contract.

A fragment contributes `paths` and `components`, plus an optional
`x-nanofaas-overlays` list — `{operationId, patch}` entries applied to an
operation already defined elsewhere with JSON-merge-patch semantics (maps
recurse, `null` removes a key, anything else replaces it). Two fragments
defining the same path+method, the same component name, or conflicting
overlay values on the same operation fail the build rather than merging
silently.

An endpoint gated by a runtime property (for example
`/v1/admin/runtime-config`, active only when
`nanofaas.admin.runtime-config.enabled=true`) stays documented in the
contract as long as its module is built in — the fragment describes the
`404` response for the disabled case instead of omitting the path. The
contract reflects what a binary was *built* to serve, not the runtime
toggles of one running instance.

`control-plane:test --tests '*OpenApiRouteCoverageTest'` asserts every
registered route is present in the composed document for the module
selection under test, so contract and routes cannot drift.

## Manual scheduler switching

When a build composes an engine that exposes `SchedulerControl` (task 8's job — plain
`runtime-config` alone does not add one), the `runtime-config` module's admin API gains a
`scheduler` namespace at `/v1/admin/runtime-config/scheduler`, gated the same as every other
admin route by `nanofaas.admin.runtime-config.enabled=true`. It reuses the existing
`{expectedRevision, values}` PATCH envelope; there is no separate execution endpoint.

```bash
curl -fsS http://localhost:8080/v1/admin/runtime-config
curl -fsS -X PATCH http://localhost:8080/v1/admin/runtime-config/scheduler \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":0,"values":{"strategy":"shared-queue"}}'
curl -fsS http://localhost:8080/v1/admin/runtime-config/scheduler
```

The `0` above must be replaced with the revision the first `curl` just read — a stale
revision answers `409`. `strategy` must be one of the namespace's own `available` ids
(`per-function` or `shared-queue`, whichever the artifact was built with); anything else
answers `422`. The namespace itself is absent (`404`, on both GET and PATCH) when the
build has no engine exposing `SchedulerControl`.

The PATCH does not return `200` until the switch has actually committed: the admin path
runs off the Netty event loop on a small bounded worker (one in flight, one queued, anything
past that answers `503` immediately rather than opening an unbounded queue), and a refusal
before commit — the target strategy failed to prepare, or the admin queue is momentarily full
— also answers `503` with nothing activated; the previous strategy stays in effect and the
revision does not advance. A client that disconnects mid-request does not trigger a rollback
either way; a follow-up GET always reflects what actually happened.

The switch is manual, effective immediately in both directions without draining pending work
or restarting, and does **not** persist across a restart (`persistence: "restart"` in the GET
response) — the strategy configured at startup wins again on the next boot.

## Retries without a queue module

Retries do not depend on a queue module. With none loaded, the core hands the
next attempt to a small bounded pool (2 core / 8 max threads, 256 queued) instead
of leaving the record parked in `QUEUED` forever, which is what it used to do.
The retry policy itself is unchanged: `maxRetries` and the retryable-error rules
are the same in every profile.

The pool is a fallback, not a queue: it does not enable the asynchronous
`:enqueue` path, which still answers `501` without the `async-queue` module. If
the pool cannot accept a retry (it is saturated), the invocation concludes as a
terminal queue-full error rather than being silently dropped. The sizing is a
compile-time constant with no configuration knob.

## Abandoned executions and administrative expiry

When an execution exceeds its maximum lifetime, the control plane closes the
shared wait and requests cancellation of its local transport resources. The
execution becomes terminal with the error code `EXECUTION_EXPIRED`:

```json
{"code": "EXECUTION_EXPIRED",
 "message": "Execution exceeded its maximum lifetime before a dispatch outcome arrived"}
```

`GET /v1/executions/{id}` reports this like any other terminal error. It marks an
outcome the platform fabricated because the real one never came, as opposed to a
genuine failure the function reported — worth distinguishing when reading error
rates.

Each dispatched attempt owns a capacity lease tied to the function's registration.
Direct admission, retries and both queue schedulers use this ownership. Removing
and re-registering a function cannot redirect an old completion to the new capacity.
The core tracks registration and concurrency updates even without queue modules.

A non-cooperative LOCAL handler keeps its lease until its work actually ends;
cancelling a `CompletableFuture` is not evidence that its worker stopped. For
EXTERNAL and DEPLOYMENT, cancellation reaches the HTTP subscriber, including when
expiry precedes handle publication. A shared deployment wake-up future remains
available to other invocations. Cancelling a local HTTP request cannot guarantee
that a remote function stops executing.

An offloaded invocation acquires no backend dispatch slot on this plane. Its
transport uses the function's timeout budget; an individual caller's shorter
`X-Timeout-Ms` affects only that waiter's subscription. Administrative expiry also
cancels the outstanding offload subscription.

## Invocation ingress and retained-input measurement

Both `POST /v1/functions/{name}:invoke` and `:enqueue` enforce a finite body boundary
before complete JSON aggregation. A declared `Content-Length` above the boundary is
answered with `413 Payload Too Large` without subscribing to the body. For chunked or
otherwise unknown-length requests, the control plane counts bytes in each received
`DataBuffer`; the first buffer that crosses the boundary is released, the server-side
body subscription is cancelled, and the response is 413. This basic bounded parsing
happens before controller validation and idempotency replay, so replay cannot bypass it.

`nanofaas.invocation-capacity.ingress-body-bytes` is the single public boundary. NanoFaaS
applies it to the default WebFlux codecs and verifies at startup that the first reader which
can decode `InvocationRequest` exposes exactly that finite limit. A custom reader which takes
precedence but does not expose a compatible bound makes startup fail; NanoFaaS never skips it
and claims that a later reader is active.

Ingress bytes and retained-input bytes are different quantities. Ingress accounting
covers transient transport buffers and bounds how much can arrive before rejection.
The retained-input estimator is a conservative policy for JSON-like scalars and explicit
array representations that an execution may keep. Lists and maps are rejected, including
JDK implementations, because their public logical size does not expose retained backing
capacity; callers must convert them to a bounded array representation and retain only that
representation. Depth, width, visited-node and byte work are bounded; unsupported opaque LOCAL
values and values beyond a bound are rejected instead of receiving a small token weight. Extra
parser, transport, HTTP-client, LOCAL-worker or retry copies are excluded from that estimate and
require their own physical-copy ownership when P07c wires quotas. Consequently, the estimate does
not by itself claim to cap process RSS, native buffers or remote memory.

## Invocation capacity and admission ordering

The control plane reserves live work before publication. Defaults are finite and calibrated for
the Helm chart's minimum supported 512 MiB control-plane container:

| Property under `nanofaas.invocation-capacity` | Default | Unit / owner |
|---|---:|---|
| `ingress-body-bytes` | 1,048,576 | received request bytes before parsing |
| `executions-global` / `executions-per-function` | 4,096 / 512 | logical execution owners |
| `canonical-input-bytes-global` / `canonical-input-bytes-per-function` | 134,217,728 / 33,554,432 | canonical retained Java representation |
| `physical-input-copy-bytes-global` / `physical-input-copy-bytes-per-function` | 67,108,864 / 16,777,216 | additional runtime/transport materializations |
| `waiters-global` / `waiters-per-function` | 8,192 / 1,024 | attached synchronous callers, including replay |
| `max-input-references` | 64 | physical/queue references to one canonical input |
| `retained-input-max-depth` | 32 | bounded estimator depth |
| `retained-input-max-container-entries` | 16,384 | entries in one canonical container |
| `retained-input-max-visited-nodes` | 65,536 | estimator work per invocation |
| `retained-input-max-bytes-per-execution` | 1,048,576 | canonical bytes for one execution/copy |

All values must be positive, each per-function value must not exceed its global value, the
per-execution retained limit must fit both per-function byte budgets, and the ingress value must
fit WebFlux's integer codec limit. Invalid or overflowing configuration aborts startup.

For invocation HTTP requests the ordering is: ingress/body boundary (413), JSON and request
validation, function/rate checks, then idempotency lookup. A genuinely new request claims its key
when present,
canonicalizes the input, reserves execution and canonical-input ownership, and publishes the live
record before a synchronous waiter is admitted. If waiter admission fails, that newly published
record, key claim and both owners are rolled back before the 429 response. A replay does not create
or publish any of those owners; it only reserves its own transient waiter before attaching to the
existing live or archived result. Async admission has no waiter and publishes to its queue only
after the new execution/input owners exist. Thus replay cannot bypass body validation or waiter
capacity, but it does reuse the existing execution and canonical input. A 413 is not overload.
Saturated execution, canonical-input, physical-input-copy or waiter capacity returns 429 with
`Retry-After: 1` and the stable body
`{"error":"invocation_quota_exceeded","resource":"execution|input|input_copy|waiter"}`. Other
key, rate or queue saturation retains its existing 429 contract.

When the optional runtime-config module is enabled, its `control-plane` namespace can change the
four global/per-function quota pairs. Lowering a cap below current occupancy does not evict,
reassign or hide existing owners: new reservations are refused until drain brings usage within
the new limit. Raising a cap takes effect immediately and does not change the generation recorded
on an existing reservation.

These counters cover retained JVM objects the control plane owns. Parser scratch space, Netty
native buffers, HTTP connection-pool buffers, thread stacks, JVM/native overhead and remote
function memory are excluded. The ingress codec bounds parser aggregation; the owned HTTP pool
and function timeout bound transport concurrency/lifetime. The figures therefore provide an
admission envelope, not a whole-process RSS or remote-memory guarantee.

## Idempotency and outcome retention

A request may carry an `Idempotency-Key` header on `:invoke` and `:enqueue`.
The key is bound to the execution it admits for the execution's whole life,
and that binding is never allowed to become re-acquirable while the answer it
points at could still be served.

The key goes through three states, each with its own expiry derived from
`nanofaas.execution-store`, never configured separately:

- **pending** — claimed but not yet published; expires after `max-lifetime`,
  the same ceiling as a stuck execution.
- **published** — bound to a live execution; expires after `max-lifetime`,
  so the key and the execution die together if the dispatch never returns.
- **terminal** — the execution settled; this is the tombstone. Its retention
  is `ttl` and starts at *completion*, not at publication, so a long execution
  that completes just before `max-lifetime` still keeps its key for the full
  outcome-readability window.

Dedup and payload retention are decoupled. When an outcome is evicted for
capacity (`max-outcome-bytes`) before its window ends, the terminal key survives
as a light tombstone (execution id + expiry). A replay of that key does **not**
re-run the function: it returns an explicit `410 Gone` with the
`X-Execution-Id` header, so the caller can tell "ran, but the answer is gone"
from "never ran".

The number of keys/tombstones is bounded by `max-keys`, separate from the
outcome budget so a byte-budget on outcomes never silently evicts dedup
protection. At budget exhaustion a **new** keyed admission is refused with
`429 Too Many Requests` before dispatch; replays of keys already held remain
serviceable.

Outcome weights include keys, backing arrays and container overhead. Freezing and
weighing share one bounded traversal, and the cache reuses that weight. Opaque or
mutable numeric types, cycles and structures beyond the traversal budget are
not retained; idempotency tombstones still protect them. Oversized arrays are
rejected before copying. The legacy `116 B` multiplier derives a default byte
budget; it does not guarantee that `max-outcomes` readable responses fit. More
conservative accounting can therefore retain fewer results at the same budget.

Relevant settings under `nanofaas.execution-store`:

| Key | Default | Meaning |
|-----|---------|---------|
| `ttl` | `5m` | Terminal key retention and readable-outcome retention, from completion |
| `sync-ttl` | `30s` | Retention of an unkeyed sync outcome (answer already handed back) |
| `max-lifetime` | `30m` | Live key/execution ceiling for a stuck dispatch |
| `max-outcomes` | `100000` | Derives the default byte budget (`x 116 B`); not enforced as a count cap |
| `max-outcome-bytes` | `0` | Outcome byte budget (capacity eviction); `0` = derived from `max-outcomes` |
| `max-keys` | `100000` | Key/tombstone budget (refuses new keyed admissions when exhausted) |

## Build metadata

`GET /modules/build-metadata` (module `build-metadata`, see
`platform/modules/build-metadata/README.md`) reports what this binary was
built from and what it is running on: version, full git revision, dirty
working-tree flag, selected modules, build type/variant/optimization, base
OCI images, and runtime architecture/kernel/JVM facts. The Dockerfiles set
`NANOFAAS_BUILD_BASE_IMAGE` / `NANOFAAS_RUNTIME_BASE_IMAGE` from their
`BUILDER_IMAGE` / `RUNTIME_IMAGE` build args so the reported base images
match whatever base an image was actually built with. Every field is
nullable; missing data is JSON `null`, never a sentinel, and never blocks
startup.

## Persistent function catalog

The control plane persists its function catalog to a JSON file and restores
it at startup. The path is configured with `nanofaas.registry.path`
(environment variable `NANOFAAS_REGISTRY_PATH`); the default is
`build/nanofaas/functions.json`.

**Restore semantics.** An absent file is a normal empty first start: the
registry starts empty. A file that is unreadable, corrupt, or whose schema
version is unsupported aborts startup — the control plane refuses to serve a
partially restored catalog. Restoration replays each managed function against
the exact backend recorded in the catalog (`requireBackend`), so a function
that was persisted for a backend no longer available fails startup rather
than silently degrading to a different backend or to an unmanaged state.

**Security.** The catalog serializes each function's spec, including its
environment variables, in plaintext. On POSIX the parent directory is written
with mode `0700` and the file with mode `0600` (owner-only), and writes are
atomic (temp file + rename) so a crash cannot leave a truncated catalog. The
control plane runs as the distroless non-root user (UID/GID `65532`); treat
the backing volume as sensitive and secure it accordingly, and back it up —
it is the source of truth for registered functions.

**Helm.** The chart mounts a persistent volume at `/var/lib/nanofaas` and sets
`NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`. Tune it with
`controlPlane.persistence.{enabled,size,storageClass,existingClaim}`; setting
`existingClaim` reuses an operator-provided PVC and suppresses the chart's own
PVC, and `enabled: false` falls back to an `emptyDir` (state lost on pod
restart).

**Docker Compose.** The `control-plane-data` named volume is mounted at
`/var/lib/nanofaas` and `NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`
is set; the volume survives `docker compose down` unless removed explicitly.

**Limitations (MVP).** Registration is not journaled: a crash between the
durable catalog write and the provisioning of a managed function's resources
can leave a catalog entry whose backing resources were never created (an
orphan). Restoration only reconciles the functions recorded in the catalog;
unrelated residual Kubernetes resources or containers left over from an
earlier run are not swept at startup.
