# runtime-config

Optional control-plane module: hot-updatable runtime configuration with an
admin HTTP API — change selected control-plane settings without a restart.

## Provides

- `RuntimeConfigExtension` / `RuntimeConfigRegistry` — modules contribute
  immutable, namespaced snapshots and own validation/application/rollback.
- `RuntimeConfigService` — versioned snapshots with optimistic concurrency;
  stale revisions raise `RevisionMismatchException`.
- `AdminRuntimeConfigController` — namespaced GET, validate and PATCH endpoints,
  active only when `nanofaas.admin.runtime-config.enabled=true`.

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

# 2. Validate the control-plane namespace without applying it
curl -X POST http://localhost:8080/v1/admin/runtime-config/control-plane/validate \
  -H 'Content-Type: application/json' \
  -d '{"rateMaxPerSecond": 500}'

# 3. Apply it — expectedRevision must match the current revision
curl -X PATCH http://localhost:8080/v1/admin/runtime-config/control-plane \
  -H 'Content-Type: application/json' \
  -d '{
    "expectedRevision": 3,
    "values": {"rateMaxPerSecond": 500}
  }'
```

- Each module owns one namespace and its patch format. The `control-plane`
  namespace exposes `rateMaxPerSecond` and partial updates for
  `maxExecutionsGlobal`, `maxExecutionsPerFunction`,
  `maxCanonicalInputBytesGlobal`, `maxCanonicalInputBytesPerFunction`,
  `maxPhysicalInputCopyBytesGlobal`, `maxPhysicalInputCopyBytesPerFunction`,
  `maxWaitersGlobal`, and `maxWaitersPerFunction`.
- Reducing a quota below current occupancy does not evict or reassign work.
  New reservations remain blocked until the corresponding owner count drains;
  increases apply immediately without changing generation ownership.
- A stale `expectedRevision` returns `409` with the current revision in
  `currentRevision` — re-read, then retry.
- Invalid patches return `422` with an `errors` list; an apply failure rolls
  back and returns `503`. Nothing is applied partially.

## Notes

- The API is unauthenticated like the rest of the control plane (project
  constraint) — enable it only where the admin port is trusted.
- E2E coverage: `experiments/e2e-runtime-config.sh`.
