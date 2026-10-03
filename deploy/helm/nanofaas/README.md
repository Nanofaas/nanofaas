# Nanofaas Helm Chart

This chart deploys the Nanofaas control-plane and (optionally) registers demo functions.

## Install

```bash
helm install nanofaas helm/nanofaas --namespace nanofaas
```

By default the chart creates the `nanofaas` Namespace object (`namespace.create=true`).

## Prometheus Metrics

The control-plane exposes Prometheus metrics via Spring Boot Actuator at:

- `GET /actuator/prometheus` on port `8081` (service port name `actuator`)

### Bundled Prometheus (recommended for dev/POC)

By default, the chart also installs an internal Prometheus instance (`prometheus.create=true`) configured with
Kubernetes service discovery to scrape any annotated Pods/Services in the Nanofaas namespace.

Disable bundled Prometheus:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set prometheus.create=false
```

### HPA External Metrics

The control plane creates an HPA only for functions registered with
`scalingConfig.strategy: HPA`. Enable the shared Prometheus Adapter alongside
bundled Prometheus when such functions are used:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas \
  --set hpa-metrics-adapter.enabled=true
```

The adapter exposes `function_in_flight` as the `nanofaas_in_flight` external
metric, filtered by the function label. It remains disabled for deployments
that do not use HPA.

### External Prometheus Scrape

The chart adds classic Prometheus scrape annotations to the control-plane Service/Pod template
(`prometheus.scrape.enabled=true`).

If you are using Prometheus Operator, you can enable a `ServiceMonitor` (requires the CRD):

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set prometheus.serviceMonitor.enabled=true
```

### Container Metrics (cAdvisor)

Per-function CPU/RAM comparisons (for example in runtime A/B experiments) should use container metrics.
The chart exposes an optional `prometheus.containerMetrics` block with two modes:

- `mode=kubelet` (recommended on k3s): scrape kubelet `/metrics/cadvisor` via apiserver proxy.
- `mode=daemonset`: deploy a dedicated `nanofaas-cadvisor` DaemonSet and scrape it.

Enable kubelet mode:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas \
  --set prometheus.containerMetrics.enabled=true \
  --set prometheus.containerMetrics.mode=kubelet
```

Enable daemonset mode:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas \
  --set prometheus.containerMetrics.enabled=true \
  --set prometheus.containerMetrics.mode=daemonset
```

Legacy key `prometheus.kubeletResourceMetrics.*` is still supported for backward compatibility, but deprecated.

## Demo Functions (DEPLOYMENT mode)

When `demos.enabled=true`, a Helm hook Job runs after install/upgrade and registers demo functions via:

- `POST /v1/functions` on the control-plane service

Bootstrap creates missing demos and preserves already registered functions,
including user edits. On HTTP 409 the hook verifies that the named function can
be read with GET before continuing; other HTTP failures and transport errors
fail the Job. A retry after partial registration therefore converges, and an
upgrade with the same registry PVC does not overwrite or redeploy existing
demos. To change an existing demo, use the function API explicitly; changing
`demos.functions` alone affects only names that have not been registered yet.

In `DEPLOYMENT` mode the control-plane will provision Kubernetes resources for each function.

Disable demos:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set demos.enabled=false
```

## Control-plane upgrades

The control plane owns execution records, queues and scheduler state in memory.
The chart requires `controlPlane.replicaCount: 1` and uses a `Recreate` rollout:
the old pod stops before its replacement starts during an upgrade. Expect API
downtime while the replacement starts and restores its function catalog. The PVC
preserves the function catalog; it does not preserve queued/running executions or
pending callbacks. In-flight work may have produced side effects even if its
result becomes unavailable. Clients must handle retries and idempotency and
should drain work before a planned upgrade when completion matters.

`Recreate` prevents overlap during Deployment upgrades; it is not a general
fencing mechanism for node partitions, forced deletion or other Kubernetes
replacement events.

## Listening ports

`controlPlane.service.ports.http` and `.actuator` configure both the Service ports
and the application's listening ports (`SERVER_PORT` and
`MANAGEMENT_SERVER_PORT`). Container ports, health probes, demo bootstrap and the
derived callback URL follow those values. For example:

```bash
helm upgrade --install nanofaas deploy/helm/nanofaas \
  --set controlPlane.service.ports.http=18080 \
  --set controlPlane.service.ports.actuator=18081
```

The chart rejects these two environment keys in `controlPlane.extraEnv` to avoid
duplicate or inconsistent port definitions. If `controlPlane.callbackUrl` is
set explicitly, keep its port consistent with the reachable callback endpoint.
