# Stabilize Task Model Before Extraction Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stabilize the internal task model used by `controlplane-tool` so it can later be extracted into a NanoFaaS-specific uv project, similar to `tui-toolkit`.

**Architecture:** Introduce a canonical internal task model first, then migrate existing scenario, VM, build, loadtest, CLI, and TUI adapters onto it without changing external behavior. Keep execution concerns separate from task descriptions: task specs are data, executors run them, renderers display them, and adapters bridge legacy models during migration.

**Tech Stack:** Python 3.11, dataclasses, existing `controlplane-tool` package layout, pytest, ruff, basedpyright, import-linter, GitNexus impact checks.

---

## Design Constraints

- Do not create the external project yet.
- Do not split host and VM into separate packages yet.
- Stabilize the internal model under `src/controlplane_tool/tasks/`.
- Keep `tui-toolkit`, `typer`, `questionary`, `prefect`, `multipass-sdk`, and shell-specific behavior out of core task spec objects.
- Preserve existing public CLI/TUI behavior.
- Keep `ScenarioPlanStep`, `RemoteCommandOperation`, `ShellExecutionResult`, and loadtest task event models working during migration.
- Every production behavior change must be test-first.
- Run GitNexus impact before modifying existing symbols.

---

### Task 1: Create Internal Task Model Package

**Files:**
- Create: `src/controlplane_tool/tasks/__init__.py`
- Create: `src/controlplane_tool/tasks/models.py`
- Test: `tests/test_task_models.py`

**Step 1: Write the failing tests**

Create `tests/test_task_models.py`:

```python
from controlplane_tool.tasks.models import (
    CommandTaskSpec,
    ExecutionTarget,
    TaskResult,
    TaskStatus,
)


def test_command_task_spec_defaults_to_host_target_and_empty_env() -> None:
    task = CommandTaskSpec(
        task_id="build.compile",
        summary="Compile project",
        argv=("./gradlew", "build"),
    )

    assert task.target == "host"
    assert task.env == {}
    assert task.cwd is None
    assert task.remote_dir is None
    assert task.expected_exit_codes == frozenset({0})


def test_vm_command_task_can_declare_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="images.build_control_plane",
        summary="Build control-plane image",
        target="vm",
        argv=("docker", "build", "-t", "image", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )

    assert task.target == "vm"
    assert task.remote_dir == "/home/ubuntu/nanofaas"


def test_task_result_reports_success_from_expected_exit_codes() -> None:
    success = TaskResult(task_id="x", status="passed", return_code=17, expected_exit_codes=frozenset({17}))
    failure = TaskResult(task_id="x", status="failed", return_code=17, expected_exit_codes=frozenset({0}))

    assert success.ok is True
    assert failure.ok is False
```

**Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest -q tests/test_task_models.py
```

Expected: FAIL because `controlplane_tool.tasks.models` does not exist.

**Step 3: Implement minimal model**

Create `src/controlplane_tool/tasks/models.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

ExecutionTarget = Literal["host", "vm"]
TaskStatus = Literal["pending", "running", "passed", "failed", "skipped"]


@dataclass(frozen=True, slots=True)
class CommandTaskSpec:
    task_id: str
    summary: str
    argv: tuple[str, ...]
    target: ExecutionTarget = "host"
    env: dict[str, str] = field(default_factory=dict)
    cwd: Path | None = None
    remote_dir: str | None = None
    expected_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))
    timeout_seconds: int | None = None


@dataclass(frozen=True, slots=True)
class TaskResult:
    task_id: str
    status: TaskStatus
    return_code: int | None = None
    expected_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))
    stdout: str = ""
    stderr: str = ""

    @property
    def ok(self) -> bool:
        return self.status == "passed" and (
            self.return_code is None or self.return_code in self.expected_exit_codes
        )
```

Create `src/controlplane_tool/tasks/__init__.py`:

```python
from controlplane_tool.tasks.models import (
    CommandTaskSpec,
    ExecutionTarget,
    TaskResult,
    TaskStatus,
)

__all__ = [
    "CommandTaskSpec",
    "ExecutionTarget",
    "TaskResult",
    "TaskStatus",
]
```

**Step 4: Run tests to verify pass**

Run:

```bash
uv run pytest -q tests/test_task_models.py
```

Expected: PASS.

**Step 5: Run quality subset**

Run:

```bash
uv run ruff check src/controlplane_tool/tasks tests/test_task_models.py
uv run basedpyright src/controlplane_tool/tasks tests/test_task_models.py
```

Expected: no errors.

**Step 6: Commit**

```bash
git add src/controlplane_tool/tasks tests/test_task_models.py
git commit -m "Add internal task model"
```

---

### Task 2: Add Command Rendering Primitives

**Files:**
- Create: `src/controlplane_tool/tasks/rendering.py`
- Test: `tests/test_task_rendering.py`

**Step 1: Write failing tests**

Create `tests/test_task_rendering.py`:

```python
from pathlib import Path

from controlplane_tool.tasks.models import CommandTaskSpec
from controlplane_tool.tasks.rendering import render_shell_command, render_task_command


def test_render_shell_command_quotes_arguments_and_env() -> None:
    rendered = render_shell_command(
        argv=("docker", "build", "-t", "local image", "."),
        env={"A": "one two"},
    )

    assert rendered == "A='one two' docker build -t 'local image' ."


def test_render_vm_task_prefixes_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        target="vm",
        argv=("docker", "build", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )

    assert render_task_command(task) == "cd /home/ubuntu/nanofaas && docker build ."


def test_render_host_task_uses_cwd_as_metadata_not_shell_prefix() -> None:
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        target="host",
        argv=("pytest", "-q"),
        cwd=Path("/repo"),
    )

    assert render_task_command(task) == "pytest -q"
```

**Step 2: Run test to verify failure**

```bash
uv run pytest -q tests/test_task_rendering.py
```

Expected: FAIL because rendering module does not exist.

**Step 3: Implement rendering**

Create `src/controlplane_tool/tasks/rendering.py`:

```python
from __future__ import annotations

import shlex
from collections.abc import Mapping

from controlplane_tool.tasks.models import CommandTaskSpec


def render_shell_command(
    argv: tuple[str, ...],
    *,
    env: Mapping[str, str] | None = None,
) -> str:
    prefixes = [f"{name}={shlex.quote(value)}" for name, value in (env or {}).items()]
    command = shlex.join(argv)
    return " ".join([*prefixes, command]) if prefixes else command


def render_task_command(task: CommandTaskSpec) -> str:
    rendered = render_shell_command(task.argv, env=task.env)
    if task.target == "vm" and task.remote_dir:
        return f"cd {shlex.quote(task.remote_dir)} && {rendered}"
    return rendered
```

**Step 4: Run tests**

```bash
uv run pytest -q tests/test_task_rendering.py tests/test_task_models.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/tasks/rendering.py tests/test_task_rendering.py
git commit -m "Add task command rendering"
```

---

### Task 3: Bridge `RemoteCommandOperation` To `CommandTaskSpec`

**Files:**
- Create: `src/controlplane_tool/tasks/adapters.py`
- Modify: `src/controlplane_tool/scenario/components/executor.py`
- Test: `tests/test_task_adapters.py`
- Test: `tests/test_e2e_runner.py`

**Step 1: Run GitNexus impact**

Run impact before editing:

```text
gitnexus_impact({target: "RemoteCommandOperation", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "operation_to_plan_step", direction: "upstream", repo: "mcFaas"})
```

If risk is HIGH or CRITICAL, report direct callers before editing.

**Step 2: Write failing adapter tests**

Create `tests/test_task_adapters.py`:

```python
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

**Step 3: Run failing test**

```bash
uv run pytest -q tests/test_task_adapters.py
```

Expected: FAIL because `operation_to_task_spec` does not exist.

**Step 4: Implement adapter**

Create `src/controlplane_tool/tasks/adapters.py`:

```python
from __future__ import annotations

from controlplane_tool.scenario.components.operations import RemoteCommandOperation
from controlplane_tool.tasks.models import CommandTaskSpec, ExecutionTarget


def operation_to_task_spec(
    operation: RemoteCommandOperation,
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

**Step 5: Use adapter in executor without changing behavior**

Modify `src/controlplane_tool/scenario/components/executor.py`.

Inside `operation_to_plan_step`, convert once:

```python
from controlplane_tool.tasks.adapters import operation_to_task_spec

# after RemoteCommandOperation guard
task = operation_to_task_spec(operation)
```

Use `task.task_id`, `task.summary`, `task.argv`, `task.env`, and `task.target` where possible, while preserving existing callback logic.

Do not remove `ScenarioPlanStep`.

**Step 6: Run tests**

```bash
uv run pytest -q tests/test_task_adapters.py tests/test_e2e_runner.py tests/test_scenario_component_library.py
```

Expected: PASS.

**Step 7: Commit**

```bash
git add src/controlplane_tool/tasks/adapters.py src/controlplane_tool/scenario/components/executor.py tests/test_task_adapters.py
git commit -m "Bridge scenario operations to task specs"
```

---

### Task 4: Replace VM Prelude Script Rendering With Task Rendering

**Files:**
- Modify: `src/controlplane_tool/infra/vm/vm_cluster_workflows.py`
- Test: `tests/test_e2e_runner.py`
- Test: `tests/test_task_rendering.py`

**Step 1: Run GitNexus impact**

```text
gitnexus_impact({target: "build_vm_cluster_prelude_plan", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "_render_operation", direction: "upstream", repo: "mcFaas"})
```

Report risk and d=1 callers before editing.

**Step 2: Add failing test for remote-dir rendering parity**

In `tests/test_task_rendering.py`, add:

```python
def test_render_vm_task_quotes_remote_dir_with_spaces() -> None:
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        target="vm",
        argv=("echo", "ok"),
        remote_dir="/home/ubuntu/my repo",
    )

    assert render_task_command(task) == "cd '/home/ubuntu/my repo' && echo ok"
```

**Step 3: Run test**

```bash
uv run pytest -q tests/test_task_rendering.py::test_render_vm_task_quotes_remote_dir_with_spaces
```

Expected: PASS if Task 2 already handles this; if not, fix rendering.

**Step 4: Replace local render helpers**

In `src/controlplane_tool/infra/vm/vm_cluster_workflows.py`:

- Import:

```python
from controlplane_tool.tasks.adapters import operation_to_task_spec
from controlplane_tool.tasks.rendering import render_task_command
```

- Change `_render_operation` to:

```python
def _render_operation(operation: RemoteCommandOperation, *, remote_dir: str | None = None) -> str:
    task = operation_to_task_spec(operation, remote_dir=remote_dir)
    return render_task_command(task)
```

- Keep `_render_operations` signature unchanged.

**Step 5: Run focused tests**

```bash
uv run pytest -q tests/test_e2e_runner.py::test_vm_cluster_prelude_plan_keeps_shared_image_and_helm_values
uv run pytest -q tests/test_e2e_runner.py::test_vm_cluster_prelude_plan_uses_k3s_component_planners
```

Expected: PASS.

**Step 6: Commit**

```bash
git add src/controlplane_tool/infra/vm/vm_cluster_workflows.py tests/test_task_rendering.py
git commit -m "Use task renderer for VM prelude scripts"
```

---

### Task 5: Add Host Executor Interface

**Files:**
- Create: `src/controlplane_tool/tasks/executors.py`
- Test: `tests/test_task_executors.py`

**Step 1: Write failing tests**

Create `tests/test_task_executors.py`:

```python
from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell, ScriptedShell
from controlplane_tool.tasks.executors import HostCommandTaskExecutor
from controlplane_tool.tasks.models import CommandTaskSpec


def test_host_executor_runs_task_with_cwd_env_and_dry_run() -> None:
    shell = RecordingShell()
    executor = HostCommandTaskExecutor(shell=shell)
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
    assert shell.commands == [["echo", "hi"]]


def test_host_executor_marks_nonzero_unexpected_code_as_failed() -> None:
    shell = ScriptedShell(stderr_map={("false",): "failed"})
    executor = HostCommandTaskExecutor(shell=shell)
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))

    result = executor.run(task)

    assert result.status == "failed"
    assert result.return_code != 0
    assert "failed" in result.stderr
```

**Step 2: Run failing tests**

```bash
uv run pytest -q tests/test_task_executors.py
```

Expected: FAIL because executor does not exist.

**Step 3: Implement executor**

Create `src/controlplane_tool/tasks/executors.py`:

```python
from __future__ import annotations

from controlplane_tool.core.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


class HostCommandTaskExecutor:
    def __init__(self, shell: ShellBackend | None = None) -> None:
        self._shell = shell or SubprocessShell()

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        if task.target != "host":
            raise ValueError(f"HostCommandTaskExecutor cannot run {task.target!r} task")
        shell_result = self._shell.run(
            list(task.argv),
            cwd=task.cwd,
            env=task.env,
            dry_run=dry_run,
        )
        status = "passed" if shell_result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=shell_result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=shell_result.stdout,
            stderr=shell_result.stderr,
        )
```

**Step 4: Run tests**

```bash
uv run pytest -q tests/test_task_executors.py tests/test_shell_backend.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/tasks/executors.py tests/test_task_executors.py
git commit -m "Add host task executor"
```

---

### Task 6: Add VM Executor Interface Without Multipass Dependency

**Files:**
- Modify: `src/controlplane_tool/tasks/executors.py`
- Test: `tests/test_task_executors.py`

**Step 1: Write failing tests**

Append to `tests/test_task_executors.py`:

```python
from controlplane_tool.tasks.executors import VmCommandRunner, VmCommandTaskExecutor


class _RecordingVmRunner:
    def __init__(self) -> None:
        self.commands: list[tuple[tuple[str, ...], dict[str, str], str | None, bool]] = []

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ):
        self.commands.append((argv, env, remote_dir, dry_run))
        return type("_Result", (), {"return_code": 0, "stdout": "ok", "stderr": ""})()


def test_vm_executor_delegates_to_injected_runner() -> None:
    runner = _RecordingVmRunner()
    executor = VmCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(
        task_id="vm.x",
        summary="VM X",
        target="vm",
        argv=("docker", "ps"),
        env={"A": "B"},
        remote_dir="/home/ubuntu/nanofaas",
    )

    result = executor.run(task, dry_run=True)

    assert result.status == "passed"
    assert runner.commands == [
        (("docker", "ps"), {"A": "B"}, "/home/ubuntu/nanofaas", True)
    ]
```

**Step 2: Run failing test**

```bash
uv run pytest -q tests/test_task_executors.py::test_vm_executor_delegates_to_injected_runner
```

Expected: FAIL because VM executor does not exist.

**Step 3: Implement protocol and executor**

In `src/controlplane_tool/tasks/executors.py`, add:

```python
from typing import Protocol


class VmCommandResult(Protocol):
    return_code: int
    stdout: str
    stderr: str


class VmCommandRunner(Protocol):
    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> VmCommandResult: ...


class VmCommandTaskExecutor:
    def __init__(self, runner: VmCommandRunner) -> None:
        self._runner = runner

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        if task.target != "vm":
            raise ValueError(f"VmCommandTaskExecutor cannot run {task.target!r} task")
        result = self._runner.run_vm_command(
            task.argv,
            env=task.env,
            remote_dir=task.remote_dir,
            dry_run=dry_run,
        )
        status = "passed" if result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=result.stdout,
            stderr=result.stderr,
        )
```

**Step 4: Run tests**

```bash
uv run pytest -q tests/test_task_executors.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/tasks/executors.py tests/test_task_executors.py
git commit -m "Add VM task executor interface"
```

---

### Task 7: Bridge Task Results To Existing Shell Results

**Files:**
- Modify: `src/controlplane_tool/tasks/adapters.py`
- Test: `tests/test_task_adapters.py`

**Step 1: Write failing tests**

Append to `tests/test_task_adapters.py`:

```python
from controlplane_tool.tasks.adapters import task_result_to_shell_result
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


def test_task_result_to_shell_result_preserves_command_and_output() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), env={"A": "B"})
    result = TaskResult(task_id="x", status="passed", return_code=0, stdout="hi\n", stderr="")

    shell_result = task_result_to_shell_result(task, result)

    assert shell_result.command == ["echo", "hi"]
    assert shell_result.env == {"A": "B"}
    assert shell_result.return_code == 0
    assert shell_result.stdout == "hi\n"
```

**Step 2: Run failing test**

```bash
uv run pytest -q tests/test_task_adapters.py::test_task_result_to_shell_result_preserves_command_and_output
```

Expected: FAIL because adapter does not exist.

**Step 3: Implement adapter**

In `src/controlplane_tool/tasks/adapters.py`, add:

```python
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.tasks.models import TaskResult


def task_result_to_shell_result(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    dry_run: bool = False,
) -> ShellExecutionResult:
    return ShellExecutionResult(
        command=list(task.argv),
        return_code=result.return_code or 0,
        stdout=result.stdout,
        stderr=result.stderr,
        dry_run=dry_run,
        env=dict(task.env),
    )
```

**Step 4: Run tests**

```bash
uv run pytest -q tests/test_task_adapters.py tests/test_shell_backend.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/tasks/adapters.py tests/test_task_adapters.py
git commit -m "Bridge task results to shell results"
```

---

### Task 8: Migrate `ScenarioPlanStep` Construction To TaskSpec Internally

**Files:**
- Modify: `src/controlplane_tool/scenario/components/executor.py`
- Test: `tests/test_e2e_runner.py`
- Test: `tests/test_scenario_component_library.py`

**Step 1: Run GitNexus impact**

```text
gitnexus_impact({target: "ScenarioPlanStep", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "operation_to_plan_step", direction: "upstream", repo: "mcFaas"})
```

Report risk and d=1 callers before editing.

**Step 2: Add regression test**

In `tests/test_e2e_runner.py`, add a focused assertion to existing operation conversion tests:

```python
def test_operation_to_plan_step_preserves_command_env_and_step_id_after_task_bridge() -> None:
    operation = RemoteCommandOperation(
        operation_id="x.step",
        summary="Run step",
        argv=("echo", "hi"),
        env={"A": "B"},
        execution_target="host",
    )
    request = E2eRequest(scenario="docker")

    step = operation_to_plan_step(operation, request=request)

    assert step.step_id == "x.step"
    assert step.summary == "Run step"
    assert step.command == ["echo", "hi"]
    assert step.env == {"A": "B"}
```

Import `operation_to_plan_step` and `E2eRequest` if not already imported.

**Step 3: Run regression test**

```bash
uv run pytest -q tests/test_e2e_runner.py::test_operation_to_plan_step_preserves_command_env_and_step_id_after_task_bridge
```

Expected: PASS before refactor. This locks behavior.

**Step 4: Refactor implementation**

In `operation_to_plan_step`, derive display data from `CommandTaskSpec`:

```python
task = operation_to_task_spec(operation)
summary = _SUMMARY_OVERRIDES.get(task.task_id, task.summary)
command = list(task.argv)
env = dict(task.env)
```

Use those locals consistently.

**Step 5: Run tests**

```bash
uv run pytest -q tests/test_e2e_runner.py tests/test_scenario_component_library.py
```

Expected: PASS.

**Step 6: Commit**

```bash
git add src/controlplane_tool/scenario/components/executor.py tests/test_e2e_runner.py
git commit -m "Use task specs in scenario plan step conversion"
```

---

### Task 9: Add Workflow Event Bridge

**Files:**
- Create: `src/controlplane_tool/tasks/workflow.py`
- Test: `tests/test_task_workflow_bridge.py`

**Step 1: Write failing tests**

Create `tests/test_task_workflow_bridge.py`:

```python
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
from controlplane_tool.tasks.workflow import task_started_event, task_result_event


def test_task_started_event_uses_task_id_and_summary() -> None:
    task = CommandTaskSpec(task_id="x", summary="Run X", argv=("echo", "x"))

    event = task_started_event(task)

    assert event.task_id == "x"
    assert event.title == "Run X"
    assert event.kind == "step"


def test_task_result_event_maps_failed_status() -> None:
    task = CommandTaskSpec(task_id="x", summary="Run X", argv=("false",))
    result = TaskResult(task_id="x", status="failed", return_code=1, stderr="failed")

    event = task_result_event(task, result)

    assert event.task_id == "x"
    assert event.kind == "fail"
    assert event.message == "failed"
```

**Step 2: Run failing tests**

```bash
uv run pytest -q tests/test_task_workflow_bridge.py
```

Expected: FAIL because bridge does not exist.

**Step 3: Implement bridge**

Inspect the current event type in `tui-toolkit` and `src/controlplane_tool/tui/prefect_bridge.py`. Then create `src/controlplane_tool/tasks/workflow.py` with only conversion functions. Do not import TUI app code.

Example shape if using `tui_toolkit.events.WorkflowEvent`:

```python
from __future__ import annotations

from tui_toolkit.events import WorkflowEvent

from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


def task_started_event(task: CommandTaskSpec) -> WorkflowEvent:
    return WorkflowEvent(kind="step", task_id=task.task_id, title=task.summary)


def task_result_event(task: CommandTaskSpec, result: TaskResult) -> WorkflowEvent:
    kind = "ok" if result.ok else "fail"
    message = result.stderr.strip() or result.stdout.strip() or None
    return WorkflowEvent(kind=kind, task_id=task.task_id, title=task.summary, message=message)
```

Adjust field names to match actual `WorkflowEvent`.

**Step 4: Run tests**

```bash
uv run pytest -q tests/test_task_workflow_bridge.py tests/test_tui_workflow.py tests/test_console_workflow.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/tasks/workflow.py tests/test_task_workflow_bridge.py
git commit -m "Add task workflow event bridge"
```

---

### Task 10: Convert Loadtest Task Functions To Return TaskSpec Metadata

**Files:**
- Modify: `src/controlplane_tool/loadtest/loadtest_tasks.py`
- Test: `tests/test_loadtest_tasks.py`

**Step 1: Run GitNexus impact**

```text
gitnexus_impact({target: "bootstrap_loadtest_task", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "run_loadtest_step_task", direction: "upstream", repo: "mcFaas"})
```

Report risk and d=1 callers before editing.

**Step 2: Add metadata assertion**

In `tests/test_loadtest_tasks.py`, add:

```python
def test_bootstrap_task_result_exposes_task_id_metadata_on_steps() -> None:
    adapter = _FakeAdapter(preflight_missing=["k6"])
    request = _loadtest_request()

    result = bootstrap_loadtest_task(adapter=adapter, request=request, run_dir=Path("/tmp/run"))

    assert result.steps[0].name == "preflight"
```

If helper names differ, reuse existing test fixtures in that file.

**Step 3: Run test**

```bash
uv run pytest -q tests/test_loadtest_tasks.py
```

Expected: PASS. This is a characterization test.

**Step 4: Add task spec helper internally**

In `src/controlplane_tool/loadtest/loadtest_tasks.py`, add:

```python
from controlplane_tool.tasks.models import CommandTaskSpec


def loadtest_step_spec(step_name: str, summary: str) -> CommandTaskSpec:
    return CommandTaskSpec(
        task_id=f"loadtest.{step_name}",
        summary=summary,
        argv=("python", "-m", "controlplane_tool.loadtest", step_name),
    )
```

Use it only for metadata initially. Do not change execution flow.

**Step 5: Run tests**

```bash
uv run pytest -q tests/test_loadtest_tasks.py tests/test_loadtest_flows.py tests/test_loadtest_runner.py
```

Expected: PASS.

**Step 6: Commit**

```bash
git add src/controlplane_tool/loadtest/loadtest_tasks.py tests/test_loadtest_tasks.py
git commit -m "Add loadtest task metadata specs"
```

---

### Task 11: Expand Basedpyright Coverage To The New Task Package

**Files:**
- Modify: `pyproject.toml`
- Test: `pyproject.toml`

**Step 1: Add task package to basedpyright include**

In `[tool.basedpyright]`, add:

```toml
include = [
    "src/controlplane_tool/devtools/quality.py",
    "src/controlplane_tool/tasks",
    "src/controlplane_tool/tui/workflow.py",
    "src/controlplane_tool/tui/workflow_controller.py",
]
```

**Step 2: Run basedpyright**

```bash
uv run basedpyright
```

Expected: 0 errors.

**Step 3: Run quality**

```bash
uv run controlplane-quality
```

Expected: `Quality checks passed`.

**Step 4: Commit**

```bash
git add pyproject.toml
git commit -m "Type-check internal task package"
```

---

### Task 12: Document Extraction Criteria

**Files:**
- Create: `docs/plans/2026-05-06-task-extraction-readiness.md`
- Test: docs only

**Step 1: Create readiness doc**

Create `docs/plans/2026-05-06-task-extraction-readiness.md`:

```markdown
# Task Extraction Readiness

The internal task model can be extracted to a sibling uv project when:

- `src/controlplane_tool/tasks/` is covered by basedpyright.
- Scenario component planners convert through `CommandTaskSpec`.
- VM prelude script rendering uses task rendering.
- Host and VM execution use task executor interfaces.
- TUI workflow rendering consumes task workflow bridge events.
- No task core module imports `typer`, `questionary`, `prefect`, `multipass`, or TUI app modules.
- Import-linter includes a contract that task core does not depend on CLI/TUI/app entrypoints.

The first external project should be NanoFaaS-specific, not generic:

```text
tools/nanofaas-tasks/
  pyproject.toml
  src/nanofaas_tasks/
  tests/
```

The external project should start as a direct move of stabilized code, not a redesign.
```

**Step 2: Commit**

```bash
git add docs/plans/2026-05-06-task-extraction-readiness.md
git commit -m "Document task extraction readiness criteria"
```

---

### Task 13: Final Verification

**Files:**
- No edits expected.

**Step 1: Run full targeted test set**

```bash
uv run pytest -q \
  tests/test_task_models.py \
  tests/test_task_rendering.py \
  tests/test_task_adapters.py \
  tests/test_task_executors.py \
  tests/test_task_workflow_bridge.py \
  tests/test_e2e_runner.py \
  tests/test_scenario_component_library.py \
  tests/test_loadtest_tasks.py \
  tests/test_loadtest_flows.py \
  tests/test_loadtest_runner.py \
  tests/test_infra_flows.py
```

Expected: PASS.

**Step 2: Run full quality gate**

```bash
uv run controlplane-quality
```

Expected: `Quality checks passed`.

**Step 3: Run GitNexus detect changes**

```text
gitnexus_detect_changes({repo: "mcFaas", scope: "all"})
```

Expected: changed symbols are limited to task model, scenario operation bridges, VM prelude rendering, and tests. If HIGH/CRITICAL risk is reported, summarize d=1/direct affected symbols before finishing.

**Step 4: Check git status**

```bash
git status --short --branch
```

Expected: clean branch after commits.

---

## Post-Stabilization Extraction Plan

Do not start this until all tasks above are complete.

1. Create sibling project `tools/nanofaas-tasks/` with uv, ruff, basedpyright, pytest, import-linter, `py.typed`.
2. Move `src/controlplane_tool/tasks/` to `tools/nanofaas-tasks/src/nanofaas_tasks/`.
3. Add editable dependency in `tools/controlplane/pyproject.toml`:

```toml
dependencies = [
    "nanofaas-tasks",
]

[tool.uv.sources]
nanofaas-tasks = { path = "../nanofaas-tasks", editable = true }
```

4. Update imports from `controlplane_tool.tasks` to `nanofaas_tasks`.
5. Run:

```bash
uv lock
uv run controlplane-quality
uv run pytest -q tests/test_task_models.py tests/test_task_rendering.py tests/test_task_adapters.py
```

6. Commit extraction separately:

```bash
git add pyproject.toml uv.lock ../nanofaas-tasks src tests
git commit -m "Extract NanoFaaS task primitives"
```

