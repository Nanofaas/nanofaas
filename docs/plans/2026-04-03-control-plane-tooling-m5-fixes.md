# Control Plane Tooling Milestone 5 Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the Milestone 5 regressions so relative scenario files resolve from the active worktree, loadtest execution benchmarks every requested target in the manifest, and `scripts/e2e-loadtest.sh` remains a truthful compatibility entrypoint for the legacy Helm/Grafana/parity workflow.

**Architecture:** Keep the new `loadtest` command group, but tighten three contracts. First, path resolution must be workspace-root aware instead of depending on the process cwd. Second, the loadtest runner must treat `scenario.load.targets` as an ordered benchmark matrix, not as a single implicit target. Third, the top-level `e2e-loadtest` wrapper must map back to the historical workflow it claims to represent, while generic `controlplane.sh loadtest run` remains the new first-class use case.

**Tech Stack:** Python, Typer, pytest, Bash wrappers, existing `experiments/e2e-loadtest.sh` and `experiments/e2e-loadtest-registry.sh`, k6, Prometheus, scenario manifest JSON.

---

## Review Findings To Fix

1. Relative `scenario-file` paths resolve against `Path.cwd()` instead of the active worktree root, so `tools/controlplane/scenarios/...` breaks when launched from the repo root.
2. The loadtest runner preserves a list of targets in `LoadtestRequest`, but `adapters.py` collapses that list to only the first target and silently skips the rest of the benchmark matrix.
3. `scripts/e2e-loadtest.sh` was reduced to a generic `loadtest run` forwarder and no longer represents the Helm-backed Grafana/parity workflow that the script name and docs still promise.

## Verified Scope Notes

- The `--summary-only` flag belongs to the registry-oriented path in `experiments/e2e-loadtest-registry.sh`, not to the plain `experiments/e2e-loadtest.sh` flow. The fix should therefore restore the real historical `e2e-loadtest.sh` contract and reject or redirect registry-only options clearly instead of blindly forwarding them.
- The existing experimental assets under `experiments/` are still present and can be reused as compatibility backends instead of re-implementing the whole VM/Grafana workflow inside M5.

## Scope Guard

**In scope**

- stable workspace-root path resolution for scenario files and payload references
- explicit multi-target loadtest execution for all selected benchmark targets
- compatibility restoration for `scripts/e2e-loadtest.sh`
- docs/tests updates needed to make the three contracts explicit

**Out of scope**

- redesign of the experimental loadtest scripts themselves
- full migration of Grafana/parity/reporting logic from `experiments/` into native Python
- milestone 6 `nanofaas-cli` work
- milestone 7 legacy retirement

## Fix Strategy

1. Lock the three regressions with focused tests.
2. Add one workspace-aware path resolver and use it consistently from scenario loading.
3. Change loadtest execution from “first target only” to “iterate all targets sequentially”.
4. Restore `scripts/e2e-loadtest.sh` as a compatibility wrapper over the real legacy backend, and document the split between generic `loadtest run` and legacy Helm/Grafana flows.

### Task 1: Lock the regressions with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_scenario_loader.py`
- Modify: `tools/controlplane/tests/test_loadtest_models.py`
- Modify: `tools/controlplane/tests/test_loadtest_runner.py`
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`
- Modify: `tools/controlplane/tests/test_adapters_k6_url.py`
- Modify: `tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py`
- Modify: `scripts/tests/test_loadtest_wrapper_runtime.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing tests**

Add focused regression coverage:

```python
def test_load_scenario_file_resolves_relative_path_from_workspace_root(monkeypatch) -> None:
    monkeypatch.chdir("/")
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    assert scenario.name == "k8s-demo-java"


def test_loadtest_runner_executes_every_selected_target(tmp_path: Path) -> None:
    result = LoadtestRunner(adapter=FakeAdapter()).run(_request_with_two_targets(), runs_root=tmp_path)
    assert "word-stats-java" in result.steps[-1].detail
    assert "json-transform-java" in result.steps[-1].detail


def test_loadtest_bootstrap_ensures_all_requested_fixtures(tmp_path: Path) -> None:
    ...


def test_e2e_loadtest_wrapper_dry_run_routes_to_legacy_loadtest_backend() -> None:
    output = run_script("e2e-loadtest.sh", "--profile", "demo-java", "--dry-run")
    assert "experiments/e2e-loadtest.sh" in output


def test_e2e_loadtest_wrapper_rejects_registry_only_summary_flag() -> None:
    proc = run_script("e2e-loadtest.sh", "--summary-only", check=False)
    assert proc.returncode == 2
    assert "e2e-loadtest-registry" in proc.stderr
```

The wrapper tests should lock the real contract:

- `--help` mirrors the legacy loadtest flow
- `--profile` still works as compatibility sugar if intentionally supported
- registry-only flags are rejected or rerouted with a clear error

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py -v

python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected: FAIL on the three known regressions.

**Step 3: Commit**

```bash
git add \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py \
  scripts/tests/test_loadtest_wrapper_runtime.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "test: lock m5 loadtest regressions"
```

### Task 2: Make scenario-file path resolution workspace-root aware

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/paths.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`
- Modify: `tools/controlplane/tests/test_paths.py`

**Step 1: Add one canonical path resolver**

Add a helper in `paths.py` with behavior like:

```python
def resolve_workspace_path(path: Path) -> Path:
    if path.is_absolute():
        return path.resolve()
    workspace_candidate = default_tool_paths().workspace_root / path
    if workspace_candidate.exists():
        return workspace_candidate.resolve()
    return (Path.cwd() / path).resolve()
```

Rules:

- prefer the active worktree root
- keep absolute paths unchanged
- fall back to cwd only for ad hoc external paths

**Step 2: Use it from scenario loading**

Replace `_resolve_input_path()` in `scenario_loader.py` so `load_scenario_file(Path("tools/controlplane/scenarios/..."))` works from any cwd in the session.

Update any command-layer code that reconstructs stored relative scenario paths to go through the same helper instead of calling `Path(...)` directly.

**Step 3: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_paths.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_commands.py -v
```

Expected: PASS.

**Step 4: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/paths.py \
  tools/controlplane/src/controlplane_tool/scenario_loader.py \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/tests/test_paths.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_commands.py
git commit -m "fix: resolve scenario files from workspace root"
```

### Task 3: Benchmark every requested loadtest target

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/run_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/report.py`
- Modify: `tools/controlplane/tests/test_loadtest_runner.py`
- Modify: `tools/controlplane/tests/test_adapters_k6_url.py`
- Modify: `tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Preserve the target list as a benchmark matrix**

Do not collapse `request.targets.targets` to the first entry. Introduce a small per-target execution model, for example:

```python
class TargetRunResult(BaseModel):
    function_key: str
    k6_summary_path: Path
    status: Literal["passed", "failed"]
    detail: str
```

You do not need a full architecture rewrite; keep the change incremental.

**Step 2: Warm every fixture before the load phase**

Change `bootstrap_loadtest()` to ensure fixtures for all selected targets, not just the first one.

Suggested minimal contract:

- `LoadtestBootstrapContext` stores `target_functions: list[str]`
- the adapter calls `_create_sut_preflight_for_target(..., fixture_name=target)` for each target

**Step 3: Run k6 sequentially for every target**

Change `run_loadtest_k6()` from one `k6 run` to a loop:

```python
for target in context.target_functions:
    summary_path = metrics_dir / target / "k6-summary.json"
    command = [...]
    results.append(...)
```

Requirements:

- preserve target order from the scenario manifest
- write per-target artifacts under `metrics/<target>/`
- aggregate the result into one detail string and one summary payload

Metrics gating can remain one post-run gate for the whole run window if you want to keep the change minimal for M5.

**Step 4: Update reporting**

Extend `summary.json` and the rendered report so they expose:

- all requested targets
- per-target k6 artifact paths/status
- aggregate final status

**Step 5: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py \
  tools/controlplane/tests/test_pipeline.py -v
```

Expected: PASS, with tests asserting two targets produce two effective k6 invocations for `k8s-demo-java`.

**Step 6: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_models.py \
  tools/controlplane/src/controlplane_tool/loadtest_runner.py \
  tools/controlplane/src/controlplane_tool/adapters.py \
  tools/controlplane/src/controlplane_tool/run_models.py \
  tools/controlplane/src/controlplane_tool/report.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py \
  tools/controlplane/tests/test_pipeline.py
git commit -m "fix: benchmark every requested loadtest target"
```

### Task 4: Restore `scripts/e2e-loadtest.sh` as a truthful compatibility wrapper

**Files:**
- Modify: `scripts/e2e-loadtest.sh`
- Modify: `scripts/tests/test_loadtest_wrapper_runtime.py`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Choose the compatibility backend explicitly**

Do not keep `scripts/e2e-loadtest.sh` as a generic forwarder to `controlplane.sh loadtest run`.

Preferred minimal fix:

- `scripts/e2e-loadtest.sh` wraps `experiments/e2e-loadtest.sh`
- it preserves the historical Helm/Grafana/parity semantics documented in the repo
- it optionally accepts `--profile <name>` only if you can map that profile into environment variables deterministically; otherwise remove `--profile` from docs and reject it clearly

For registry-only summary flows:

- reject `--summary-only` with a clear message that points to `experiments/e2e-loadtest-registry.sh`
- or add a dedicated wrapper for the registry script if you want a top-level compatibility alias

**Step 2: Write wrapper behavior clearly**

The wrapper must support:

- `--help`
- `--dry-run`
- env-driven compatibility flags like `SKIP_GRAFANA` and `VERIFY_OUTPUT_PARITY`

It must not claim to be the same thing as `controlplane.sh loadtest run` unless the execution path is truly equivalent.

**Step 3: Update docs**

Make the split explicit:

- `controlplane.sh loadtest run` = generic first-class loadtest use case
- `scripts/e2e-loadtest.sh` = compatibility wrapper for the legacy Helm/Grafana/parity workflow

Also correct any stale reference that suggests `--summary-only` belongs to the plain `e2e-loadtest.sh` path.

**Step 4: Run focused tests**

Run:

```bash
python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py -v

bash scripts/e2e-loadtest.sh --help | sed -n '1,40p'
bash scripts/e2e-loadtest.sh --dry-run
```

Expected: PASS, and dry-run/help reflect the real compatibility workflow.

**Step 5: Commit**

```bash
git add \
  scripts/e2e-loadtest.sh \
  scripts/tests/test_loadtest_wrapper_runtime.py \
  tools/controlplane/README.md \
  README.md \
  docs/e2e-tutorial.md \
  docs/testing.md \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py
git commit -m "fix: restore e2e loadtest compatibility workflow"
```

### Task 5: Final verification for the M5 fixes

**Files:**
- No new files; verification only

**Step 1: Run the full focused M5 suite**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_catalog.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py -v

python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected: PASS.

**Step 2: Run CLI/wrapper dry-runs**

Run:

```bash
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
bash scripts/e2e-loadtest.sh --dry-run
bash scripts/e2e-loadtest.sh --help | sed -n '1,40p'
```

Expected:

- `controlplane.sh loadtest run` works from the repo root and the worktree root
- the loadtest plan shows both demo-java targets
- the compatibility wrapper shows the real Helm/Grafana/parity path instead of a generic loadtest plan

**Step 3: Commit**

```bash
git add -A
git commit -m "chore: verify m5 regression fixes"
```
