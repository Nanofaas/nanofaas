# Control Plane Local Tooling (`controlplane-tool`)

Interactive local tool to configure control-plane builds, run optional test phases, and generate run reports.

This app is implemented in Python and managed with `uv`.

## What it does

- Loads or creates a profile (`tooling/profiles/*.toml`)
- Builds control-plane (`java` or `rust`)
- Builds Docker image
- Optionally runs:
  - API tests
  - mock Kubernetes tests (Fabric8-based)
  - metrics checks + k6 load test
- Produces artifacts (`summary.json`, `report.html`, logs, metrics JSON)

## Prerequisites

- `uv`
- Java toolchain / Gradle wrapper (`./gradlew`) for Java path
- Docker
- `k6` (when metrics test is enabled)
- Rust/Cargo only if using `implementation = "rust"`

## Quickstart

From repository root:

```bash
scripts/controlplane-tool.sh --help
scripts/controlplane-tool.sh --profile-name dev
scripts/controlplane-tool.sh --profile-name dev --use-saved-profile
```

## CLI options

```text
--profile-name TEXT      Profile name to save/use (default: default)
--use-saved-profile      Load profile from tooling/profiles/<name>.toml
```

## Exit codes

- `0`: run completed with final status `passed`
- `1`: run completed with final status `failed`
- `2`: profile loading/validation error

## Profile example

```toml
name = "dev"
modules = ["sync-queue", "runtime-config"]

[control_plane]
implementation = "java"
build_mode = "jvm"

[tests]
enabled = true
api = true
e2e_mockk8s = true
metrics = true
load_profile = "quick"

[metrics]
required = [
  "function_dispatch_total",
  "function_success_total",
  "function_warm_start_total",
  "function_latency_ms",
  "function_queue_wait_ms",
  "function_e2e_latency_ms"
]
strict_required = false

[report]
title = "Dev run"
include_baseline = false
```

## Artifacts

Each run writes to:

`tooling/runs/<timestamp>-<profile>/`

Main outputs:

- `summary.json`
- `report.html`
- `build.log`
- `test.log`
- `metrics/observed-metrics.json`
- `metrics/series.json`
- `metrics/k6-summary.json` (if k6 executed)

## Notes on metrics and k6

- k6 is executed with base URL:
  - `NANOFAAS_URL=http://localhost:8080`
- Before k6, the tool performs deterministic SUT preflight:
  - verifies control-plane API availability (`/v1/functions`)
  - ensures fixture function `tool-metrics-echo` is registered in `executionMode=LOCAL`
  - executes one warm-up invocation and fails fast if it is not successful
  - ensures demo function `demo-word-stats-deployment` is registered in `executionMode=DEPLOYMENT` and verifies the mode via API lookup
- During metrics/k6, the tool auto-bootstraps:
  - a local mock Kubernetes API backend (for Deployment/ReplicaSet provisioning path)
  - a tool-managed control-plane runtime wired to that mock backend
- Prometheus URL is not requested in the wizard.
- During metrics step, the tool checks for an available Prometheus endpoint first.
- If unavailable, it pulls `prom/prometheus` when needed and runs a temporary Docker container automatically.
- Existing endpoint override is still supported with:
  - `NANOFAAS_TOOL_PROMETHEUS_URL=http://127.0.0.1:9090`
- The metrics gate defaults to a scenario-compatible core set (dispatch/success/warm start/latency/queue wait/e2e latency).
- Strict full-gate override is available in profile:
  - `strict_required = true`
  - `required = [...]` with the full list to enforce.
- Prometheus API discovery/query details are collected in `metrics/observed-metrics.json`.

## Troubleshooting

- `Profile not found: <name>`
  - Create the profile via wizard or place `tooling/profiles/<name>.toml`
- `Invalid profile '<name>'`
  - Fix schema fields/types in profile TOML
- Rust build fails immediately
  - Ensure Rust control-plane sources are present and configured for this repo layout

## Development

Run tool tests:

```bash
uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests -v
```
