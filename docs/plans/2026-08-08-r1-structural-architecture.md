# R1 Structural Architecture Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove the five top-level core-package cycles, re-enable ArchUnit R1, and preserve the existing runtime behaviour.

**Architecture:** Configuration fallback beans live with the interfaces and lifecycle they provide, instead of in a cross-domain `config` class. The offload SPI receives only its own `OffloadTrigger`. Deployment keeps ownership of its coordinator and receives a small deployment value object from the registry, never `RegisteredFunction`.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, Mockito, AssertJ, ArchUnit, Gradle.

---

### Task 1: Give each fallback bean a domain-owned configuration

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ServiceDefaultsConfiguration.java`
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueDefaultsConfiguration.java`
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/RegistryDefaultsConfiguration.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ServiceDefaultsConfigurationTest.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueDefaultsConfigurationTest.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/RegistryDefaultsConfigurationTest.java`
- Delete: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/CoreDefaults.java`
- Delete: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/CoreDefaultsTest.java`

**Step 1: Write the failing configuration tests**

In each new test, build an `AnnotationConfigApplicationContext`, register only the configuration under test and required collaborators, then assert these contracts:

```java
assertThat(context.getBean(InvocationEnqueuer.class)).isSameAs(InvocationEnqueuer.noOp());
assertThat(context.getBean(ScalingMetricsSource.class)).isSameAs(ScalingMetricsSource.noOp());
assertThat(context.getBean(SyncQueueGateway.class)).isSameAs(SyncQueueGateway.noOp());
assertThatCode(() -> context.getBean(ImageValidator.class).validate(null)).doesNotThrowAnyException();
```

For each `@ConditionalOnMissingBean` fallback, create a second context with an explicit bean and assert that Spring selects that explicit bean. Keep the metrics lifecycle test, but move it to `ServiceDefaultsConfigurationTest`: it verifies that `onRemove` removes the function metrics and `onRegister` permits them to be recorded again.

**Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew :control-plane:test --tests '*ServiceDefaultsConfigurationTest' --tests '*SyncQueueDefaultsConfigurationTest' --tests '*RegistryDefaultsConfigurationTest'
```

Expected: compilation failure because the three configuration classes do not exist.

**Step 3: Implement the smallest domain-owned configurations**

Implement exactly these beans:

```java
// ServiceDefaultsConfiguration
@Bean @ConditionalOnMissingBean(InvocationEnqueuer.class)
InvocationEnqueuer invocationEnqueuer() { return InvocationEnqueuer.noOp(); }

@Bean @ConditionalOnMissingBean(ScalingMetricsSource.class)
ScalingMetricsSource scalingMetricsSource() { return ScalingMetricsSource.noOp(); }

@Bean
FunctionRegistrationListener metricsLifecycleListener(Metrics metrics) { /* existing listener body */ }

// SyncQueueDefaultsConfiguration
@Bean @ConditionalOnMissingBean(SyncQueueGateway.class)
SyncQueueGateway syncQueueGateway() { return SyncQueueGateway.noOp(); }

@Bean @Fallback
SyncQueueRuntimeDefaults syncQueueRuntimeDefaults() { return SyncQueueRuntimeDefaults.defaults(); }

@Bean @Fallback
SyncQueueConfigSource syncQueueConfigSource(SyncQueueRuntimeDefaults defaults) {
    return SyncQueueConfigSource.fixed(defaults);
}

// RegistryDefaultsConfiguration
@Bean @Fallback @ConditionalOnMissingBean(ImageValidator.class)
ImageValidator imageValidator() { return ImageValidator.noOp(); }
```

Delete `CoreDefaults`. Do not replace its `@EnableConfigurationProperties`: `ControlPlaneApplication` already uses `@ConfigurationPropertiesScan`, which discovers `ExecutionStoreProperties`.

**Step 4: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ServiceDefaultsConfiguration.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueDefaultsConfiguration.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/RegistryDefaultsConfiguration.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ServiceDefaultsConfigurationTest.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueDefaultsConfigurationTest.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/RegistryDefaultsConfigurationTest.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/CoreDefaults.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/CoreDefaultsTest.java
git commit -m "Separate core fallback configurations"
```

### Task 2: Make the offload SPI independent of sync-queue reasons

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/offload/OffloadGateway.java:29`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/offload/NoOpOffloadGateway.java:27`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java:121`
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java:64`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinatorOffloadTest.java`
- Modify: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGatewayTest.java`

**Step 1: Change the tests to the desired SPI**

Replace pressure-policy stubs and verifications with the reason-free method:

```java
when(offloadGateway.shouldOffloadOnPressure(spec)).thenReturn(true);
assertThat(gateway.shouldOffloadOnPressure(noPolicy)).isTrue();
```

Keep the existing assertions that only `DEPTH` and `EST_WAIT` lead to offload; those are responsibilities of `ReactiveInvocationCoordinator.pressureTrigger`, not the gateway.

**Step 2: Run the focused tests to verify compilation fails**

Run:

```bash
./gradlew :control-plane:test :control-plane-modules:offload:test --tests '*ReactiveInvocationCoordinatorOffloadTest' --tests '*DefaultOffloadGatewayTest'
```

Expected: compilation failure because `shouldOffloadOnPressure(FunctionSpec)` does not exist.

**Step 3: Remove the unused boundary parameter**

Change the SPI and both implementations to:

```java
boolean shouldOffloadOnPressure(FunctionSpec spec);
```

Pass no queue reason from `ReactiveInvocationCoordinator`; retain its current conversion from `SyncQueueRejectReason` to `OffloadTrigger` and pass that trigger to `invokeRemote`. Remove `SyncQueueRejectReason` imports from the offload SPI, no-op implementation, and optional offload module.

**Step 4: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/offload/OffloadGateway.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/offload/NoOpOffloadGateway.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinatorOffloadTest.java platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGatewayTest.java
git commit -m "Decouple offload SPI from sync reasons"
```

### Task 3: Give deployment a registry-independent target value

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentTarget.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinator.java:3-47`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java:29-204`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinatorTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java`

**Step 1: Write the failing deployment-boundary test**

Construct the coordinator with a mocked provider and a target:

```java
ManagedDeploymentTarget target = new ManagedDeploymentTarget("fn", "k8s");
coordinator.setReplicas(target, 3);
verify(provider).setReplicas("fn", 3);
```

Cover `getReadyReplicas`, `getReplicaStatus`, and `deprovision` with the same target. Assert that a blank backend is rejected in the target constructor; this protects the boundary currently enforced by `isManagedDeployment`.

In `FunctionServiceManagedDeploymentTest`, retain the observed behaviours: successful removal deprovisions the recorded backend, and a registration-listener failure triggers deprovision and leaves no function registered.

**Step 2: Run the focused tests to verify they fail**

Run:

```bash
./gradlew :control-plane:test --tests '*ManagedDeploymentCoordinatorTest' --tests '*FunctionServiceManagedDeploymentTest'
```

Expected: compilation failure because `ManagedDeploymentTarget` and the target-based methods do not exist.

**Step 3: Implement the deployment-owned boundary**

Create the minimal validated record:

```java
public record ManagedDeploymentTarget(String functionName, String backendId) {
    public ManagedDeploymentTarget {
        if (functionName == null || functionName.isBlank() || backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("functionName and backendId are required");
        }
    }
}
```

Make `ManagedDeploymentCoordinator` accept only `ManagedDeploymentTarget` and call `deploymentProviderResolver.requireBackend(target.backendId())`. It must no longer import `RegisteredFunction`, `DeploymentMetadata`, or `ExecutionMode`.

In `FunctionService`, add one private conversion method that returns `Optional<ManagedDeploymentTarget>` only when the registered function has effective `DEPLOYMENT` mode and a nonblank backend. Use that value for replica operations, deletion, and rollback. Preserve the current public exceptions for non-deployment functions and the existing rollback semantics.

**Step 4: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentTarget.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinator.java platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinatorTest.java platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java
git commit -m "Decouple deployment coordinator from registry"
```

### Task 4: Enforce R1

**Files:**
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureTest.java:20-31`

**Step 1: Remove the suppression**

Delete `@ArchIgnore` and its obsolete list of five cycles. Keep the R1 description and rule unchanged:

```java
@ArchTest
static final ArchRule core_packages_are_free_of_cycles =
        slices().matching("..controlplane.(*)..").should().beFreeOfCycles();
```

**Step 2: Run the architecture test**

Run:

```bash
./gradlew :control-plane:test --tests '*CoreArchitectureTest'
```

Expected: PASS; ArchUnit reports no cycles among top-level core packages.

**Step 3: Commit**

```bash
git add platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureTest.java
git commit -m "Enforce acyclic core architecture"
```

### Task 5: Verify scope and the integrated result

**Files:**
- Modify only files listed in Tasks 1-4.

**Step 1: Perform required impact checks before each code edit**

For every class or method about to change, run GitNexus upstream impact analysis. Specifically include `CoreDefaults`, `OffloadGateway`, `NoOpOffloadGateway`, `ReactiveInvocationCoordinator`, `DefaultOffloadGateway`, `ManagedDeploymentCoordinator`, and `FunctionService`. Resolve all direct callers before editing; stop and report if any result is HIGH or CRITICAL.

**Step 2: Run the full Java test suite**

Run:

```bash
./gradlew test
```

Expected: PASS.

**Step 3: Inspect change scope**

Run GitNexus change detection before the final commit/merge. Confirm that only the expected default-configuration, offload-SPI, deployment-boundary, test, and R1 architecture flows changed.

**Step 4: Final status**

Report the exact passing commands and that R1 is active (no `@ArchIgnore`). Do not add adapters, compatibility overloads, or new modules: all callers are in this repository and must use the new boundaries directly.
