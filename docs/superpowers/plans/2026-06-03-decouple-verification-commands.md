# Decouple verification planners from controlplane-tool (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the three `workflow_tasks` verification planners read the controlplane-tool command from the execution context instead of hard-coding it, and have controlplane inject those commands — so the library no longer knows how to invoke controlplane.

**Architecture:** Add three neutral command fields to the library's `ScenarioExecutionContext` dataclass; controlplane (which builds the context) populates them with its controlplane-tool argv; the three planners read `context.*_command` (a guard raises if unset). Ordered so every task leaves BOTH suites green: (1) add inert context fields, (2) controlplane injects them (planners still hard-code — inert), (3) flip the planners to read the injected commands + update tests.

**Tech Stack:** Python, dataclasses, pytest, uv.

**Commands:** library `uv run --project tools/workflow-tasks pytest <path>`; controlplane `uv run --project tools/controlplane pytest <path>`. Branch: `refactor/wt-decouple-verification-commands` (created). Spec: `docs/superpowers/specs/2026-06-03-decouple-verification-commands-design.md`. Baseline both suites green.

**Verified facts:**
- `ScenarioExecutionContext` = frozen dataclass in `tools/workflow-tasks/src/workflow_tasks/components/context.py` (fields end with `manifest_path`, `release`, `loadgen_vm_request`, all with defaults).
- The 3 planners are in `tools/workflow-tasks/src/workflow_tasks/components/verification.py`; their current argv is hard-coded (see Task 3).
- Library test: `tools/workflow-tasks/tests/components/test_verification.py` builds the context via a `_ctx(...)` helper.
- Controlplane builds the context at exactly 2 src sites: `scenario/components/environment.py:61` (factory, has `repo_root`) and `infra/vm/vm_cluster_workflows.py:116` (a "legacy" context, has `vm.repo_root`). `two_vm_loadtest._loadgen_context` uses `dataclasses.replace` (carries new fields automatically).

---

### Task 1: Add inert command fields to the context

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/context.py`

- [ ] **Step 1: Add the three fields**

At the end of the `ScenarioExecutionContext` dataclass (after `loadgen_vm_request`), add:
```python
    # Controlplane-tool verification commands, injected by the controlplane context
    # factory. Empty by default so this library module stays controlplane-agnostic.
    k3s_curl_verify_command: tuple[str, ...] = ()
    loadtest_run_command: tuple[str, ...] = ()
    autoscaling_command: tuple[str, ...] = ()
```

- [ ] **Step 2: Both suites stay green (inert change)**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks -q 2>&1 | tail -3`
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3`
Expected: both pass (the fields are unused so far).

- [ ] **Step 3: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/context.py
git commit -m "feat(workflow-tasks): add injectable verification-command fields to context"
```

---

### Task 2: Controlplane defines and injects the commands

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/components/verification_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/environment.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py`

- [ ] **Step 1: Create the command builders**

Create `tools/controlplane/src/controlplane_tool/scenario/components/verification_commands.py`:
```python
from __future__ import annotations

from pathlib import Path


def k3s_curl_verify_command() -> tuple[str, ...]:
    return ("python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack")


def loadtest_run_command() -> tuple[str, ...]:
    return ("uv", "run", "--project", "tools/controlplane", "--locked",
            "controlplane-tool", "loadtest", "run")


def autoscaling_command(repo_root: Path) -> tuple[str, ...]:
    return ("uv", "run", "--project", str(Path(repo_root) / "tools" / "controlplane"),
            "--locked", "python", str(Path(repo_root) / "experiments" / "autoscaling.py"))
```
(These argv are copied verbatim from the planners' current hard-coded values so the command snapshots do not change.)

- [ ] **Step 2: Inject in the factory (`environment.py`)**

In `tools/controlplane/src/controlplane_tool/scenario/components/environment.py`, add the import near the top:
```python
from controlplane_tool.scenario.components import verification_commands as _vc
```
In the `ScenarioExecutionContext(...)` construction (the `return ScenarioExecutionContext(` block around line 61), add the three fields (after `loadgen_vm_request=...`):
```python
        k3s_curl_verify_command=_vc.k3s_curl_verify_command(),
        loadtest_run_command=_vc.loadtest_run_command(),
        autoscaling_command=_vc.autoscaling_command(repo_root),
```
(`repo_root` is already in scope in this factory.)

- [ ] **Step 3: Inject in `vm_cluster_workflows.py`**

In `tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py`, add the import near the top:
```python
from controlplane_tool.scenario.components import verification_commands as _vc
```
In the `scenario_context = ScenarioExecutionContext(` block (around line 116), add after `cleanup_vm=True,`:
```python
        k3s_curl_verify_command=_vc.k3s_curl_verify_command(),
        loadtest_run_command=_vc.loadtest_run_command(),
        autoscaling_command=_vc.autoscaling_command(vm.repo_root),
```

- [ ] **Step 4: Both suites stay green (still inert — planners ignore the fields)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3` → pass.
Run: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter` → 0 broken.
Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/components/verification_commands.py tools/controlplane/src/controlplane_tool/scenario/components/environment.py tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py` → clean.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/verification_commands.py tools/controlplane/src/controlplane_tool/scenario/components/environment.py tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py
git commit -m "feat(controlplane): define + inject verification commands into the scenario context"
```

---

### Task 3: Flip the planners to read the injected commands

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/verification.py`
- Modify: `tools/workflow-tasks/tests/components/test_verification.py`

- [ ] **Step 1: Update the library tests first (they will fail)**

In `tools/workflow-tasks/tests/components/test_verification.py`, extend the `_ctx(...)` helper to inject test commands, and update/add the planner assertions.

Change `_ctx` to accept the commands (with non-empty test defaults):
```python
def _ctx(*, manifest: Path | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
        manifest_path=manifest,
        k3s_curl_verify_command=("verify", "k3s"),
        loadtest_run_command=("loadtest", "go"),
        autoscaling_command=("autoscale", "go"),
    )
```
Replace the existing `test_run_k3s_curl_checks_runs_controlplane_runner` body to assert the INJECTED command, and add loadtest/autoscaling + empty-guard tests:
```python
def test_run_k3s_curl_checks_uses_injected_command() -> None:
    ops = ver.plan_run_k3s_curl_checks(_ctx())
    assert ops[0].argv == ("verify", "k3s")


def test_loadtest_run_uses_injected_command() -> None:
    ops = ver.plan_loadtest_run(_ctx())
    assert ops[0].argv == ("loadtest", "go")
    assert ops[0].execution_target == "vm"


def test_autoscaling_uses_injected_command() -> None:
    ops = ver.plan_autoscaling_experiment(_ctx())
    assert ops[0].argv == ("autoscale", "go")


def test_planner_raises_when_command_not_injected() -> None:
    import dataclasses
    import pytest

    ctx = dataclasses.replace(_ctx(), loadtest_run_command=())
    with pytest.raises(ValueError):
        ver.plan_loadtest_run(ctx)
```
(If the old `test_run_k3s_curl_checks_runs_controlplane_runner` asserted the hard-coded `controlplane_tool` argv, delete it — it is replaced by `test_run_k3s_curl_checks_uses_injected_command`.)

- [ ] **Step 2: Run the library tests — they fail**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_verification.py -q`
Expected: FAIL (planners still return the hard-coded argv, not the injected one).

- [ ] **Step 3: Rewrite the three planners to read the context**

In `tools/workflow-tasks/src/workflow_tasks/components/verification.py`, add a guard helper near the top (after the imports / existing helpers):
```python
def _require_command(command: tuple[str, ...], name: str) -> tuple[str, ...]:
    if not command:
        raise ValueError(f"context.{name} was not provided by the context factory")
    return command
```
Replace the three planners:
```python
def plan_run_k3s_curl_checks(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="tests.run_k3s_curl_checks",
            summary="Run k3s-junit-curl verification",
            argv=_require_command(context.k3s_curl_verify_command, "k3s_curl_verify_command"),
            env=_frozen_env(),
        ),
    )


def plan_loadtest_run(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    env = dict(_managed_vm_env(context))
    remote_manifest_path = _remote_manifest_path(context)
    if remote_manifest_path is not None:
        env["NANOFAAS_SCENARIO_PATH"] = remote_manifest_path
    return (
        RemoteCommandOperation(
            operation_id="loadtest.run",
            summary="Run k6 loadtest via controlplane runner",
            argv=_require_command(context.loadtest_run_command, "loadtest_run_command"),
            env=_frozen_env(env),
            execution_target="vm",
        ),
    )


def plan_autoscaling_experiment(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    env = dict(_managed_vm_env(context))
    if context.manifest_path is not None:
        env["NANOFAAS_SCENARIO_PATH"] = str(context.manifest_path)
    return (
        RemoteCommandOperation(
            operation_id="experiments.autoscaling",
            summary="Run autoscaling experiment (Python)",
            argv=_require_command(context.autoscaling_command, "autoscaling_command"),
            env=_frozen_env(env),
        ),
    )
```
(Removes the `controlplane_tool_project = ...` lines and the hard-coded argv tuples. `plan_run_k8s_junit` and all other planners are unchanged. If `Path` is now unused in the file after removing the autoscaling `Path(...)` usage, leave it if other code uses it; if ruff flags it as unused, remove the import.)

- [ ] **Step 4: Run the library tests — they pass**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_verification.py -q` → pass.
Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks -q 2>&1 | tail -3` → all pass, coverage ≥ 90%.

- [ ] **Step 5: Controlplane suite is now green again (commands injected in Task 2)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3`
Expected: 0 failures. The loadtest/k3s-curl/autoscaling command snapshots match because controlplane injects the same argv. If a snapshot mismatches, compare the injected `verification_commands.*` argv against the expected snapshot and reconcile the command builder (do NOT change behavior).

- [ ] **Step 6: Verify the invocation coupling is gone + lint**

Run: `grep -n "controlplane_tool\|controlplane-tool" tools/workflow-tasks/src/workflow_tasks/components/verification.py`
Expected: EMPTY (no controlplane invocation argv left; the only remaining controlplane string anywhere in the library is the `tools/controlplane/runs/manifests` path in `_remote_manifest_path`, which is level-B and out of scope — confirm it is the ONLY remaining hit via `grep -rn "controlplane" tools/workflow-tasks/src/workflow_tasks/components/verification.py`).
Run: `uv run --project tools/workflow-tasks ruff check tools/workflow-tasks/src/workflow_tasks/components/verification.py` → clean.
Run: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter` → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/verification.py tools/workflow-tasks/tests/components/test_verification.py
git commit -m "refactor(workflow-tasks): verification planners read injected commands (no controlplane-tool invocation)"
```

---

## Self-Review

- **Spec coverage:** context fields → Task 1; controlplane command builders + injection at both construction sites → Task 2; planners read context + guard + tests → Task 3; snapshots unchanged → Task 3 Step 5 (same argv injected); invocation coupling gone → Task 3 Step 6; level-B path strings explicitly left → noted. ✓
- **Ordering:** every task leaves both suites green — T1 inert (unused fields), T2 inert (planners still hard-code), T3 flips + tests in lockstep. The guard's empty-raise can only trigger if a context isn't injected; both src construction sites are injected in T2, and `_loadgen_context` uses `replace` (carries the fields). ✓
- **Placeholder scan:** none — full code for the fields, the 3 command builders, both injection edits, the guard, the 3 rewritten planners, and every test. Exact grep/run commands with expected output.
- **Type consistency:** field names `k3s_curl_verify_command` / `loadtest_run_command` / `autoscaling_command` are identical across the context dataclass (T1), the controlplane injection (T2), the planners' `_require_command(context.<name>, "<name>")` (T3), and the tests. The builder `autoscaling_command(repo_root)` takes `repo_root`; both call sites pass a real repo root (`repo_root` / `vm.repo_root`). ✓
