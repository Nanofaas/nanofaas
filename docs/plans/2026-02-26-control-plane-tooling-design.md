# Control Plane Local Tooling Design (TUI + Build/Test/Report)

Date: 2026-02-26  
Status: Approved for v1 implementation  
Scope: Local developer machine only (macOS/Linux)

## 1. Goal

Build a Python tool (managed with `uv`) launched via shell wrapper (`.sh`) that provides a TUI workflow to:

1. Configure control-plane variant and modules.
2. Build control-plane binary/artifacts (Rust or Java native/JVM).
3. Build final Docker image.
4. Optionally execute API, E2E, metrics, and load tests.
5. Produce an HTML report with build/test outcomes and time-series charts.

The v1 objective is to accelerate local validation while producing evidence-rich outputs.

## 2. Non-Goals (v1)

- CI-first flows and required non-interactive mode.
- Real k3s/k8s cluster execution as default path.
- AuthN/AuthZ workflows.
- Multi-host/distributed orchestration.

## 3. High-Level Architecture

Project root:

- `tooling/controlplane_tui/` (Python project, `uv`-managed)
- `scripts/controlplane-tool.sh` (wrapper)
- `tooling/profiles/*.toml` (saved user profiles)
- `tooling/runs/<timestamp-profile>/` (artifacts per run)

Internal modules:

- `app/tui/`: interactive wizard (runtime, modules, build mode, tests).
- `app/core/`:
  - `ProfileManager`: create/load/save profile TOML.
  - `PipelineRunner`: ordered step execution with status tracking.
  - `RunContext`: shared runtime state and artifact paths.
- `app/steps/`:
  - `configure`
  - `compile`
  - `docker_image`
  - `test_api`
  - `test_e2e_mockk8s`
  - `test_metrics_prometheus_k6`
  - `report_html`
- `app/adapters/`: wrappers for `gradle`, `cargo`, `docker`, `k6`, `prometheus`.
- `app/reporting/`: aggregation, chart generation, HTML templating.

Recommended stack:

- TUI: `textual`
- CLI plumbing: `typer`
- Validation/models: `pydantic`
- Reporting: `jinja2` + `plotly`

## 4. User Workflow

1. Start via wrapper: `scripts/controlplane-tool.sh`.
2. TUI opens profile list:
   - Create new profile or select existing.
3. Wizard asks:
   - Control plane implementation: `rust`, `java-native`, `java-jvm`.
   - Modules to include (each shown with descriptive text).
   - Build and test options.
4. Profile saved to `tooling/profiles/<name>.toml`.
5. Runner executes pipeline and streams progress.
6. At completion, tool prints artifact location and opens summary path.

## 5. Profile and Run Data Model

### Profile TOML

`tooling/profiles/<name>.toml`

Sections:

- `[control_plane]`: implementation, build flags.
- `[modules]`: enabled module list + module-specific options.
- `[tests]`: toggles for API/E2E/metrics/load, profile `quick|stress`.
- `[metrics]`: required metrics and optional thresholds.
- `[report]`: title, baseline policy, detail level.

### Run Output

`tooling/runs/YYYYMMDD-HHMMSS-<profile>/`

Artifacts:

- `summary.json`
- `build.log`
- `test.log`
- `metrics/*.csv` and `metrics/*.json`
- `report.html`

`summary.json` contains step status, durations, errors, image metadata, test assertions, and final verdict.

## 6. Mock Kubernetes Strategy (v1)

Chosen approach: Fabric8 Kubernetes Mock Server as the official base.

Rationale:

- Mature and aligned with existing Java/Kubernetes ecosystem in repository.
- Better API compatibility than fully custom mocks.
- Enables deterministic simulation for Deployment/ReplicaSet/Pod control loops.

Execution model:

- Python orchestrator launches dedicated Java test suite using Fabric8 mock server.
- Test suite simulates function lifecycle through Deployment/ReplicaSet semantics.
- Scenarios include scale-up/down, readiness transitions, retries, timeout/failure behaviors.

Focus is correctness of control-plane logic under Kubernetes-like API behavior, not full container network/runtime fidelity.

## 7. Testing and Metrics Plan

If user enables testing:

1. `test_api`
   - Control-plane endpoint smoke and contract checks.
2. `test_e2e_mockk8s`
   - Deployment/ReplicaSet simulation scenarios via Fabric8-based suite.
3. `test_metrics_prometheus_k6`
   - Start Prometheus locally.
   - Generate load with `k6` (`quick` or `stress`).
   - Assert all required exposed metrics are present.
   - Validate key signals (latency percentiles, counters, queue/scaling behavior).

Missing required metric is a hard failure with explicit reason in report.

## 8. Error Handling and Execution Policy

Each step emits a structured `StepResult`:

- `status`: `passed|failed|skipped`
- `duration_ms`
- `error_code` (stable categories, e.g. `E_BUILD_*`, `E_TEST_*`, `E_METRIC_*`)
- `human_message`
- `debug_context`

Policies:

- Build/image steps are blocking.
- Test steps can be configured fail-fast vs best-effort.
- Artifact collection and summary write always run (finalization block), even after failures.

## 9. Report Requirements

`report.html` must include:

- Run metadata and selected profile.
- Step timeline with durations and statuses.
- Build outputs (artifact path, image tag/digest/size where available).
- Test matrix results by category.
- Time-series charts for metrics and load windows.
- Optional baseline comparison against previous run with same profile.

Charts are interactive (Plotly), with clear axis labels and units.

## 10. Delivery Plan (v1)

1. Bootstrap Python project + wrapper + profile/run models.
2. Implement TUI wizard with module catalog descriptions.
3. Implement compile and docker image steps (Rust/Java native/JVM).
4. Integrate API test step and Fabric8-based E2E mock step.
5. Integrate Prometheus + k6 metrics/load step.
6. Implement report aggregation and HTML generation.

## 11. Risks and Mitigations

- Toolchain drift across developer machines.
  - Mitigation: strict preflight checks with actionable install hints.
- Mock fidelity gaps vs real cluster behavior.
  - Mitigation: document scope and keep upgrade path to kind/k3d profiles.
- Report complexity growth.
  - Mitigation: fixed schema in `summary.json`; template driven HTML.

## 12. Acceptance Criteria

- User can configure and save reusable profiles from TUI.
- Tool can build selected control-plane variant and produce Docker image locally.
- Optional test phase executes API + mock-k8s + metrics/load suite.
- Report HTML generated for every run, including failed runs.
- Required metric catalog is enforced; missing metrics fail run explicitly.
