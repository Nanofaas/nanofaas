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
./nanolab.sh inspect packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
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
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
# Invalid: the deployment providers are mutually exclusive.
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider,container-deployment-provider
```

The settings plugin `it.unimib.datai.nanofaas.control-plane-modules` discovers
the immediate Gradle projects under `platform/modules`, reads the required
`module.properties` descriptor, and validates selection before any task runs.
The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`;
the project property `-PcontrolPlaneModules=...` has precedence. `none` cannot
be combined with other values. `all` selects every compatible module, preferring
`defaultEnabled=true` when a default and a non-default module conflict; conflicts
between modules with equal `defaultEnabled` values are ambiguous and fail the
build. Unknown names and other constraint violations also fail the build. The
default selects only descriptors whose `defaultEnabled=true`; this keeps
`async-queue` enabled and `sync-queue` disabled.

`k8s-deployment-provider` and `container-deployment-provider` are mutually
exclusive. Kubernetes is default-enabled, so `all` selects
`k8s-deployment-provider` and excludes the local container provider. Select
`container-deployment-provider` explicitly for local Docker workflows; selecting
both providers explicitly fails the build.

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
  one of `async-queue` or `sync-queue`**
- `runtime-config` — hot runtime config service and namespaced admin API; modules
  contribute their editable parameters through the runtime-config extension SPI
- `build-metadata` — `/modules/build-metadata` diagnostics endpoint
- `k8s-deployment-provider` — default-enabled Kubernetes managed deployment
  backend; mutually exclusive with `container-deployment-provider`
- `container-deployment-provider` — local Docker-compatible deployment backend;
  mutually exclusive with `k8s-deployment-provider`
- `offload` — conditional transparent proxy of sync invocations to a remote
  instance

### Modules that need other modules

`concurrency-control` consumes two contracts from the selected queue provider:
`WorkloadMetricsSource` for queue depth and in-flight observations, and
`WorkloadCapacityController` for publishing the computed limits that enforce
concurrency. The module declares `requires.oneOf=async-queue,sync-queue`, so
exactly one provider must be selected; it **refuses to start** when neither is
present.

`autoscaler` requires one of `async-queue` or `sync-queue`; its workload metrics
source is supplied by the selected provider. The Gradle module selector rejects
an autoscaler selection without a queue provider.

Image validation is **not** a standalone module: each deployment provider owns
its validator (`KubernetesImageValidator`, `DockerImageValidator`) and
activates it when selected as the deployment backend.

Each module owns its tests and explicit `@Bean` registrations; module packages
are not discovered through application component scanning.

## OpenAPI contract

The API contract is split between `openapi/core.yaml` (the always-present
routes) and a per-module `platform/modules/<id>/openapi.yaml` fragment. A
Gradle-time task (`OpenApiComposer` in `platform/gradle-plugin`) merges the
core document with the fragments of whatever modules were actually selected
for the build, and packages the result as `META-INF/resources/openapi.yaml`
inside the jar. There is no static `openapi.yaml` at the repository root
anymore: the contract is generated per artifact, so a route whose module was
not selected is absent from both the build and the published contract.

A fragment contributes `paths` and `components`, plus an optional
`x-nanofaas-overlays` list — `{operationId, patch}` entries applied to an
operation already defined elsewhere with JSON-merge-patch semantics (maps
recurse, `null` removes a key, anything else replaces it). Two fragments
defining the same path+method, the same component name, or conflicting
overlay values on the same operation fail the build rather than merging
silently.

An endpoint gated by a runtime property (for example
`/v1/admin/runtime-config`, active only when
`nanofaas.admin.runtime-config.enabled=true`) stays documented in the
contract as long as its module is built in — the fragment describes the
`404` response for the disabled case instead of omitting the path. The
contract reflects what a binary was *built* to serve, not the runtime
toggles of one running instance.

`control-plane:test --tests '*OpenApiRouteCoverageTest'` asserts every
registered route is present in the composed document for the module
selection under test, so contract and routes cannot drift.

## Build metadata

`GET /modules/build-metadata` (module `build-metadata`, see
`platform/modules/build-metadata/README.md`) reports what this binary was
built from and what it is running on: version, full git revision, dirty
working-tree flag, selected modules, build type/variant/optimization, base
OCI images, and runtime architecture/kernel/JVM facts. The Dockerfiles set
`NANOFAAS_BUILD_BASE_IMAGE` / `NANOFAAS_RUNTIME_BASE_IMAGE` from their
`BUILDER_IMAGE` / `RUNTIME_IMAGE` build args so the reported base images
match whatever base an image was actually built with. Every field is
nullable; missing data is JSON `null`, never a sentinel, and never blocks
startup.

## Persistent function catalog

The control plane persists its function catalog to a JSON file and restores
it at startup. The path is configured with `nanofaas.registry.path`
(environment variable `NANOFAAS_REGISTRY_PATH`); the default is
`build/nanofaas/functions.json`.

**Restore semantics.** An absent file is a normal empty first start: the
registry starts empty. A file that is unreadable, corrupt, or whose schema
version is unsupported aborts startup — the control plane refuses to serve a
partially restored catalog. Restoration replays each managed function against
the exact backend recorded in the catalog (`requireBackend`), so a function
that was persisted for a backend no longer available fails startup rather
than silently degrading to a different backend or to an unmanaged state.

**Security.** The catalog serializes each function's spec, including its
environment variables, in plaintext. On POSIX the parent directory is written
with mode `0700` and the file with mode `0600` (owner-only), and writes are
atomic (temp file + rename) so a crash cannot leave a truncated catalog. The
control plane runs as the distroless non-root user (UID/GID `65532`); treat
the backing volume as sensitive and secure it accordingly, and back it up —
it is the source of truth for registered functions.

**Helm.** The chart mounts a persistent volume at `/var/lib/nanofaas` and sets
`NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`. Tune it with
`controlPlane.persistence.{enabled,size,storageClass,existingClaim}`; setting
`existingClaim` reuses an operator-provided PVC and suppresses the chart's own
PVC, and `enabled: false` falls back to an `emptyDir` (state lost on pod
restart).

**Docker Compose.** The `control-plane-data` named volume is mounted at
`/var/lib/nanofaas` and `NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`
is set; the volume survives `docker compose down` unless removed explicitly.

**Limitations (MVP).** Registration is not journaled: a crash between the
durable catalog write and the provisioning of a managed function's resources
can leave a catalog entry whose backing resources were never created (an
orphan). Restoration only reconciles the functions recorded in the catalog;
unrelated residual Kubernetes resources or containers left over from an
earlier run are not swept at startup.
