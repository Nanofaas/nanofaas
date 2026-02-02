# ISSUE-005-centralize-configuration

**Title:** Centralize Configuration Classes
**Severity:** Low
**Impacted Modules:** `control-plane`

## Evidence
- `KubernetesClientConfig.java` and `KubernetesProperties.java` are in `core`.
- `HttpClientConfig.java` is in `config`.

## Proposed Solution
Move all configuration and property classes to `com.nanofaas.controlplane.config`.

## Status: COMPLETED
This issue was resolved during the refactoring of ISSUE-002. `KubernetesProperties.java` and `KubernetesClientConfig.java` were moved to the `com.nanofaas.controlplane.config` package.

## Plan (Original)
- [x] Move `KubernetesClientConfig` and `KubernetesProperties` to `config` package.
- [x] Fix imports.


## Risk
- **Very Low**.

## Validation
- Build checks.
