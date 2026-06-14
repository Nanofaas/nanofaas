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

In `DEPLOYMENT` mode the control-plane will provision Kubernetes resources for each function.

Disable demos:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set demos.enabled=false
```
