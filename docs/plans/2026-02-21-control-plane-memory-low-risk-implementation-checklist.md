# Control-Plane Memory Low-Risk: Implementation Checklist

Reference design:
- `docs/plans/2026-02-21-control-plane-memory-graalvm-optimized.md`

## 0) Preconditions
- [ ] Confirm target profile is `stress`
- [ ] Confirm acceptance guardrail: no measurable regression in latency/error rate
- [ ] Keep stack default at `-Xss256k` unless safety override is required

## 1) Baseline (Before Changes)
- [ ] Deploy baseline control-plane image (no compaction toggle)
- [ ] Run stress load profile
- [ ] Capture JVM metrics snapshot:
  - Heap used/max
  - GC pause/count
  - Allocation trend (if available)
  - P50/P95/P99 latency
  - Error rate
- [ ] Store baseline artifacts under a dated folder (logs + metrics)

Suggested commands (adapt to your workflow):
```bash
# tests quick sanity before baseline run
./gradlew :control-plane:test

# run experiment/deploy (interactive)
./experiments/run.sh

# run load test (stress profile)
./experiments/e2e-loadtest.sh
```

## 2) TDD: Add/Update Tests First
### ExecutionStore parity tests
- [ ] Add tests to verify identical behavior before/after compaction:
  - put/get/getOrNull
  - eviction at ttl/staleTtl boundaries
  - cleanup behavior after cleanupTtl

### IdempotencyStore parity tests
- [ ] Add tests for:
  - put/getExecutionId
  - putIfAbsent atomic semantics
  - expiry behavior
  - overwrite of expired entries

Suggested commands:
```bash
./gradlew :control-plane:test --tests "*ExecutionStore*" --tests "*IdempotencyStore*"
```

## 3) Implement Timestamp Compaction (Low-Risk Scope)
### 3.1 ExecutionStore
- [ ] `StoredExecution.createdAt` -> `long createdAtMillis`
- [ ] Replace `Instant.now()/isBefore()` comparisons with epoch-ms arithmetic
- [ ] Keep public methods and semantics unchanged

### 3.2 IdempotencyStore
- [ ] `StoredKey.storedAt` -> `long storedAtMillis`
- [ ] Replace expiry checks with epoch-ms arithmetic
- [ ] Keep external behavior unchanged

### 3.3 Toggle
- [ ] Add config toggle: `nanofaas.optimizations.epoch-millis-enabled` (default `false`)
- [ ] Ensure rollback path is config-only (no code revert required)

## 4) Validation After Implementation
- [ ] Run full control-plane tests
- [ ] Run non-stress smoke e2e
- [ ] Run stress profile with compaction ON
- [ ] Compare against baseline

Suggested commands:
```bash
./gradlew :control-plane:test

# optional broader verification
./gradlew :common:test :control-plane:test

# deploy and load test
./experiments/run.sh
./experiments/e2e-loadtest.sh
```

## 5) Optional Safety Run (Stack Override)
Only if needed (e.g., stack-related failures):
- [ ] Re-run stress with controlled stack override (e.g., `-Xss512k`)
- [ ] Verify guardrail still respected

## 6) Acceptance Decision
- [ ] Memory improves vs baseline
- [ ] No measurable latency regression (focus P99)
- [ ] No error-rate increase
- [ ] Status/idempotency semantics unchanged

Decision matrix:
- [ ] Enable compaction by default
- [ ] Keep compaction behind toggle (if gains are marginal)
- [ ] Roll back compaction (if guardrail fails)

## 7) Rollback Procedure
- [ ] Set `nanofaas.optimizations.epoch-millis-enabled=false`
- [ ] Keep stack at default (`-Xss256k`) unless explicitly overridden for safety
- [ ] Re-run smoke + short stress sanity

## 8) Post-Implementation Notes (Optional)
- [ ] If profiling proves benefit, evaluate typed idempotency key (`record IdempotencyKey`) in a separate change
- [ ] Keep that evaluation isolated from this low-risk merge
