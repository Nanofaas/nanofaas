# Runtime Config PATCH Serialization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prevent concurrent runtime-config PATCH operations from interleaving snapshot updates, side effects, and rollback.

**Architecture:** Serialize the existing PATCH method on the singleton controller. Keep compare-and-set revision checks, validation, application, and rollback behavior unchanged.

**Tech Stack:** Java 21, Spring MVC, JUnit 5, AssertJ, `java.util.concurrent`.

---

### Task 1: Reproduce the interleaving

**Files:**
- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigControllerTest.java`

1. Add a test applier that blocks and fails its first call, then delegates later calls normally.
2. Start the first PATCH and wait until its apply phase is blocked.
3. Start a second PATCH using the original revision and assert it cannot complete before the first is released.
4. Release the first apply, then assert the first response is `503`, the second is `200`, and snapshot plus rate limiter contain only the second value.
5. Run `./gradlew :control-plane-modules:runtime-config:test --tests '*AdminRuntimeConfigControllerTest.concurrentPatchesSerializeApplyAndRollback' -PcontrolPlaneModules=all` and verify RED because the second request currently returns `409` before the first rollback.

### Task 2: Serialize PATCH

**Files:**
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigController.java`

1. Declare `patch(PatchRequest request)` as `synchronized` so the existing transaction is the critical section.
2. Run the focused concurrency test and verify GREEN.
3. Run `./gradlew :control-plane-modules:runtime-config:test -PcontrolPlaneModules=all --rerun-tasks`.

### Task 3: Verify and commit

1. Run the relevant control-plane and runtime-config suites.
2. Run GitNexus change detection and verify only the runtime-config PATCH path is affected.
3. Run `git diff --check` and inspect the final diff.
4. Commit the design, plan, test, and one-line production change.
