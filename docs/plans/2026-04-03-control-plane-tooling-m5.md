# Control Plane Tooling Milestone 5 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Promote load generation, Prometheus validation, and benchmark reporting to first-class `controlplane-tool` use cases, so load/metrics workflows no longer depend on the legacy `pipeline-run` flow or ad hoc docs/scripts.

**Architecture:** Extract the existing metrics-and-k6 behavior from the profile-driven pipeline into explicit loadtest domain models, commands, and runners. Reuse the Milestone 4 scenario manifest as the canonical selection input, then layer load profiles, Prometheus gates, and report generation on top of it. Keep `pipeline-run` as a temporary compatibility alias during the milestone, but make `loadtest` the real UX surface.

**Tech Stack:** Python, Typer, Rich, pytest, k6, Prometheus HTTP API, existing `controlplane_tool.adapters`, existing HTML reporting, TOML scenarios and profiles.

---

## Scope Guard

**In scope**

- dedicated `loadtest` command group in `controlplane-tool`
- typed load profile catalog and scenario-to-load resolution
- explicit metrics gate model and report artifacts
- reuse of Milestone 4 function/scenario manifests for load targets
- compatibility wrapper for the documented loadtest entrypoint
- TUI/profile support for configuring and launching load scenarios

**Out of scope**

- full replacement of shell backends with native Python
- redesign of benchmark payload fixtures beyond what is needed for the contract
- `nanofaas-cli` integrated test flows
- final deletion of legacy entrypoints

## Milestone 5 Contract

At the end of this milestone, the repository should support this UX:

```text
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest show-profile quick
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/controlplane.sh loadtest run --saved-profile perf-java
scripts/controlplane.sh loadtest inspect --saved-profile perf-java
scripts/e2e-loadtest.sh --profile perf-java --dry-run
```

Rules:

- load generation always starts from one resolved scenario manifest
- load profiles are explicit and discoverable, not implied by old pipeline flags
- Prometheus gating and report generation are part of the loadtest contract
- `pipeline-run` may remain as a compatibility alias, but it must call the same runner

### Task 1: Introduce typed loadtest models and catalog

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest_models.py`
- Create: `tools/controlplane/src/controlplane_tool/loadtest_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Create: `tools/controlplane/tests/test_loadtest_models.py`
- Create: `tools/controlplane/tests/test_loadtest_catalog.py`

**Step 1: Write the failing tests**

Add tests that lock the new domain contract:

```python
from controlplane_tool.loadtest_catalog import list_load_profiles, resolve_load_profile


def test_load_profile_catalog_exposes_quick_smoke_stress() -> None:
    names = [profile.name for profile in list_load_profiles()]
    assert names == ["quick", "smoke", "stress"]


def test_resolve_load_profile_returns_staged_k6_shape() -> None:
    profile = resolve_load_profile("quick")
    assert profile.stages
    assert profile.summary_window_seconds > 0
```

Add a scenario-side test that verifies a resolved scenario carries an optional `load_profile_name` and target list without losing function selection metadata.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_catalog.py -v
```

Expected: FAIL because the loadtest catalog and models do not exist yet.

**Step 3: Write minimal implementation**

Create:

- `LoadProfileDefinition`
- `LoadTargetSelection`
- `MetricsGate`
- `LoadtestRequest`

Seed the catalog with at least:

- `quick`
- `smoke`
- `stress`

Keep the schema small and typed. Do not wire execution yet.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_models.py \
  tools/controlplane/src/controlplane_tool/loadtest_catalog.py \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_catalog.py
git commit -m "feat: add loadtest domain models"
```

### Task 2: Extract a first-class loadtest runner from the legacy pipeline

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`
- Modify: `tools/controlplane/src/controlplane_tool/report.py`
- Create: `tools/controlplane/tests/test_loadtest_runner.py`
- Modify: `tools/controlplane/tests/test_pipeline.py`
- Modify: `tools/controlplane/tests/test_adapters_k6_url.py`
- Modify: `tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Write the failing tests**

Add tests that prove loadtest execution is its own use case:

```python
def test_loadtest_runner_executes_preflight_k6_and_metrics_gate(tmp_path: Path) -> None:
    adapter = FakeAdapter()
    result = LoadtestRunner(adapter=adapter).run(request, runs_root=tmp_path)
    assert [step.name for step in result.steps] == [
        "preflight",
        "bootstrap",
        "load_k6",
        "metrics_gate",
        "report",
    ]


def test_pipeline_run_delegates_metrics_load_flow_to_loadtest_runner() -> None:
    ...
```

Add adapter tests that assert the k6 command uses the resolved scenario manifest instead of hardcoded fixture names.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py -v
```

Expected: FAIL because the dedicated runner and delegation do not exist yet.

**Step 3: Write minimal implementation**

Create a `LoadtestRunner` that:

- takes a resolved scenario plus a resolved load profile
- reuses the existing adapter for compile/image/bootstrap hooks
- runs the k6 step and Prometheus queries through explicit methods
- writes machine-readable artifacts under `tools/controlplane/runs/<timestamp>-<name>/`

Refactor `pipeline.py` so metrics/load execution is a thin call into `LoadtestRunner` instead of an embedded special case.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_runner.py \
  tools/controlplane/src/controlplane_tool/adapters.py \
  tools/controlplane/src/controlplane_tool/pipeline.py \
  tools/controlplane/src/controlplane_tool/report.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "refactor: extract loadtest runner from pipeline flow"
```

### Task 3: Expose `loadtest` as a first-class CLI and wrapper

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Create: `scripts/e2e-loadtest.sh`
- Modify: `scripts/controlplane.sh`
- Create: `tools/controlplane/tests/test_loadtest_commands.py`
- Create: `scripts/tests/test_loadtest_wrapper_runtime.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`

**Step 1: Write the failing tests**

Add CLI tests:

```python
def test_loadtest_group_lists_profiles_and_run_command() -> None:
    result = CliRunner().invoke(app, ["loadtest", "--help"])
    assert result.exit_code == 0
    assert "list-profiles" in result.stdout
    assert "run" in result.stdout


def test_loadtest_run_dry_run_renders_resolved_scenario_and_k6_plan() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--scenario-file", "tools/controlplane/scenarios/k8s-demo-java.toml", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "k6" in result.stdout
```

Add wrapper tests that assert `scripts/e2e-loadtest.sh` routes to `controlplane-tool loadtest run`.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_cli_smoke.py -v

python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected: FAIL because the command group and wrapper do not exist yet.

**Step 3: Write minimal implementation**

Expose:

- `loadtest list-profiles`
- `loadtest show-profile <name>`
- `loadtest run ...`
- `loadtest inspect ...`

Keep `pipeline-run` as an alias to the same runner for now. Add `scripts/e2e-loadtest.sh` as a compatibility wrapper that forwards to `scripts/controlplane.sh loadtest run`.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/main.py \
  scripts/e2e-loadtest.sh \
  scripts/controlplane.sh \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_cli_smoke.py \
  scripts/tests/test_loadtest_wrapper_runtime.py
git commit -m "feat: add first-class loadtest commands"
```

### Task 4: Integrate TUI, profiles, and docs with the new loadtest contract

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_profiles.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add tests that lock:

- saved profiles can store loadtest defaults
- TUI shows loadtest choices without inventing a separate execution semantic
- docs/examples reference `scripts/controlplane.sh loadtest ...` and `scripts/e2e-loadtest.sh`

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py -v
```

Expected: FAIL until profile fields, TUI prompts, and docs are updated.

**Step 3: Write minimal implementation**

Add saved-profile fields for:

- default load profile
- metrics gate mode
- preferred scenario file or preset

Update docs to make `loadtest` the canonical terminology and point `pipeline-run` to compatibility status.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/profiles.py \
  tools/controlplane/src/controlplane_tool/tui.py \
  tools/controlplane/README.md \
  README.md \
  docs/e2e-tutorial.md \
  docs/testing.md \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py
git commit -m "docs: align tooling with first-class loadtest workflow"
```

### Task 5: Final verification for Milestone 5

**Files:**
- No new files; verification only

**Step 1: Run the focused tool test suite**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v
python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected: PASS.

**Step 2: Run CLI dry-runs**

Run:

```bash
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/e2e-loadtest.sh --dry-run
```

Expected: commands succeed and render one canonical loadtest plan.

**Step 3: Commit**

```bash
git add -A
git commit -m "chore: verify milestone 5 loadtest workflow"
```
