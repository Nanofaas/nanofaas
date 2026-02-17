# Load Test Payload Profile

This document describes the new payload variability model used by k6 load tests,
the new payload metrics, and how to validate them.

It applies to:

- `scripts/e2e-loadtest.sh`
- `scripts/e2e-loadtest-registry.sh`
- `scripts/e2e-loadtest-registry.sh --interactive`
- k6 workload scripts under `k6/word-stats-*.js` and `k6/json-transform-*.js`

## Payload Modes

Payload behavior is controlled with:

- `K6_PAYLOAD_MODE`
- `K6_PAYLOAD_POOL_SIZE`

### `K6_PAYLOAD_MODE`

Supported values:

- `legacy-random`: historical behavior (non-pool random generation)
- `pool-sequential`: deterministic walk over a pool of size `K6_PAYLOAD_POOL_SIZE`
- `pool-random`: uniform random pick from a pool of size `K6_PAYLOAD_POOL_SIZE`

Default:

- `legacy-random`

### `K6_PAYLOAD_POOL_SIZE`

Positive integer (`>= 1`), default `5000`.

The value is used by `pool-sequential` and `pool-random`.

## How Payloads Are Built

Shared logic is implemented in:

- `k6/payload-model.js` (pure functions, unit-tested with Node)
- `k6/common.js` (k6 runtime integration and metrics)

For each iteration:

1. A payload index is selected according to mode.
2. Input object is generated (`word-stats` or `json-transform`).
3. Request JSON is built as `{ "input": ... }`.
4. Payload byte size is recorded in k6 metric `payload_size_bytes`.

## New k6 Metric

### `payload_size_bytes`

Type: `Trend`.

Recorded in `k6/common.js` for every request before `http.post(...)`.

Summary export includes:

- `avg`
- `med` (Q2)
- `p(25)` (Q1)
- `p(75)` (Q3)

This is enabled by running k6 with:

- `--summary-trend-stats "avg,min,med,max,p(25),p(75),p(90),p(95)"`

(`scripts/e2e-loadtest.sh` now sets this by default).

## New Summary Section

`scripts/e2e-loadtest-registry.sh` now prints:

- `SECTION 9: PAYLOAD PROFILE (k6 INPUT MIX)`

Columns:

- `Iter`: iteration count from k6 summary
- `Unique`: estimated unique payloads used
- `Cover%`: estimated coverage of the configured pool
- `Reuse`: `Iter / Unique`
- `Collisions`: `Iter - Unique`
- `Avg(B)`, `Q1(B)`, `Q2(B)`, `Q3(B)`: payload size distribution

### Estimation model

For pool size `N` and iterations `k`:

- `pool-sequential`:
  - `Unique = min(k, N)` (exact)
  - `Cover% = Unique / N * 100`
- `pool-random`:
  - `Unique = N * (1 - (1 - 1/N)^k)` (expected distinct)
  - `Cover% = Unique / N * 100`
  - `Collisions = k - Unique`
- `legacy-random`:
  - `Unique/Cover%/Reuse/Collisions` are shown as `-`

## Interactive Flow

`./scripts/e2e-loadtest-registry.sh --interactive` now asks for:

1. payload mode
2. pool size (when mode is `pool-sequential` or `pool-random`)

Selected values are propagated to load generation automatically.

## How To Run

### Registry interactive run

```bash
./scripts/e2e-loadtest-registry.sh --interactive
```

### Non-interactive run

```bash
K6_PAYLOAD_MODE=pool-sequential \
K6_PAYLOAD_POOL_SIZE=5000 \
./scripts/e2e-loadtest-registry.sh
```

### Summary only from existing artifacts

```bash
K6_PAYLOAD_MODE=pool-sequential \
K6_PAYLOAD_POOL_SIZE=5000 \
./scripts/e2e-loadtest-registry.sh --summary-only --no-refresh-summary-metrics
```

## Validation and Tests

### Unit tests for payload logic (Node)

```bash
node --test k6/tests/payload-model.test.mjs
```

### Pytest bridge (runs Node test from scripts suite)

```bash
uv run pytest scripts/tests/test_k6_payload_model_js.py -q
```

### Full scripts test suite

```bash
uv run pytest scripts/tests -q
```

### k6 smoke test (syntax/integration)

```bash
K6_PAYLOAD_MODE=pool-sequential K6_PAYLOAD_POOL_SIZE=5000 NANOFAAS_URL=http://127.0.0.1:1 \
  k6 run --stage 1s:1 --stage 1s:0 k6/word-stats-java.js

K6_PAYLOAD_MODE=pool-sequential K6_PAYLOAD_POOL_SIZE=5000 NANOFAAS_URL=http://127.0.0.1:1 \
  k6 run --stage 1s:1 --stage 1s:0 k6/json-transform-java.js
```

Note: with `NANOFAAS_URL=http://127.0.0.1:1` requests fail by design; this smoke
test is only for script/runtime validation.
