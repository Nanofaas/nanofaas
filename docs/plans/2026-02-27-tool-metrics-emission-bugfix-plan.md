# Tool Metrics Emission Bugfix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the tooling bug where metrics are not emitted during `test_metrics_prometheus_k6` by making the tool provision a deterministic invocable fixture before load generation.

**Architecture:** Introduce a dedicated SUT preflight/bootstrap layer in the Python tool. Before running k6, the tool must ensure an invocable function exists (registered through control-plane API in deterministic local mode), run a warm-up invocation, then execute load against that fixture endpoint. The metrics gate will validate run-window series for scenario-compatible required metrics (strict mode remains available via profile override).

**Tech Stack:** Python 3.12, Typer, urllib/http JSON calls, k6, Prometheus API, pytest, uv.

---

### Task 1: Reproduce and lock the bug with failing tests (tool-level)

**Files:**
- Create: `tooling/controlplane_tui/tests/test_sut_preflight.py`
- Modify: `tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Write the failing test**

```python
def test_metrics_step_requires_successful_invocation_preflight(...):
    # Arrange fake k6 success path but SUT preflight fails (function missing).
    # Expected: adapter returns clear failure before relying on metric presence.
    ...

def test_metrics_step_registers_fixture_before_k6(...):
    # Verify register + warm-up invocation happen before k6 command.
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_sut_preflight.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: FAIL because no SUT preflight/bootstrap exists yet.

**Step 3: Write minimal implementation hooks**

- Add adapter integration points (`_create_sut_preflight` or equivalent) without full behavior.
- Wire call order: preflight -> register fixture -> warm-up invoke -> k6.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_sut_preflight.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/tests/test_sut_preflight.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py tooling/controlplane_tui/src/controlplane_tool/adapters.py
git commit -m "test(tooling): lock metrics preflight contract before load generation"
```

### Task 2: Implement structural SUT bootstrap/preflight

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/sut_preflight.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Test: `tooling/controlplane_tui/tests/test_sut_preflight.py`

**Step 1: Write the failing test**

```python
def test_ensure_fixture_registers_local_execution_function(...):
    # Calls POST /v1/functions with deterministic fixture payload
    ...

def test_ensure_fixture_warmup_invocation_must_return_200(...):
    # Warm-up call required; fail fast with actionable error
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_sut_preflight.py -v`  
Expected: FAIL because module/behavior not yet present.

**Step 3: Write minimal implementation**

- Add `SutPreflight` class with:
  - `ensure_control_plane_ready(base_url)`
  - `ensure_fixture_registered(base_url, fixture_name)`
  - `warmup_invoke(base_url, fixture_name)`
- Fixture contract:
  - Function name: deterministic (`tool-metrics-echo`)
  - Registration payload uses `executionMode=LOCAL` to avoid external runtime dependency.
  - Idempotent register flow (`201` or already exists path).
- Return rich diagnostics when registration/invocation fails (status code + response body sample).

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_sut_preflight.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/sut_preflight.py tooling/controlplane_tui/src/controlplane_tool/adapters.py tooling/controlplane_tui/tests/test_sut_preflight.py
git commit -m "feat(tooling): add deterministic control-plane fixture preflight for metrics runs"
```

### Task 3: Replace generic k6 target with tool-owned fixture load script

**Files:**
- Create: `tooling/controlplane_tui/assets/k6/tool-metrics-echo.js`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Modify: `tooling/controlplane_tui/tests/test_adapters_k6_url.py`

**Step 1: Write the failing test**

```python
def test_metrics_k6_uses_tool_fixture_script_and_base_url(...):
    # Ensures k6 command points to tool-owned script and fixture function endpoint
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_k6_url.py -v`  
Expected: FAIL because adapter still uses `experiments/k6/word-stats-java.js`.

**Step 3: Write minimal implementation**

- Add tool-owned k6 script that:
  - invokes `tool-metrics-echo`,
  - asserts `status == 200`,
  - validates response payload shape for local echo fixture.
- Update adapter to reference this script by default.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_k6_url.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/assets/k6/tool-metrics-echo.js tooling/controlplane_tui/src/controlplane_tool/adapters.py tooling/controlplane_tui/tests/test_adapters_k6_url.py
git commit -m "feat(tooling): run load test against deterministic local fixture"
```

### Task 4: Align metrics gating with scenario-compatible requirements

**Files:**
- Modify: `tooling/controlplane_tui/src/controlplane_tool/tui.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/models.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Modify: `tooling/controlplane_tui/tests/test_tui_choices.py`
- Modify: `tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Write the failing test**

```python
def test_default_required_metrics_for_local_fixture_are_core_set(...):
    # No impossible metrics in default gate (e.g., cold/warm start unless explicitly requested)
    ...

def test_strict_metrics_profile_still_enforces_all_requested_metrics(...):
    # User-provided required list remains strict
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_tui_choices.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: FAIL until defaults/gating are adjusted.

**Step 3: Write minimal implementation**

- Introduce two behavior modes:
  - default core required metrics (scenario-compatible, emitted by local fixture flow),
  - strict custom required metrics (when profile explicitly sets full list).
- Keep report complete (`available_in_prometheus` + full `series.json`) even when gate checks only core defaults.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_tui_choices.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/tui.py tooling/controlplane_tui/src/controlplane_tool/models.py tooling/controlplane_tui/src/controlplane_tool/adapters.py tooling/controlplane_tui/tests/test_tui_choices.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "fix(tooling): enforce scenario-compatible metric gate with strict override"
```

### Task 5: Docs + end-to-end QA evidence

**Files:**
- Modify: `tooling/controlplane_tui/README.md`
- Modify: `docs/testing.md`
- Modify: `tooling/controlplane_tui/tests/test_docs_links.py`
- Create/Update run evidence under `tooling/runs/` (no commit required for artifacts)

**Step 1: Write the failing test**

```python
def test_docs_describe_fixture_preflight_and_local_load_contract():
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: FAIL until docs updated.

**Step 3: Write minimal implementation**

- Document:
  - automatic fixture registration (`tool-metrics-echo`),
  - local execution-mode rationale,
  - meaning of strict vs default metrics gate.

**Step 4: Run full verification + live QA**

Run:
- `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests -v`
- `./gradlew :control-plane:test --tests '*MockK8sDeploymentReplicaSetFlowTest'`
- `scripts/controlplane-tool.sh --profile-name qa-live-full --use-saved-profile`

Expected:
- tests pass,
- tool run no longer fails because of “all requests failed / missing all metrics” precondition bug,
- `metrics/observed-metrics.json` shows `source=prometheus-api` with non-empty `observed_run_window`.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/README.md docs/testing.md tooling/controlplane_tui/tests/test_docs_links.py
git commit -m "docs(tooling): describe deterministic fixture-based metrics QA flow"
```

### Acceptance Criteria

- Tool metrics flow does not rely on pre-existing external function registration.
- Before k6, tool ensures a deterministic invocable fixture is available and validates one successful warm-up invocation.
- k6 no longer runs against a function name that may not exist in current environment.
- Prometheus bootstrap/collection continues to work with absolute bind mounts and API parsing.
- Default metrics gate checks only scenario-compatible required metrics; strict full-gate remains available through profile configuration.
- Live run `qa-live-full` no longer fails with `http_req_failed=100%` due missing fixture setup.
