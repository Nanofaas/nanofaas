# Function definition

A function manifest contains the control-plane `FunctionSpec` at its YAML root
and optional build instructions under `x-cli.build`. The CLI consumes the build
instructions; they are not part of the registration API.

For write → build → register → invoke → restart, use
[Function lifecycle](function-lifecycle.md). For SDK scaffolding, use the
[function tutorial](tutorial-function.md).

## Contract and defaults

The registration contract is `FunctionSpec` in [core OpenAPI](../openapi/core.yaml).
`GET /openapi.yaml` returns the running artifact's composed contract, including
its selected optional modules. Record the source commit and image digest when
reporting behaviour; a version tag alone does not identify a source revision.

The table covers every root field. Timeout, concurrency, queue size and retry
defaults come from `nanofaas.defaults` in
[application.yml](../platform/control-plane/src/main/resources/application.yml)
and can be overridden by the operator.

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | string | yes | Nonblank name, unique within this control plane; not a Kubernetes namespace selector. |
| `image` | string | yes | Nonblank image reference. Required even for EXTERNAL, where it is not run. Use immutable tags or digests. |
| `command` | string array | no | Override the managed container command. |
| `env` | map of strings | no | Literal values. Quote YAML values such as `"true"` and `"5432"`. No application Secret references. |
| `resources` | object | no | Soft requests and hard limits; see units and the restart defect below. |
| `timeoutMs` | integer | no | Milliseconds; published API range 1–300000. Default 30000. |
| `concurrency` | integer | no | Maximum concurrent in-flight invocations for the function, at least 1. Default 4; separate from replicas. |
| `queueSize` | integer | no | In-memory queue capacity, at least 1. Default 100; immutable through PATCH. |
| `maxRetries` | integer | no | Retry count, at least 0. Default 3; 0 disables retries. Applications handle idempotency. |
| `endpointUrl` | string | for EXTERNAL | Reachable invocation URL, e.g. `http://host:9090/invoke`. Managed providers determine their endpoint. |
| `executionMode` | enum | no | DEPLOYMENT (default), EXTERNAL or LOCAL. |
| `runtimeMode` | enum | no | HTTP, STDIO or FILE; omit to preserve the image's watchdog default. Does not select a backend. |
| `runtimeCommand` | string | no | Process command for watchdog-based images; interpretation depends on the runtime/image. |
| `scalingConfig` | object | no | Replica strategy, bounds and metrics, plus optional concurrency control. |
| `imagePullSecrets` | string array | no | Kubernetes private-registry Secret names; not application credentials. Check provider support. |
| `offload` | object | no | Per-function policy (`enabled`, `targetUrl`, `mode`); requires offload module. Omit to follow global defaults. |

### Modes and backends

- DEPLOYMENT provisions warm instances with the selected `k8s`,
  `container-local` (Docker) or `containerd` provider.
- EXTERNAL invokes an existing HTTP endpoint without managing its process,
  resources, scaling or deployment.
- LOCAL runs an available handler inside the control-plane process; it does
  not run an arbitrary function image on the host.

Select one managed provider module at build time; they are mutually exclusive.
See [Kubernetes](k8s.md), [Docker](local.md),
[containerd](deployment-containerd.md) and
[module selection](control-plane.md#control-plane-modules).

### Resources

Supported shape:

```yaml
resources:
  requests:
    cpu: 0.05
    memoryMiB: 128
  limits:
    cpu: 1
    memoryMiB: 256
```

CPU is in cores (0.05 = 50 millicores), in increments of 0.001. Memory is an
integer number of MiB, at least 1. Requests must not exceed corresponding
limits. Mapping and enforcement differ by provider.

**Known restart defect:** the source-built JVM control plane at commit
`4f3d58b7` writes `resources.requestWithinLimit` into the catalog and rejects
it on its next startup. Registration and invocation can succeed first. Track
[issue #247](https://github.com/miciav/nanofaas/issues/247) and verify a fix in
your build before relying on this round trip. The native build was not tested
in that reproduction. WeekPantry omits `resources` and uses a Kubernetes
`LimitRange`; that does not repair an affected catalog or provide a Docker/
containerd workaround.

### Scaling, retries and durable data

`scalingConfig.strategy` is NONE, INTERNAL or HPA. NONE with equal
`minReplicas`/`maxReplicas` fixes the replica count. Internal autoscaling needs
its module; HPA is Kubernetes-specific. Concurrency control is independent and
needs the `concurrency-control` module. See `ScalingConfig` and
`ConcurrencyControlConfig` in OpenAPI for nested fields and bounds.

NanoFaaS may retry failed attempts. A caller's wait timeout does not prove the
shared execution stopped. For database mutations, use an application request
ID and record the effect and replay result in the same database transaction.
The function catalog does not persist queues, execution results or app data.

## Environment values and secrets

`env` is persisted in plaintext in the catalog. Owner-only POSIX permissions
do not make it a Secret-reference mechanism. Reading a Kubernetes Secret
during registration and passing its value in `env` creates another literal
copy in the catalog.

Application `secretKeyRef`/`envFrom` fields are not supported. Portable
bindings for Kubernetes, standalone Docker and containerd are requested in
[issue #248](https://github.com/miciav/nanofaas/issues/248). Protect catalogs
containing credentials and do not publish catalog/registration output.

## Registration and updates

POST `/v1/functions` registers a function (201); an existing name returns 409.
PATCH `/v1/functions/{name}` accepts only `concurrency`, `timeoutMs`,
`maxRetries` and `concurrencyControl`. Omitted fields keep their values;
`concurrencyControl` replaces the whole block. Other fields are rejected
with 400, including image, env, resources and queueSize.

CLI `fn apply`/`deploy --replace` can delete and register a replacement
when immutable fields change. It is not atomic and can cause downtime.
If deletion reports pending removal, retry cleanup before registering again.
An identical deployment need not replace the function. Image/env updates
without deleting registration are requested in
[issue #249](https://github.com/miciav/nanofaas/issues/249).

## CLI build metadata

`nanofaas deploy -f function.yaml` builds/pushes the image, then applies the
specification. `nanofaas fn apply -f function.yaml` only applies it. Build paths
are resolved relative to the manifest.

| Field | Type | Meaning |
| --- | --- | --- |
| `x-cli.build.context` | string | Docker build context directory. |
| `x-cli.build.dockerfile` | string | Dockerfile; defaults to Dockerfile. |
| `x-cli.build.platform` | string | Optional platform, e.g. linux/arm64. |
| `x-cli.build.push` | boolean | Defaults to true; false uses --load into local Docker. |
| `x-cli.build.buildArgs` | map of strings | Optional build arguments. |

Loading into Docker does not populate Kubernetes's or a separate containerd's
image store. Push to a reachable registry or use that runtime's import workflow.
HTTP/private registry configuration is also runtime-specific.

## Managed HTTP example

This deliberately omits explicit resources because of the restart defect:

```yaml
name: greet
image: registry.example/greet:release-1
executionMode: DEPLOYMENT
runtimeMode: HTTP
env:
  GREETING: "Hello"
timeoutMs: 10000
concurrency: 2
queueSize: 50
maxRetries: 0
scalingConfig:
  strategy: NONE
  minReplicas: 1
  maxReplicas: 1
x-cli:
  build:
    context: .
    dockerfile: Dockerfile
    push: true
```

The image implements the [HTTP contract](function-lifecycle.md#runtime-and-gateway-contracts).
The caller receives InvocationResponse JSON even for HTML/base64 output.
Browser-facing HTTP/asset serving is requested in
[issue #246](https://github.com/miciav/nanofaas/issues/246).
