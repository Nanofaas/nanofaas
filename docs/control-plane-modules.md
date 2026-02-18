# Control Plane Modules

The control-plane is split into:

- a minimal core (always included)
- optional modules under `control-plane-modules/`

Optional modules are loaded through the `ControlPlaneModule` SPI (via `ServiceLoader`) and imported as Spring `@Configuration` classes at startup.

The core provides no-op defaults for:

- `InvocationEnqueuer`
- `ScalingMetricsSource`
- `SyncQueueGateway`
- `ImageValidator`

## Optional modules

- `async-queue`: per-function async queues + scheduler; provides queue-backed `InvocationEnqueuer` and `ScalingMetricsSource`; enables `POST /v1/functions/{name}:enqueue`.
- `sync-queue`: sync invocation queueing/admission control and wait estimation; provides `SyncQueueGateway` behavior for `POST /v1/functions/{name}:invoke`.
- `autoscaler`: internal autoscaling components that consume scaling metrics and update concurrency/replica behavior.
- `runtime-config`: hot runtime config service (rate limit + sync-queue knobs) and optional admin API at `/v1/admin/runtime-config` when `nanofaas.admin.runtime-config.enabled=true`.
- `image-validator`: Kubernetes-backed image pull validation for function registration (overrides core no-op validator).
- `build-metadata`: diagnostic endpoint `GET /modules/build-metadata`.

## Build-time selection

Use one of these:

- `-PcontrolPlaneModules=<csv>`
- `NANOFAAS_CONTROL_PLANE_MODULES=<csv>`

Example:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue,sync-queue
```

`all` expands to every optional module:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
```

`none` means core-only:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
```

Rules:

- `none` cannot be combined with other values.
- unknown module names fail the build.

Default behavior when the selector is omitted:

- Runtime/artifact tasks (`bootRun`, `bootJar`, `bootBuildImage`, `build`, `assemble`) include all optional modules.
- Non-runtime tasks (for example `:control-plane:test`) keep the core-only setup.

## Module layout

Create a module under `control-plane-modules/<module-name>/`:

```text
control-plane-modules/
  my-module/
    build.gradle
    src/main/java/.../MyModule.java
    src/main/java/.../MyModuleConfiguration.java
    src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule
```

## Module contract

Implement `it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule` and return one or more Spring `@Configuration` classes.
The control-plane loads these module classes via `ServiceLoader` during startup.

## Conventions

- **Package:** `it.unimib.datai.nanofaas.modules.<module-name>`
- **Bean registration:** Declare beans explicitly with `@Bean` in `@Configuration` classes. Do not rely on component scanning, because module packages are not part of the default app scan.
- **Controllers:** `@Controller` and `@RestController` are both fine when the controller bean is created from module configuration.

## Example module

`control-plane-modules/build-metadata` is a reference module.
When included, it adds endpoint `GET /modules/build-metadata`.
