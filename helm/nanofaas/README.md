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

By default, the chart adds classic Prometheus scrape annotations to the control-plane Service
(`prometheus.scrape.enabled=true`).

If you are using Prometheus Operator, you can enable a `ServiceMonitor` (requires the CRD):

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set prometheus.serviceMonitor.enabled=true
```

## Demo Functions (DEPLOYMENT mode)

When `demos.enabled=true`, a Helm hook Job runs after install/upgrade and registers demo functions via:

- `POST /v1/functions` on the control-plane service

In `DEPLOYMENT` mode the control-plane will provision Kubernetes resources for each function.

Disable demos:

```bash
helm upgrade --install nanofaas helm/nanofaas --namespace nanofaas --set demos.enabled=false
```
