# Control-plane operation

The Java control plane is built directly with Gradle and deployed to Kubernetes
with Helm:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
helm upgrade --install nanofaas deploy/helm/nanofaas
```

The nanolab tool coordinates validation and experiments without owning task
implementations. Scenarios under `packages/nanolab/scenarios-v2` (in the nanolab
checkout) compose tasks from `workflow-tasks`; environment files bind host,
stack, and load-generator roles.

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh inspect packages/nanolab/scenarios-v2/validate-k8s.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

Remote commands run through SSH and assume the repository at `<home>/nanofaas`.
VM provisioning remains an explicit Ansible/provider concern.

## Control-plane modules

The control plane has a minimal core and optional modules under
`platform/modules`. Modules implement `ControlPlaneModule` and expose Spring
configuration through `ServiceLoader`.

Select modules at build time with Gradle:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue,sync-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
```

The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`. `none`
cannot be combined with other values and unknown names fail the build.

Every invocation defaults to `all`, tests included, so the suite exercises the
configuration that ships. `CoreOnlyApiTest` asserts the opposite — no-op
enqueuer, `501` on `:enqueue` — and self-skips when the modules are present, so
it gets its own invocation (`-PcontrolPlaneModules=none`); module selection is
resolved at configuration time and cannot vary within one build.

Current modules:

- `async-queue` — per-function queues + scheduler for the async path
- `sync-queue` — sync admission/backpressure queue
- `autoscaler` — internal replica scaler and scaling metrics integration
- `concurrency-control` — per-function concurrency governor (`FIXED`,
  `STATIC_PER_POD`, `ADAPTIVE_PER_POD`, `BUDGETED`, `SOJOURN`); **requires
  `async-queue`**
- `runtime-config` — hot runtime config service and admin API
- `build-metadata` — `/modules/build-metadata` diagnostics endpoint
- `k8s-deployment-provider` — Kubernetes managed deployment backend
- `container-deployment-provider` — local Docker-compatible deployment backend
- `offload` — conditional transparent proxy of sync invocations to a remote
  instance

### Modules that need other modules

`concurrency-control` reads queue depth and in-flight count from
`ScalingMetricsSource`, which only `async-queue` supplies, and writes the limits
it computes back through the same interface — that write is what actually
enforces them. Selected without `async-queue` it would run against the core's
no-op source: zeroes in, limits enforced by nobody. It now **refuses to start**
and names the missing module.

`autoscaler` reads the same source but is not fatal without it: the `rps` metric
comes from a meter, not from the source, so only `queue_depth` and `in_flight`
scaling go blind. Those log a warning on first use.

Image validation is **not** a standalone module: each deployment provider owns
its validator (`KubernetesImageValidator`, `DockerImageValidator`) and
activates it when selected as the deployment backend.

Each module owns its tests and explicit `@Bean` registrations; module packages
are not discovered through application component scanning.
