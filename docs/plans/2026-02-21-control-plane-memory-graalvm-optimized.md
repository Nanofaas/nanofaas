# Control-Plane Memory Optimization (Low-Risk v2)

## Objective
Reduce control-plane memory footprint in a low-risk way while preserving current behavior and avoiding measurable regressions in latency/error rate under stress.

## Constraints and Success Criteria
- Profile: `stress`
- Measurement scope: JVM metrics only
- Guardrail: no measurable increase in latency (especially P99) and no increase in error rate
- Semantics: idempotency replay and status behavior must remain unchanged
- Success criteria:
  - measurable JVM memory improvement (heap/allocations) in at least one optimized configuration
  - no API/behavior regressions

## Technical Design

### A) Timestamp Compaction (Keep)
Convert object-heavy internal timestamps to primitives where impact is high and risk is low:
- `ExecutionStore.StoredExecution`: `Instant createdAt` -> `long createdAtMillis`
- `IdempotencyStore.StoredKey`: `Instant storedAt` -> `long storedAtMillis`

Notes:
- Use epoch milliseconds (`System.currentTimeMillis()`).
- Keep public API/DTO shapes unchanged.
- Keep TTL semantics identical.

### B) Allocation Reduction in Idempotency Hot Path (Keep, Gated)
Retain the optimization idea, but gate it behind benchmarks:
- Evaluate replacing string concatenation keys (`functionName + ":" + key`) with a typed key (`record IdempotencyKey(...)`) only if profiling confirms lower allocation/retention.
- Use `ConcurrentHashMap.compute(...)` patterns to avoid unnecessary temporary allocations.

This is still in scope but not mandatory for the first merge.

### C) Stack Size Guardrail (Keep, Safety-Only)
Current JVM Dockerfile already sets `-Xss256k`.
- Keep `256k` as default.
- Allow controlled override only as rollback/safety knob (e.g., to `512k`) if stress tests surface `StackOverflowError`.
- Do not treat larger stack as an optimization target.

## Explicitly Out of Scope (for Low-Risk Iteration)
- Refactoring `ExecutionRecord` internal time fields
- Refactoring `InvocationTask.enqueuedAt`
- Native-image specific RSS benchmarking
- API/DTO timestamp format changes

## Validation Plan (JVM-Only)

### Run Matrix
1. Baseline: compaction OFF
2. Compaction ON
3. Optional safety run: compaction ON + stack override (only if needed after failures)

### Metrics to Collect
- JVM heap used/max
- GC pause/count
- Allocation trend (from available JVM tooling)
- P50/P95/P99 latency
- Error rate

### Acceptance
- Latency/error guardrail respected vs baseline
- Memory metrics improved with compaction ON
- No semantic regressions in status/idempotency behavior

## TDD Execution Plan
1. Add/extend tests for semantic parity in stores:
   - `ExecutionStore`: put/get/evict/cleanup behavior parity
   - `IdempotencyStore`: put/putIfAbsent/expiry parity
2. Implement timestamp compaction for the two stores.
3. Keep API contracts unchanged.
4. Run control-plane tests + stress validation.
5. Optionally evaluate typed idempotency key optimization behind profiling evidence.

## Rollback Plan
- If any regression appears:
  - disable compaction toggle
  - keep default stack (`-Xss256k`) unless explicit safety override is required
  - preserve current production semantics by design
