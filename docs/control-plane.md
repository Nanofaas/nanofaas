# Control-plane operation

The Java control plane is built directly with Gradle and deployed to Kubernetes
with Helm:

```bash
./gradlew :control-plane:bootJar
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
`platform/modules`. Each module publishes its entry-point configuration through
Spring Boot's `AutoConfiguration.imports` convention.

Select modules at build time with Gradle:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
```

The settings plugin `it.unimib.datai.nanofaas.control-plane-modules` discovers
the immediate Gradle projects under `platform/modules`, reads the required
`module.properties` descriptor, and validates selection before any task runs.
The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`;
the project property `-PcontrolPlaneModules=...` has precedence. `none` cannot
be combined with other values, `all` selects every module, and unknown names or
constraint violations fail the build. The default selects descriptors whose
`defaultEnabled=true`; this keeps `async-queue` enabled and `sync-queue`
disabled.

Each descriptor uses this format:

```properties
schemaVersion=1
id=sync-queue
defaultEnabled=false
requires.strong=
requires.weak=runtime-config
conflicts=async-queue
```

`requires.strong` must be selected (including transitively), while
`requires.weak` is optional. `conflicts` rejects a selected incompatible pair.
Selections are exposed to project builds as the immutable sorted Gradle extra
property `nanofaasSelectedControlPlaneModules`.

Every invocation uses the descriptor defaults unless a selector is supplied;
module selection is resolved at settings configuration time and cannot vary
within one build.

Current modules:

- `async-queue` — per-function queues + scheduler for the async path
- `sync-queue` — sync admission/backpressure queue
- `async-queue` conflicts with `sync-queue`; select only one of them
- `autoscaler` — internal replica scaler and scaling metrics integration
- `concurrency-control` — per-function concurrency governor (`FIXED`,
  `STATIC_PER_POD`, `ADAPTIVE_PER_POD`, `BUDGETED`, `SOJOURN`); **requires
  `async-queue`**
- `runtime-config` — hot runtime config service and namespaced admin API; modules
  contribute their editable parameters through the runtime-config extension SPI
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
