# ISSUE-002-decompose-core-package

**Title:** Decompose control-plane.core package
**Severity:** Medium
**Impacted Modules:** `control-plane`

## Evidence
- `control-plane/src/main/java/com/nanofaas/controlplane/core/` contains 27 files.
- Mixes Queue logic, Scheduling, Dispatching, K8s configs, and Domain services.

## Proposed Solution
Move classes into semantic packages:
- `com.nanofaas.controlplane.registry`: `FunctionRegistry`, `FunctionService`, `FunctionSpecResolver`.
- `com.nanofaas.controlplane.queue`: `QueueManager`, `FunctionQueueState`, `QueueFullException`.
- `com.nanofaas.controlplane.scheduler`: `Scheduler`, `InvocationTask`.
- `com.nanofaas.controlplane.dispatch`: `Dispatcher`, `DispatcherRouter`, `KubernetesDispatcher`, `LocalDispatcher`, `PoolDispatcher`, `KubernetesJobBuilder`.

## Status: COMPLETED
- Core package decomposed into semantic sub-packages.
- Production and test files moved.
- Package declarations and imports updated.
- Compilation and tests verified.

## Risk
- **Low**: Structural move only. No logic change.

## Validation
- `./gradlew :control-plane:test`
