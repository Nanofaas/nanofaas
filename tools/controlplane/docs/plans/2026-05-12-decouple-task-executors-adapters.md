# Decouple Task Executors And Adapters Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `controlplane_tool.tasks` a logic-only package that can be moved into a future `tools/nanofaas-tasks/` uv library without depending on shellcraft, TUI/workflow rendering, CLI entrypoints, or controlplane runtime adapters.

**Architecture:** Keep task specs and task executors as pure logical abstractions. The task package owns only data models, rendering, executor protocols, and task-result mapping; concrete shell execution and TUI/workflow event conversion live outside `controlplane_tool.tasks`. Add import and source-scan boundary tests so the separation remains enforceable before the external library is created.

**Tech Stack:** Python 3.11, dataclasses, Protocol typing, pytest, basedpyright, import-linter, GitNexus impact checks, uv.

---

## Current State And Constraints

- `src/controlplane_tool/tasks/models.py` is already library-shaped.
- `src/controlplane_tool/tasks/rendering.py` is already library-shaped.
- `src/controlplane_tool/tasks/executors.py` currently imports `controlplane_tool.core.shell_backend`, which pulls in `shellcraft` and the TUI-aware `SubprocessShell` shim.
- `src/controlplane_tool/tasks/adapters.py` contains one pure adapter, `operation_to_task_spec`, and one controlplane-specific adapter, `task_result_to_shell_result`.
- `src/controlplane_tool/tasks/workflow.py` imports `controlplane_tool.workflow.workflow_models.WorkflowEvent`; that module re-exports `tui_toolkit` workflow primitives, so it does not belong in the future task library.
- Existing GitNexus impact checks found:
  - `HostCommandTaskExecutor`: HIGH risk, mostly tests/direct users.
  - `VmCommandTaskExecutor`: HIGH risk, mostly tests/direct users.
  - `task_result_to_shell_result`: LOW risk, direct tests only.
  - `operation_to_task_spec`: HIGH risk, used by scenario/VM planning.

Do not change user-facing CLI/TUI behavior. This is a dependency-boundary refactor.

## Target Shape

After this plan:

```text
controlplane_tool.tasks
  models.py        # pure task data
  rendering.py     # pure command rendering
  executors.py     # pure protocols + host/vm task executor logic
  adapters.py      # pure structural conversion to CommandTaskSpec

controlplane_tool.core
  task_shell_adapter.py  # bridges ShellBackend/ShellExecutionResult to task protocols

controlplane_tool.workflow
  task_events.py   # bridges task models/results to WorkflowEvent/TUI-compatible events
```

`controlplane_tool.tasks` must not import:

- `controlplane_tool.core`
- `controlplane_tool.workflow`
- `controlplane_tool.tui`
- `controlplane_tool.app`
- `controlplane_tool.cli`
- `controlplane_tool.orchestation`
- `shellcraft`
- `tui_toolkit`
- `typer`
- `questionary`
- `prefect`
- `multipass`

---

### Task 1: Make Host Command Execution Depend On A Protocol

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tasks/executors.py`
- Modify: `tools/controlplane/tests/test_task_executors.py`

**Step 1: Run GitNexus impact before editing**

Run from repo root:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"HostCommandTaskExecutor","direction":"upstream","includeTests":true,"maxDepth":3}'
```

Expected: HIGH risk. Confirm the direct production blast radius is limited and the known direct tests are in `tools/controlplane/tests/test_task_executors.py`.

**Step 2: Write the failing tests**

Edit `tools/controlplane/tests/test_task_executors.py` so it no longer imports `RecordingShell` or `ScriptedShell` from `controlplane_tool.core.shell_backend`.

Use local fakes instead:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pytest

from controlplane_tool.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from controlplane_tool.tasks.models import CommandTaskSpec


@dataclass(frozen=True)
class _CommandResult:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _RecordingCommandRunner:
    def __init__(self, *, return_code: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.return_code = return_code
        self.stdout = stdout
        self.stderr = stderr
        self.commands: list[tuple[list[str], Path | None, dict[str, str], bool]] = []

    def run(
        self,
        argv: list[str],
        *,
        cwd: Path | None,
        env: dict[str, str],
        dry_run: bool,
    ) -> _CommandResult:
        self.commands.append((argv, cwd, env, dry_run))
        return _CommandResult(
            return_code=self.return_code,
            stdout=self.stdout,
            stderr=self.stderr,
        )


def test_host_executor_runs_task_with_cwd_env_and_dry_run() -> None:
    runner = _RecordingCommandRunner()
    executor = HostCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        argv=("echo", "hi"),
        env={"A": "B"},
        cwd=Path("/repo"),
    )

    result = executor.run(task, dry_run=True)

    assert result.status == "passed"
    assert result.return_code == 0
    assert runner.commands == [(["echo", "hi"], Path("/repo"), {"A": "B"}, True)]


def test_host_executor_marks_nonzero_unexpected_code_as_failed() -> None:
    runner = _RecordingCommandRunner(return_code=1, stderr="failed")
    executor = HostCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))

    result = executor.run(task)

    assert result.status == "failed"
    assert result.return_code == 1
    assert result.stderr == "failed"


def test_host_executor_rejects_vm_tasks() -> None:
    executor = HostCommandTaskExecutor(runner=_RecordingCommandRunner())
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), target="vm")

    with pytest.raises(ValueError, match="cannot run 'vm' task"):
        executor.run(task)
```

Keep the existing VM executor fake/test in the same file.

**Step 3: Run the focused test to verify it fails**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_executors.py
```

Expected: FAIL because `HostCommandTaskExecutor.__init__` still accepts `shell=...` and `executors.py` still imports `controlplane_tool.core.shell_backend`.

**Step 4: Implement the pure executor protocol**

In `tools/controlplane/src/controlplane_tool/tasks/executors.py`, replace the shell imports with local protocols:

```python
from __future__ import annotations

from pathlib import Path
from typing import Protocol

from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


class CommandRunResult(Protocol):
    @property
    def return_code(self) -> int: ...

    @property
    def stdout(self) -> str: ...

    @property
    def stderr(self) -> str: ...


class HostCommandRunner(Protocol):
    def run(
        self,
        argv: list[str],
        *,
        cwd: Path | None,
        env: dict[str, str],
        dry_run: bool,
    ) -> CommandRunResult: ...


class HostCommandTaskExecutor:
    def __init__(self, runner: HostCommandRunner) -> None:
        self._runner = runner

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        if task.target != "host":
            raise ValueError(f"HostCommandTaskExecutor cannot run {task.target!r} task")
        command_result = self._runner.run(
            list(task.argv),
            cwd=task.cwd,
            env=dict(task.env),
            dry_run=dry_run,
        )
        status = "passed" if command_result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=command_result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=command_result.stdout,
            stderr=command_result.stderr,
        )
```

Keep `VmCommandResult`, `VmCommandRunner`, and `VmCommandTaskExecutor` in the same file. They are already protocol-shaped and should not import shell code.

**Step 5: Run the focused test to verify it passes**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_executors.py
```

Expected: PASS.

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/tasks/executors.py tools/controlplane/tests/test_task_executors.py
git commit -m "Decouple task executors from shell backend"
```

---

### Task 2: Add A Shell Adapter Outside The Task Package

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/core/task_shell_adapter.py`
- Create: `tools/controlplane/tests/test_task_shell_adapter.py`
- Modify: `tools/controlplane/pyproject.toml`

**Step 1: Write the failing tests**

Create `tools/controlplane/tests/test_task_shell_adapter.py`:

```python
from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.core.task_shell_adapter import (
    ShellCommandTaskRunner,
    task_result_to_shell_result,
)
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


def test_shell_command_task_runner_adapts_shell_backend_to_task_runner_protocol() -> None:
    shell = RecordingShell()
    runner = ShellCommandTaskRunner(shell=shell)

    result = runner.run(
        ["echo", "hi"],
        cwd=Path("/repo"),
        env={"A": "B"},
        dry_run=True,
    )

    assert result.return_code == 0
    assert shell.commands == [["echo", "hi"]]


def test_task_result_to_shell_result_preserves_command_and_output() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), env={"A": "B"})
    result = TaskResult(task_id="x", status="passed", return_code=0, stdout="hi\n", stderr="warn\n")

    shell_result = task_result_to_shell_result(task, result, dry_run=True)

    assert shell_result.command == ["echo", "hi"]
    assert shell_result.env == {"A": "B"}
    assert shell_result.return_code == 0
    assert shell_result.stdout == "hi\n"
    assert shell_result.stderr == "warn\n"
    assert shell_result.dry_run is True


def test_task_result_to_shell_result_maps_missing_failed_return_code_to_failure() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))
    result = TaskResult(task_id="x", status="failed", return_code=None)

    shell_result = task_result_to_shell_result(task, result)

    assert shell_result.return_code == 1


def test_task_result_to_shell_result_maps_missing_passed_return_code_to_success() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("true",))
    result = TaskResult(task_id="x", status="passed", return_code=None)

    shell_result = task_result_to_shell_result(task, result)

    assert shell_result.return_code == 0
```

**Step 2: Run the focused test to verify it fails**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_shell_adapter.py
```

Expected: FAIL because `controlplane_tool.core.task_shell_adapter` does not exist.

**Step 3: Implement the shell adapter**

Create `tools/controlplane/src/controlplane_tool/core/task_shell_adapter.py`:

```python
from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


class ShellCommandTaskRunner:
    """Adapter from the pure task runner protocol to the controlplane shell backend."""

    def __init__(self, shell: ShellBackend | None = None) -> None:
        self._shell = shell or SubprocessShell()

    def run(
        self,
        argv: list[str],
        *,
        cwd: Path | None,
        env: dict[str, str],
        dry_run: bool,
    ) -> ShellExecutionResult:
        return self._shell.run(
            argv,
            cwd=cwd,
            env=env,
            dry_run=dry_run,
        )


def task_result_to_shell_result(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    dry_run: bool = False,
) -> ShellExecutionResult:
    return_code = (
        result.return_code
        if result.return_code is not None
        else 0
        if result.status == "passed"
        else 1
    )
    return ShellExecutionResult(
        command=list(task.argv),
        return_code=return_code,
        stdout=result.stdout,
        stderr=result.stderr,
        dry_run=dry_run,
        env=dict(task.env),
    )
```

**Step 4: Include the new adapter in basedpyright**

In `tools/controlplane/pyproject.toml`, add the file to `[tool.basedpyright].include`:

```toml
"src/controlplane_tool/core/task_shell_adapter.py",
```

**Step 5: Run the focused test to verify it passes**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_shell_adapter.py
uv run basedpyright src/controlplane_tool/core/task_shell_adapter.py tests/test_task_shell_adapter.py
```

Expected: PASS and 0 basedpyright errors.

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/core/task_shell_adapter.py tools/controlplane/tests/test_task_shell_adapter.py tools/controlplane/pyproject.toml
git commit -m "Add shell adapter for task execution"
```

---

### Task 3: Remove Shell-Specific Conversion From Task Adapters

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tasks/adapters.py`
- Modify: `tools/controlplane/tests/test_task_adapters.py`

**Step 1: Run GitNexus impact before editing**

Run from repo root:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"task_result_to_shell_result","direction":"upstream","includeTests":true,"maxDepth":3}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"operation_to_task_spec","direction":"upstream","includeTests":true,"maxDepth":3}'
```

Expected:

- `task_result_to_shell_result`: LOW risk.
- `operation_to_task_spec`: HIGH risk. Do not change its behavior in this task.

**Step 2: Write the failing test change**

Edit `tools/controlplane/tests/test_task_adapters.py` so it only imports and tests `operation_to_task_spec`:

```python
from __future__ import annotations

from types import MappingProxyType

from controlplane_tool.scenario.components.operations import RemoteCommandOperation
from controlplane_tool.tasks.adapters import operation_to_task_spec


def test_remote_command_operation_converts_to_task_spec() -> None:
    operation = RemoteCommandOperation(
        operation_id="images.build",
        summary="Build image",
        argv=("docker", "build", "."),
        env=MappingProxyType({"A": "B"}),
        execution_target="vm",
    )

    task = operation_to_task_spec(operation)

    assert task.task_id == "images.build"
    assert task.summary == "Build image"
    assert task.argv == ("docker", "build", ".")
    assert task.env == {"A": "B"}
    assert task.target == "vm"
```

**Step 3: Run the focused tests**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_adapters.py tests/test_task_shell_adapter.py
```

Expected: PASS at this point if Task 2 is done. The tests now express the desired ownership split.

**Step 4: Remove shell imports and conversion from task adapters**

In `tools/controlplane/src/controlplane_tool/tasks/adapters.py`, remove:

```python
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.tasks.models import TaskResult
```

Delete the whole `task_result_to_shell_result` function from this file.

Keep only:

```python
from __future__ import annotations

from collections.abc import Mapping
from typing import Protocol

from controlplane_tool.tasks.models import CommandTaskSpec, ExecutionTarget


class RemoteCommandOperationLike(Protocol):
    @property
    def operation_id(self) -> str: ...

    @property
    def summary(self) -> str: ...

    @property
    def argv(self) -> tuple[str, ...]: ...

    @property
    def env(self) -> Mapping[str, str]: ...

    @property
    def execution_target(self) -> str: ...


def operation_to_task_spec(
    operation: RemoteCommandOperationLike,
    *,
    remote_dir: str | None = None,
) -> CommandTaskSpec:
    target: ExecutionTarget = "vm" if operation.execution_target == "vm" else "host"
    return CommandTaskSpec(
        task_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(operation.argv),
        target=target,
        env=dict(operation.env),
        remote_dir=remote_dir if target == "vm" else None,
    )
```

**Step 5: Run the focused tests and type check**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_adapters.py tests/test_task_shell_adapter.py
uv run basedpyright src/controlplane_tool/tasks src/controlplane_tool/core/task_shell_adapter.py tests/test_task_adapters.py tests/test_task_shell_adapter.py
```

Expected: PASS and 0 basedpyright errors.

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/tasks/adapters.py tools/controlplane/tests/test_task_adapters.py
git commit -m "Keep shell conversion outside task adapters"
```

---

### Task 4: Move Task-To-Workflow Event Conversion Out Of Tasks

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/workflow/task_events.py`
- Modify: `tools/controlplane/tests/test_task_workflow_bridge.py`
- Delete: `tools/controlplane/src/controlplane_tool/tasks/workflow.py`

**Step 1: Run GitNexus impact before editing**

Run from repo root:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"task_started_event","direction":"upstream","includeTests":true,"maxDepth":3}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"task_result_event","direction":"upstream","includeTests":true,"maxDepth":3}'
```

Expected: direct references are in `tests/test_task_workflow_bridge.py`. If GitNexus reports more direct references, update those imports in this task too.

**Step 2: Write the failing test change**

Edit `tools/controlplane/tests/test_task_workflow_bridge.py` so the bridge functions come from `controlplane_tool.workflow.task_events`:

```python
from __future__ import annotations

from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
from controlplane_tool.tui.prefect_bridge import TuiPrefectBridge
from controlplane_tool.workflow.task_events import task_result_event, task_started_event
```

Keep the existing test bodies unchanged.

**Step 3: Run the focused test to verify it fails**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_workflow_bridge.py
```

Expected: FAIL because `controlplane_tool.workflow.task_events` does not exist.

**Step 4: Implement the workflow bridge outside tasks**

Create `tools/controlplane/src/controlplane_tool/workflow/task_events.py`:

```python
from __future__ import annotations

from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
from controlplane_tool.workflow.workflow_models import WorkflowEvent


def task_started_event(task: CommandTaskSpec, *, flow_id: str) -> WorkflowEvent:
    return WorkflowEvent(
        kind="task.running",
        flow_id=flow_id,
        task_id=task.task_id,
        title=task.summary,
    )


def task_result_event(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    flow_id: str,
) -> WorkflowEvent:
    return WorkflowEvent(
        kind=_result_event_kind(result),
        flow_id=flow_id,
        task_id=task.task_id,
        title=task.summary,
        detail=_result_event_detail(result),
    )


def _result_event_kind(result: TaskResult) -> str:
    if result.ok:
        return "task.completed"
    if result.status == "skipped":
        return "task.completed"
    return "task.failed"


def _result_event_detail(result: TaskResult) -> str:
    return result.stderr.strip() or result.stdout.strip() or result.status
```

**Step 5: Delete the old workflow module under tasks**

Delete:

```text
tools/controlplane/src/controlplane_tool/tasks/workflow.py
```

Use `git rm`:

```bash
git rm tools/controlplane/src/controlplane_tool/tasks/workflow.py
```

**Step 6: Run the focused tests**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_workflow_bridge.py tests/test_tui_prefect_bridge.py
```

Expected: PASS.

**Step 7: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/workflow/task_events.py tools/controlplane/tests/test_task_workflow_bridge.py
git add -u tools/controlplane/src/controlplane_tool/tasks/workflow.py
git commit -m "Move task workflow events out of task package"
```

---

### Task 5: Add Boundary Tests For Logic-Only Tasks

**Files:**
- Create: `tools/controlplane/tests/test_task_package_boundaries.py`
- Modify: `tools/controlplane/.importlinter`

**Step 1: Write source-scan boundary tests**

Create `tools/controlplane/tests/test_task_package_boundaries.py`:

```python
from __future__ import annotations

from pathlib import Path


TASK_PACKAGE = Path(__file__).resolve().parents[1] / "src" / "controlplane_tool" / "tasks"

FORBIDDEN_IMPORT_TOKENS = (
    "controlplane_tool.core",
    "controlplane_tool.workflow",
    "controlplane_tool.tui",
    "controlplane_tool.app",
    "controlplane_tool.cli",
    "controlplane_tool.cli_validation",
    "controlplane_tool.orchestation",
    "shellcraft",
    "tui_toolkit",
    "typer",
    "questionary",
    "prefect",
    "multipass",
)


def test_task_package_contains_only_logic_modules() -> None:
    modules = sorted(path.name for path in TASK_PACKAGE.glob("*.py"))

    assert modules == [
        "__init__.py",
        "adapters.py",
        "executors.py",
        "models.py",
        "rendering.py",
    ]


def test_task_package_does_not_import_runtime_or_ui_boundaries() -> None:
    for path in TASK_PACKAGE.glob("*.py"):
        text = path.read_text(encoding="utf-8")
        for token in FORBIDDEN_IMPORT_TOKENS:
            assert token not in text, f"{path} imports or references {token}"
```

**Step 2: Run the test to verify it passes after Tasks 1-4**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_task_package_boundaries.py
```

Expected: PASS after the earlier tasks are complete. If it fails, fix the remaining forbidden dependency rather than relaxing the test.

**Step 3: Add import-linter contract**

Append this contract to `tools/controlplane/.importlinter`:

```ini
[importlinter:contract:tasks_are_logic_only]
name = tasks must not depend on runtime, orchestration, cli, app, or tui packages
type = forbidden
source_modules =
    controlplane_tool.tasks
forbidden_modules =
    controlplane_tool.app
    controlplane_tool.building
    controlplane_tool.cli
    controlplane_tool.cli_validation
    controlplane_tool.core
    controlplane_tool.e2e
    controlplane_tool.functions
    controlplane_tool.infra
    controlplane_tool.loadtest
    controlplane_tool.orchestation
    controlplane_tool.scenario
    controlplane_tool.sut
    controlplane_tool.tui
    controlplane_tool.workflow
    controlplane_tool.workspace
```

**Step 4: Run import-linter**

Run:

```bash
cd tools/controlplane
uv run lint-imports
uv run pytest -q tests/test_import_contracts.py tests/test_task_package_boundaries.py
```

Expected:

- import-linter reports 5 kept contracts.
- tests PASS.

**Step 5: Commit**

Run:

```bash
git add tools/controlplane/.importlinter tools/controlplane/tests/test_task_package_boundaries.py
git commit -m "Enforce logic-only task package boundary"
```

---

### Task 6: Update Extraction Readiness Documentation

**Files:**
- Modify: `tools/controlplane/docs/plans/2026-05-06-task-extraction-readiness.md`

**Step 1: Update the readiness checklist**

Edit `tools/controlplane/docs/plans/2026-05-06-task-extraction-readiness.md` so it says the task package is ready for a direct move only when these are true:

```markdown
- `src/controlplane_tool/tasks/` contains only logic modules: `models.py`, `rendering.py`, `executors.py`, and `adapters.py`.
- `HostCommandTaskExecutor` depends on a local runner Protocol, not `ShellBackend` or `SubprocessShell`.
- Shell adapters live outside the task package, currently in `controlplane_tool.core.task_shell_adapter`.
- Task-to-workflow/TUI event conversion lives outside the task package, currently in `controlplane_tool.workflow.task_events`.
- Import-linter includes `tasks_are_logic_only`.
- A source-scan regression test forbids direct imports of `shellcraft`, `tui_toolkit`, `typer`, `questionary`, `prefect`, and `multipass` from the task package.
```

Keep the existing recommendation that the first external project should be NanoFaaS-specific:

```text
tools/nanofaas-tasks/
  pyproject.toml
  src/nanofaas_tasks/
  tests/
```

**Step 2: Run docs/link-adjacent tests**

Run:

```bash
cd tools/controlplane
uv run pytest -q tests/test_docs_links.py tests/test_architecture_docs.py
```

Expected: PASS.

**Step 3: Commit**

Run:

```bash
git add tools/controlplane/docs/plans/2026-05-06-task-extraction-readiness.md
git commit -m "Document task library extraction boundary"
```

---

### Task 7: Final Verification And GitNexus Scope Check

**Files:**
- No new source files expected.

**Step 1: Run focused task-library verification**

Run:

```bash
cd tools/controlplane
uv run pytest -q \
  tests/test_task_models.py \
  tests/test_task_rendering.py \
  tests/test_task_executors.py \
  tests/test_task_adapters.py \
  tests/test_task_shell_adapter.py \
  tests/test_task_workflow_bridge.py \
  tests/test_task_package_boundaries.py \
  tests/test_import_contracts.py
```

Expected: PASS.

**Step 2: Run static checks**

Run:

```bash
cd tools/controlplane
uv run ruff check src/controlplane_tool/tasks src/controlplane_tool/core/task_shell_adapter.py src/controlplane_tool/workflow/task_events.py tests/test_task_executors.py tests/test_task_adapters.py tests/test_task_shell_adapter.py tests/test_task_workflow_bridge.py tests/test_task_package_boundaries.py
uv run basedpyright src/controlplane_tool/tasks src/controlplane_tool/core/task_shell_adapter.py src/controlplane_tool/workflow/task_events.py tests/test_task_executors.py tests/test_task_adapters.py tests/test_task_shell_adapter.py tests/test_task_workflow_bridge.py tests/test_task_package_boundaries.py
uv run lint-imports
```

Expected:

- Ruff passes.
- basedpyright reports 0 errors.
- import-linter reports 5 kept contracts.

**Step 3: Run the full Python quality gate**

Run:

```bash
cd tools/controlplane
uv run controlplane-quality
```

Expected: PASS.

**Step 4: Run GitNexus detect changes before finishing**

Run from repo root:

```bash
codex mcp call gitnexus detect_changes '{"repo":"mcFaas","scope":"all"}'
```

Expected: changes are limited to:

- `tools/controlplane/src/controlplane_tool/tasks/executors.py`
- `tools/controlplane/src/controlplane_tool/tasks/adapters.py`
- `tools/controlplane/src/controlplane_tool/tasks/workflow.py` deletion
- `tools/controlplane/src/controlplane_tool/core/task_shell_adapter.py`
- `tools/controlplane/src/controlplane_tool/workflow/task_events.py`
- task-related tests
- `.importlinter`
- extraction readiness docs

If GitNexus reports unexpected affected flows outside task execution, VM planning, or TUI workflow event rendering, stop and inspect before committing.

**Step 5: Final commit if anything remains**

Run:

```bash
git add -A tools/controlplane
git commit -m "Prepare task package for library extraction"
```

Skip this commit if all previous task commits already captured the work.

---

## Completion Criteria

The work is complete when:

- `controlplane_tool.tasks` imports only stdlib and `controlplane_tool.tasks.*`.
- `HostCommandTaskExecutor` has no default shell implementation and accepts an injected runner Protocol.
- `task_result_to_shell_result` lives outside `controlplane_tool.tasks`.
- Task-to-workflow event conversion lives outside `controlplane_tool.tasks`.
- import-linter enforces the internal package boundary.
- tests enforce the absence of UI/runtime/orchestration dependencies from task logic.
- focused tests, static checks, `controlplane-quality`, and GitNexus change detection pass.

At that point, creating `tools/nanofaas-tasks/` becomes mostly a direct package move plus import rewrite, not a design cleanup.
