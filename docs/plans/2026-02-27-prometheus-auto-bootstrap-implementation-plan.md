# Prometheus Auto Bootstrap Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove Prometheus URL input from the TUI and make metrics runs automatically ensure Prometheus is available by starting a Docker Prometheus container when needed (pulling image when missing).

**Architecture:** Keep wizard UX zero-config for Prometheus. During `test_metrics_prometheus_k6`, detect an already-available Prometheus endpoint first; if unavailable, auto-bootstrap one by ensuring Docker image availability, starting an owned Prometheus container with generated scrape config targeting local control-plane metrics, waiting for readiness, querying metrics through Prometheus API, then cleaning up only owned resources.

**Tech Stack:** Python 3.12, Typer, Questionary, Docker CLI, Prometheus (`prom/prometheus`), Pytest, uv.

---

### Task 1: Regression tests for profile serialization and no Prometheus prompt

**Files:**
- Modify: `tooling/controlplane_tui/tests/test_profiles.py`
- Modify: `tooling/controlplane_tui/tests/test_tui_choices.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/profiles.py`

**Step 1: Write the failing test**

```python
def test_profile_roundtrip_without_prometheus_url(tmp_path):
    profile = Profile(..., metrics=MetricsConfig(required=[...], prometheus_url=None))
    save_profile(profile, root=tmp_path)
    loaded = load_profile(profile.name, root=tmp_path)
    assert loaded.metrics.prometheus_url is None


def test_tui_no_longer_prompts_for_prometheus_url(monkeypatch):
    # mock questionary.* and ensure .text("Prometheus ...") is never called
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_profiles.py tooling/controlplane_tui/tests/test_tui_choices.py -v`  
Expected: FAIL because serialization includes `None` and TUI still prompts for URL.

**Step 3: Write minimal implementation**

- In `save_profile`, use `profile.model_dump(mode="python", exclude_none=True)`.
- Remove Prometheus URL question from wizard flow.
- Keep `metrics.prometheus_url` model field for backward compatibility (optional override from pre-existing profiles), but no interactive prompt.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_profiles.py tooling/controlplane_tui/tests/test_tui_choices.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/profiles.py tooling/controlplane_tui/tests/test_profiles.py tooling/controlplane_tui/tests/test_tui_choices.py
git commit -m "fix(tooling): remove prometheus prompt and allow none-free profile serialization"
```

### Task 2: Add Prometheus runtime manager (detect/start/wait/cleanup)

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/prometheus_runtime.py`
- Create: `tooling/controlplane_tui/tests/test_prometheus_runtime.py`

**Step 1: Write the failing test**

```python
def test_ensure_prometheus_uses_existing_endpoint(...):
    ...
    assert runtime.started_container is False


def test_ensure_prometheus_starts_container_when_unavailable(...):
    ...
    assert runtime.started_container is True
    assert "docker run" in recorded_cmd


def test_cleanup_stops_owned_container_only(...):
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_prometheus_runtime.py -v`  
Expected: FAIL because runtime manager does not exist.

**Step 3: Write minimal implementation**

- Implement `PrometheusRuntimeManager` with:
  - `ensure_available(run_dir) -> PrometheusSession`
  - `PrometheusSession.url` (e.g. `http://127.0.0.1:9090`)
  - `PrometheusSession.owned_container_name | None`
  - `cleanup(session)`
- Behavior:
  - Check `/ -/ready` on local endpoint first.
  - If unavailable: run `docker image inspect prom/prometheus` and `docker pull prom/prometheus` when image not present.
  - Generate Prometheus config into run folder, then `docker run` with bound config and mapped port.
  - Wait until ready with bounded retries.
  - On cleanup: stop/remove only if owned container.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_prometheus_runtime.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/prometheus_runtime.py tooling/controlplane_tui/tests/test_prometheus_runtime.py
git commit -m "feat(tooling): auto-bootstrap prometheus runtime via docker"
```

### Task 3: Integrate runtime manager into metrics step and switch to Prometheus API-backed series

**Files:**
- Modify: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/metrics.py`
- Create: `tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Write the failing test**

```python
def test_metrics_step_bootstraps_prometheus_when_missing(...):
    ...
    assert observed_metrics_json["source"] == "prometheus-api"


def test_metrics_step_always_cleans_up_owned_prometheus(...):
    ...
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: FAIL because adapter currently only optional-scrapes URL and does not manage Prometheus lifecycle.

**Step 3: Write minimal implementation**

- In `run_metrics_tests`:
  - Create manager session (`ensure_available`).
  - Run existing metrics tests and k6.
  - Query Prometheus API (`/api/v1/query_range`) for required metrics across run window.
  - Build `series.json` from Prometheus API responses.
  - Write source metadata as `prometheus-api`.
  - Ensure `cleanup()` in `finally`.
- Keep fallback to catalog only if Prometheus bootstrap fails hard and return explicit error.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/adapters.py tooling/controlplane_tui/src/controlplane_tool/metrics.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "feat(tooling): collect metric series via auto-managed prometheus"
```

### Task 4: Update docs and UX contract

**Files:**
- Modify: `tooling/controlplane_tui/README.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/testing.md`
- Modify: `tooling/controlplane_tui/tests/test_docs_links.py`

**Step 1: Write the failing test**

```python
def test_docs_describe_prometheus_auto_bootstrap():
    assert "automatically starts a Prometheus container" in readme_or_docs
    assert "no prompt for prometheus url" in readme_or_docs
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: FAIL until docs updated.

**Step 3: Write minimal implementation**

- Document that TUI no longer asks Prometheus URL.
- Document auto-detect + docker bootstrap behavior.
- Document required Docker permission and expected port usage.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/README.md docs/quickstart.md docs/testing.md tooling/controlplane_tui/tests/test_docs_links.py
git commit -m "docs(tooling): describe automatic prometheus bootstrap flow"
```

### Task 5: Full verification and QA replay

**Files:**
- Modify only if verification reveals gaps.

**Step 1: Write failing regression test(s) if any gap found**

- Add test only for discovered regression before patch.

**Step 2: Run targeted fail check**

- Run only added test(s), verify RED.

**Step 3: Implement minimal patch**

- Fix only the discovered gap.

**Step 4: Run full verification**

Run:
- `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests -v`
- `./gradlew :control-plane:test --tests '*MockK8sDeploymentReplicaSetFlowTest'`
- `scripts/controlplane-tool.sh --help`

Expected: all pass.

### Acceptance Criteria

- TUI no longer asks for Prometheus URL in any interactive path.
- Metrics flow automatically checks for reachable Prometheus before running k6/metrics validation.
- If Prometheus is not reachable, tool auto-bootstraps via Docker without user prompts:
  - Pulls `prom/prometheus` image when missing.
  - Starts a dedicated container with generated scrape config.
  - Waits for readiness and uses Prometheus HTTP API for query range data.
- Generated report includes metric time-series from Prometheus API.
- Owned Prometheus container is cleaned up at end of run (including failure paths).
- Existing external Prometheus (if already reachable) is reused and never force-stopped.

**Step 5: Commit**

```bash
git add -A
git commit -m "test(tooling): finalize prometheus auto-bootstrap regression coverage"
```
