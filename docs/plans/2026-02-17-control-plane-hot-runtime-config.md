# Control-Plane Hot Runtime Config Plan

Date: 2026-02-17
Status: Proposed
Owner: control-plane

## 1. Problem Statement

Today most control-plane operational knobs are loaded from `application.yml` at startup and require pod restart or Helm upgrade to change. This blocks iterative tuning during load experiments and makes incident response slower.

Current exceptions are limited and ad-hoc (for example manual replica changes via `PUT /v1/functions/{name}/replicas`).

Goal: support safe, observable, and validated hot updates for selected runtime parameters while the control-plane is running.

## 2. Scope

In scope:
- Runtime update of selected control-plane parameters via explicit admin API.
- Strong validation and deterministic apply order.
- Versioned config with optimistic locking.
- Auditability and metrics for changes.

Out of scope:
- Full dynamic reconfiguration of all Spring properties.
- Distributed consensus between multiple control-plane pods.
- Authentication/authorization redesign.

## 3. Design Principles

- Single Source of Truth: one runtime config snapshot shared by all consumers.
- Explicitness over magic: no hidden framework refresh side effects.
- Atomicity: all-or-nothing config updates.
- Backward compatibility: default behavior unchanged when feature unused.
- Separation of concerns: API, validation, state, and apply logic are isolated.
- Observability-first: every update is measurable and traceable.

## 4. Target Architecture

Introduce a dedicated runtime configuration subsystem:

- `RuntimeConfigSnapshot` (immutable value object)
  - Contains effective runtime knobs and `revision`.
- `RuntimeConfigService`
  - Holds `AtomicReference<RuntimeConfigSnapshot>`.
  - Handles compare-and-set updates.
- `RuntimeConfigValidator`
  - Domain invariants and cross-field checks.
- `RuntimeConfigApplier`
  - Applies new snapshot to mutable runtime components in defined order.
- `RuntimeConfigStore` (port)
  - Phase 1: in-memory only.
  - Phase 2 optional: ConfigMap-backed persistence.
- `AdminRuntimeConfigController`
  - API entry point for GET, validate, PATCH.

This avoids coupling runtime behavior to Spring bean re-instantiation.

## 5. Configuration Classes and Ownership

The initial config values remain loaded from existing properties classes:
- `RateLimiter` (`nanofaas.rate.*`) — mutable `@Component` class with `volatile` field and setter. Already hot-safe.
- `SyncQueueProperties` (`sync-queue.*`) — immutable `@ConfigurationProperties` record. Fields read per-call by `SyncQueueAdmissionController` and `SyncQueueService`, but the record itself cannot be mutated.
- `ScalingProperties` (`nanofaas.scaling.*`) — immutable `@ConfigurationProperties` record.

After bootstrap, effective runtime values are read from `RuntimeConfigSnapshot` through typed accessors exposed by `RuntimeConfigService`.

### Consumer wiring strategy

Since `SyncQueueProperties` and `ScalingProperties` are immutable Java records, consumers that currently hold a `final` reference to these records cannot observe changes through them. The migration path for Phase 1:
- Hot-safe consumers (`SyncQueueAdmissionController`, `SyncQueueService`) are refactored to inject `RuntimeConfigService` and read values from the current snapshot instead of from the properties record.
- `RateLimiter` already has a mutable setter; `RuntimeConfigApplier` calls `rateLimiter.setMaxPerSecond()` directly.
- The original properties records remain as the bootstrap source; `RuntimeConfigSnapshot` is seeded from them at startup.

## 6. Hot-Update Matrix

### 6.1 Safe in phase 1
- `nanofaas.rate.maxPerSecond` — `RateLimiter` already has `volatile` field + setter; applier calls it directly.
- `sync-queue.enabled` — read per-call by `SyncQueueService.enabled()`; allows disabling the sync queue at runtime for incident response.
- `sync-queue.admission-enabled` — read per-call by `SyncQueueAdmissionController.evaluate()`.
- `sync-queue.max-estimated-wait` — read per-call by `SyncQueueAdmissionController.evaluate()`.
- `sync-queue.max-queue-wait` — read per-call by `SyncQueueService.isTimedOut()`.
- `sync-queue.retry-after-seconds` — read per-call by `SyncQueueService.retryAfterSeconds()`.

### 6.2 Requires dedicated refactor
- `sync-queue.max-depth` — `SyncQueueService` sizes a `LinkedBlockingQueue` at construction; fixed capacity.
- `sync-queue.throughput-window`, `sync-queue.per-function-min-samples` — `WaitEstimator` captures these as `final` fields at construction; requires reconstruction or mutable wrapper.
- `nanofaas.scaling.poll-interval-ms` — baked into `ScheduledExecutorService.scheduleAtFixedRate()` at `InternalScaler.start()`; requires dynamic scheduler resubscription.
- Adaptive concurrency thresholds and cooldowns:
  - `nanofaas.scaling.concurrency-high-load-threshold`
  - `nanofaas.scaling.concurrency-low-load-threshold`
  - `nanofaas.scaling.concurrency-upscale-cooldown-ms`
  - `nanofaas.scaling.concurrency-downscale-cooldown-ms`

  **Note**: `AdaptivePerPodConcurrencyController` currently reads these values from per-function `ConcurrencyControlConfig` (inside `FunctionSpec`), not from `ScalingProperties`. The `ScalingProperties` fields exist in `application.yml` but are not wired to the controller. Making these hot-updatable requires either: (a) adding a global-override mechanism in `AdaptivePerPodConcurrencyController` that takes precedence over per-function config, or (b) updating all registered `FunctionSpec` records in `FunctionRegistry`. Either approach requires non-trivial refactoring.

### 6.3 Not hot (restart/rollout required)
- Infrastructure wiring and immutable deployment-level settings.
- Endpoint bindings, ports, K8s connectivity base config.
- `nanofaas.http-client.*` — used to configure `WebClient` at startup.

## 7. Admin API Contract

Base path: `/v1/admin/runtime-config`

Endpoints:
- `GET /v1/admin/runtime-config`
  - Returns full snapshot with current revision.
- `POST /v1/admin/runtime-config/validate`
  - Validates patch without applying.
- `PATCH /v1/admin/runtime-config`
  - Applies partial update with optimistic lock.

PATCH request model:
- `expectedRevision` required.
- `patch` object with optional fields.

PATCH response model:
- `revision`
- `effectiveConfig`
- `appliedAt`
- `changeId`
- `warnings`

Error semantics:
- `409` revision mismatch.
- `422` validation error.
- `503` apply failure (no partial apply persisted).

## 8. Update Workflow

1. Read current snapshot.
2. Merge patch into candidate snapshot.
3. Validate candidate.
4. CAS (`expectedRevision`) on service.
5. Apply candidate to runtime components.
6. Emit audit log and metrics.
7. Return new snapshot.

If apply fails:
- Restore previous snapshot.
- Emit failed update metric and structured error.

## 9. Concurrency and Consistency

- All updates are serialized by CAS on revision.
- Readers are lock-free through immutable snapshot reads.
- Apply order is deterministic and idempotent.
- Repeated PATCH with same patch and same base revision is deterministic.

## 10. Observability

Add metrics:
- `controlplane_runtime_config_revision` (gauge)
- `controlplane_runtime_config_updates_total{status}` (counter)
- `controlplane_runtime_config_apply_duration_seconds` (timer)

Add audit log event:
- `event=runtime_config_update`
- `change_id`
- `from_revision`
- `to_revision`
- changed keys
- outcome

## 11. Security and Operational Guardrails

Until auth is introduced:
- Keep admin endpoints disabled by default behind feature flag.
- Enable only in controlled environments.
- Optional network restriction at ingress/service level.

Feature flag:
- `nanofaas.admin.runtime-config.enabled=false` by default.

## 12. Implementation Phases

### Phase 1: Core runtime config subsystem
- Introduce snapshot, service, validator, controller, in-memory store.
- Refactor `SyncQueueAdmissionController` and `SyncQueueService` to read hot-safe fields from `RuntimeConfigService` instead of from immutable `SyncQueueProperties` record.
- Wire `RuntimeConfigApplier` to call `RateLimiter.setMaxPerSecond()` directly.

### Phase 2: Scheduler, queue depth, and adaptive concurrency
- Add dynamic poll interval rescheduling in `InternalScaler`.
- Add safe runtime queue depth reconfiguration strategy for `SyncQueueService`.
- Reconstruct or wrap `WaitEstimator` to support hot `throughputWindow` and `perFunctionMinSamples`.
- Add global-override mechanism in `AdaptivePerPodConcurrencyController` for concurrency thresholds and cooldowns (currently read from per-function `ConcurrencyControlConfig`, not from `ScalingProperties`).

### Phase 3: Optional persistence
- ConfigMap-backed store with startup restore.
- Clear precedence rules between boot config and persisted runtime config.

## 13. Testing Strategy

Unit tests:
- Validator invariants.
- Patch merge behavior.
- Revision mismatch behavior.

Integration tests:
- PATCH updates change runtime behavior without restart.
- Rollback on apply error.

Concurrency tests:
- Simultaneous PATCH requests only one succeeds per revision.

E2E tests:
- During load test, modify thresholds and verify immediate metric/behavior shift.

## 14. Acceptance Criteria

- Runtime update endpoint can change at least phase-1 keys without pod restart.
- No partial updates visible after apply failure.
- All config changes are observable in metrics and logs.
- Existing behavior unchanged when admin API is disabled.

## 15. Risks and Mitigations

- Risk: hidden coupling to static config fields.
  - Mitigation: ban direct reads from legacy properties in hot paths, enforce via review.

- Risk: inconsistent behavior during update.
  - Mitigation: immutable snapshot + CAS + deterministic apply order.

- Risk: operational misuse.
  - Mitigation: feature flag, strict validation, audit logs.

- Risk: immutable record wiring mismatch.
  - `SyncQueueProperties` and `ScalingProperties` are Java records (immutable). Consumers holding `final` references to these records will not observe runtime changes unless explicitly refactored to read from `RuntimeConfigService`.
  - Mitigation: Phase 1 refactors all hot-safe consumers to inject `RuntimeConfigService`. Add a test that verifies no hot-safe field is read directly from a properties record in production code.

## 16. Open Questions

- Should runtime config persist across restart in phase 1 or remain ephemeral by design?
- Which team owns admin endpoint governance until auth is available?
- Do we need per-function hot overrides in this cycle or only global knobs?
