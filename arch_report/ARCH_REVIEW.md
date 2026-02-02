# Architectural Review: nanofaas

**Date:** 2026-01-25
**Reviewer:** Principal Software Architect (Gemini)
**Scope:** `control-plane`, `function-runtime`, `common`

## 1. Executive Summary

1.  **Architecture Violation:** Critical duplication of `InvocationResult` between `control-plane` and `common`.
2.  **Organization:** The `control-plane` module suffers from a "Dumping Ground" anti-pattern in the `com.nanofaas.controlplane.core` package (27 mixed-concern files).
3.  **Encapsulation:** `QueueManager` leaks internal state (`FunctionQueueState` collections) to the `Scheduler`, creating tight coupling.
4.  **Complexity:** `Scheduler` manages thread lifecycle, looping logic, and business dispatch logic simultaneously.
5.  **Configuration:** Inconsistent placement of configuration classes (some in `config`, some in `core`).
6.  **Dispatch Logic:** `KubernetesDispatcher` mixes infrastructure calls with domain result creation.
7.  **Testing:** Native AOT tests are failing due to unsupported Mock definitions, though standard JVM tests pass.
8.  **Dependencies:** `control-plane` has a healthy dependency on `common`, but fails to use it consistently.
9.  **Resilience:** `CallbackClient` implements retries but constructs URLs via string manipulation, which is error-prone.
10. **Observability:** Metrics are tightly coupled to logic classes (e.g., `QueueManager` registering its own Gauges).

## 2. Current Architecture Map

### Modules
*   **`common`**: Shared DTOs (`InvocationRequest`, `FunctionSpec`) and interfaces.
*   **`control-plane`**:
    *   **Entry Point**: `ControlPlaneApplication` (WebFlux).
    *   **Core**: `com.nanofaas.controlplane.core` (The "Bucket"). Contains Registry, Queue, Scheduler, Dispatchers, Services.
    *   **API**: `FunctionController` (Management), `InvocationController` (Traffic).
*   **`function-runtime`**:
    *   **Entry Point**: `FunctionRuntimeApplication` (WebMvc).
    *   **Core**: `CallbackClient`, `HandlerRegistry`.
    *   **API**: `InvokeController`.

### Key Flows
1.  **Sync Invoke**: `InvocationController` -> `InvocationService` -> `QueueManager` -> `Scheduler` -> `Dispatcher` -> `Job` -> `Runtime` -> `Callback`.
2.  **Registry**: `FunctionController` -> `FunctionService` -> `FunctionRegistry`.

## 3. Evidence Tables

### 3.1 Duplication Candidates
| Concept | Locations | Finding | Recommendation |
| :--- | :--- | :--- | :--- |
| `InvocationResult` | `control-plane/core/InvocationResult.java`<br>`common/model/InvocationResult.java` | **Exact Duplicate.** | **Merge:** Delete `control-plane` version, use `common`. |

### 3.2 Multi-Concern Hotspots
| Class | Concerns | Proposal |
| :--- | :--- | :--- |
| `Scheduler` | Thread mgmt, Polling loop, Dispatch routing, Error handling | Extract `PollingStrategy` or `Dispatcher` logic. |
| `QueueManager` | State holding, Metrics registration, Concurrency control | Separate Metrics from Logic. Hide `states()` map. |
| `KubernetesDispatcher` | Fabric8 API calls, Result Mapping, Async chaining | Use an `Adapter` pattern for K8s interaction. |

### 3.3 Dependency Hygiene
*   **Good**: `common` is used by both service modules.
*   **Bad**: `control-plane` re-declares `InvocationResult` effectively ignoring the one in `common`.

## 4. Proposed Target Architecture

We will adopt a **Package-by-Feature** (or Component) structure within the `control-plane` to eliminate the `core` dumping ground.

### Boundaries & Rules
1.  **`common`**: Pure DTOs. No Spring dependencies (keep it lightweight).
2.  **`control-plane`**:
    *   `com.nanofaas.controlplane.registry`: `FunctionRegistry`, `FunctionService`.
    *   `com.nanofaas.controlplane.queue`: `QueueManager`, `FunctionQueueState`.
    *   `com.nanofaas.controlplane.scheduler`: `Scheduler`, `InvocationTask`.
    *   `com.nanofaas.controlplane.dispatch`: `Dispatcher`, `KubernetesDispatcher`, `LocalDispatcher`, `PoolDispatcher`.
    *   `com.nanofaas.controlplane.web`: Controllers.
    *   `com.nanofaas.controlplane.config`: All `@Configuration` and `@ConfigurationProperties`.

## 5. Refactoring Roadmap

### Stage 0: Safety Net (Done)
*   Ensure `./gradlew test` passes (Confirmed).

### Stage 1: Structural Cleanup (Low Risk)
*   **Refactor 1:** Delete `control-plane/.../InvocationResult.java` and fix imports.
*   **Refactor 2:** Move configuration classes from `core` to `config`.
*   **Refactor 3:** Explode `core` package into `registry`, `queue`, `scheduler`, `dispatch`.

### Stage 2: Consolidation (Medium Risk)
*   **Refactor 4:** Encapsulate `QueueManager`. Remove `states()` accessor. Add `process(Consumer<InvocationTask>)`.
*   **Refactor 5:** Simplify `Scheduler`.

## 6. Issue Backlog Summary
*   **ISSUE-01**: Remove duplicate `InvocationResult`.
*   **ISSUE-02**: Decompose `control-plane.core` package.
*   **ISSUE-03**: Fix `QueueManager` encapsulation leak.
*   **ISSUE-04**: Simplify `Scheduler` responsibilities.
*   **ISSUE-05**: Centralize configuration.
*   **ISSUE-06**: Decouple `KubernetesDispatcher` from API logic.

## 7. "Do NOT do yet"
*   **Switching to a real Database**: The project is designed as in-memory MVP. Introducing a DB now would complicate the refactor.
*   **Microservices Split**: Do not split `control-plane` further. It is small enough to be a modular monolith.
