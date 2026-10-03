# P2P exchange of real node information

Date: 2026-10-03

Status: proposed specification, awaiting review. The user approved use of the existing P2P messaging transport; implementation has not started.

Base: `origin/main` at `ef856960e99a6c56b93be6a978965535f7a3eba7`, which includes P2P discovery and runtime participation controls. The original local `main` was behind this revision. Work takes place in the attached `p2p-node-information` worktree.

## Intent and scope

Each NanoFaaS instance must exchange its actual registered functions, available images and resource usage with its active P2P neighbors. Operators must be able to enable and disable each category without restarting, to compare experimental configurations. Discovery, latency measurements, neighbor selection and ACTIVE/ISOLATED/LEFT participation retain their existing meanings.

The agreed transport is `PeerMessaging`. This design uses periodic request/response exchanges on that transport: each node polls its active neighbors, and each responder returns its current local snapshot. This gives bounded refresh and direct attribution without introducing another HTTP transport or placing inventories in SWIM membership metadata. Periodic broadcasts would also work, but require additional ordering and restart bookkeeping; embedding inventories in discovery would couple application data size to membership traffic.

The following interpretations are proposed for review:

- Images means images actually present in the configured deployment runtime, including cached images not currently used by a registered function. A registry reference in a function definition is not proof of local availability.
- Resources means measured CPU/memory for the environment visible to NanoFaaS and the existing NanoFaaS workload measurements. CPU/memory per function container is outside this first extension.
- The three switches control local collection and publication, independently of receipt of neighbors' data. Disabling publication does not isolate the node or prevent it from observing other nodes.
- Exchange is between active neighbors only, respecting both sides' existing selection rules. Nodes do not relay third-party snapshots.

## Data sources and module boundaries

`p2p-discovery` owns exchange, validation and the peer snapshot cache. It consumes `FunctionCatalogView` from `control-plane-spi`, `WorkloadMetricsSource` from `workload-metrics`, and a new small read-only image inventory contract in `control-plane-spi`. It does not depend on another optional module or on the mutable core registry.

Provider modules implement image inventory against their actual backend. The new contract returns source identity, scope, collection time, status and image entries; it neither pulls images nor mutates the runtime. Provider clients and resources retain their existing ownership and shutdown rules.

### Functions

Read an immutable catalog snapshot through `FunctionCatalogView.listRegistered()`. Publish function name, execution mode, configured image reference where present, and backend where applicable. Read the current registry on each collection so registrations, updates and deletions converge automatically. Use a dedicated summary DTO rather than transmitting the entire `FunctionSpec` with environment variables, invocation configuration and endpoints.

### Images

Publish image references/tags and a digest or immutable image ID when available. Include backend and inventory scope; sort and deduplicate entries deterministically.

| Backend | Source and semantics |
| --- | --- |
| container-local, docker-java | List images from the configured Docker daemon, using the existing client lifecycle. |
| container-local, CLI | List images through the configured runtime executable using structured machine-readable output and a bounded command timeout. |
| containerd | List images in the configured containerd namespace through its client. An image in another namespace is not included. |
| Kubernetes | Read `Node.status.images` and retain the Kubernetes node identity for each inventory. Report source age/partial visibility explicitly: kubelet-reported inventory is not a guarantee that every cluster node has the image or that the list is exhaustive. Do not substitute pod image references. |
| No managed provider | Report `UNAVAILABLE` with reason `NO_PROVIDER`. |

A provider listing failure or missing Kubernetes permission returns `UNAVAILABLE`, not an empty successful inventory. Kubernetes node listing requires the appropriate read-only RBAC rules in deployment manifests. The specification does not add image downloads, registry enumeration or image synchronization.

### Resources

Publish resource values with units, source and scope. Use existing Micrometer CPU/JVM memory measurements where available, and the platform management API for visible environment memory. Report environment CPU utilization as a ratio, environment memory used/total in bytes, process CPU utilization, and JVM heap used/max in bytes as distinct fields. JVM heap is never labeled as total process RAM; visible environment values are never labeled as a whole remote Docker host or Kubernetes cluster. Missing/unsupported or non-finite measurements are unavailable, never synthetic zeroes.

Use `WorkloadMetricsSource` for per-function in-flight count, effective concurrency, queue depth and dispatchable backlog, preserving its current definitions. In particular, engine queue depth counts reservations, not strictly only waiting requests. Collection may read function names internally when function publication is disabled; the functions category remains unpublished. Resource publication itself includes per-function names alongside workload measurements.

Only the visible environment and local NanoFaaS workload are covered. This feature does not introduce Kubernetes Metrics Server, Prometheus scraping, a per-container statistics loop or host agents. A deployment whose control plane cannot observe execution-host CPU/RAM must expose that limitation rather than claiming to measure that host. This boundary is a review point for the intended experiments.

## Snapshot contract

Use UTF-8 JSON inside the existing opaque `byte[]` application payload. Topic: `nanofaas.node-info.v1`; it avoids the reserved `p2p.` prefix. Envelope: `schemaVersion`, `nodeId`, `sampledAt`, and the three category results. Each category carries `status`, `collectedAt` when measured, `source`, `scope`, optional fixed `reasonCode`, and its typed data when available.

Category statuses are `AVAILABLE`, `PARTIAL`, `DISABLED` and `UNAVAILABLE`. `PARTIAL` indicates real but explicitly incomplete observations, such as kubelet image inventories or a resources result with unsupported fields. A successful empty list is `AVAILABLE` with an empty array. `DISABLED` and `UNAVAILABLE` carry no previous values.

The receiver verifies schema version, structure, units, finite/nonnegative values where applicable, and that the envelope node ID matches the peer identified by the messaging layer. Unknown additive fields are tolerated within schema version 1. Unsupported versions and malformed responses are rejected without damaging discovery or other peer exchanges. This is validation, not authentication: existing P2P trust assumptions remain unchanged.

Set a 1 MiB application payload limit, below the existing 2 MiB transport frame limit, with bounded JSON nesting/string sizes. Bound catalog/inventory collections during construction and decoding. If a category exceeds its budget, return `UNAVAILABLE` with `LIMIT_EXCEEDED` for that category; never silently truncate it or fail the whole snapshot. Use a 300 KiB encoded budget and 5,000-entry cap per category, leaving envelope space. Return fixed reason codes rather than raw backend exception text.

## Collection and exchange lifecycle

Start one node-information lifecycle alongside `P2pService`, only when P2P is enabled and ACTIVE. Register the topic handler once and preserve it through rejoin, following the current handler lifecycle. Backend I/O runs on bounded worker execution, never on cluster event loops or the invocation/scheduler path. The request handler returns an already prepared immutable snapshot and performs no backend I/O.

Collect local enabled categories every 5 seconds by default. Isolate category failures so that a failed image daemon does not suppress functions or resources. Each collector has a 2-second timeout and at most one actual backend operation in flight; do not start replacements indefinitely when a blocking client ignores cancellation. Configure underlying client/command deadlines as well as reactive timeouts. A missed collection remains timestamped and becomes unavailable after the local freshness window.

Poll current active neighbors every 5 seconds, with a 2-second request timeout and at most four concurrent peer requests. Do not overlap rounds or requests for the same peer. A response provides the responder's full current snapshot, replacing the previous one atomically. Thus a received disabled category removes its prior data. Failed exchanges do not stop subsequent rounds or other peers. An older node without the topic times out and appears as having no current information while continuing normal discovery.

Store peer information in memory only. Track receipt freshness with the receiver's monotonic clock; wall-clock timestamps are diagnostic and cannot extend freshness. Expire a snapshot after 15 seconds without a successful exchange, and expire category observations after 15 seconds at the source. Do not renew stale category data merely because a peer successfully retransmits it. Show expired peer information as `STALE` with no usable data. Cache size is bounded by known peers; delete entries when membership removes a peer, and make data unavailable immediately when a peer is excluded or no longer active.

ISOLATED, LEFT and stop halt collection/exchange and clear usable remote snapshots. Re-entering ACTIVE requires fresh exchanges. Fence completions from a previous participation generation so that an in-flight request cannot repopulate the cache after leaving. Configuration changes similarly fence collector results started under older publication settings.

## Runtime controls and persistence

Extend the existing P2P configuration endpoint and `P2pSettings`; do not introduce a dependency on the optional runtime-config module.

| Key | Default | Meaning |
| --- | --- | --- |
| `shareFunctions` | `false` | Collect and publish the local registered-function summary. |
| `shareImages` | `false` | Collect and publish the actual runtime image inventory. |
| `shareResources` | `false` | Collect and publish the resource/workload measurements above. |

Boolean keys reject null, strings and numbers. PATCH validates the entire request before applying it atomically. Default-off preserves the existing discovery-only configuration; P2P itself retains its existing deployment master switch. The collection/poll/freshness durations above are fixed initial defaults, injectable for tests, rather than additional runtime settings in this scope.

Example:

```http
PATCH /v1/admin/p2p/config
Content-Type: application/json

{"shareFunctions":true,"shareImages":false,"shareResources":true}
```

After the PATCH response, new snapshot requests must never return a disabled category's old data. Mask it immediately, stop its collectors and discard late collection results. Enabling a category triggers collection without waiting for the next scheduled interval; until the first result, report `UNAVAILABLE`/`NOT_COLLECTED`. Remote convergence occurs at the next successful poll; already transmitted packets cannot be recalled.

Even with all three categories disabled, lightweight snapshot requests/responses continue to communicate disabled state and observe neighbors. No category collector runs. Disabling resource sharing does not disable metrics collection already needed by other NanoFaaS components.

Expose startup equivalents under `nanofaas.p2p`, include them in the state-file `config`, and persist runtime overrides using existing precedence: state overrides > file config > application properties. DELETE `/v1/admin/p2p/overrides` restores base values and existing peer-mode behavior. Older files missing these keys retain false defaults. Snapshot contents themselves are never persisted.

## Inspection API and documentation

Add GET `/v1/admin/p2p/information` for the current local publication snapshot and GET `/v1/admin/p2p/peers/{id}/information` for a known peer's last usable snapshot and freshness metadata. Unknown peer IDs return 404. Known peers with no fresh information return a typed availability state (`NOT_RECEIVED`, `STALE` or `INACTIVE`) rather than invented data. Apply the existing P2P/admin gate to both routes. GET `/config` includes effective flags and overrides.

Update the P2P OpenAPI fragment, README, control-plane documentation, application configuration and supported deployment configuration/RBAC. Include examples of enabling one category, all categories, none, and restoring defaults. Document backend image scope and CPU/memory scope next to the examples.

## Acceptance and validation

1. Three real P2P nodes exchange distinct local catalog, image and workload snapshots; each sees only active neighbors and no relayed third-party data.
2. Register/update/delete functions and add/remove backend images; peers converge to the actual updated inventories after collection and exchange.
3. Exercise all eight flag combinations on a running node. Assert exact category state, no disabled collector calls, atomic invalid-patch rejection, immediate local masking, and remote removal on the next successful exchange.
4. Verify state-file round trips, old files, startup precedence and reset of overrides.
5. Verify missing providers, backend errors, missing individual measurements and legitimate zero/empty measurements remain distinguishable. Test each supported backend adapter's real listing format/API contract; use existing container integration facilities for backend smoke coverage where available.
6. Verify payload/entry limits, malformed JSON, unsupported schema, unknown extra fields, identity mismatch, timeouts and peers running the old discovery-only version.
7. Use an injected clock to test freshness without wall-clock sleeps. Test exclusion, loss of active status, membership removal and ACTIVE/ISOLATED/LEFT transitions including late completions and rejoin.
8. Verify native serialization reachability metadata for new DTOs, module architecture rules and the control-plane module integration matrix. Keep Scalecube use confined to `PeerCluster`.
9. Run focused tests for affected modules and native smoke validation where the environment supports it. Record any unverified backend/native behavior explicitly; a mocked inventory is not evidence that real backend collection works.

Baseline evidence: the unchanged module at the base revision completed `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --console=plain` with 114 tests, zero failures/errors/skips. This validates only the starting point, not this proposed extension.

## Non-goals

No automatic remote registration, image transfer, invocation routing changes, resource-based offload decisions, transitive federation, history storage or authentication changes. The exchanged information prepares those possible consumers without implementing them.
