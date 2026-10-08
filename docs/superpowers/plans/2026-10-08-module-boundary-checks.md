# Module Boundary Checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make the existing module-boundary policy enforceable without changing runtime composition or creating API projects.

**Architecture:** Use the existing control-plane architecture suite as the composition-level authority, with ownership derived from code-source locations. Retain local checks and constrain the sync composition exception to exact dependencies. The checks must also work after the #236 moves.

**Tech Stack:** Java 25, existing Gradle wrapper, JUnit 5 API on JUnit Platform, ArchUnit 1.5.0, AssertJ.

**Spec:** [2026-10-08-module-boundaries-and-layout-design.md](../specs/2026-10-08-module-boundaries-and-layout-design.md), especially Architecture safeguards and Acceptance 1–3.

## Global Constraints

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

## Review Focus

- SPI and implementation share `controlplane.*`: recognize artifact ownership rather than accepting every class in that namespace (Tasks 1–2).
- Exploded directories, JARs and paths with spaces after relocation: ownership must remain correct and prefix collisions must fail (Task 1).
- A new implementation or another class named `SyncQueueConfiguration`: neither receives a blanket exemption (Task 2).
- Core-only versus a missing selected module: accept the former, fail the latter rather than passing vacuously (Task 2).
- Legitimate integration-test imports and Docker/containerd shared runtime reuse: keep them outside the production prohibition (Task 2).

---

## File map and execution prerequisites

All paths below use the current layout. Execute this plan before the layout plan.

| File | Responsibility |
| --- | --- |
| `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureTest.java` | Existing source ownership checks; make their location comparison independent of checkout layout. |
| `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureSourceTest.java` | Pure URI ownership regressions. |
| `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/ModuleBoundaryArchitectureTest.java` (new) | Production dependency rule, exact sync exception, selected-module subject guard and negative evaluations. |
| `platform/control-plane-spi/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/SpiPurityTest.java` | Contract artifact purity despite overlapping package names. |
| `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/architecture/ArchitectureTest.java` | Restrict the existing local exception; retain local sibling-module protection. |
| `platform/modules/forecasting/src/test/java/it/unimib/datai/nanofaas/modules/forecasting/ForecastingArchitectureTest.java` | Ban every sibling implementation rather than two named packages; production-only import. |
| `platform/modules/p2p-discovery/src/test/java/it/unimib/datai/nanofaas/modules/p2pdiscovery/architecture/ArchitectureTest.java` | Exclude test classes consistently. |
| `platform/modules/build-metadata/src/test/java/it/unimib/datai/nanofaas/modules/buildmetadata/architecture/ArchitectureTest.java` | Exclude test classes consistently. |
| `.github/workflows/gitops.yml`, `docs/testing.md` | Run/document the composition checks with `all` and `none`. |

Use an isolated worktree at execution time; preserve the existing untracked experiment files. Before modifying existing symbols, run GitNexus upstream impact with an exact symbol/file, report callers/processes/risk, and investigate UNKNOWN with source checks. Before every commit run `detect-changes --scope all --repo .`; partial/truncated is not a clean result. GitNexus commands below assume the repository runner is available on PATH.

Baseline verification is the 44 architecture/SPI tests recorded in the conversation. No new full platform/E2E campaign is needed for test-only changes. Negative evaluations/mutations must demonstrate the new checks can actually fail.

### Task 1: Make source ownership independent of physical directories

**Files:** Modify the first two files in the file map.

**Interfaces:**
- Consumes: `JavaClass.getSource(): Optional<Source>`, `Source.getUri(): URI`, and `Class.getProtectionDomain().getCodeSource().getLocation(): URL`.
- Produces: package-private `static boolean isFromCodeSource(URI classUri, URI codeSourceUri)` on `CoreArchitectureTest`; preserve `isCoreSource(URI)`, `isContractSource(URI)`, `isRuntimeSource(URI)` signatures for existing callers.

- [x] **Step 1: Add URI comparison tests in `CoreArchitectureSourceTest`.**

Use parameterized cases; these assertions fix the semantics:

```java
assertThat(isFromCodeSource(URI.create("file:/repo/platform/libs/control-plane-spi/build/classes/java/main/a/B.class"),
        URI.create("file:/repo/platform/libs/control-plane-spi/build/classes/java/main/"))).isTrue();
assertThat(isFromCodeSource(URI.create("jar:file:/repo%20copy/spi.jar!/a/B.class"),
        URI.create("file:/repo%20copy/spi.jar"))).isTrue();
assertThat(isFromCodeSource(URI.create("file:/repo/core-extra/a/B.class"),
        URI.create("file:/repo/core/"))).isFalse();
assertThat(isFromCodeSource(URI.create("jar:file:/repo/other.jar!/a/B.class"),
        URI.create("file:/repo/spi.jar"))).isFalse();
assertThat(isFromCodeSource(null, URI.create("file:/repo/core/"))).isFalse();
```

Keep equivalent cases for the old layout and null code source. Replace the existing synthetic-path tests of wrapper methods with assertions on the real class resources of `FunctionController`, `FunctionCatalogView`, and `ExecutionRecord`; each belongs to exactly one owner. No version literal belongs in these tests.

- [x] **Step 2: Run the new tests and record the expected missing-helper failure.**

Run: `./gradlew :control-plane:test --tests '*CoreArchitectureSourceTest' -PcontrolPlaneModules=all --console=plain`

- [x] **Step 3: Implement the comparison and delegate the three existing wrappers.**

Compare normalized URI locations with directory/JAR entry boundaries, not unbounded string prefixes. Derive each artifact's code source from its marker class above. Keep missing/unrecognized sources a failure of the existing R6 condition, and keep the wrappers package-private. This is test code only.

- [x] **Step 4: Verify existing and new ownership tests.**

Run: `./gradlew :control-plane:test --tests '*CoreArchitecture*' -PcontrolPlaneModules=all --console=plain`
Expected: PASS, including namespace and scheduler-policy rules. The old/new synthetic roots and real JAR/directory ownership cases all pass.

- [x] **Step 5: Analyze graph changes and commit the two test files.**

Commit message: `Make architecture ownership checks independent of layout`.

### Task 2: Enforce implementation boundaries with a narrow composition exception

**Files:** Create `ModuleBoundaryArchitectureTest.java`; modify the five SPI/local architecture files and CI/docs listed above.

**Interfaces:**
- Consumes: Task 1 ownership predicates; Gradle's existing `nanofaas.selectedControlPlaneModules` test system property.
- Produces on the new test class: `static ArchRule optionalModulesUseContractsOnly()`, `static ArchCondition<JavaClass> moduleDependencyCondition()`, `static boolean isApprovedCompositionPair(String originName, String targetName)`, `static void assertSelectedModulesPresent(JavaClasses classes, Set<String> selectedModules)` and JUnit tests evaluating that rule. Keep helpers package-private, confined to this test class.
- Private constants: exact full names of allowed sync origin/targets from the spec and a map of the 12 descriptor IDs to their actual Java package prefixes. Use `k8s-deployment-provider -> it.unimib.datai.nanofaas.modules.k8s`; other prefixes must come from current source declarations, not guessed hyphen removal.

- [x] **Step 1: Add negative rule evaluations and subject-guard tests.**

Keep small nested fixture classes inside the new test file; explicitly import them for rule evaluation, never for the production scan. Apply `moduleDependencyCondition()` directly to the explicit fixtures and use that same condition behind the production rule's optional-module subject predicate. Assert on `rule.evaluate(importedClasses).hasViolation()` and on failure details naming origin and target.

Tests and exact expectations:

```java
static final class IllegalCoreFixture { FunctionService service; }
static final class IllegalRuntimeFixture { ExecutionStore store; }
static final class AllowedContractFixture {
    FunctionCatalogView catalog;
    ManagedDeploymentProvider provider;
}

@Test
void rejectsCoreImplementationDependencies() {
    var subjects = new ClassFileImporter().importClasses(IllegalCoreFixture.class);
    var result = classes().should(moduleDependencyCondition()).evaluate(subjects);
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails().toString())
            .contains("IllegalCoreFixture", "FunctionService");
}
```

Add `rejectsRuntimeImplementationDependencies` with `IllegalRuntimeFixture` and `hasViolation()==true`, and `acceptsExistingContracts` with `AllowedContractFixture` and `hasViolation()==false`. Add `rejectsConfigurationNameImpostor` using a nested `SyncQueueConfiguration` fixture with an `EngineSyncQueueGateway` field. Assert `isApprovedCompositionPair` rejects the real sync origin paired with `FunctionService`. The subject tests must assert: a known selected module with no subjects throws `AssertionError`; unknown selected ID throws `AssertionError`; empty selection with zero optional subjects passes. Do not build a class generator or reflection-based fixture factory.

- [x] **Step 2: Run the focused test and confirm it fails before the rule exists.**

Run: `./gradlew :control-plane:test --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=all --console=plain`

- [x] **Step 3: Implement the production rule and subject guard.**

Import production classes with `ImportOption.DoNotIncludeTests`. Use the code-source ownership predicates for core/runtime targets; fail explicitly when a referenced internal type in their namespaces has no source. SPI types sharing those namespaces remain allowed. Classify sibling optional modules by the explicit descriptor/package map. Shared container runtime, workload metrics and API libraries remain allowed.

Inspect `getDirectDependenciesFromSelf()` on the imported ArchUnit `JavaClass` for `SyncQueueConfiguration` and encode only the spec's pairs. Structural nested-class metadata must not grant permission to construct/call the enclosing `EngineInvocationEnqueuer`; use ArchUnit access checks for that distinction. No blanket simple-name or whole-class exclusion. The subject guard compares imported production packages with selected modules and verifies marker presence for each selected ID. For IDE runs with no Gradle property, derive the set from observed module packages and still require the core/SPI/runtime markers.

- [x] **Step 4: Align the existing local rules and SPI purity.**

Replace the sync rule's whole-class exclusion with its exact permitted dependencies and include `EngineSyncQueueGateway` among prohibited targets for other origins. Retain its existing sibling prohibition. Replace forecasting's two-package blacklist with the same all-siblings-except-self pattern already used by other modules. Add production-only import options to forecasting, P2P and build-metadata.

In `SpiPurityTest`, import the SPI's own code-source URL (`ClassFileImporter.importUrl(URL)`, available in ArchUnit 1.5.0), and allow internal target names only from that artifact or `common`; retain the existing external-package constraints. Assert the import is nonempty and includes `FunctionCatalogView`. Do not whitelist the whole shared `controlplane` namespace. Add nested test fixtures `ForeignImplementation` and `SpiConsumerFixture { ForeignImplementation implementation; }` in this test file: their package has the shared `controlplane` prefix, but their classes are absent from the SPI production artifact. `rejectsForeignTypesInTheControlplaneNamespace` must evaluate the same purity rule on `SpiConsumerFixture` and assert a violation naming `ForeignImplementation`. This needs no dependency on core, even for tests.

- [x] **Step 5: Verify rejection with two temporary source mutations and restore them.**

First add a field typed `EngineSyncQueueGateway` to a production sync class other than its configuration. Then add a newly named, empty class in core and a field of that type to `SyncQueueConfiguration`. Run the focused composition check for each mutation: both must FAIL with origin/target diagnostics. Restore only the exact temporary edits, then rerun: PASS. Run GitNexus impact before these edits too. These mutations prove the check is not only a test of its helper predicate.

- [x] **Step 6: Run the focused architecture matrix.**

```bash
./gradlew :control-plane:test --tests '*CoreArchitecture*' --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=all
./gradlew :control-plane:test --tests '*CoreArchitecture*' --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=none
./gradlew :control-plane:test --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=async-queue
./gradlew :control-plane:test --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=sync-queue
./gradlew :control-plane-spi:test --tests '*SpiPurityTest' -PcontrolPlaneModules=all
./gradlew :control-plane-modules:sync-queue:test --tests '*ArchitectureTest' :control-plane-modules:forecasting:test --tests '*ArchitectureTest' :control-plane-modules:p2p-discovery:test --tests '*ArchitectureTest' :control-plane-modules:build-metadata:test --tests '*ArchitectureTest' -PcontrolPlaneModules=all
```

Expected: PASS. Every `--tests` option must follow the task it filters. Preserve XML reports separately for the all/none/async/sync runs before the next invocation overwrites them. Include existing runtime/container architecture checks once; no full performance campaign.

- [x] **Step 7: Connect the verified checks to CI and document the boundary.**

Add an explicit focused `all` composition check in `gitops.yml`; add the two architecture class patterns to its existing core-only task. Keep all existing CI checks. Document in `docs/testing.md` the focused command, intentional shared implementation dependencies and exact sync exception; explain that test-only consumers are allowed. Do not claim every Gradle project is an independently replaceable component.

- [x] **Step 8: Self-review scope, analyze graph changes and commit.**

Expected diff: tests, CI and current testing documentation only. No `src/main` edits survive the negative mutations. Commit message: `Enforce module boundaries and explicit composition exceptions`.

## Handoff to layout migration

Record the successful profiles and the safeguard commit. Then execute the separate [layout plan](2026-10-08-issue-236-repository-layout.md). The new checks must pass after directory relocation without adding old/new path allowlists.

Execution evidence: all/none/async/sync/default profiles each passed 26 composition/source tests. SPI and local/runtime architecture checks passed. Temporary gateway, new-core-type and direct-enqueuer-field mutations were rejected and restored. The profile subject guard now compares whole IDs; `async-queue` no longer matches `sync-queue`. Core scans production classes only; SPI regression uses its existing JUnit assertions.
