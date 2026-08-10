# runtime-config

Optional control-plane module: hot-updatable runtime configuration with an
admin HTTP API — change selected control-plane settings without a restart.

## Provides

- `RuntimeConfigService` — versioned snapshots (`RuntimeConfigSnapshot`) with
  optimistic concurrency: each patch carries the expected revision, a stale
  revision raises `RevisionMismatchException`.
- `AdminRuntimeConfigController` — `/v1/admin/runtime-config` (GET snapshot,
  PATCH with `RuntimeConfigPatch`), active only when
  `nanofaas.admin.runtime-config.enabled=true`.
- `RuntimeConfigValidator` / `RuntimeConfigApplier` — validate then apply
  patches to the live components (invalid patches fail with
  `RuntimeConfigApplyException`, nothing is partially applied).

## Configuration

```yaml
nanofaas:
  admin:
    runtime-config:
      enabled: false   # default: admin API off
```

## Usage example

With `nanofaas.admin.runtime-config.enabled=true`, read the current snapshot
and patch it with optimistic concurrency:

```bash
# 1. Read the current snapshot (note the revision)
curl http://localhost:8080/v1/admin/runtime-config

# 2. Validate a patch without applying it
curl -X POST http://localhost:8080/v1/admin/runtime-config/validate \
  -H 'Content-Type: application/json' \
  -d '{"rateMaxPerSecond": 500}'

# 3. Apply it — expectedRevision must match the current revision
curl -X PATCH http://localhost:8080/v1/admin/runtime-config \
  -H 'Content-Type: application/json' \
  -d '{
    "expectedRevision": 3,
    "rateMaxPerSecond": 500,
    "syncQueueMaxEstimatedWait": "PT1S"
  }'
```

- Null fields are left unchanged; durations are ISO-8601 (`PT1S` = 1 second).
- A stale `expectedRevision` returns `409` with the current revision in
  `currentRevision` — re-read, then retry.
- Invalid patches return `422` with an `errors` list; an apply failure rolls
  back and returns `503`. Nothing is applied partially.

## Notes

- The API is unauthenticated like the rest of the control plane (project
  constraint) — enable it only where the admin port is trusted.
- E2E coverage: `experiments/e2e-runtime-config.sh`.
