# Persistent Function Registry Design

**Date:** 2026-09-01

**Status:** Validated

## Goal

Persist the registered-function catalog as core control-plane state so completed
registrations, updates, removals, and core-managed replica targets survive a
control-plane Pod or container replacement. On startup, restore the catalog and
reconcile managed workloads before the control plane becomes ready.

Persistence belongs to `platform/control-plane`, not to an optional module. The
registration catalog is required by invocation, queue providers, metrics,
autoscaling, concurrency control, updates, and deletion; making it optional
would make a fundamental control-plane invariant optional.

## Non-goals

- Multiple control-plane replicas or concurrent writers.
- A database-backed or pluggable storage SPI.
- A distributed transaction across the catalog and deployment backends.
- A journal for operations interrupted before their API request completes.
- Persisting Kubernetes HPA decisions made outside NanoFaaS.
- Application-level encryption of the catalog.

## Architecture

`FunctionRegistry` remains the in-memory read model. Invocation and lookup paths
continue to read from its `ConcurrentHashMap` and perform no filesystem I/O.

A concrete core component owns catalog serialization and atomic file
replacement. No storage interface is introduced until a second implementation
exists. Registry mutations are serialized by one global mutation lock, which
prevents two concurrent mutations from publishing snapshots based on different
views. Function invocation does not acquire this lock.

The catalog is a versioned JSON envelope:

```json
{
  "schemaVersion": 1,
  "functions": [
    {
      "spec": {},
      "deploymentMetadata": {
        "requestedExecutionMode": "DEPLOYMENT",
        "effectiveExecutionMode": "DEPLOYMENT",
        "deploymentBackend": "k8s",
        "degradationReason": null,
        "effectiveEndpointUrl": "http://fn-echo.nanofaas.svc.cluster.local:8080/invoke",
        "deploymentObjects": {},
        "desiredReplicas": 1
      }
    }
  ]
}
```

Entries are sorted by function name before serialization so snapshots are
deterministic. `desiredReplicas` is null for non-managed functions. For a new
managed registration it is initialized from `scalingConfig.minReplicas`, or to
`1` when no minimum is configured.

## Durable file replacement

The configured catalog path is `nanofaas.registry.path`. A mutation writes a
temporary file in the catalog directory, forces the file contents to disk, and
atomically replaces the canonical file. The successful API response is not
returned before this operation completes.

If a write fails, the in-memory mutation is restored and the request fails. A
temporary file left by a terminated process is ignored at startup and replaced
by the next write. The canonical file is never inferred from the temporary
file. An empty catalog is stored as a valid versioned document rather than by
deleting the file.

An absent canonical file means a first start with an empty catalog. A present
but unreadable file, malformed JSON, unsupported schema version, duplicate
function name, or invalid record fails startup. The control plane must never
silently convert damaged persisted state into an empty registry.

## Mutation semantics

Registration retains its existing synchronous semantics:

1. Resolve and validate the `FunctionSpec`.
2. Provision the managed workload when required.
3. Notify registration listeners.
4. Commit the `RegisteredFunction` to memory and the durable catalog.
5. Return success.

If the durable commit fails, listener effects and newly provisioned resources
are compensated. The existing registration rollback path must include listener
compensation when persistence, rather than a listener, is the failing step.

Updates first commit the new durable desired state and then notify listeners,
retaining the existing convergent listener behavior. Deletion removes listener
and provider state before committing removal from the catalog. If the process
terminates before the removal commit, the still-persisted registration is
authoritative and startup reconciliation recreates any resources already
removed. A live commit failure restores and reconciles the registration.

This design guarantees completed operations. A hard termination during an
incomplete registration may leave backend resources that are not in the
catalog. An intent journal is deferred until this failure mode is demonstrated
to require one.

## Persisted replica target

`ManagedDeploymentCoordinator` is a core component and is the single path for
replica changes initiated by the core API, the internal autoscaler, and the
scale-to-zero wake-up gate. Persistence therefore belongs in
`ManagedDeploymentCoordinator.setReplicas()`, not only in
`FunctionService.setReplicas()`.

For each replica change, the coordinator:

1. Durably updates `DeploymentMetadata.desiredReplicas`.
2. Applies the target to the selected provider.
3. Restores the previous durable target if the provider rejects the change.

Replica changes for one function must be serialized so concurrent manual,
autoscaler, and wake-up decisions cannot leave the provider and catalog in
opposite orders. The internal autoscaler calls the coordinator only for an
actual scaling decision, not on every polling cycle, so persistence follows
scaling events rather than metric polling.

For `ScalingStrategy.HPA`, Kubernetes owns dynamic replica decisions and does
not call the coordinator. NanoFaaS persists the HPA configuration but not every
replica count selected by the Kubernetes controller. Recreating the HPA lets
Kubernetes recalculate the target.

## Startup and readiness

Startup restoration is blocking. The control plane does not become ready until
all records have been loaded, managed functions reconciled, refreshed metadata
saved, and registration listeners replayed.

Records are processed in function-name order. `LOCAL` and `EXTERNAL` functions
require no provider action. A `DEPLOYMENT` function must use the backend ID saved
in its deployment metadata; startup does not rerun default backend selection or
degrade the function to `EXTERNAL`.

For non-HPA functions, the persisted `desiredReplicas` is the desired replica
target during reconciliation. HPA functions restore their Deployment and HPA
configuration and allow Kubernetes to select the dynamic target.

After provider reconciliation, returned endpoints and deployment object
metadata replace stale values in the in-memory record. One updated snapshot is
written after the complete reconciliation pass. Registration listeners are
then replayed so queues, metrics, autoscaling, and concurrency-control state are
recreated before readiness changes to accepting traffic.

Any catalog, backend-resolution, provider-reconciliation, snapshot, or listener
failure aborts application startup. Kubernetes or Compose restart policy then
retries the same idempotent restoration.

## Provider reconciliation contract

`ManagedDeploymentProvider` gains a distinct reconciliation operation that
receives the persisted specification and deployment metadata. It is not a
default alias for `provision()`: every provider must explicitly define safe
restart behavior.

### Kubernetes

The Kubernetes provider inspects the expected Deployment, Service, and HPA. It
preserves coherent existing objects and creates missing ones. It does not
delete and recreate the whole workload merely because the control plane
restarted. For non-HPA functions it restores the persisted desired replica
target when the actual target is missing or inconsistent. When the entire
workload is absent, creation starts from `desiredReplicas`.

### Container-local

Container instances receive NanoFaaS ownership labels including function name
and replica index. Runtime adapters expose the minimum inspection needed to
enumerate managed instances and recover their network or published-port
endpoint.

On reconciliation, the provider creates a new in-process proxy, rebuilds its
internal state, and adopts healthy owned containers. It recreates only a
missing or unhealthy replica, and scales the adopted set to the persisted
`desiredReplicas`. A normal control-plane restart must not call the existing
destructive `runContainer()` path for an adoptable replica. Instances above the
persisted target are removed as a normal scale-down, not as wholesale restart
cleanup.

## Deployment configuration

The local `bootRun` default stores the file below
`build/nanofaas/functions.json`, preserving zero-setup development while
allowing `clean` to reset development state intentionally. Real deployments set
the path to `/var/lib/nanofaas/functions.json`.

The Helm chart mounts `/var/lib/nanofaas` from a control-plane PVC. Persistence
is enabled by default. Values cover only PVC size, optional storage class, and
an optional existing claim. The Pod security context grants the Distroless
`nonroot` user write access without an init container.

The file-based catalog supports one control-plane Pod, matching the existing
single-control-plane architecture. `controlPlane.replicaCount` refers only to
control-plane Pods and remains `1`; it does not constrain function replica
counts or scale-to-zero.

Docker Compose declares a `control-plane-data` named volume and mounts it at the
same path. `docker compose down` preserves the catalog; `docker compose down -v`
or explicit volume removal deletes it. Standalone Docker uses the equivalent
named-volume mount.

## Security and observability

On POSIX deployments the catalog directory is restricted to `0700` and the file
to `0600`. The catalog is not encrypted by NanoFaaS. Its volume is part of the
control-plane trust boundary and can contain sensitive `FunctionSpec.env`
values; this limitation is documented explicitly.

Startup errors include the catalog path, function name, and backend where
useful, but never log environment values, the complete specification, or raw
catalog contents. A successful startup log reports counts of loaded and
reconciled functions. No new metric is added: fail-fast readiness and startup
logs cover this lifecycle, and metrics are unavailable when startup aborts.

## Testing

Core filesystem tests use temporary directories and cover:

- absent and empty catalogs;
- complete round trips and deterministic ordering;
- unsupported versions, malformed JSON, invalid records, and duplicate names;
- atomic replacement and ignored temporary files;
- in-memory rollback after a write failure;
- concurrent mutations without lost snapshots;
- persisted replica targets and rollback after provider failure.

Startup integration tests use fake providers and listeners to verify:

- no provider call for `LOCAL` and `EXTERNAL`;
- exact saved-backend selection for `DEPLOYMENT`;
- refreshed endpoint and deployment metadata;
- replay of listeners before readiness;
- startup failure on provider, persistence, or listener errors.

Kubernetes provider tests cover preservation of existing resources, creation of
each missing resource, replica restoration for non-HPA functions, and HPA
restoration without persisting controller decisions.

Container-local tests cover ownership labels, discovery and adoption of healthy
instances, selective recreation, proxy reconstruction, endpoint recovery,
scaling to the persisted target, and the absence of destructive container
replacement during an ordinary restart.

Helm tests cover PVC creation, existing claims, mount paths, and permissions.
Compose tests cover the named volume and configured catalog path. The real
Kubernetes lifecycle scenario belongs to NanoLab: register a function, recreate
the control-plane Pod, wait for readiness, and invoke without registering the
function again.

## Alternatives rejected

An embedded database adds a driver, schema migrations, native-image work, and
operational complexity without a current scale or concurrency requirement.
Kubernetes CRDs or ConfigMaps couple core registration to Kubernetes and do not
serve `LOCAL`, `EXTERNAL`, or `container-local` deployments. A persistence
module makes catalog durability optional even though the entire control plane
depends on the catalog. These alternatives can be reconsidered only when the
single-writer file design has a measured limitation.
