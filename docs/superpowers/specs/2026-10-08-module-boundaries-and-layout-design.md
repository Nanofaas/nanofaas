# Module boundaries and repository layout

Status: approved specification derived from the 8 October 2026 conversation. Native execution started; architecture safeguards verified before layout migration.

Baseline: NanoFaaS `748ab0d0c85ff5be610a3958fa5c7252a485254b`. The audit covered 35 Java projects, including 12 optional modules; 44 existing architecture/SPI tests passed. Related issue: [#236](https://github.com/Nanofaas/nanofaas/issues/236). Preserve the engine ownership decision in [ADR 238](../../architecture/adr-238-engine-boundaries.md).

## Goal and decisions

Improve maintainability, readability and consistency by making existing module boundaries enforceable and by adopting the directory ownership proposed in #236. Physical relocation must not become a runtime redesign.

Two coordinated, independently reviewable plans implement this specification:

1. [Architecture safeguards](../plans/2026-10-08-module-boundary-checks.md): useful on the existing layout and independent of the relocation.
2. [Issue #236 layout migration](../plans/2026-10-08-issue-236-repository-layout.md): follows the safeguards and includes the affected NanoLab consumers.

Keep `control-plane-spi`, `p2p-api` and `forecasting-api` as the established contracts. Retain concrete core/execution-runtime collaboration, shared Docker/containerd implementation, and the small workload-metrics library. Retain the sync module's composition exception, but constrain it to the known origin/target pairs. An application, build plugin or reusable implementation library does not automatically require a new API artifact.

## Global constraints

- Java 25; Python 3.12 or newer for Python checks.
- No new production dependencies, API libraries, public interfaces, or runtime behavior changes.
- Preserve Java packages, Gradle project paths, plugin IDs, module descriptor IDs, backend IDs, artifact identities and public configuration keys.
- Preserve engine state ownership, lock ordering, admission, retry and resource-release behavior.
- Keep tests beside their owning projects; use existing JUnit, ArchUnit and pytest dependencies.
- Keep `container-deployment-provider` as the module ID and `container-local` as the backend ID.
- Recipe Dockerfiles remain filesystem inputs; do not copy them into staged application directories.
- Preserve native Dockerfile stages, named build contexts, build arguments and executable validation.
- Preserve recorded historical paths, raw results, checksums, source revisions and provenance manifests.
- No benchmark migration (#240), public website work (#239), SDK redesign, package renaming, or release publication is included.

## Architecture safeguards

The new authoritative composition check belongs in control-plane tests, which can observe core, SPI, runtime and the selected modules together. Existing local module checks remain useful for isolated runs. Do not introduce another Gradle project or a shared test framework.

Identify core/runtime/SPI ownership by the classes' actual code-source locations, using an existing representative class for each artifact. Do not classify by the shared `controlplane.*` namespace or hardcoded `platform/<directory>` strings. Support exploded class directories and ordinary dependency JARs. Missing source information for a relevant internal target must produce an explicit test failure.

For production classes in optional modules:

- Reject dependencies on core and execution-runtime implementations, except the exact approved composition pairs below.
- Reject dependencies on implementations in another optional module. Existing SPI/API and shared library dependencies remain allowed.
- Exclude test classes. Real integration tests may depend on implementations.
- Require subjects for every selected module; `controlPlaneModules=none` is a legitimate empty optional-module set. An unknown/new descriptor must require an explicit update to the subject map.

Only `it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration` receives the composition exception. Its approved targets are:

- `it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway`
- `it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer$AdmissionProfile`
- `it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer`, only when ArchUnit reports the structural enclosing-type dependency of that enum; direct construction/calls are not admitted by this exception.
- `it.unimib.datai.nanofaas.execution.SchedulerEngine`
- `it.unimib.datai.nanofaas.execution.PendingWorkStore`
- `it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionController`
- `it.unimib.datai.nanofaas.execution.admission.WaitEstimator`

This is an origin/target allowlist, not an exemption for the whole configuration class or for similarly named classes. The existing metric callbacks and estimator maintenance remain authorized. Detect new core/runtime dependencies even when the target class was not known when the test was written.

SPI purity must distinguish the SPI from core/runtime despite their shared packages. Keep its current permitted external contracts: common models, JDK, Reactor/reactive-streams and SLF4J. Convenience NoOps remain permitted.

## Layout mapping

| Existing path | Destination |
| --- | --- |
| `platform/common` | `platform/libs/common` |
| `platform/control-plane-spi` | `platform/libs/control-plane-spi` |
| `platform/execution-runtime` | `platform/libs/execution-runtime` |
| `platform/container-deployment-runtime` | `platform/libs/container-deployment-runtime` |
| `platform/workload-metrics` | `platform/libs/workload-metrics` |
| `platform/p2p-api` | `platform/libs/p2p-api` |
| `platform/forecasting-api` | `platform/libs/forecasting-api` |
| `platform/gradle-plugin` | `tools/gradle-plugin` |
| `deploy/recipes/Dockerfile.jvm` | `tools/gradle-plugin/dockerfiles/Dockerfile.jvm` |
| `deploy/recipes/Dockerfile.native` | `tools/gradle-plugin/dockerfiles/Dockerfile.native` |
| `deploy/native-java` | `tools/native-java` |
| `deploy/compose/Dockerfile` | `platform/control-plane/Dockerfile.from-source` |

The two API libraries are included because they are existing mandatory library projects with the same ownership as the five libraries listed in #236. This is a directory move, not a new extraction.

## Naming convention and explicit rename decisions

Names must identify both the architectural role and the existing semantic capability. Consistency is not a reason to flatten real distinctions. The user's follow-up requires evaluating names as part of this work, rather than treating the old names as inherently correct.

| Role | Convention and decision |
| --- | --- |
| Shared libraries | Live under `platform/libs/`; the leaf name describes the capability. |
| Consumer contracts | `*-api`: retain `p2p-api` and `forecasting-api`. |
| Extension contracts | `*-spi`: retain `control-plane-spi`; optional implementations extend the host through these ports. |
| Shared execution implementations | `*-runtime`: retain `execution-runtime` and `container-deployment-runtime`; do not present them as pure interfaces. |
| Optional deployment implementations | `*-deployment-provider`: retain the existing three module IDs; backend identifiers are distinct public configuration values. |
| Other optional capabilities | Retain semantic names such as `sync-queue`, `async-queue`, `autoscaler`, `concurrency-control`, `offload`, `runtime-config` and `build-metadata`. Their role is also expressed by `platform/modules/` and their descriptor. |
| Build tooling | `tools/gradle-plugin` and `tools/native-java`; these own build implementation and templates. |
| Deployment configuration | `deploy/` contains operational configuration rather than the shared build toolchain. |
| Alternative build entrypoint | Rename `deploy/compose/Dockerfile` to `platform/control-plane/Dockerfile.from-source`; this identifies the source-build semantics beside the existing prebuilt-JAR Dockerfile. |

`common` remains the shared models/handler-contract/support library. Renaming it `api` would inaccurately imply a pure contract artifact, while `shared` would not make its contents clearer. Document that role instead. `workload-metrics` already names its capability; adding a runtime suffix to four small types offers little benefit. Java/Java-lite SDK and example names retain their current execution semantics. No class/package or public module-ID rename is justified by the audited scope.

The migration plan must record this naming decision matrix in the current architecture documentation. If execution discovers a materially misleading name not covered here, assess its consumers and compatibility before extending the plan; do not perform cosmetic repository-wide substitutions.

Keep `platform/control-plane/Dockerfile` as the prebuilt-JAR packaging path. `Dockerfile.from-source` continues to compile the source tree. Compose keeps the repository root as context. Examples, SDKs, services, optional module directories and recipe definitions remain in their existing categories.

## Consumer compatibility and evidence

Update active consumers: settings/projectDir, included build, recipe command generation and schemas, native wrapper, Docker COPY paths, Compose, tests, current guides and contributor instructions. Relative references inside moved build files must be checked, not blindly rewritten.

NanoLab already consumes the shared native Dockerfile and has recipe-observation fixtures using plugin/template paths. Its image plan must select `tools/native-java/Dockerfile` for the new layout and continue accepting `deploy/native-java/Dockerfile` for older source revisions. Prefer the new path when both are valid. Carry the selected path in the immutable native build description so planning and rendering cannot diverge; never mutate a process-wide constant according to the last repository inspected. A missing supported Dockerfile must fail explicitly.

Land the backward-compatible NanoLab change before relying on the NanoFaaS relocation in shared workflows. Record both verified commits. Do not bypass NanoLab's existing source-compatibility policy or rewrite old evidence to make a check pass.

Historical material includes `docs/experiments`, completed `docs/plans`/`docs/superpowers` plans and specs, archived review reports and evidence under `docs/testing/evidence`. Preserve those bytes. Update current operational guides selectively; an ADR may receive a clearly dated current-path note while its historical sections remain intact.

## Acceptance

1. New core/runtime/sibling dependencies fail the architecture check; approved contracts, shared runtimes and exact sync composition continue to pass.
2. Negative checks prove the rule rejects forbidden dependencies, a same-simple-name impostor, and new target classes; tests cannot pass with a missing selected module.
3. Default, all, none, async and sync profiles remain valid before and after relocation.
4. Project/plugin/backend identities and dependency relationships are unchanged by the moves.
5. Recipe assembly keeps the same staging contents; JVM, Compose source, native export and native recipe packaging retain their build contexts and arguments.
6. NanoLab handles both layouts and emits the selected native Dockerfile consistently in its bake output.
7. Current documentation resolves; historical evidence is unchanged.
8. Real image/native verification is recorded separately from mocked command tests. Unavailable infrastructure leaves its acceptance item open; a dry run is not an image-build result.
