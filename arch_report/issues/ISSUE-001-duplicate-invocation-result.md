# ISSUE-001-duplicate-invocation-result

**Title:** Remove duplicate InvocationResult class
**Severity:** High
**Impacted Modules:** `control-plane`, `common`

## Evidence
- `control-plane/src/main/java/com/nanofaas/controlplane/core/InvocationResult.java`
- `common/src/main/java/com/nanofaas/common/model/InvocationResult.java`
- Both classes are identical records. `control-plane` depends on `common` so the `control-plane` version is redundant and dangerous (shadowing).

## Proposed Solution
1. Delete `control-plane/src/main/java/com/nanofaas/controlplane/core/InvocationResult.java`.
2. Update all references in `control-plane` to import `com.nanofaas.common.model.InvocationResult`.

## Status: COMPLETED
- Duplicate file removed.
- Imports updated across the module.
- Compilation and tests verified.

## Risk
- **Low**: Pure deletion of exact duplicate.

## Validation
- `./gradlew :control-plane:test`
