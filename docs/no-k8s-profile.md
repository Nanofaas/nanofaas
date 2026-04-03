# No-K8s Profile

`container-deployment-provider` enables `DEPLOYMENT` mode without Kubernetes by managing warm function containers through a local container runtime CLI and exposing a stable local HTTP endpoint for invocation.

## What It Does

- Treats `executionMode=DEPLOYMENT` as a managed deployment intent backed by `deploymentBackend=container-local`
- Starts one or more local warm containers for the function image
- Exposes a stable local `endpointUrl` through a small round-robin HTTP proxy, so the existing core dispatcher can keep using a single endpoint
- Supports Docker-compatible CLIs through a runtime-adapter boundary; the first milestone ships with a generic CLI adapter that works with `docker`, `podman`, or `nerdctl`

## Prerequisites

- A Docker-compatible runtime available on `PATH`
  - `docker`
  - `podman`
  - `nerdctl`
- Function images must expose:
  - `POST /invoke`
  - `GET /health`

## Configuration

Global deployment selection:

- `nanofaas.deployment.default-backend`
  - Set to `container-local` to prefer the local managed provider when multiple providers are present

Container-local provider properties:

- `nanofaas.container-local.runtime-adapter`
  - CLI binary to use
  - Default: `docker`
- `nanofaas.container-local.bind-host`
  - Host interface used for proxy and published container ports
  - Default: `127.0.0.1`
- `nanofaas.container-local.readiness-timeout`
  - Max time to wait for `GET /health`
  - Default: `20s`
- `nanofaas.container-local.readiness-poll-interval`
  - Probe interval while waiting for readiness
  - Default: `250ms`
- `nanofaas.container-local.callback-url`
  - Optional callback base URL injected as `CALLBACK_URL` for async runtimes
  - Example: `http://host.docker.internal:8080/v1/internal/executions`
  - Default: unset

## Run Control Plane With Only Container-Local

```bash
scripts/control-plane-build.sh run --profile container-local -- \
  --args='--nanofaas.deployment.default-backend=container-local'
```

Using Podman:

```bash
scripts/control-plane-build.sh run --profile container-local -- \
  --args='--nanofaas.deployment.default-backend=container-local --nanofaas.container-local.runtime-adapter=podman'
```

## Notes And Current Limits

- The first milestone uses a CLI adapter, not an embedded containerd client.
- `imagePullSecrets` are not supported by `container-local` in this first cut.
- The stable invocation endpoint is provided by a small local proxy inside the module, which keeps the core `PoolDispatcher` unchanged.
- Async callbacks require `nanofaas.container-local.callback-url` to point back to the local control plane from inside the function container.
- Port allocation is best-effort: the current ephemeral-port probe has the usual bind-close-rebind race and is intended for local dev/single-node usage, not hardened multi-tenant scheduling.
- The adapter boundary is intentionally runtime-neutral so a future containerd-native adapter can replace the CLI implementation without changing the core provider contract.
