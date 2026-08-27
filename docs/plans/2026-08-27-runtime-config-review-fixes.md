# Runtime Config Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix revision rollback, restore the established runtime-config metrics, and reject numeric configuration values that cannot be represented exactly as positive Java `int` values.

**Architecture:** Keep validation owned by each namespace extension and keep `sync-queue` weakly coupled through `compileOnly`. Make `RuntimeConfigService.update` transactional by applying and materializing the complete snapshot before committing the revision; use that same revision holder as the Micrometer gauge source and time only live apply attempts.

**Tech Stack:** Java 25, Spring Boot 4, Micrometer, Gradle, JUnit 5, AssertJ.

---

### Task 1: Reject lossy numeric configuration values

**Files:**
- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtensionTest.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtension.java`
- Modify: `platform/modules/sync-queue/build.gradle`
- Create: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueRuntimeConfigAutoConfigurationTest.java`
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueRuntimeConfigAutoConfiguration.java`

**Step 1: Add failing boundary tests for `control-plane`**

Extend `ControlPlaneRuntimeConfigExtensionTest` with a parameterized or compact loop-based test that rejects all values that cannot be narrowed exactly to a positive `int`:

```java
@Test
void rejectsRateLimitsThatAreNotPositiveExactInts() {
    RateLimiter limiter = new RateLimiter();
    ControlPlaneRuntimeConfigExtension extension = new ControlPlaneRuntimeConfigExtension(limiter);

    for (Number value : List.of(0, -1, 1.5, 2147483648L)) {
        assertThat(extension.validate(Map.of("rateMaxPerSecond", value))).isNotEmpty();
    }
    assertThat(extension.validate(Map.of("rateMaxPerSecond", Integer.MAX_VALUE))).isEmpty();
}
```

Add `java.util.List` if the test does not already import it.

**Step 2: Run the focused test and verify the overflow case fails**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*ControlPlaneRuntimeConfigExtensionTest' \
  -PcontrolPlaneModules=none
```

Expected: FAIL because `2147483648L` is currently accepted.

**Step 3: Implement exact positive-int validation in the control-plane extension**

Add one private helper to `ControlPlaneRuntimeConfigExtension`; do not create a shared numeric utility for two call sites:

```java
private static boolean isPositiveInt(Object value) {
    if (!(value instanceof Number number)) {
        return false;
    }
    try {
        return new BigDecimal(number.toString()).intValueExact() > 0;
    } catch (ArithmeticException | NumberFormatException ignored) {
        return false;
    }
}
```

Replace the current `longValue`/`doubleValue` condition with `!isPositiveInt(value)`. Import `java.math.BigDecimal`. Keep `apply` unchanged: successful validation now proves that `intValue()` is lossless and in range.

**Step 4: Run the focused control-plane extension test**

Run the command from Step 2.

Expected: PASS.

**Step 5: Add the runtime-config SPI to sync-queue's test runtime**

Keep the production dependency unchanged:

```groovy
compileOnly project(':control-plane-modules:runtime-config')
```

Add only:

```groovy
testImplementation project(':control-plane-modules:runtime-config')
```

This permits direct unit testing of the conditional bridge without putting `runtime-config` in the produced sync-only application.

**Step 6: Add a failing sync-queue boundary test**

Create `SyncQueueRuntimeConfigAutoConfigurationTest` in the same package as the auto-configuration. Construct `SyncQueueProperties`, `MutableSyncQueueConfigSource`, and the extension directly; no Spring context is needed.

```java
@Test
void retryAfterSecondsMustBeAPositiveExactInt() {
    SyncQueueProperties properties = new SyncQueueProperties(
            true, true, 100, Duration.ofSeconds(1), Duration.ofSeconds(2),
            2, Duration.ofSeconds(30), 50);
    MutableSyncQueueConfigSource source = new MutableSyncQueueConfigSource(properties);
    RuntimeConfigExtension extension = new SyncQueueRuntimeConfigAutoConfiguration()
            .syncQueueRuntimeConfigExtension(source);

    assertThat(extension.validate(Map.of("retryAfterSeconds", 1.5))).isNotEmpty();
    assertThat(extension.validate(Map.of("retryAfterSeconds", 2147483648L))).isNotEmpty();
    assertThat(extension.validate(Map.of("retryAfterSeconds", Integer.MAX_VALUE))).isEmpty();
}
```

**Step 7: Run the sync-queue test and verify it fails**

Run:

```bash
./gradlew :control-plane-modules:sync-queue:test \
  --tests '*SyncQueueRuntimeConfigAutoConfigurationTest' \
  -PcontrolPlaneModules=none
```

Expected: FAIL because fractional and oversized numbers are currently accepted through `Number.intValue()`.

**Step 8: Reuse the same exact conversion rule locally in sync-queue**

Add the same private `isPositiveInt(Object)` helper to `SyncQueueRuntimeConfigAutoConfiguration` and replace:

```java
!(candidate.get("retryAfterSeconds") instanceof Number number)
        || number.intValue() < 1
```

with:

```java
!isPositiveInt(candidate.get("retryAfterSeconds"))
```

Do not change `MutableSyncQueueConfigSource.restore`; the extension remains the API trust boundary, while restore continues to consume already-validated snapshots.

**Step 9: Run both numeric-validation test classes**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*ControlPlaneRuntimeConfigExtensionTest' \
  -PcontrolPlaneModules=none
./gradlew :control-plane-modules:sync-queue:test \
  --tests '*SyncQueueRuntimeConfigAutoConfigurationTest' \
  -PcontrolPlaneModules=none
```

Expected: PASS.

**Step 10: Commit the numeric boundary fix**

Before editing, run GitNexus impact for `ControlPlaneRuntimeConfigExtension` and `SyncQueueRuntimeConfigAutoConfiguration`; before committing, run `gitnexus_detect_changes(scope: "all")`.

```bash
git add platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtension.java \
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/ControlPlaneRuntimeConfigExtensionTest.java \
  platform/modules/sync-queue/build.gradle \
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueRuntimeConfigAutoConfiguration.java \
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueRuntimeConfigAutoConfigurationTest.java
git commit -m "Validate runtime config integer bounds"
```

### Task 2: Commit revision only after snapshot materialization

**Files:**
- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigService.java`

**Step 1: Add a failing snapshot-failure rollback test**

Extend the test extension with a `failSnapshotAfterApply` flag. When enabled, `apply` updates the value and causes the next `snapshot()` call to throw; `restore` must reset the flag so the post-failure snapshot can be read.

Add this test:

```java
@Test
void failedSnapshotRestoresStateWithoutAdvancingRevision() {
    TestExtension extension = new TestExtension("queue", 1);
    extension.failSnapshotAfterApply = true;
    RuntimeConfigService service = service(extension);

    assertThatThrownBy(() -> service.update(0, "queue", Map.of("value", 2)))
            .isInstanceOf(RuntimeConfigApplyException.class);

    assertThat(extension.value).isEqualTo(1);
    assertThat(service.getSnapshot().revision()).isZero();
}
```

**Step 2: Run the focused service test and verify it fails**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*RuntimeConfigServiceTest.failedSnapshotRestoresStateWithoutAdvancingRevision' \
  -PcontrolPlaneModules=none
```

Expected: FAIL because the current code increments `revision` before calling `getSnapshot()`.

**Step 3: Move the revision commit after successful snapshot materialization**

In `RuntimeConfigService.update`, capture the current revision once while holding the existing synchronized lock. After `extension.apply`, construct the return value directly with the candidate revision and the complete registry snapshot:

```java
long currentRevision = revision;
// stale check, extension lookup, previous snapshot and validation remain unchanged

RuntimeConfigSnapshot updated;
try {
    extension.apply(Map.copyOf(patch));
    updated = new RuntimeConfigSnapshot(currentRevision + 1, registry.snapshot());
} catch (Exception applyFailure) {
    // existing restore and failure wrapping
}
revision++;
return updated;
```

The revision must not change inside the guarded apply/snapshot block. Because `update` is synchronized, no competing update can commit between snapshot construction and the subsequent revision increment.

**Step 4: Run all service tests**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test \
  --tests '*RuntimeConfigServiceTest' \
  -PcontrolPlaneModules=none
```

Expected: PASS, including stale revision, apply rollback, and snapshot rollback cases.

### Task 3: Restore the established Micrometer gauge and timer

**Files:**
- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java`
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigService.java`

**Step 1: Add failing metric compatibility tests**

Use a retained `SimpleMeterRegistry` instead of the current helper that discards it. Add assertions that:

```java
assertThat(registry.get("controlplane_runtime_config_revision").gauge().value()).isZero();

service.update(0, "queue", Map.of("value", 2));

assertThat(registry.get("controlplane_runtime_config_revision").gauge().value()).isEqualTo(1.0);
assertThat(registry.get("controlplane_runtime_config_apply_duration_seconds").timer().count()).isEqualTo(1);
assertThat(registry.get("controlplane_runtime_config_updates_total")
        .tags("status", "success", "namespace", "queue").counter().count()).isEqualTo(1.0);
```

In the snapshot-failure test, retain the registry and also assert:

```java
assertThat(registry.get("controlplane_runtime_config_revision").gauge().value()).isZero();
assertThat(registry.get("controlplane_runtime_config_apply_duration_seconds").timer().count()).isEqualTo(1);
assertThat(registry.get("controlplane_runtime_config_updates_total")
        .tags("status", "failure", "namespace", "queue").counter().count()).isEqualTo(1.0);
```

**Step 2: Run the service tests and verify missing meters fail**

Run the Task 2 Step 4 command.

Expected: FAIL with `MeterNotFoundException` for the revision gauge and apply timer.

**Step 3: Register the gauge and timer in `RuntimeConfigService`**

Replace the primitive revision field with the gauge's single source of truth:

```java
private final AtomicLong revision = new AtomicLong();
private final Timer applyTimer;
```

Register the established metric names in the constructor:

```java
Gauge.builder("controlplane_runtime_config_revision", revision, AtomicLong::get)
        .register(meterRegistry);
this.applyTimer = Timer.builder("controlplane_runtime_config_apply_duration_seconds")
        .register(meterRegistry);
```

Update reads/checks to use `revision.get()`. Keep the existing synchronized methods; the atomic is for safe gauge observation and as a single revision source, not a replacement for the update lock.

**Step 4: Time every live apply attempt**

Start a `Timer.Sample` immediately before `extension.apply` and stop it in `finally`, so successful applies, apply failures, and post-apply snapshot failures are all measured, while stale and validation failures are not:

```java
Timer.Sample sample = Timer.start(meterRegistry);
try {
    extension.apply(Map.copyOf(patch));
    updated = new RuntimeConfigSnapshot(currentRevision + 1, registry.snapshot());
} catch (Exception applyFailure) {
    // restore, failure counter, wrap
} finally {
    sample.stop(applyTimer);
}
```

After the guarded block succeeds, increment the revision, increment the existing namespaced success counter, and return the already-materialized snapshot. Do not reintroduce `RuntimeConfigApplier`.

**Step 5: Run the runtime-config module tests**

Run:

```bash
./gradlew :control-plane-modules:runtime-config:test -PcontrolPlaneModules=none
```

Expected: PASS.

**Step 6: Commit transactional revision and metric compatibility together**

These changes belong in one commit because the gauge must observe the same commit point as the API revision. Before editing, run GitNexus impact for `RuntimeConfigService`; before committing, run `gitnexus_detect_changes(scope: "all")`.

```bash
git add platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigService.java \
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java
git commit -m "Preserve runtime config revision metrics"
```

### Task 4: Verify strong, weak, and combined module modes

**Files:**
- No production files expected.

**Step 1: Run both changed module suites**

```bash
./gradlew :control-plane-modules:runtime-config:test \
  :control-plane-modules:sync-queue:test \
  -PcontrolPlaneModules=none
```

Expected: PASS.

**Step 2: Run the combined control-plane suite**

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=sync-queue,runtime-config
```

Expected: PASS.

**Step 3: Verify the weak sync-only artifact still excludes runtime-config**

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue
jar tf platform/control-plane/build/libs/control-plane-*.jar | rg 'BOOT-INF/lib/runtime-config'
```

Expected: `bootJar` succeeds and `rg` returns no matches.

**Step 4: Run final repository checks**

```bash
git diff --check
```

Run `gitnexus_detect_changes(scope: "compare", base_ref: "feature/issue-204-auto-configuration")` and confirm only runtime-config/sync-queue validation, metrics, tests, and this plan are affected. Resolve every d=1 dependency before completion.

No OpenAPI or user documentation update is required: the endpoint shape and documented configuration keys do not change; this patch only tightens invalid numeric inputs and restores existing metrics.
