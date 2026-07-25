# container-deployment-provider

Optional control-plane module: the local backend (`container-local`) for the
managed `DEPLOYMENT` execution mode — runs function instances as containers
via a Docker-compatible CLI, no Kubernetes required.

## Provides

- `ContainerLocalDeploymentProvider` — provisions/deprovisions container
  instances for DEPLOYMENT functions and applies replica changes.
- `CliContainerRuntimeAdapter` + `ProcessCliCommandExecutor` — drive the
  container runtime CLI (docker/podman-compatible).
- `EphemeralPortAllocator` and `HttpEndpointProbe` — port assignment and
  readiness polling for each instance.
- `RoundRobinFunctionProxy` (`ManagedFunctionProxyFactory`) — load-balances
  invocations across the instances of a function.

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
