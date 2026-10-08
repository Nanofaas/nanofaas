# Module boundaries and repository layout

The platform separates host contracts, optional capabilities and shared implementation libraries. Gradle project names and Java packages remain stable when their source directories move.

## Roles and names

| Location/name | Role and semantics |
| --- | --- |
| `platform/control-plane` | Application composition: HTTP entrypoints, lifecycle and dispatch orchestration. |
| `platform/libs/common` | Shared DTOs, handler contracts and support code. `api` would imply a pure contract library; `shared` would add no precision. |
| `platform/libs/control-plane-spi` | Host extension contracts used by optional modules. `spi` denotes extension ports. |
| `platform/libs/p2p-api`, `platform/libs/forecasting-api` | Consumer contracts for the named capabilities. `api` denotes what consumers may reference. |
| `platform/libs/execution-runtime` | Shared execution state and lifecycle implementation. |
| `platform/libs/container-deployment-runtime` | Shared managed-container implementation used by Docker and containerd providers. |
| `platform/libs/workload-metrics` | Small shared workload metrics library; its capability name is already precise. |
| `platform/modules/<capability>` | Optional implementations, identified by their descriptor ID. Queue, offload, forecasting and configuration names retain their semantics. |
| `sdks/<language>`, `services/<language>`, `functions/<language>` | SDKs, runnable services and function examples, with their existing execution semantics. |

Shared implementations retain `runtime` names; they are not presented as interface libraries. Optional deployment implementations retain `*-deployment-provider` IDs. The `container-deployment-provider` module implements the `container-local` backend: a module ID and a public runtime backend identifier have different roles. No package or public identifier is renamed for directory consistency.

Build-tool and Dockerfile locations will be updated in the subsequent tooling migration described in [the approved plan](../superpowers/plans/2026-10-08-issue-236-repository-layout.md).

## Dependency boundaries

Production optional modules may depend on existing SPI/API contracts and shared libraries. They may not depend on another optional implementation or core/execution-runtime implementations except the explicit sync composition below. Tests that exercise a real implementation may depend on it.

`SyncQueueConfiguration` binds `EngineSyncQueueGateway`, `EngineInvocationEnqueuer.AdmissionProfile`, `SchedulerEngine`, `PendingWorkStore`, `SyncQueueAdmissionController` and `WaitEstimator`. Only that exact origin receives these exact dependencies; the enclosing enqueuer is admitted as structural enum metadata, not as a field, constructor or call dependency.

Core and execution runtime retain their concrete collaboration, state ownership and locks. Docker and containerd retain their shared container runtime. Small libraries and applications do not need a separate API artifact solely because they are Gradle projects. See [ADR 238](adr-238-engine-boundaries.md).

Composition architecture checks derive core/SPI/runtime ownership from class code-source locations, supporting directories and JARs. SPI purity uses the types in the SPI artifact rather than the shared package prefix. A selected module with no production subjects fails explicitly. Commands and the core-only profile are documented in [testing](../testing.md#module-boundaries).
