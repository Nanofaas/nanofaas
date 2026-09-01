# Kubernetes deployment

The `k8s` deployment backend (`platform/modules/k8s-deployment-provider`)
implements the managed `DEPLOYMENT` execution mode on Kubernetes. Registering
a function in `DEPLOYMENT` mode provisions a Deployment + Service in the
cluster; invocations are dispatched to the warm pods. Functions registered in
`EXTERNAL` mode are never touched by this backend.

Backend selection: set `nanofaas.deployment.default-backend=k8s` (or build the
control plane with only this provider on the classpath).

## Control plane

- Deployment: 1 replica, ports 8080 (HTTP) and 8081 (actuator/metrics).
- Service: ClusterIP for internal access.
- ServiceAccount + RBAC: create/list/watch Deployments, Services, HPAs, Pods.

## Function execution (DEPLOYMENT)

For each DEPLOYMENT function the provider provisions:

- **Deployment** with the function image, command and env from the
  `FunctionSpec`, `restartPolicy: Never`, and a Service pointing at it.
- **Service** (ClusterIP) on port 8080, selected by the `function=<name>`
  label.

Deployments and Services are **reconciled in place** during provisioning
updates instead of being deleted and recreated. HPA objects are reconciled
only when the function scaling strategy is `HPA`; stale HPAs are deleted when
a function is updated to another strategy. The `autoscaler` module's
`INTERNAL` strategy drives replica changes through the same provider.

### Per-function resources

Requests and limits use a backend-neutral contract:

```yaml
resources:
  requests:
    cpu: 0.25
    memoryMiB: 256
  limits:
    cpu: 1
    memoryMiB: 512
```

Kubernetes renders these as `250m`/`256Mi` requests and `1`/`512Mi` limits.
The `container-local` backend uses CPU shares/memory reservation for requests
and CPU quota/memory limit for limits.

### Image pull policy

Configurable through `nanofaas.k8s.image-pull-policy` (default `Always`, to
preserve mutable-tag behavior). Use `IfNotPresent` with immutable image
references to reduce registry pulls.

## Labels & annotations

- Labels: `app=nanofaas`, `function=<name>`, `executionId=<id>`.
- Annotations: `traceId`, `idempotencyKey`.

## Network

- The control plane calls the Kubernetes API in-cluster and dispatches
  invocations over HTTP to the function Service.
- Function pods never call the control plane back in DEPLOYMENT mode (warm
  HTTP responses are returned directly); the watchdog is used for
  STDIO/FILE-mode runtimes that wrap non-HTTP processes.

## Secrets

- Use Kubernetes Secrets for function env if needed; the control plane reads
  secret refs and injects them into the Deployment env.
- The control plane itself has no authentication (project constraint) — do
  not expose its API outside the cluster unless trusted.

## Setup and validation

Deploy the control plane with the Helm chart (`deploy/helm/nanofaas`), then
run the `validate-k8s` scenario for end-to-end validation:

```bash
export NANOFAAS_ROOT="$(pwd)"
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml --provision)
```

See `docs/e2e-tutorial.md` for the Multipass and external-SSH variants.
