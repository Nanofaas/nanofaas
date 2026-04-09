# Controlplane Tooling M4-M8 Remediation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the concrete regressions found in the M4-M8 branch and finish the missing M7-M8 work so the TUI, CLI, and workflow runtime actually share one Prefect-oriented orchestration model.

**Architecture:** Keep the existing normalized workflow event model from M1-M3, then finish the migration in two layers. First, repair concrete runtime bugs and replace TUI-owned orchestration with flow launch through the shared runtime facade. Second, add a declarative flow catalog and optional Prefect deployment metadata so local execution and future remote orchestration resolve the same flow definitions without placeholder no-op flows.

**Tech Stack:** Python 3.11, Prefect 3 local runtime facade, Typer, Rich, pytest, existing `tools/controlplane` workflow/event models.

---

## Scope

- fix the `mockk8s` pipeline runtime regression introduced in `M4`
- complete `M7` so the TUI launches and monitors shared flows instead of calling legacy runners directly
- complete `M8` so flow lookup and optional deployment metadata exist as planned
- remove placeholder no-op flow execution paths such as `k8s-vm` without a request payload

## Explicit Non-Goals

- fixing the 4 pre-existing unrelated suite failures already present on the branch
- redesigning scenario semantics, profiles, or the Rich dashboard look and feel
- requiring a remote Prefect server for local execution

## Acceptance Criteria

- `build_pipeline_flow()` executes the `mockk8s` step without `NameError`
- the TUI launches shared flows through `run_local_flow()` and consumes normalized workflow events
- `build_scenario_flow(..., request=None)` is no longer silently executable for runtime scenarios
- a declarative flow catalog resolves supported flow names to executable definitions and task IDs
- optional deployment metadata exists for known flows without becoming mandatory for local runs

---

### Task 1: Fix the `mockk8s` pipeline regression

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/infra_flows.py`
- Modify: `tools/controlplane/tests/test_pipeline.py`
- Modify: `tools/controlplane/tests/test_infra_flows.py`

**Step 1: Write the failing tests**

Add a regression test that executes `build_pipeline_flow(...).run()` with:

```python
tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=True, metrics=False)
```

and asserts:

```python
assert result.final_status == "passed"
assert any(step.name == "test_e2e_mockk8s" for step in result.steps)
```

Also add a narrow test that fails if `mockk8s_tests_task` is not wired into the pipeline flow module.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_infra_flows.py -q
```

Expected: FAIL with `NameError` or missing-step assertions.

**Step 3: Write minimal implementation**

Import and wire `mockk8s_tests_task` in `infra_flows.py` alongside the other shared build tasks, and keep the flow step naming contract unchanged:

```python
from controlplane_tool.build_tasks import (
    ...
    mockk8s_tests_task,
    ...
)
```

Do not broaden the refactor here. Fix only the concrete runtime regression and its local tests.

**Step 4: Run test to verify it passes**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/infra_flows.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/infra_flows.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_infra_flows.py
git commit -m "fix: restore mockk8s pipeline task wiring"
```

---

### Task 2: Introduce the missing TUI Prefect bridge

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py`
- Create: `tools/controlplane/tests/test_tui_prefect_bridge.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow.py`
- Modify: `tools/controlplane/src/controlplane_tool/console.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`

**Step 1: Write the failing tests**

Add tests that require the bridge to consume normalized workflow events:

```python
def test_tui_bridge_maps_task_started_event_to_running_step() -> None:
    bridge = TuiPrefectBridge()
    bridge.handle_event(task_started("vm.ensure_running"))
    snapshot = bridge.snapshot()
    assert snapshot.phases[0].task_id == "vm.ensure_running"
    assert snapshot.phases[0].status == "running"


def test_tui_bridge_preserves_log_buffer_across_toggle() -> None:
    bridge = TuiPrefectBridge()
    bridge.handle_event(log_line("images.build_core", "docker push ok"))
    bridge.toggle_logs()
    bridge.toggle_logs()
    assert "docker push ok" in bridge.snapshot().logs[-1]
```

Add a console/TUI test proving `task.updated`, `task.cancelled`, and `log.line` keep flowing through the same event path.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_tui_prefect_bridge.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_console_workflow.py -q
```

Expected: FAIL because the bridge module does not exist yet.

**Step 3: Write minimal implementation**

Implement `TuiPrefectBridge` as a small adapter from normalized workflow events to the existing Rich dashboard model:

- accept `WorkflowEvent`
- keep a persistent log buffer
- map semantic `task_id` values to dashboard rows
- expose a `snapshot()` or equivalent view model for rendering

Keep Rich rendering in `tui_workflow.py`. Do not put rendering code inside the bridge.

**Step 4: Run test to verify it passes**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py \
  tools/controlplane/src/controlplane_tool/tui_workflow.py \
  tools/controlplane/src/controlplane_tool/console.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py \
  tools/controlplane/src/controlplane_tool/tui_workflow.py \
  tools/controlplane/src/controlplane_tool/console.py \
  tools/controlplane/tests/test_tui_prefect_bridge.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_console_workflow.py
git commit -m "refactor: add prefect bridge for tui workflow state"
```

---

### Task 3: Rewire the TUI to launch shared flows instead of legacy runners

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`

**Step 1: Write the failing tests**

Add TUI-level tests that monkeypatch `run_local_flow` and assert menu actions call the shared runtime instead of direct runner methods:

```python
def test_tui_vm_menu_runs_vm_flow_via_runtime(monkeypatch) -> None:
    ...
    assert called["flow_id"] == "vm.provision_base"


def test_tui_loadtest_menu_runs_loadtest_flow_via_runtime(monkeypatch) -> None:
    ...
    assert called["flow_id"].startswith("loadtest.")
```

Add at least one `k8s-vm` or `cli` scenario test asserting the TUI does not call `E2eRunner.execute()` directly.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py -q
```

Expected: FAIL because `tui_app.py` still calls `VmOrchestrator`, `E2eRunner`, and legacy runner objects directly.

**Step 3: Write minimal implementation**

Refactor `tui_app.py` so each live workflow:

- builds the relevant flow definition through the shared flow builder
- launches it through `run_local_flow()`
- feeds normalized events into `TuiPrefectBridge`

Specific constraints:

- remove direct calls like `orchestrator.ensure_running(...)`, `runner.run()`, and `runner.execute(...)` from TUI action handlers
- keep existing prompt UX and dashboard layout
- reuse the same `task_ids` and flow IDs exposed to CLI commands

If a scenario needs runtime inputs, construct them first, then build an executable flow definition from shared code. Do not leave placeholder `run=lambda: None` branches reachable from the TUI.

**Step 4: Run test to verify it passes**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_vm_commands.py \
  tools/controlplane/tests/test_cli_commands.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_loadtest_commands.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/tui_app.py \
  tools/controlplane/src/controlplane_tool/infra_flows.py \
  tools/controlplane/src/controlplane_tool/scenario_flows.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py
git commit -m "refactor: run tui workflows through shared flow runtime"
```

---

### Task 4: Add the declarative flow catalog and remove placeholder executable no-ops

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Create: `tools/controlplane/tests/test_flow_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/local_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`

**Step 1: Write the failing tests**

Add tests that require one catalog entrypoint for local flow resolution:

```python
def test_flow_catalog_resolves_k8s_vm_to_executable_flow_definition() -> None:
    definition = resolve_flow_definition("e2e.k8s-vm", request=sample_request())
    assert definition.flow_id == "e2e.k8s_vm"
    assert "vm.ensure_running" in definition.task_ids


def test_requestless_runtime_scenario_definition_is_not_silently_executable() -> None:
    with pytest.raises(ValueError):
        build_scenario_flow("k8s-vm", repo_root=Path("/repo"))
```

Add command-level tests proving dry-run paths read task metadata from the catalog instead of placeholder no-op flow objects.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_cli_commands.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_loadtest_commands.py -q
```

Expected: FAIL because no flow catalog exists and `k8s-vm` without a request currently returns a no-op executable.

**Step 3: Write minimal implementation**

Implement a small `flow_catalog.py` that:

- resolves stable user-facing names to flow builders
- separates `task_ids` discovery from executable `run` construction
- refuses to create silently executable runtime flows when required inputs are absent

Then switch CLI entrypoints and dry-run rendering to use the catalog instead of `build_*_flow(..., request=None)` placeholder definitions.

**Step 4: Run test to verify it passes**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/flow_catalog.py \
  tools/controlplane/src/controlplane_tool/scenario_flows.py \
  tools/controlplane/src/controlplane_tool/infra_flows.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/flow_catalog.py \
  tools/controlplane/src/controlplane_tool/scenario_flows.py \
  tools/controlplane/src/controlplane_tool/infra_flows.py \
  tools/controlplane/src/controlplane_tool/cli_commands.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/src/controlplane_tool/local_e2e_commands.py \
  tools/controlplane/src/controlplane_tool/cli_e2e_commands.py \
  tools/controlplane/src/controlplane_tool/k3s_e2e_commands.py \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/pipeline.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_cli_commands.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_loadtest_commands.py
git commit -m "feat: add declarative flow catalog for local workflows"
```

---

### Task 5: Add optional Prefect deployment readiness artifacts

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/prefect_deployments.py`
- Create: `tools/controlplane/tests/test_prefect_deployments.py`
- Create: `tools/controlplane/prefect.yaml`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Modify: `tools/controlplane/tests/test_profiles.py`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`

**Step 1: Write the failing tests**

Add tests for optional deployment metadata:

```python
def test_prefect_deployment_spec_is_optional_for_local_runs() -> None:
    assert build_prefect_deployment("e2e.k8s_vm", enabled=False) is None


def test_prefect_deployment_spec_includes_known_flow_name() -> None:
    deployment = build_prefect_deployment("e2e.k8s_vm", enabled=True)
    assert deployment.flow_id == "e2e.k8s_vm"
```

Add a simple test that `prefect.yaml` includes at least one known deployment target or template reference for a supported flow.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_prefect_deployments.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py -q
```

Expected: FAIL because the deployment helper and config file do not exist yet.

**Step 3: Write minimal implementation**

Implement a minimal deployment helper and configuration layer that:

- resolves only known flow IDs from the new catalog
- returns `None` when remote deployment is disabled
- leaves local execution unchanged
- documents the intended local vs optional remote usage in `README.md`

Do not add any requirement for a running Prefect API server.

**Step 4: Run test to verify it passes**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/prefect_deployments.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/prefect_deployments.py \
  tools/controlplane/prefect.yaml \
  tools/controlplane/README.md \
  tools/controlplane/src/controlplane_tool/profiles.py \
  tools/controlplane/src/controlplane_tool/scenario_loader.py \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/tests/test_prefect_deployments.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py
git commit -m "feat: add optional prefect deployment metadata"
```

---

### Task 6: Run the final remediation verification sweep

**Files:**
- Modify only if needed based on failing verification

**Step 1: Run the focused remediation suite**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_infra_flows.py \
  tools/controlplane/tests/test_tui_prefect_bridge.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_console_workflow.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_prefect_deployments.py \
  tools/controlplane/tests/test_cli_commands.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_k3s_e2e_commands.py -q
```

Expected: PASS.

**Step 2: Run the full tooling suite**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
```

Expected:

- no new failures introduced by the remediation work
- if the same 4 unrelated branch failures remain, record them explicitly in the handoff

**Step 3: Run compile verification**

Run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/*.py
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/controlplane
git commit -m "test: verify m4-m8 prefect remediation"
```

---

## Notes for Execution

- Treat `Task 1` as mandatory first, because it is a concrete runtime bug already reproducible.
- Do not fold `Task 4` into `Task 3`; the catalog is what removes the placeholder no-op flow trap cleanly.
- If TUI refactoring reveals more direct runner invocations, keep removing them in `Task 3` until TUI launch paths all go through `run_local_flow()`.
- Preserve local-first behavior throughout. `PREFECT_API_URL` must remain optional.
