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

## Notes

- The API is unauthenticated like the rest of the control plane (project
  constraint) — enable it only where the admin port is trusted.
- E2E coverage: `experiments/e2e-runtime-config.sh`.
