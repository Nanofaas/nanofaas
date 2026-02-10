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

