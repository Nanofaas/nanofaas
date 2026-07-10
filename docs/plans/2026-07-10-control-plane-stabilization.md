# Control Plane Stabilization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task.

**Goal:** Fix the four confirmed control-plane defects around empty HTTP responses, terminal queued work, retry semantics, and Prometheus sync-queue metrics.

**Architecture:** Keep the existing dispatch and queue architecture. Add the smallest guard or fallback at each shared boundary, then lock the behavior with focused regression tests. Do not add dependencies or refactor unrelated collaborators.

**Tech Stack:** Java 21, Spring WebFlux, Reactor, Micrometer Prometheus, JUnit 5, Mockito, MockWebServer.

---

### Task 1: Empty successful pool responses

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/PoolDispatcher.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/PoolDispatcherTest.java`

1. Add a MockWebServer regression test returning HTTP 204 and assert that dispatch completes with a successful result whose output is null.
2. Run the single test and verify it fails because the future yields null.
3. Add `defaultIfEmpty` to the successful response body pipeline so every 2xx response produces a `DispatchResult`.
4. Run `PoolDispatcherTest` and the control-plane dispatch tests.

### Task 2: Do not execute terminal queued records

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandlerTest.java`

1. Add a test that stores a timed-out record, calls `dispatch`, and asserts no dispatcher is invoked while the acquired slot is released once.
2. Run the test and verify it fails because dispatch currently proceeds.
3. Add an early terminal-state guard in the central dispatch method.
4. Run completion-handler and invocation-service tests.

### Task 3: Make maxRetries count retries

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`
- Modify tests that currently encode three total attempts for `maxRetries=3`.

1. Change the regression test to require attempts 1 through 4 when `maxRetries=3`.
2. Run it and verify the future currently completes after attempt 3.
3. Change the retry predicate to permit `attempt <= maxRetries`.
4. Run all retry and completion-handler tests.

### Task 4: Prometheus-compatible sync queue metrics

**Files:**
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueMetrics.java`
- Test: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueMetricsTest.java`

1. Add a test using `PrometheusMeterRegistry` that registers a function and asserts both global and per-function depth/wait meters exist.
2. Run it and verify per-function registration fails due to inconsistent tag keys.
3. Give the global series the same `function` tag using a reserved value such as `__all__`.
4. Update affected assertions and run all sync-queue tests.

### Task 5: Full verification

1. Run all control-plane and optional-module tests with `-PcontrolPlaneModules=all`.
2. Run GitNexus change detection and verify only the expected symbols and flows changed.
3. Inspect `git diff --check`, `git status`, and the final diff.
