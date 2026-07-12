# Control-plane modules

The control plane has a minimal core and optional modules under `platform/modules`. Modules implement `ControlPlaneModule` and expose Spring configuration through `ServiceLoader`.

Select modules at build time with Gradle:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue,sync-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
```

The equivalent environment selector is `NANOFAAS_CONTROL_PLANE_MODULES`. `none` cannot be combined with other values and unknown names fail the build.

Current modules include async and sync queues, autoscaling, runtime configuration, image validation, Kubernetes and container deployment providers, and build metadata. Each module owns its tests and explicit `@Bean` registrations; module packages are not discovered through application component scanning.
