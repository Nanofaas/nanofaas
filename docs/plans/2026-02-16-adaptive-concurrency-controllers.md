# Adaptive Concurrency Controllers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add function-specific concurrency controllers that can run in two modes: static per-pod target and adaptive per-pod target, while preserving backward compatibility with existing fixed `concurrency`.

**Architecture:** Keep replica scaling in `InternalScaler` and add a sibling concurrency-control phase in the same loop. Compute an effective per-function queue concurrency and push it into `FunctionQueueState`. Support two policies: `STATIC_PER_POD` (deterministic) and `ADAPTIVE_PER_POD` (feedback-based with cooldown and hysteresis). Keep existing behavior as default when no policy is configured.

**Tech Stack:** Java 21, Spring Boot, Micrometer, JUnit 5, existing control-plane scaling/queue modules.

---

### Task 1: Add Domain Model for Concurrency Control

**Files:**
- Create: `common/src/main/java/it/unimib/datai/nanofaas/common/model/ConcurrencyControlMode.java`
- Create: `common/src/main/java/it/unimib/datai/nanofaas/common/model/ConcurrencyControlConfig.java`
- Modify: `common/src/main/java/it/unimib/datai/nanofaas/common/model/ScalingConfig.java`
- Modify: `common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java`

**Step 1: Write the failing tests**

Add tests in `CommonModelTest` for:
- enum values: `FIXED`, `STATIC_PER_POD`, `ADAPTIVE_PER_POD`
- `ScalingConfig` exposing `concurrencyControl()` field
- `ConcurrencyControlConfig` record accessors and null-safe defaults behavior at resolver layer (assert only model accessors here)

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :common:test --tests "*CommonModelTest"
```
Expected: FAIL due to missing classes/fields.

**Step 3: Write minimal implementation**

Implement:
- `ConcurrencyControlMode` enum
- `ConcurrencyControlConfig` record fields:
  - `ConcurrencyControlMode mode`
  - `Integer targetInFlightPerPod`
  - `Integer minTargetInFlightPerPod`
  - `Integer maxTargetInFlightPerPod`
  - `Long upscaleCooldownMs`
  - `Long downscaleCooldownMs`
  - `Double highLoadThreshold`
  - `Double lowLoadThreshold`
- add `ConcurrencyControlConfig concurrencyControl` to `ScalingConfig`

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :common:test --tests "*CommonModelTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add common/src/main/java/it/unimib/datai/nanofaas/common/model/ConcurrencyControlMode.java common/src/main/java/it/unimib/datai/nanofaas/common/model/ConcurrencyControlConfig.java common/src/main/java/it/unimib/datai/nanofaas/common/model/ScalingConfig.java common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java
git commit -m "Add concurrency control domain model"
```

### Task 2: Resolve Defaults and Preserve Backward Compatibility

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolver.java`
- Modify: `control-plane/src/main/resources/application.yml`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingProperties.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolverTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingPropertiesTest.java`

**Step 1: Write the failing tests**

In `FunctionSpecResolverTest` add cases:
- when `concurrencyControl` is null, behavior remains fixed (no dynamic override requested)
- when `mode=STATIC_PER_POD` and missing `targetInFlightPerPod`, resolver fills from default property
- clamp invalid min/max target values into valid range

In `ScalingPropertiesTest` add defaults for new knobs:
- `defaultTargetInFlightPerPod` (e.g. 2)
- cooldown defaults
- high/low load thresholds

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :control-plane:test --tests "*FunctionSpecResolverTest" --tests "*ScalingPropertiesTest"
```
Expected: FAIL.

**Step 3: Write minimal implementation**

Extend `ScalingProperties` with:
- `Integer defaultTargetInFlightPerPod`
- `Long concurrencyUpscaleCooldownMs`
- `Long concurrencyDownscaleCooldownMs`
- `Double concurrencyHighLoadThreshold`
- `Double concurrencyLowLoadThreshold`

Extend resolver logic:
- normalize `concurrencyControl` defaults only for `DEPLOYMENT + INTERNAL`
- keep existing fixed `concurrency` semantics untouched when no mode is set

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :control-plane:test --tests "*FunctionSpecResolverTest" --tests "*ScalingPropertiesTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolver.java control-plane/src/main/resources/application.yml control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingProperties.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolverTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingPropertiesTest.java
git commit -m "Add resolver defaults for concurrency control"
```

### Task 3: Implement Static Per-Pod Concurrency Controller

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ConcurrencyController.java`
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/StaticPerPodConcurrencyController.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/FunctionQueueState.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManager.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/StaticPerPodConcurrencyControllerTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/FunctionQueueStateTest.java`

**Step 1: Write the failing tests**

Add tests for static policy:
- `effectiveConcurrency = readyReplicas * targetInFlightPerPod`
- clamp to at least 1 and at most configured fixed `concurrency` (safety cap)
- updating effective concurrency at runtime does not violate in-flight accounting

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :control-plane:test --tests "*StaticPerPodConcurrencyControllerTest" --tests "*FunctionQueueStateTest"
```
Expected: FAIL.

**Step 3: Write minimal implementation**

Implement:
- strategy interface returning computed effective concurrency
- static controller formula and clamps
- `FunctionQueueState` split:
  - `configuredConcurrency` (fixed cap)
  - `effectiveConcurrency` (mutable runtime limit)
  - `tryAcquireSlot()` should use `effectiveConcurrency`

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :control-plane:test --tests "*StaticPerPodConcurrencyControllerTest" --tests "*FunctionQueueStateTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ConcurrencyController.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/StaticPerPodConcurrencyController.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/FunctionQueueState.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManager.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/StaticPerPodConcurrencyControllerTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/FunctionQueueStateTest.java
git commit -m "Add static per-pod concurrency controller"
```

### Task 4: Implement Adaptive Per-Pod Concurrency Controller

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptivePerPodConcurrencyController.java`
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptiveConcurrencyState.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingMetricsReader.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScaler.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptivePerPodConcurrencyControllerTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScalerTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScalerBranchTest.java`

**Step 1: Write the failing tests**

Add tests covering:
- high load while at max replicas -> target per pod decreases
- low load -> scaler should reduce replicas first; only after stable low-load window increase target per pod
- cooldown/hysteresis prevents oscillation
- state is per-function (changes on fnA do not affect fnB)

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :control-plane:test --tests "*AdaptivePerPodConcurrencyControllerTest" --tests "*InternalScalerTest" --tests "*InternalScalerBranchTest"
```
Expected: FAIL.

**Step 3: Write minimal implementation**

Implement adaptive logic:
- maintain per-function state map
- compute load index using queue/in-flight/dispatch-rate trends available in `ScalingMetricsReader`
- decision order:
  - if overloaded and replicas already maxed -> decrement target per pod
  - if underloaded -> keep target stable while scaling down replicas
  - after downscale cooldown + stable low load -> increment target per pod
- always clamp by:
  - `minTargetInFlightPerPod..maxTargetInFlightPerPod`
  - fixed `concurrency` cap

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :control-plane:test --tests "*AdaptivePerPodConcurrencyControllerTest" --tests "*InternalScalerTest" --tests "*InternalScalerBranchTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptivePerPodConcurrencyController.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptiveConcurrencyState.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/ScalingMetricsReader.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScaler.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/AdaptivePerPodConcurrencyControllerTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScalerTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/scaling/InternalScalerBranchTest.java
git commit -m "Add adaptive per-pod concurrency controller"
```

### Task 5: Expose Effective Concurrency and Controller State as Metrics

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ConcurrencyControlMetrics.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManager.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManagerTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManagerGaugeCleanupTest.java`

**Step 1: Write the failing tests**

Add tests for gauges:
- `function_effective_concurrency{function}`
- `function_target_inflight_per_pod{function}`
- `function_concurrency_controller_mode{function,mode}`
- cleanup on function delete

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :control-plane:test --tests "*QueueManagerTest" --tests "*QueueManagerGaugeCleanupTest"
```
Expected: FAIL.

**Step 3: Write minimal implementation**

Register/update/remove gauges with function lifecycle and dynamic updates from scaler/controller.

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :control-plane:test --tests "*QueueManagerTest" --tests "*QueueManagerGaugeCleanupTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ConcurrencyControlMetrics.java control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManager.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManagerTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/queue/QueueManagerGaugeCleanupTest.java
git commit -m "Expose dynamic concurrency control metrics"
```

### Task 6: API and Documentation Updates

**Files:**
- Modify: `openapi.yaml`
- Modify: `docs/control-plane.md`
- Modify: `docs/architecture.md`
- Modify: `docs/function-pod-architecture.md`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionControllerTest.java`

**Step 1: Write the failing tests**

Add API test payloads that include `scalingConfig.concurrencyControl` and verify round-trip persistence.

**Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :control-plane:test --tests "*FunctionControllerTest"
```
Expected: FAIL.

**Step 3: Write minimal implementation**

Update OpenAPI and docs with:
- new object `concurrencyControl`
- mode semantics
- defaults and backward compatibility rules
- operational guidance for adaptive mode

**Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :control-plane:test --tests "*FunctionControllerTest"
```
Expected: PASS.

**Step 5: Commit**

```bash
git add openapi.yaml docs/control-plane.md docs/architecture.md docs/function-pod-architecture.md control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionControllerTest.java
git commit -m "Document concurrency controller modes in API and docs"
```

### Task 7: Full Verification and Regression Pass

**Files:**
- Modify: none (verification only)

**Step 1: Run focused suites**

```bash
./gradlew :common:test :control-plane:test --tests "*InternalScaler*" --tests "*Scaling*" --tests "*Queue*" --tests "*FunctionSpecResolverTest" --tests "*FunctionControllerTest"
```

**Step 2: Run full module tests**

```bash
./gradlew :common:test :control-plane:test
```

**Step 3: Manual sanity checks**

Run local scenario:
- function A with `STATIC_PER_POD` and 3 replicas -> check effective concurrency = `3 * target`
- function B with `ADAPTIVE_PER_POD` + maxed replicas + high queue depth -> verify target decreases
- low load interval -> verify replicas drop before target increases

**Step 4: Commit verification notes**

```bash
git commit --allow-empty -m "chore: verify adaptive concurrency controller test matrix"
```

