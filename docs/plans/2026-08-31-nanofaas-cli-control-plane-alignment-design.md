# NanoFaaS CLI control-plane alignment design

## Goal

Bring `nanofaas-cli` onto the current control-plane contract, remove unsafe or
inert behavior, and expose the new function-update, replica, build metadata,
artifact-specific OpenAPI, and runtime-configuration capabilities.

## Command surface

The CLI keeps one stable, offline-capable Picocli command tree:

```text
nanofaas control-plane info
nanofaas control-plane contract
nanofaas control-plane config get [namespace]
nanofaas control-plane config validate <namespace> -f patch.yaml
nanofaas control-plane config patch <namespace> -f patch.yaml
nanofaas fn update <name> [options]
nanofaas fn replicas get <name>
nanofaas fn replicas set <name> <count>
```

Commands remain visible when an optional control-plane capability is absent.
Making `--help` depend on a live endpoint would make help and shell completion
unreliable whenever the server, DNS, tunnel, or network is unavailable.

The existing top-level `fn`, `invoke`, `enqueue`, `exec`, and `deploy` commands
remain. The inert `--namespace` option and its configuration fields are removed:
the control-plane HTTP contract has no per-request function namespace, so the
option currently promises behavior that no request implements.

## Client and capability architecture

`ControlPlaneClient` remains the only HTTP boundary. It gains typed methods for
function patches, replica status and changes, build metadata, OpenAPI retrieval,
and runtime configuration. Existing Java `HttpClient`, Jackson JSON/YAML, and
Picocli dependencies are sufficient; no generated client or new dependency is
introduced.

A small `ControlPlaneCapabilities` value is derived from `/openapi.yaml`. It
records only the routes and HTTP methods the CLI needs: function update,
replicas, asynchronous invocation, build metadata, and runtime configuration.
It is not a general OpenAPI object model.

Optional commands follow one flow:

```text
resolve endpoint
→ GET /openapi.yaml
→ verify path and HTTP method
→ call the API or report “not supported by this build” locally
```

Failure to retrieve the contract is a transport error, not evidence that a
capability is absent. Runtime-config routes present in OpenAPI followed by a
`404` mean the module is installed but its administrative API is disabled; the
CLI explains that `nanofaas.admin.runtime-config.enabled=true` is required.

`control-plane info` reads `/modules/build-metadata` and prints version,
revision, dirty state, selected modules, build type and variant, base images,
and runtime identity. When build metadata is unavailable, it still prints the
capabilities derived from OpenAPI and labels identity metadata unavailable.
`control-plane contract` prints the artifact-specific contract unchanged.

## Invocation semantics

The control-plane now uses a function-decided status as the real HTTP response
status. The CLI uses both that status and `X-NanoFaaS-Function-Status` because
the status alone cannot distinguish, for example, a function returning `404`
from an unregistered function, or a function returning `500` from a platform
failure.

- A marked response is a completed function invocation. The CLI prints its
  envelope, including `statusCode`, `headers`, and `encoding`.
- A marked 2xx response exits `0`.
- A marked non-2xx response prints the envelope and exits `1`, without calling
  it a control-plane error.
- An unmarked non-200 response remains a platform error.
- A marked `204` has no HTTP body; the CLI prints a minimal JSON result carrying
  the HTTP status and exits `0`.

This preserves useful shell exit semantics while retaining the function's
actual result for pipelines and diagnostics.

## Safe function updates

`fn apply` compares the desired manifest with the registered function and
classifies differences explicitly:

- no differences: no write;
- only `concurrency`, `timeoutMs`, `maxRetries`, or `concurrencyControl` differ:
  send `PATCH /v1/functions/{name}` and keep the deployment serving;
- an immutable field differs: fail without changing remote state unless the
  caller supplies `--replace`.

`--replace` deliberately preserves the existing `DELETE` followed by `POST`
behavior and warns that it is not atomic. The CLI cannot provide rollback or
preflight guarantees that the server does not expose. It must never perform
this destructive sequence implicitly.

`fn update` sends an explicit partial patch for the four mutable settings. It
shares request construction with the mutable branch of `fn apply`; no second
update implementation is introduced.

## Replicas and runtime configuration

`fn replicas get` prints desired and ready replicas. `fn replicas set` changes
the desired count, including zero, through the existing managed-deployment API.
Unsupported execution modes and unavailable providers retain the server's
`400` and `503` distinctions.

Runtime configuration lives under `control-plane config` because it administers
the control-plane rather than functions. `get` returns the complete snapshot or
one namespace. `validate` submits values without applying them. `patch` reads
the current revision unless the input supplies one, then submits an optimistic
concurrency update. A `409` reports stale revision information; `422` prints all
validation errors; a disabled API gets a dedicated explanation.

Absence of runtime-config never affects normal CLI commands or control-plane
operation. Static startup configuration remains effective, and weakly
integrated modules such as `sync-queue` continue using their initial settings.

## Testing and documentation

HTTP contract tests use the existing `MockWebServer`. Invocation coverage
distinguishes marked and unmarked `201`, `204`, `404`, and `500` responses.
Apply coverage proves mutable changes use `PATCH`, immutable changes are inert
without `--replace`, replacement is explicit, and unchanged manifests do not
write.

Capability tests use minimal inline OpenAPI documents for present routes,
missing routes, missing methods, and transport failure. Runtime-config tests
cover aggregate and namespace reads, validation, patching, stale revisions,
validation errors, missing modules, and disabled administration. Replica tests
cover reads, writes, unmanaged functions, and provider failures.

`docs/nanofaas-cli.md` and command examples are updated together with the
Picocli help. No dynamic help, generated SDK, generic diff engine, automatic
rollback, or runtime capability cache is introduced.
