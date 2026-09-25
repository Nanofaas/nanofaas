# container-deployment-provider

Optional control-plane module: the local backend (`container-local`) for the
managed `DEPLOYMENT` execution mode — runs function instances as containers
via Docker Java or a Docker-compatible CLI, no Kubernetes required.

## Provides

- `ContainerLocalDeploymentProvider` — provisions/deprovisions container
  instances for DEPLOYMENT functions and applies replica changes.
- `DockerJavaContainerRuntimeAdapter` — uses the Docker Engine API through
  docker-java (the default adapter).
- `CliContainerRuntimeAdapter` + `ProcessCliCommandExecutor` — optional
  Docker-compatible CLI adapter, selected with `nanofaas.container-local.runtime-adapter`.
- `EphemeralPortAllocator` — Docker host-port assignment. The adapters return
  the reachable endpoint, including container DNS when a Docker network is used.
- Shared `HttpEndpointProbe` — readiness polling for each instance.
- Shared `RoundRobinFunctionProxy` (`RoundRobinFunctionProxyFactory`) — load-balances
  invocations across the instances of a function, concurrently and under a
  bound (see below).

The lifecycle, runtime contract, readiness probe and proxy live in the ordinary
[`container-deployment-runtime`](../../container-deployment-runtime/) library.
It has no Spring auto-configuration or Docker dependency. This module owns Docker
configuration, port allocation and adapters; `ContainerLocalDeploymentProvider`
selects the `container-local` backend and retains its persisted container names.

## Proxy behaviour and its outcomes

The proxy serves invocations concurrently on virtual threads, under an admission
bound derived from the function itself: `replicas x spec.concurrency`. That
tracks the platform's own per-replica concurrency ceiling, so governed traffic is
never rejected here while a burst beyond that ceiling still gets a defined answer
instead of unbounded queueing. Health checks are never subject to the bound.

| Condition | Status |
|---|---|
| Admission bound already full | `503` — "Too many concurrent invocations" |
| Cannot connect to a replica | `502` |
| Replica accepted but did not answer within the hop timeout | `504` |
| Proxy closed, or no healthy backend | `503` |

The single-hop timeout is the **function's own** `timeoutMs`, not a fixed value:
this hop is where the function actually runs, so a smaller constant would cut a
call the caller still considers in budget. Both the timeout and the admission
bound are refreshed whenever the replica set changes *and* whenever the function
is patched (`PATCH /v1/functions/{name}`) — a spec update reaches the proxy
through the provider, so a raised timeout takes effect without redeploying.

## Configuration (`nanofaas.container-local.*`)

- `runtime-adapter` — CLI to use (e.g. `docker`)
- `bind-host` — host to bind/reach container ports on
- `readiness-timeout`, `readiness-poll-interval` — instance readiness probing

Backend selection: set
`nanofaas.deployment.default-backend=container-local` (see the
`container-local` run profile).

## Notes

- Requires a running container daemon; `DeployCommandTest` and the
  `validate-container` scenarios fail without one.
- E2E: `./nanolab.sh e2e run validate-docker-pool` (from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo).
