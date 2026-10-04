# P2P Node Information Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exchange actual function, image and resource snapshots between active NanoFaaS neighbors, with independent runtime publication switches.

**Architecture:** Reuse `PeerMessaging` request/response and existing P2P configuration/persistence. P2P owns collection scheduling, wire validation and freshness; backend modules supply image inventories through a read-only SPI. Existing catalog/workload views supply function and load data without changing invocation behavior.

**Tech Stack:** Java 25, Spring Boot, Reactor, Jackson 3, Micrometer, Scalecube behind `PeerCluster`, existing Docker/containerd/Fabric8 clients, JUnit and Awaitility.

**Spec:** [Approved design](../specs/2026-10-03-p2p-node-information-design.md).

## Global Constraints

- Worktree: `/Users/micheleciavotta/.codex/worktrees/p2p-node-information/nanofaas`; base `ef856960e99a6c56b93be6a978965535f7a3eba7` from `origin/main`. Preserve the original checkout's local changes.
- Use the existing package root `it.unimib.datai.nanofaas`, despite the outdated shorthand in repository guidelines.
- `shareFunctions`, `shareImages`, `shareResources` default to `false`; Boolean keys reject null, strings and numbers. Overrides retain state > file config > application precedence.
- Topic: `nanofaas.node-info.v1`; UTF-8 JSON in `byte[]`; `schemaVersion=1`. No new HTTP transport, module-to-module dependency, authentication or routing behavior.
- Collection/poll every 5 seconds; collector/request timeout 2 seconds; freshness 15 seconds; at most four peer requests and one actual operation per collector. Inject shorter timings only in tests.
- Payload limit 1 MiB; per-category encoded limit 300 KiB and 5,000 entries. Unavailable data is never zero or an apparently successful empty inventory.
- Snapshot/cache state is memory-only. Existing P2P participation, neighbor selection and persistent configuration semantics remain authoritative.
- Before modifying each existing symbol, run GitNexus upstream impact, inspect direct callers and report risk; warn on HIGH/CRITICAL. Before every commit run `detect_changes(scope=all)` and inspect completeness. Reindex after code commits, preserving embeddings if present.
- For each task: write behavior tests, observe the expected failure, implement, run the indicated checks, inspect the diff, then commit only that task's files. Do not rerun the entire suite for documentation-only changes.

## Review Focus

- A slow collector ignores interruption: timeouts cannot spawn unlimited replacement calls, and disabling/re-enabling cannot publish its old result (Tasks 3, 5).
- Source wall clocks differ or jump: repeated responses cannot make an old measurement fresh or extend its remaining lifetime (Tasks 1, 5).
- A peer is excluded and reactivated while a request is in flight: an old response cannot become current again merely because the same ID is active (Task 5).
- CLI stdout exceeds pipe capacity or includes diagnostic stderr: collection must terminate within its deadline, retain bounded output and avoid accepting partial JSON (Task 4).
- Backend scope is incomplete: Kubernetes observations, untagged Docker images and namespace-specific containerd inventories must preserve identity and honest availability (Task 4).

## File map and ownership

Paths below use these exact source-root aliases; test files use the same package path under `src/test/java` instead of `src/main/java`.

| Alias | Exact directory |
| --- | --- |
| `P2P` | `platform/modules/p2p-discovery/src/main/java/it/unimib/datai/nanofaas/modules/p2pdiscovery` |
| `SPI` | `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane` |
| `DOCKER` | `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider` |
| `CTR` | `platform/modules/containerd-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerddeploymentprovider` |
| `K8S` | `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s` |

New units: `ImageInventorySource`/`ImageInventory` own the provider contract; `NodeInformation`/`NodeInformationCodec` own the P2P wire shape; `NodeInformationCollector` owns local measurements; `NodeInformationExchange` owns scheduling/peer freshness. Provider-specific inventory implementations stay next to their existing runtime adapters. Keep config additions in existing config files and avoid restructuring unrelated classes.

## Task 1: Define the inventory SPI and bounded snapshot codec

**Files:** Create `SPI/deployment/ImageInventorySource.java`, `SPI/deployment/ImageInventory.java`, `P2P/NodeInformation.java`, `P2P/NodeInformationCodec.java`; create `NodeInformationCodecTest.java` and `ImageInventoryTest.java` in the corresponding test roots. Modify `platform/modules/p2p-discovery/build.gradle` for `:control-plane-spi` and `:workload-metrics` dependencies, without optional-module dependencies.

**Interfaces:** `ImageInventorySource.snapshot(Duration timeout, int maxEntries): ImageInventory`. `ImageInventory` carries `backend`, `scope`, `Instant collectedAt`, `Status status`, `String reasonCode`, and immutable `List<Entry> entries`; inventory statuses are AVAILABLE/PARTIAL/UNAVAILABLE. Each `Entry` carries nullable `nodeId`, immutable `List<String> references`, nullable `String digest`, and nullable `String imageId` (at least one reference/digest/ID must exist).

`NodeInformation` carries `int schemaVersion`, `String nodeId`, `Instant sampledAt`, `Category<List<FunctionInfo>> functions`, `Category<ImageInventory> images`, and `Category<ResourceInfo> resources`. Nest its records/enums to keep one wire-model unit. `Category<T>` carries status AVAILABLE/PARTIAL/DISABLED/UNAVAILABLE, nullable collectedAt, source, scope, reasonCode, nullable data, and `long ageMillis` for source-monotonic observation age. DISABLED/UNAVAILABLE must have null data. `FunctionInfo` has name, executionMode, image and backend; `ResourceInfo` has nullable CPU ratios/memory-byte measurements plus immutable `List<FunctionLoad>` (name, queueDepth, inFlight, effectiveConcurrency, dispatchableBacklog).

`NodeInformationCodec.encode(NodeInformation): byte[]` replaces oversized categories with LIMIT_EXCEEDED; `decode(byte[], String expectedNodeId): NodeInformation` rejects malformed/oversized input. Use Jackson constraints: depth 16, string length 16,384, numeric length 64; validate numbers, entry counts and UTF-8 structure. Ignore unknown additive fields, but reject missing required fields/status-dependent invalid data and versions other than 1.

- [ ] Write codec tests with representative assertions: `assertThat(decode(encode(emptyAvailable), "a").functions().status()).isEqualTo(AVAILABLE)`; DISABLED differs from empty AVAILABLE; identity mismatch/version 2 fail; 1 MiB + 1 bytes fails; 5,001 entries or 300 KiB + 1 encoded bytes produces LIMIT_EXCEEDED only for that category. Test NaN/Infinity, invalid CPU ratios, negative counts, nested objects and additional fields. Verify immutable defensive copies in the SPI test.
- [ ] Run `./gradlew :control-plane-spi:test --tests '*ImageInventoryTest' --console=plain` and then `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --tests '*NodeInformationCodecTest' --console=plain`; expect missing types/tests to fail before implementation, then all selected tests pass after it.
- [ ] Implement these records and codec; use bounded output during serialization rather than allocating an unbounded JSON byte array before checking its size. Preserve the existing outer transport codec and Java serialization allow-list.
- [ ] Re-run the focused tests plus P2P `ArchitectureTest`; commit `Add bounded node information contracts` after scope checks.

## Task 2: Extend existing runtime settings and state-file precedence

**Files:** Modify `P2P/P2pSettings.java`, `P2pProperties.java`, `P2pFile.java`, `P2pService.java`, `P2pConfiguration.java`, `P2pAdminController.java`; extend existing `P2pSettingsTest`, `P2pPropertiesTest`, `P2pStateFileTest`, `P2pServiceTest`, `P2pAdminControllerTest` and their constructor call sites.

**Interfaces:** Add `P2pSettings.Sharing(boolean functions, boolean images, boolean resources)` and synchronized `sharing(): Sharing`. Extend base settings with the three booleans; keep `effective(): NeighborSelector.Settings` unchanged. Add startup properties and nullable file-config Booleans (absence inherits application values). Existing `patch(Map<String,Object>)`, `loadOverrides`, `clearOverrides` remain the sole mutation path. Expose flags through the existing admin config view. Preserve constructor overloads used by existing tests when this reduces unrelated churn.

- [ ] Write parameterized tests for all masks 0..7: `assertThat(settings.sharing()).isEqualTo(expectedSharing(mask))`. Assert patching `{shareImages:true, shareResources:"yes"}` changes nothing; null/numeric booleans fail; old files yield all false; explicit false in file overrides application true; saved runtime false overrides base true; deleting overrides restores bases.
- [ ] Run the P2P settings/properties/state/controller tests and observe failures for the new keys.
- [ ] Implement flags, atomic validation, file precedence and config views. Preserve behavior of null maxNeighbors/maxLatencyMs and peer-mode resets.
- [ ] Run `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --tests '*P2pSettingsTest' --tests '*P2pPropertiesTest' --tests '*P2pStateFileTest' --tests '*P2pServiceTest' --tests '*P2pAdminControllerTest' --console=plain`; commit `Add runtime node information switches`.

## Task 3: Collect actual local functions and scoped resources

**Files:** Create `P2P/NodeInformationCollector.java` and `NodeInformationCollectorTest.java`; modify `P2P/P2pConfiguration.java` to supply optional providers using `ObjectProvider` without startup I/O.

**Interfaces:** Constructor consumes `FunctionCatalogView`, `WorkloadMetricsSource`, optional `ImageInventorySource`, `MeterRegistry`, `Clock`, and a `Supplier<EnvironmentMemory>` seam for the platform management API. Missing services are represented as missing sources, not fake empty registries. Methods `collectFunctions(): Category<List<FunctionInfo>>`, `collectImages(Duration): Category<ImageInventory>`, `collectResources(): Category<ResourceInfo>`. Results initially have ageMillis=0; exchange orchestration stamps their local monotonic completion time.

- [ ] Write tests asserting registered function add/update/delete reflects the next collection and transmitted summaries contain no env/endpoint fields. Assert workload values exactly match a supplied `WorkloadMetricsSource`, including true zero values. Resources still collect when function sharing is off. Assert missing providers return NO_PROVIDER; missing catalog/workload sources are distinguished from a measured empty catalog.
- [ ] Add tests: valid CPU ratio 0 stays 0; NaN, unsupported negative readings and missing meters become null with PARTIAL/UNAVAILABLE; heap is labeled separately from environment memory; inconsistent free > total is unavailable; metric gauges throwing exceptions do not erase the valid functions category. No configured resource limits are reported as measured usage.
- [ ] Run `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --tests '*NodeInformationCollectorTest' --console=plain` and observe failures.
- [ ] Implement deterministic catalog summaries and resources using existing `system.cpu.usage`, `process.cpu.usage`, JVM memory meters where present; sum valid heap meters with their `area=heap` tag and handle unsupported max values. Scope visible environment measurements as `control-plane-visible-environment`, process values as `control-plane-process`, heap as `jvm-heap`. Enumerate functions through the existing read-only catalog; leave current metrics profiles untouched.
- [ ] Run collector tests and P2P module context tests with all sources present and each source absent; commit `Collect live node function and resource information`.

## Task 4: Supply real inventories for the supported deployment backends

**Files:** Create `DOCKER/DockerImageInventorySource.java`, `DOCKER/CliImageInventorySource.java`, `CTR/ContainerdImageInventorySource.java`, `K8S/KubernetesImageInventorySource.java`, with matching tests. Modify each existing provider configuration to register its source. For Docker Java, modify `ContainerDeploymentProviderConfiguration.java` and `DockerJavaContainerRuntimeAdapter.java` only as needed to share a managed DockerClient bean without changing runtime operations. Create `DOCKER/ImageInventoryCommand.java` and its test for a bounded inventory-specific process runner; do not change the global deployment-command timeout.

**Interfaces:** Each source implements Task 1's `snapshot(Duration timeout, int maxEntries)`. Docker source takes the configured DockerClient, CLI source takes the configured executable and `ImageInventoryCommand`, containerd takes `ContainerdClient` plus namespace identity, Kubernetes takes the existing `KubernetesClient`. `ImageInventoryCommand.run(List<String>, Duration timeout, int maxBytes): String` drains stdout/stderr concurrently into bounded buffers, destroys the process on deadline/interruption/overflow, and rejects nonzero exit. Keep backend package helpers package-private.

- [ ] Resolve the pinned backend dependencies with the repository's Gradle configurations and inspect their actual public listing/deadline APIs before implementing adapters (containerd artifacts were not cached during planning). Do not guess library methods, change dependency versions, or substitute registry references. Confirm the configured CLI variants from `ContainerLocalProperties` and cover their exact machine-readable listing formats.
- [ ] Write provider tests: tagged and untagged image identity retained; duplicate aliases deduplicated; legitimate empty listing AVAILABLE; backend failure UNAVAILABLE; >5,000 entries LIMIT_EXCEEDED; containerd namespace passed through; K8s entries retain node IDs and always disclose PARTIAL with `KUBELET_REPORTED`. K8s has no authoritative image-inventory timestamp: query time is collectedAt, source age is explicitly unknown, never inferred from pod status or a heartbeat.
- [ ] Add process tests with a child emitting more than pipe capacity, malformed/partial JSON, separate diagnostic stderr, nonzero exit and a hanging child. Assert bounded buffers, termination within deadline plus bounded cleanup, and no surviving child on interruption. Set command stdout budget 1 MiB and stderr budget 16 KiB; reject overflow rather than parsing a prefix.
- [ ] Run each provider's new focused tests and confirm failures before implementation. Implement actual image listing using existing backend clients; enforce per-request deadlines without changing mutation timeouts. Share and close Docker clients exactly once via Spring ownership. Use Kubernetes pagination/limits where supported and stop at the application cap. Collection failure affects only images.
- [ ] Run the focused tests and existing provider configuration/architecture tests with `./gradlew :control-plane-modules:<provider>:test -PcontrolPlaneModules=all --tests '*ImageInventory*Test' --tests '*ConfigurationTest' --tests '*ArchitectureTest' --console=plain` separately for `container-deployment-provider`, `containerd-deployment-provider`, `k8s-deployment-provider`; test `ImageInventoryCommandTest` separately if not selected by the pattern. Commit `Expose backend image inventories` after reviewing client ownership and deadline behavior.

## Task 5: Integrate bounded collection, peer polling and lifecycle fencing

**Files:** Create `P2P/NodeInformationExchange.java` and `NodeInformationExchangeTest.java`; modify `P2P/P2pService.java`, `P2pConfiguration.java`, `P2pAdminController.java` and extend lifecycle/admin tests. Use the existing table/messaging APIs; change them only if required for a narrowly scoped activation-generation notification, after impact analysis.

**Interfaces:** `NodeInformationExchange` consumes collector, codec, settings, table, wall clock, monotonic `LongSupplier`, injectable timing values and workers. Methods `activate(String nodeId, PeerMessaging messaging)`, `deactivate()`, `settingsChanged()`, `local(): NodeInformation`, `peer(String peerId): Optional<PeerInformation>`, `close()`. `PeerInformation` is a nested immutable record with state CURRENT/NOT_RECEIVED/STALE/INACTIVE, nullable snapshot and diagnostic receivedAt/ageMillis. `P2pService.information()` exposes the exchange and invokes lifecycle methods at the corresponding participation transitions. After admin patch/reset call `settingsChanged()` before returning.

- [ ] Use a fake Wire/clock and controlled collectors to test all flag combinations: disabled collectors have zero calls; re-enable triggers immediate work; disabling immediately masks data; collector failure is category-local. A never-returning collector gets only one actual invocation across multiple periods, including disable/re-enable, and late completion cannot publish across the old config generation.
- [ ] Add tests for max four peer requests, no round overlap, timeout recovery, absent-topic legacy peers and atomic replacement of a snapshot containing DISABLED. Assert monotonic expiry at 15 seconds despite wall-clock jumps. Encode source ageMillis from monotonic elapsed time; cache a category only for `min(15 seconds since receipt, 15 seconds minus advertised age)` so retransmission cannot renew stale measurements. Boundary age >=15 seconds yields no usable category data.
- [ ] Add regression tests for exclude/reinclude while a request is pending, peer leave/rejoin under the same ID, and ACTIVE→ISOLATED→ACTIVE. Assert late results from the earlier participation or peer activation generation are discarded. Snapshot reads must consult current table state immediately, not wait for the poll tick; departure removes cache entries. Register the handler only once, preserving it on rejoin.
- [ ] Run `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --tests '*NodeInformationExchangeTest' --tests '*P2pLifecycleTest' --console=plain` and observe new failures.
- [ ] Implement scheduling with a bounded worker pool and actual-operation guards released only by task completion, not by a reactive timeout callback. Do not await collector completion on a cluster/request event loop. Handler reads precollected data only. Encode current masks and monotonic category ages at response time. Keep snapshots unavailable if sources have aged out; do not cache encoded bytes whose age becomes incorrect. Poll via `PeerMessaging.request` with an empty request payload; reject nonempty/malformed requests. Unknown peers remain outside the cache.
- [ ] Run exchange/lifecycle/admin tests, inspect shutdown for scheduler/task leaks, then commit `Exchange fresh node snapshots over P2P`.

## Task 6: Expose inspection endpoints and native-safe DTOs

**Files:** Modify `P2P/P2pAdminController.java`, `P2pAdminControllerHttpTest.java`, `P2pAdminGateTest.java`, `P2pModuleIntegrationTest.java`, `platform/modules/p2p-discovery/openapi.yaml`, `platform/modules/p2p-discovery/src/main/resources/META-INF/native-image/it.unimib.datai.nanofaas/p2p-discovery/reachability-metadata.json`; create `P2pInformationRuntimeHintsTest.java` in the P2P test root.

**Interfaces:** GET `/v1/admin/p2p/information` returns `NodeInformation`; GET `/v1/admin/p2p/peers/{id}/information` returns `PeerInformation` or 404 for an unknown ID. Existing gate controls both. Config PATCH/reset uses Task 5's settings callback. Include all enum values, units, nullable unavailable fields, category caps and config defaults in OpenAPI.

- [ ] Write HTTP tests for both endpoints, unknown peer, NOT_RECEIVED/STALE/INACTIVE with null snapshot, every disabled gate combination, and patch→GET immediate masking. Assert a disabled P2P module has no collectors, sockets or background loops. Assert an enabled admin API cannot enable a deployment-disabled module.
- [ ] Run focused admin/module tests and observe failures for absent routes. Implement endpoints and native reflection entries for nested DTOs, category types, image entries and resource-management methods used reflectively. Reflection metadata must not resolve runtime share flags at image-build time.
- [ ] Verify typed JSON round trips, reachability coverage tests, and module architecture tests. Run `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery --tests '*P2pAdmin*Test' --tests '*P2pModuleIntegrationTest' --tests '*P2pInformationRuntimeHintsTest' --tests '*ArchitectureTest' --console=plain`; commit `Expose P2P node information diagnostics`.

## Task 7: Validate integration and document deployment controls

**Files:** Create `P2P` test-root `P2pNodeInformationIntegrationTest.java`; extend `P2pThreeNodeIntegrationTest.java` if sharing its existing node fixture is simpler. Extend real backend integration tests in their current modules. Modify `platform/modules/p2p-discovery/README.md`, `docs/control-plane.md`, `platform/control-plane/src/main/resources/application.yml`, `deploy/compose/compose.yaml`, `deploy/helm/nanofaas/values.yaml`, `deploy/helm/nanofaas/values.schema.json`, `deploy/helm/nanofaas/templates/control-plane-deployment.yaml`, `deploy/helm/nanofaas/templates/rbac.yaml`, and `deploy/k8s/control-plane-deployment.yaml`. Create the optional `deploy/k8s/p2p-image-inventory-rbac.yaml`.

**Interfaces:** Add Helm `controlPlane.p2p.shareFunctions/shareImages/shareResources=false` mapped to `NANOFAAS_P2P_SHAREFUNCTIONS`, `NANOFAAS_P2P_SHAREIMAGES`, `NANOFAAS_P2P_SHARERESOURCES` using Spring's relaxed binding (verify actual binding in a test). Add independent `rbac.nodeImageInventory=false` to provision a release-scoped ClusterRole/ClusterRoleBinding with only `nodes: [get,list]`; it must support runtime enablement even when startup shareImages=false. Raw Kubernetes manifests provide an explicitly documented optional node-inventory RBAC document, rather than granting node access through a namespaced Role.

- [ ] Add three-node tests over real sockets with independent mutable function catalogs, test inventory sources and workload values. Assert direct active-neighbor data, registration/deletion convergence, resource updates, all eight runtime flag combinations, disabled-data removal, failure isolation and rejoin freshness. Assert no third-party snapshots propagate. Fixture image sources prove exchange only, not real backend collection.
- [ ] Extend backend integration coverage to pull a small existing fixture image, query its real identity and remove only the test-owned fixture where safe. Exercise existing Docker infrastructure; run containerd and Kubernetes smoke checks only using available established environments/NanoLab, never provision new infrastructure. Explicitly record unsupported/unavailable environments.
- [ ] Add deployment/config tests verifying the three Boolean environment bindings and Helm node-RBAC opt-in (both off and on). Render/lint Helm with each RBAC setting; no release-name collisions and no new permissions when disabled. Update examples for single/all/no categories, reset, freshness and honest scopes.
- [ ] Run `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=all --console=plain`; expected all previous 114 tests plus new coverage pass. Run affected provider suites separately, SPI tests and `./gradlew -p platform/gradle-plugin test --tests '*RepositoryModuleDescriptorsTest' --tests '*ControlPlaneModuleProjectPluginTest' --tests '*OpenApiComposerTest' --console=plain`. Verify generated OpenAPI and route coverage using the repository's module-selected control-plane checks. Avoid claiming mocked tests are backend smoke validation.
- [ ] Check available GraalVM tooling before native work. Where available, build the P2P-enabled native artifact and exercise two-node exchange plus runtime enable/disable to verify AOT reachability; otherwise report native validation as unverified. Run final GitNexus scope analysis, review changed callers and the approved spec's nine acceptance criteria, then commit `Validate and document P2P node information exchange`.

## Completion evidence and execution handoff

Record exact commands, passed test counts, backend environments exercised, and native limitations. Report freshness/convergence behavior and example PATCH/GET commands. Do not claim completion with only simulated inventories; distinguish implemented provider support from smoke-tested environments. Keep the worktree attached for review and do not push, publish or merge without the corresponding user request.

Self-review: all spec sections map to Tasks 1–7; provider/source failure semantics and limits are in Tasks 1/3/4, lifecycle and runtime races in Tasks 2/5/6, supported deployments/native coverage in Tasks 6/7. Five Review Focus conditions have explicit tests in their owning tasks. No implementation code has been changed as part of this plan.

Recommended execution: Native (inline implementation in this chat), because config, data types and lifecycle changes share interfaces across most tasks. The execution method and written plan still require user review under the active planning workflow.
