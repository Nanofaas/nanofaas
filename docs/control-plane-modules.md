# Control-plane modules

The control plane has a minimal core and optional modules under `platform/modules`. Modules implement `ControlPlaneModule` and expose Spring configuration through `ServiceLoader`.

Select modules at build time with Gradle:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue,sync-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
```

The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`. `none` cannot be combined with other values and unknown names fail the build.

Every invocation defaults to `all`, tests included, so the suite exercises the
configuration that ships. `CoreOnlyApiTest` asserts the opposite — no-op
enqueuer, `501` on `:enqueue` — and self-skips when the modules are present, so
it gets its own invocation (`-PcontrolPlaneModules=none`); module selection is
resolved at configuration time and cannot vary within one build.

Current modules:

- `async-queue` — per-function queues + scheduler for the async path
- `sync-queue` — sync admission/backpressure queue
- `autoscaler` — internal replica scaler and scaling metrics integration
- `concurrency-control` — per-function concurrency governor (`FIXED`, `STATIC_PER_POD`, `ADAPTIVE_PER_POD`, `BUDGETED`, `SOJOURN`)
- `runtime-config` — hot runtime config service and admin API
- `build-metadata` — `/modules/build-metadata` diagnostics endpoint
- `k8s-deployment-provider` — Kubernetes managed deployment backend
- `container-deployment-provider` — local Docker-compatible deployment backend
- `offload` — conditional transparent proxy of sync invocations to a remote instance

Image validation is **not** a standalone module: each deployment provider owns
its validator (`KubernetesImageValidator`, `DockerImageValidator`) and
activates it when selected as the deployment backend.

Each module owns its tests and explicit `@Bean` registrations; module packages are not discovered through application component scanning.
