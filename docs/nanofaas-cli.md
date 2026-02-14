# nanofaas CLI

This repository includes a standalone CLI in the `nanofaas-cli/` subproject.

The distribution provides:
- `nanofaas-cli`: the executable (native, when built with GraalVM)
- `nanofaas`: a small wrapper that executes `nanofaas-cli`, so you can run `nanofaas <command>`

## Build

Run on the JVM:

```bash
./gradlew :nanofaas-cli:run --args="--help"
```

Build a GraalVM native executable:

```bash
./gradlew :nanofaas-cli:nativeCompile
./nanofaas-cli/build/native/nativeCompile/nanofaas-cli --help
```

Build archives (contains `bin/nanofaas` and `bin/nanofaas-cli` start script):

```bash
./gradlew :nanofaas-cli:distTar :nanofaas-cli:distZip
```

## Configuration

You can pass the control-plane endpoint via:
- `--endpoint http://...`
- env `NANOFAAS_ENDPOINT`
- config file `~/.config/nanofaas/config.yaml`

Global flags:
- `--config <path>`: override config path
- `--endpoint <url>`: override endpoint (wins over env/config)
- `-n, --namespace <ns>`: Kubernetes namespace (used by `k8s` commands)

Config file format:

```yaml
currentContext: dev
contexts:
  dev:
    endpoint: http://localhost:8080
    namespace: nanofaas
```

Supported env overrides:
- `NANOFAAS_ENDPOINT`
- `NANOFAAS_CONTEXT`
- `NANOFAAS_NAMESPACE`

## Commands

### `fn`

- `nanofaas fn list`: list registered functions (name + image)
- `nanofaas fn get <name>`: get the function spec
- `nanofaas fn delete <name>`: delete a function
- `nanofaas fn apply -f function.yaml`: create or replace a function

`apply` behavior:
- `POST /v1/functions`
- if the server returns `409`, the CLI does `GET /v1/functions/<name>` and, if different, performs `DELETE` then `POST` again
- the control-plane always validates image pull before registration
  - `IMAGE_NOT_FOUND` (`422`): image/tag does not exist
  - `IMAGE_PULL_AUTH_REQUIRED` (`424`): private image requires Kubernetes `imagePullSecrets`
  - `IMAGE_REGISTRY_UNAVAILABLE` (`503`): temporary registry validation issue

### `deploy`

`nanofaas deploy -f function.yaml` always:
1. builds and pushes the function image via `docker buildx build --push`
2. registers/replaces the function on the control-plane (same semantics as `fn apply`)

Requirements:
- `docker` must be available locally
- the image tag must be pullable by the Kubernetes cluster (typically via a registry reachable from the cluster)

The function YAML must include `image:` and an `x-cli.build` section:

```yaml
name: echo
image: registry.example/team/echo:1.2.3
timeoutMs: 10000
concurrency: 2
imagePullSecrets:
  - ghcr-creds

x-cli:
  build:
    context: .
    dockerfile: Dockerfile
    platform: linux/amd64
    push: true
    buildArgs:
      VERSION: "1.2.3"
```

### `invoke`

Synchronous invocation:

```bash
echo '{"message":"hi"}' | nanofaas invoke echo -d @-
```

Options:
- `-d, --data @file|@-` (required): JSON payload that becomes `InvocationRequest.input`
- `--timeout-ms N`: sent as `X-Timeout-Ms`
- `--idempotency-key K`: sent as `Idempotency-Key`
- `--trace-id T`: sent as `X-Trace-Id`

### `enqueue`

Asynchronous invocation:

```bash
nanofaas enqueue echo -d @request.json
```

Options:
- `-d, --data @file|@-` (required)
- `--idempotency-key K`
- `--trace-id T`

### `exec`

Get execution status:

```bash
nanofaas exec get <executionId>
nanofaas exec get <executionId> --watch
```

Options:
- `--watch`: poll until terminal status (`success`, `error`, `timeout`)
- `--interval PT1S`: polling interval (ISO-8601 duration)
- `--timeout PT5M`: maximum watch time (ISO-8601 duration)

### `k8s`

Kubernetes helper commands (via Fabric8; kubeconfig or in-cluster config required):
- `nanofaas k8s pods <function>`
- `nanofaas k8s logs <function> [--container function]`
- `nanofaas k8s describe <function>`

Resource conventions (as created by the control-plane):
- label selector: `function=<name>`
- deployment/service/hpa name: `fn-<name>`

### `platform`

Platform lifecycle commands (requires `helm` and Kubernetes access via kubeconfig/in-cluster config):

- `nanofaas platform install`
- `nanofaas platform status`
- `nanofaas platform uninstall`

#### `platform install`

Installs/upgrades the Helm chart and configures a NodePort endpoint suitable for k3s defaults.

Defaults:
- release: `nanofaas`
- chart: `helm/nanofaas`
- namespace: `nanofaas` (or resolved global `--namespace`/config/env)
- API NodePort: `30080`
- actuator NodePort: `30081`

Example:

```bash
nanofaas platform install
```

Custom release/namespace/tag:

```bash
nanofaas platform install --release nanofaas-dev -n dev --control-plane-tag v0.9.2
```

Local image (k3s e2e) and no demo registration:

```bash
nanofaas platform install \
  --release nanofaas-dev \
  -n dev \
  --control-plane-repository nanofaas/control-plane \
  --control-plane-tag e2e \
  --control-plane-pull-policy Never \
  --demos-enabled=false
```

After install, the CLI resolves an endpoint like `http://<node-ip>:30080` and stores it in the active CLI context.

#### `platform status`

Shows:
- control-plane deployment readiness (`ready/desired`)
- service type
- resolved endpoint

Example:

```bash
nanofaas platform status
```

#### `platform uninstall`

Runs Helm uninstall for the release/namespace.

Example:

```bash
nanofaas platform uninstall --release nanofaas -n nanofaas
```

## Testing

### Unit tests

```bash
./gradlew :nanofaas-cli:test
```

Comprehensive unit tests cover all commands using OkHttp MockWebServer (HTTP)
and Fabric8 MockClient (Kubernetes). See [docs/testing.md](testing.md) for
details and coverage targets.

### E2E tests

```bash
# Full CLI E2E against a real k3s cluster (47 tests)
./scripts/e2e-cli.sh

# Keep VM for debugging
KEEP_VM=true ./scripts/e2e-cli.sh
```

Requires Multipass. Creates a VM, deploys nanofaas on k3s,
and exercises every CLI command end-to-end. See [docs/testing.md](testing.md)
for configuration options and debugging instructions.
