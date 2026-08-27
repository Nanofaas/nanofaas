# Runtime Config Extension Registry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `runtime-config` depend only on the control plane while optional modules register namespaced, hot-updatable configuration through either a strong or weak dependency on `runtime-config`.

**Architecture:** `runtime-config` owns one HTTP contract, revision handling, validation orchestration, metrics, and rollback. It discovers Spring beans implementing its public `RuntimeConfigExtension` SPI; each extension owns its namespace, values, validation, application, and restoration. `sync-queue` is the first weak integration: its normal runtime remains independent, while a conditionally loaded bridge contributes configuration only when `runtime-config` is on the runtime classpath.

**Tech Stack:** Java 25, Spring Boot/WebFlux, Gradle multi-project builds, Jackson's existing HTTP conversion, Micrometer, JUnit 5, AssertJ, Mockito, Spring `ApplicationContextRunner`, ArchUnit, OpenAPI 3.

---

## Decisions and scope

- Keep `RuntimeConfigExtension` in the `runtime-config` module. Do not move the SPI into `common` or `control-plane`.
- Keep one centrally owned API. Extensions register handlers and values, never arbitrary Spring controllers or paths.
- Use one namespace per extension and one namespace per PATCH. This avoids an unnecessary distributed transaction across modules.
- Keep one global revision for compatibility with the current optimistic-concurrency model. Successful changes increment it; validation failures, unknown namespaces, stale revisions, and rolled-back applies do not.
- Replace the fixed request/response shape with namespaced maps. This is an intentional breaking change to the experimental admin API and must update `openapi.yaml` and documentation in the same change.
- Keep the control-plane rate limiter as the built-in `control-plane` extension because `runtime-config` is allowed to know core types.
- Move all sync-queue-specific state, validation, and mutation into `sync-queue`; `runtime-config` must have no imports from `..sync..` or references to `SyncQueue*`.
- Implement the currently needed weak dependency for `sync-queue` with `compileOnly`. Document strong dependency as Gradle `implementation`, which transitively includes `runtime-config` and fails dependency resolution if it is unavailable. Do not add a custom dependency DSL until a real hard-dependent module needs selector-level validation.
- Do not add JSON Schema generation, runtime registration after Spring startup, extension ordering, persistence, or multi-module PATCH.

## Required execution discipline

Before each production-symbol edit, run GitNexus impact analysis and report the blast radius. Existing analysis on 2026-08-27 found MEDIUM risk for `AdminRuntimeConfigController`, `RuntimeConfigService`, and `SyncQueueConfigSource`, LOW risk for `SyncQueueConfiguration`, and no HIGH/CRITICAL result. Re-run because the index may change.

Before every commit:

```bash
gitnexus_detect_changes({scope: "all"})
git diff --check
```

Confirm all depth-1 dependents are handled. After every commit, inspect `.gitnexus/meta.json`; because the current index has zero embeddings, refresh with:

```bash
npx gitnexus analyze
```

Use `@superpowers:test-driven-development` for every implementation task and `@superpowers:verification-before-completion` before claiming completion.

### Task 1: Introduce the extension contract and registry

**Files:**

- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigExtension.java`
- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigRegistry.java`
- Create: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigRegistryTest.java`

**Step 1: Write the failing registry tests**

Cover deterministic namespace ordering, lookup, aggregate snapshots, immutable returned maps, and duplicate rejection. Use a tiny test extension:

```java
private static RuntimeConfigExtension extension(String namespace, int value) {
    return new RuntimeConfigExtension() {
        private int current = value;

        @Override public String namespace() { return namespace; }
        @Override public Map<String, Object> snapshot() { return Map.of("value", current); }
        @Override public List<String> validate(Map<String, Object> patch) { return List.of(); }
        @Override public void apply(Map<String, Object> patch) {
            if (patch.containsKey("value")) current = ((Number) patch.get("value")).intValue();
        }
        @Override public void restore(Map<String, Object> snapshot) {
            current = ((Number) snapshot.get("value")).intValue();
        }
    };
}

@Test
void rejectsDuplicateNamespaces() {
    assertThatThrownBy(() -> new RuntimeConfigRegistry(List.of(
            extension("queue", 1), extension("queue", 2))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("queue");
}
```

**Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*RuntimeConfigRegistryTest'
```

Expected: compilation fails because `RuntimeConfigExtension` and `RuntimeConfigRegistry` do not exist.

**Step 3: Add the minimal public SPI**

```java
package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.util.List;
import java.util.Map;

public interface RuntimeConfigExtension {
    String namespace();
    Map<String, Object> snapshot();
    List<String> validate(Map<String, Object> patch);
    void apply(Map<String, Object> patch);
    void restore(Map<String, Object> snapshot);
}
```

Implement `RuntimeConfigRegistry` with a constructor accepting `List<RuntimeConfigExtension>`, a lexicographically ordered immutable map, duplicate/blank namespace rejection, `extension(String)`, and `snapshot()` returning immutable copies. Do not add dynamic `register()`/`unregister()` methods: Spring startup discovery is sufficient.

**Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 5: Check scope and commit**

```bash
git add platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigExtension.java \
  platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigRegistry.java \
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigRegistryTest.java
git commit -m "Add runtime config extension registry"
```

### Task 2: Make the service generic and transactional per namespace

**Files:**

- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigService.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigSnapshot.java`
- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/UnknownRuntimeConfigNamespaceException.java`
- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigValidationException.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigApplyException.java`
- Rewrite test: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java`

**Step 1: Run impact before editing**

```text
gitnexus_impact({target: "RuntimeConfigService", direction: "upstream", includeTests: true})
gitnexus_impact({target: "RuntimeConfigSnapshot", direction: "upstream", includeTests: true})
```

Expected: MEDIUM or lower. Stop and warn before proceeding if either becomes HIGH/CRITICAL.

**Step 2: Replace service tests with generic behavior tests**

Write tests proving:

- initial revision is `0` and snapshots are grouped by namespace;
- a valid partial patch calls exactly one extension and increments revision once;
- stale revisions throw `RevisionMismatchException` without calling `apply`;
- unknown namespaces throw `UnknownRuntimeConfigNamespaceException` without incrementing revision;
- validation errors throw `RuntimeConfigValidationException` without applying;
- apply failure restores the captured snapshot and leaves the revision unchanged;
- restore failure is suppressed onto the original apply failure;
- concurrent updates are serialized and observe revision order.

Representative rollback test:

```java
@Test
void failedApplyRestoresExtensionAndKeepsRevision() {
    TestExtension extension = new TestExtension("queue", Map.of("enabled", true));
    extension.failNextApply();
    RuntimeConfigService service = service(extension);

    assertThatThrownBy(() -> service.update(0, "queue", Map.of("enabled", false)))
            .isInstanceOf(RuntimeConfigApplyException.class);

    assertThat(service.snapshot().revision()).isZero();
    assertThat(extension.snapshot()).containsEntry("enabled", true);
}
```

**Step 3: Run and verify RED**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*RuntimeConfigServiceTest'
```

Expected: compilation failures against the old fixed DTO/service API.

**Step 4: Implement the minimum generic service**

Change the snapshot to:

```java
public record RuntimeConfigSnapshot(
        long revision,
        Map<String, Map<String, Object>> modules
) {}
```

`RuntimeConfigService` receives `RuntimeConfigRegistry` plus `MeterRegistry`, owns the revision, and exposes:

```java
public synchronized RuntimeConfigSnapshot snapshot();
public List<String> validate(String namespace, Map<String, Object> patch);
public synchronized RuntimeConfigSnapshot update(
        long expectedRevision, String namespace, Map<String, Object> patch);
```

In `update`, check revision and namespace, copy the previous extension snapshot, validate before side effects, call `apply`, increment revision only after success, and call `restore(previous)` on failure. Keep the existing metric names and update logs, adding `namespace` to log fields. Put metrics here and delete the need for a separate orchestration layer later.

`RuntimeConfigValidationException` must expose an immutable `List<String> errors()`. `UnknownRuntimeConfigNamespaceException` must include the namespace. `RuntimeConfigApplyException` must retain the original apply failure and any restore failure via `addSuppressed`.

**Step 5: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: PASS.

**Step 6: Check scope and commit**

```bash
git add platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig \
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java
git commit -m "Generalize runtime config updates"
```

### Task 3: Register the control-plane rate limiter as the built-in extension

**Files:**

- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtension.java`
- Create: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtensionTest.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigConfiguration.java`

**Step 1: Run impact before editing**

```text
gitnexus_impact({target: "RuntimeConfigConfiguration", direction: "upstream", includeTests: true})
gitnexus_impact({target: "RateLimiter", direction: "upstream", includeTests: true})
```

Do not proceed past a new HIGH/CRITICAL warning without reporting it.

**Step 2: Write failing extension tests**

Test namespace `control-plane`, snapshot shape, partial no-op patch, unknown-key rejection, non-number/inexact-number rejection, `<= 0` rejection, successful apply, and restore.

```java
@Test
void appliesPositiveRateLimit() {
    RateLimiter limiter = new RateLimiter();
    limiter.setMaxPerSecond(1000);
    ControlPlaneRuntimeConfigExtension extension = new ControlPlaneRuntimeConfigExtension(limiter);

    assertThat(extension.validate(Map.of("rateMaxPerSecond", 500))).isEmpty();
    extension.apply(Map.of("rateMaxPerSecond", 500));

    assertThat(limiter.getMaxPerSecond()).isEqualTo(500);
}
```

**Step 3: Run and verify RED**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*ControlPlaneRuntimeConfigExtensionTest'
```

Expected: compilation fails because the extension does not exist.

**Step 4: Implement and wire the built-in extension**

The extension accepts only `rateMaxPerSecond`, using `Number` only when its exact integer value is positive. Register it as a bean, inject `List<RuntimeConfigExtension>` into `RuntimeConfigRegistry`, then inject the registry and `MeterRegistry` into `RuntimeConfigService`. Remove all `SyncQueueRuntimeDefaults` parameters and imports from `RuntimeConfigConfiguration`.

**Step 5: Run focused and module tests**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*ControlPlaneRuntimeConfigExtensionTest' \
  --tests '*RuntimeConfigServiceTest'
```

Expected: PASS.

**Step 6: Check scope and commit**

```bash
git add platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig \
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtensionTest.java
git commit -m "Register control plane runtime settings"
```

### Task 4: Replace the fixed admin API with the namespaced contract

**Files:**

- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigController.java`
- Rewrite: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigControllerTest.java`
- Rewrite: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigIntegrationTest.java`

**Step 1: Run impact before editing**

```text
gitnexus_impact({target: "AdminRuntimeConfigController", direction: "upstream", includeTests: true})
```

Expected: MEDIUM or lower; direct consumers are the runtime-config tests and API contract.

**Step 2: Write failing controller tests for the new routes**

The HTTP contract is:

```text
GET   /v1/admin/runtime-config
GET   /v1/admin/runtime-config/{namespace}
POST  /v1/admin/runtime-config/{namespace}/validate
PATCH /v1/admin/runtime-config/{namespace}
```

PATCH body:

```json
{
  "expectedRevision": 3,
  "values": {
    "rateMaxPerSecond": 500
  }
}
```

Cover success plus status mapping:

- malformed/missing JSON fields → `400`;
- unknown namespace → `404`;
- stale revision → `409` with `currentRevision`;
- validation errors → `422` with `errors`;
- apply/rollback failure → `503`;
- valid PATCH → `200`, effective namespace config, revision, timestamp, and change ID;
- concurrent PATCH calls are serialized by the service, not the controller.

**Step 3: Run and verify RED**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*AdminRuntimeConfigControllerTest' \
  --tests '*AdminRuntimeConfigIntegrationTest'
```

Expected: route and JSON-shape assertions fail against the fixed controller.

**Step 4: Implement the generic controller**

Use records with map payloads only at the HTTP boundary:

```java
public record PatchRequest(Long expectedRevision, Map<String, Object> values) {}
public record NamespaceResponse(long revision, String namespace, Map<String, Object> values) {}
public record SnapshotResponse(long revision, Map<String, Map<String, Object>> modules) {}
```

Delegate all state transitions to `RuntimeConfigService`; do not retain fixed parameter parsing or `synchronized` on the controller. Preserve the existing `nanofaas.admin.runtime-config.enabled=true` condition.

**Step 5: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: PASS.

**Step 6: Delete obsolete fixed-shape classes and tests**

Delete after all references have moved:

- `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigPatch.java`
- `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigValidator.java`
- `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigApplier.java`
- `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigValidatorTest.java`
- `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigApplierTest.java`

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test
```

Expected: PASS with no references to deleted types.

**Step 7: Check scope and commit**

```bash
git add -A platform/modules/runtime-config
git commit -m "Expose namespaced runtime config API"
```

### Task 5: Give sync-queue an internal mutable configuration source

**Files:**

- Create: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/config/MutableSyncQueueConfigSource.java`
- Create: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/config/MutableSyncQueueConfigSourceTest.java`
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueConfiguration.java`
- Modify: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueConfigurationTest.java`

**Step 1: Run impact before editing**

```text
gitnexus_impact({target: "SyncQueueConfiguration", direction: "upstream", includeTests: true})
gitnexus_impact({target: "SyncQueueConfigSource", direction: "upstream", includeTests: true})
```

The previous risk was LOW and MEDIUM respectively. Update every depth-1 consumer only if its contract changes; this plan keeps the interface unchanged.

**Step 2: Write failing source/configuration tests**

Test that the source starts from `SyncQueueRuntimeDefaults`, reads a coherent immutable snapshot, replaces all values atomically, and is the primary `SyncQueueConfigSource` when the module is loaded.

```java
@Test
void replacesAllRuntimeValuesAtomically() {
    MutableSyncQueueConfigSource source = new MutableSyncQueueConfigSource(DEFAULTS);
    SyncQueueRuntimeDefaults updated = new SyncQueueRuntimeDefaults(
            false, false, Duration.ofSeconds(1), Duration.ofSeconds(3), 4);

    source.replace(updated);

    assertThat(source.snapshot()).isEqualTo(updated);
}
```

**Step 3: Run and verify RED**

```bash
./gradlew :control-plane-modules:sync-queue:test \
  --tests '*MutableSyncQueueConfigSourceTest' \
  --tests '*SyncQueueConfigurationTest'
```

Expected: compilation fails because the mutable source does not exist.

**Step 4: Implement the source and bean**

Use one `AtomicReference<SyncQueueRuntimeDefaults>`; implement the existing `SyncQueueConfigSource` getters by reading the current record. Add `snapshot()` and `replace(SyncQueueRuntimeDefaults)` methods. Register the concrete type as `@Primary` in `SyncQueueConfiguration`, initialized from `SyncQueueProperties.runtimeDefaults()`.

**Step 5: Run sync-queue tests and verify GREEN**

```bash
./gradlew :control-plane-modules:sync-queue:test
```

Expected: PASS.

**Step 6: Check scope and commit**

```bash
git add platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue \
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue
git commit -m "Make sync queue settings mutable"
```

### Task 6: Add the weak sync-queue runtime-config integration

**Files:**

- Modify: `platform/modules/sync-queue/build.gradle`
- Create: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/config/SyncQueueRuntimeConfigExtension.java`
- Create: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/config/SyncQueueRuntimeConfigConfiguration.java`
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueModule.java`
- Create: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/config/SyncQueueRuntimeConfigExtensionTest.java`
- Create: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/config/SyncQueueRuntimeConfigOptionalityTest.java`
- Modify: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/architecture/ArchitectureTest.java`

**Step 1: Run impact before editing**

```text
gitnexus_impact({target: "SyncQueueModule", direction: "upstream", includeTests: true})
gitnexus_impact({target: "SyncQueueConfiguration", direction: "upstream", includeTests: true})
```

Stop on HIGH/CRITICAL.

**Step 2: Add the compile-only dependency and failing behavior tests**

Add:

```groovy
compileOnly project(':control-plane-modules:runtime-config')
testImplementation project(':control-plane-modules:runtime-config')
```

Test namespace `sync-queue`, complete snapshot, all five mutable fields, ISO-8601 duration parsing, unknown fields, wrong JSON types, positive durations, `maxEstimatedWait <= maxQueueWait`, retry seconds `>= 1`, partial apply, and exact restore.

**Step 3: Add the weak-absence test before implementation**

Use Spring Boot's `FilteredClassLoader` to hide `it.unimib.datai.nanofaas.modules.runtimeconfig` and an `ApplicationContextRunner` containing the sync-queue configuration. Assert the context starts, `MutableSyncQueueConfigSource` exists, and no runtime-config bridge bean is created. Also retain an integration test with the normal classloader asserting the extension bean is present.

**Step 4: Run and verify RED**

```bash
./gradlew :control-plane-modules:sync-queue:test \
  --tests '*SyncQueueRuntimeConfigExtensionTest' \
  --tests '*SyncQueueRuntimeConfigOptionalityTest'
```

Expected: compilation fails because the bridge classes do not exist.

**Step 5: Implement the conditional bridge**

`SyncQueueRuntimeConfigExtension` implements `RuntimeConfigExtension`, delegates state to `MutableSyncQueueConfigSource`, and contains all sync-queue-specific validation. Guard its configuration using the string form so the optional type is not resolved early:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name =
        "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigExtension")
public class SyncQueueRuntimeConfigConfiguration {
    @Bean
    RuntimeConfigExtension syncQueueRuntimeConfigExtension(MutableSyncQueueConfigSource source) {
        return new SyncQueueRuntimeConfigExtension(source);
    }
}
```

Add this configuration class to `SyncQueueModule.configurationClasses()`. The optionality test is the authority on whether Spring can skip it safely when the SPI class is absent; if class verification still resolves the missing return type, move the bridge into a nested conditionally imported configuration without changing the public SPI.

**Step 6: Sanction only this cross-module dependency in ArchUnit**

Change the sync-queue architecture predicate to exclude `..modules.runtimeconfig..` from forbidden targets and document that this is the approved weak extension boundary. Continue forbidding every other module-to-module dependency.

**Step 7: Run both classpath variants**

```bash
./gradlew :control-plane-modules:sync-queue:test
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue
jar tf platform/control-plane/build/libs/app.jar > /tmp/nanofaas-sync-queue-only-jar.txt
if rg -q 'RuntimeConfig|runtimeconfig' /tmp/nanofaas-sync-queue-only-jar.txt; then false; fi
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
jar tf platform/control-plane/build/libs/app.jar | rg 'RuntimeConfigExtension.class'
```

Expected: tests pass; the first artifact contains no runtime-config classes; the second contains the SPI and starts with both modules discoverable.

**Step 8: Check scope and commit**

```bash
git add platform/modules/sync-queue
git commit -m "Expose sync queue runtime settings optionally"
```

### Task 7: Prove runtime-config no longer knows sync-queue

**Files:**

- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/architecture/ArchitectureTest.java`
- Modify: `platform/modules/runtime-config/build.gradle` only if a now-unused dependency can be removed

**Step 1: Write the architecture rule**

Add an explicit rule:

```java
@ArchTest
static final ArchRule runtime_config_only_depends_on_core_not_optional_modules =
        noClasses()
                .that().resideInAPackage("..modules.runtimeconfig..")
                .should().dependOnClassesThat().resideInAPackage("..modules..")
                .as("runtime-config must be agnostic of optional modules");
```

The existing broader module-isolation rule may already enforce this. If so, rename/improve its description rather than adding a duplicate rule.

**Step 2: Run the architecture test**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*runtimeconfig.architecture.ArchitectureTest'
```

Expected: PASS and no source reference under `platform/modules/runtime-config` matches `SyncQueue`:

```bash
rg -n 'SyncQueue|sync-queue' platform/modules/runtime-config/src/main && exit 1 || true
```

**Step 3: Check scope and commit**

```bash
git add platform/modules/runtime-config
git commit -m "Enforce runtime config module isolation"
```

### Task 8: Update OpenAPI and operator documentation

**Files:**

- Modify: `openapi.yaml`
- Modify: `platform/modules/runtime-config/README.md`
- Modify: `docs/control-plane.md`
- Modify: `platform/control-plane/src/main/resources/application.yml` only if comments/examples still describe fixed fields
- Modify: `experiments/e2e-runtime-config.sh` if retained by the repository

**Step 1: Write the API contract before prose**

Replace fixed schemas with:

```yaml
RuntimeConfigPatchRequest:
  type: object
  required: [expectedRevision, values]
  properties:
    expectedRevision:
      type: integer
      format: int64
      minimum: 0
    values:
      type: object
      additionalProperties: true

RuntimeConfigSnapshot:
  type: object
  required: [revision, modules]
  properties:
    revision:
      type: integer
      format: int64
    modules:
      type: object
      additionalProperties:
        type: object
        additionalProperties: true
```

Document all four routes, status codes, namespace semantics, global revision behavior, and the intentional replacement of the old flat payload.

**Step 2: Document strong and weak module integration**

Add concise examples to `platform/modules/runtime-config/README.md`:

```groovy
// Strong: runtime-config is transitively required at runtime.
implementation project(':control-plane-modules:runtime-config')

// Weak: compile the bridge, omit it safely from the runtime artifact.
compileOnly project(':control-plane-modules:runtime-config')
```

State that weak bridge code must be isolated behind `@ConditionalOnClass(name = ...)` and tested with runtime-config absent. Explain that a future hard-dependent module may add explicit selector validation if transitive inclusion is not desired; do not create that machinery now.

**Step 3: Update the E2E script**

If `experiments/e2e-runtime-config.sh` exists, patch `control-plane/rateMaxPerSecond` and `sync-queue/maxQueueWait`, verify revisions, validate malformed values, and verify stale-revision `409` behavior. If the script no longer exists, do not recreate it; cover the behavior in `AdminRuntimeConfigIntegrationTest` only.

**Step 4: Run contract checks**

```bash
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest'
./gradlew :control-plane-modules:runtime-config:test
./gradlew :control-plane-modules:sync-queue:test
```

Expected: PASS.

**Step 5: Check scope and commit**

```bash
git add openapi.yaml docs/control-plane.md platform/modules/runtime-config/README.md \
  platform/control-plane/src/main/resources/application.yml experiments/e2e-runtime-config.sh
git commit -m "Document extensible runtime configuration"
```

Only add files that actually changed; omit a missing E2E script or unchanged application configuration from `git add`.

### Task 9: Full verification and handoff

**Files:** none unless verification reveals a defect.

**Step 1: Verify selected module combinations**

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane-modules:runtime-config:test -PcontrolPlaneModules=runtime-config
./gradlew :control-plane-modules:sync-queue:test -PcontrolPlaneModules=sync-queue
./gradlew :control-plane:test -PcontrolPlaneModules=sync-queue,runtime-config
```

Expected: all PASS. The core-only build has no optional module dependency; sync-queue passes with and without runtime-config.

**Step 2: Verify the assembled artifacts and native reachability**

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane:nativeTestCompile -PcontrolPlaneModules=sync-queue,runtime-config
```

Expected: all tasks succeed; no optional bridge causes missing-class errors during Spring AOT/native analysis.

**Step 3: Run repository checks**

```bash
git diff --check main...HEAD
git status --short
```

Run:

```text
gitnexus_detect_changes({scope: "compare", base_ref: "main"})
```

Expected: changed symbols and flows are limited to runtime-config administration, rate limiting configuration, sync-queue configuration reads, module packaging, API specification, and docs. Verify every depth-1 dependent identified by impact analysis is covered.

**Step 4: Request review**

Use `@superpowers:requesting-code-review`. Specifically ask the reviewer to check optional class loading, rollback correctness, unknown JSON types, OpenAPI drift, and accidental runtime-config inclusion in the sync-queue-only artifact.

**Step 5: Finish the branch**

Use `@superpowers:finishing-a-development-branch` to choose merge, PR, or cleanup. Do not commit verification-only output or Gradle reports.
