# workflow-tasks PR1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `tools/workflow-tasks/` as a standalone Python library with zero external dependencies, moving task and workflow domain code out of `controlplane_tool` and `tui_toolkit`, while adding backward-compat shims so all existing tests continue to pass.

**Architecture:** New library at `tools/workflow-tasks/` holds `tasks/` (CommandTaskSpec, executors, rendering, adapters), `workflow/` (events, models, context, event_builders, reporting), and `integrations/prefect.py`. Shims in `tui_toolkit/events.py` and `controlplane_tool/tasks/` re-export from the new library. `tui_toolkit/workflow.py` keeps the Rich renderer but imports types from `workflow_tasks`. Full tooling stack (ruff, basedpyright, import-linter, pytest-cov, grimp) added to both new lib and `tui_toolkit`.

**Tech Stack:** Python 3.11+, setuptools, uv, pytest, pytest-cov, ruff, basedpyright, import-linter, grimp, pydeps

**Design spec:** `docs/superpowers/specs/2026-05-15-workflow-tasks-extraction-design.md`

---

## File Map

### Created
- `tools/workflow-tasks/pyproject.toml`
- `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- `tools/workflow-tasks/src/workflow_tasks/tasks/__init__.py`
- `tools/workflow-tasks/src/workflow_tasks/tasks/models.py`
- `tools/workflow-tasks/src/workflow_tasks/tasks/executors.py`
- `tools/workflow-tasks/src/workflow_tasks/tasks/rendering.py`
- `tools/workflow-tasks/src/workflow_tasks/tasks/adapters.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/__init__.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/events.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/models.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/context.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/event_builders.py`
- `tools/workflow-tasks/src/workflow_tasks/workflow/reporting.py`
- `tools/workflow-tasks/src/workflow_tasks/integrations/__init__.py`
- `tools/workflow-tasks/src/workflow_tasks/integrations/prefect.py`
- `tools/workflow-tasks/src/workflow_tasks/py.typed`
- `tools/workflow-tasks/.importlinter`
- `tools/workflow-tasks/src/workflow_tasks/devtools/__init__.py`
- `tools/workflow-tasks/src/workflow_tasks/devtools/quality.py`
- `tools/workflow-tasks/tests/conftest.py`
- `tools/workflow-tasks/tests/tasks/test_models.py`
- `tools/workflow-tasks/tests/tasks/test_executors.py`
- `tools/workflow-tasks/tests/tasks/test_rendering.py`
- `tools/workflow-tasks/tests/tasks/test_adapters.py`
- `tools/workflow-tasks/tests/workflow/test_events.py`
- `tools/workflow-tasks/tests/workflow/test_models.py`
- `tools/workflow-tasks/tests/workflow/test_context.py`
- `tools/workflow-tasks/tests/workflow/test_event_builders.py`
- `tools/workflow-tasks/tests/workflow/test_reporting.py`
- `tools/workflow-tasks/tests/integrations/test_prefect.py`
- `tools/workflow-tasks/tests/test_package_boundaries.py`
- `tools/workflow-tasks/tests/test_public_api.py`

### Modified
- `tools/tui-toolkit/pyproject.toml` — add dev tools
- `tools/tui-toolkit/src/tui_toolkit/events.py` — shim → workflow_tasks
- `tools/tui-toolkit/src/tui_toolkit/workflow.py` — import types from workflow_tasks
- `tools/tui-toolkit/src/tui_toolkit/__init__.py` — no change to exports needed
- `tools/tui-toolkit/.importlinter` — new file
- `tools/controlplane/pyproject.toml` — add workflow-tasks dep + grimp cross-project check
- `tools/controlplane/src/controlplane_tool/tasks/models.py` — shim
- `tools/controlplane/src/controlplane_tool/tasks/executors.py` — shim
- `tools/controlplane/src/controlplane_tool/tasks/rendering.py` — shim
- `tools/controlplane/src/controlplane_tool/tasks/adapters.py` — shim
- `tools/controlplane/src/controlplane_tool/tasks/__init__.py` — shim
- `tools/controlplane/src/controlplane_tool/workflow/workflow_models.py` — shim domain models
- `tools/controlplane/src/controlplane_tool/workflow/workflow_events.py` — shim normalize_task_state
- `tools/controlplane/src/controlplane_tool/orchestation/prefect_event_bridge.py` — deleted
- `tools/controlplane/src/controlplane_tool/tui/prefect_bridge.py` — renamed event_aggregator.py, class renamed
- `tools/controlplane/src/controlplane_tool/tui/event_aggregator.py` — new name
- `tools/controlplane/src/controlplane_tool/devtools/quality.py` — add grimp cross-project check
- `tools/controlplane/.importlinter` — add workflow contract

---

## Task 1: Scaffold `tools/workflow-tasks/`

**Files:**
- Create: `tools/workflow-tasks/pyproject.toml`
- Create: `tools/workflow-tasks/src/workflow_tasks/py.typed`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p tools/workflow-tasks/src/workflow_tasks/tasks
mkdir -p tools/workflow-tasks/src/workflow_tasks/workflow
mkdir -p tools/workflow-tasks/src/workflow_tasks/integrations
mkdir -p tools/workflow-tasks/src/workflow_tasks/devtools
mkdir -p tools/workflow-tasks/tests/tasks
mkdir -p tools/workflow-tasks/tests/workflow
mkdir -p tools/workflow-tasks/tests/integrations
touch tools/workflow-tasks/tests/__init__.py
touch tools/workflow-tasks/tests/tasks/__init__.py
touch tools/workflow-tasks/tests/workflow/__init__.py
touch tools/workflow-tasks/tests/integrations/__init__.py
touch tools/workflow-tasks/src/workflow_tasks/py.typed
```

- [ ] **Step 2: Write `pyproject.toml`**

```toml
# tools/workflow-tasks/pyproject.toml
[project]
name = "workflow-tasks"
version = "0.1.0"
description = "Task execution primitives and workflow event infrastructure. No external dependencies."
requires-python = ">=3.11"
dependencies = []

[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
package-dir = {"" = "src"}

[tool.setuptools.packages.find]
where = ["src"]

[tool.setuptools.package-data]
workflow_tasks = ["py.typed"]

[dependency-groups]
dev = [
    "basedpyright>=1.39.3",
    "grimp>=3.14",
    "import-linter>=2.11",
    "pydeps>=3.0.6",
    "pytest>=9.0.3",
    "pytest-cov>=6.0",
    "ruff>=0.15.12",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
addopts = "-q --cov=workflow_tasks --cov-report=term-missing --cov-fail-under=90"

[tool.ruff]
target-version = "py311"
line-length = 100

[tool.ruff.lint]
select = ["F", "SLF"]

[tool.ruff.lint.per-file-ignores]
"tests/**/*.py" = ["F841", "SLF001"]

[tool.basedpyright]
include = ["src/workflow_tasks"]
typeCheckingMode = "basic"
reportMissingTypeStubs = false
reportPrivateUsage = false
```

- [ ] **Step 3: Install with uv**

```bash
cd tools/workflow-tasks && uv sync
```

Expected: lock file created, no errors.

- [ ] **Step 4: Verify package is importable**

```bash
cd tools/workflow-tasks && uv run python -c "import workflow_tasks; print('ok')"
```

Expected: fails with `ModuleNotFoundError` (package exists but `__init__.py` not written yet — this is the failing test for Task 14).

- [ ] **Step 5: Commit scaffold**

```bash
git add tools/workflow-tasks/
git commit -m "feat(workflow-tasks): scaffold package structure"
```

---

## Task 2: Move task domain models

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/models.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/__init__.py`
- Create: `tools/workflow-tasks/tests/tasks/test_models.py`

- [ ] **Step 1: Write `tasks/models.py`**

Content is identical to `tools/controlplane/src/controlplane_tool/tasks/models.py` — no import changes needed (only stdlib).

```python
# tools/workflow-tasks/src/workflow_tasks/tasks/models.py
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Literal

ExecutionTarget = Literal["host", "vm"]
TaskStatus = Literal["pending", "running", "passed", "failed", "skipped"]


@dataclass(frozen=True, slots=True)
class CommandTaskSpec:
    task_id: str
    summary: str
    argv: tuple[str, ...]
    target: ExecutionTarget = "host"
    env: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))
    cwd: Path | None = None
    remote_dir: str | None = None
    expected_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))
    timeout_seconds: int | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "env", MappingProxyType(dict(self.env)))


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

- [ ] **Step 2: Write `tasks/__init__.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/tasks/__init__.py
from workflow_tasks.tasks.models import (
    CommandTaskSpec,
    ExecutionTarget,
    TaskResult,
    TaskStatus,
)

__all__ = ["CommandTaskSpec", "ExecutionTarget", "TaskResult", "TaskStatus"]
```

- [ ] **Step 3: Write test file**

```python
# tools/workflow-tasks/tests/tasks/test_models.py
from __future__ import annotations

from types import MappingProxyType
from typing import get_args

from workflow_tasks.tasks.models import (
    CommandTaskSpec,
    ExecutionTarget,
    TaskResult,
    TaskStatus,
)


def test_task_type_aliases_cover_expected_values() -> None:
    assert set(get_args(ExecutionTarget)) == {"host", "vm"}
    assert set(get_args(TaskStatus)) == {"pending", "running", "passed", "failed", "skipped"}


def test_command_task_spec_defaults_to_host_target_and_empty_env() -> None:
    task = CommandTaskSpec(task_id="build.compile", summary="Compile project", argv=("./gradlew", "build"))
    assert task.target == "host"
    assert task.env == {}
    assert task.cwd is None
    assert task.remote_dir is None
    assert task.expected_exit_codes == frozenset({0})


def test_vm_command_task_can_declare_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="images.build",
        summary="Build image",
        target="vm",
        argv=("docker", "build", "-t", "img", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )
    assert task.target == "vm"
    assert task.remote_dir == "/home/ubuntu/nanofaas"


def test_command_task_spec_defensively_copies_env() -> None:
    env = {"A": "B"}
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "x"), env=env)
    env["A"] = "changed"
    assert dict(task.env) == {"A": "B"}


def test_command_task_spec_env_is_immutable() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "x"), env={"A": "B"})
    assert isinstance(task.env, MappingProxyType)


def test_task_result_ok_from_expected_exit_codes() -> None:
    success = TaskResult(task_id="x", status="passed", return_code=17, expected_exit_codes=frozenset({17}))
    failure = TaskResult(task_id="x", status="failed", return_code=17, expected_exit_codes=frozenset({0}))
    assert success.ok is True
    assert failure.ok is False
```

- [ ] **Step 4: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/tasks/test_models.py -v
```

Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/tasks/ tools/workflow-tasks/tests/tasks/test_models.py
git commit -m "feat(workflow-tasks): add task domain models"
```

---

## Task 3: Move task executors

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/executors.py`
- Create: `tools/workflow-tasks/tests/tasks/test_executors.py`

- [ ] **Step 1: Write `tasks/executors.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/tasks/executors.py
from __future__ import annotations

from pathlib import Path
from typing import Protocol

from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


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
        result = self._runner.run(list(task.argv), cwd=task.cwd, env=dict(task.env), dry_run=dry_run)
        status = "passed" if result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=result.stdout,
            stderr=result.stderr,
        )


class VmCommandResult(Protocol):
    @property
    def return_code(self) -> int: ...
    @property
    def stdout(self) -> str: ...
    @property
    def stderr(self) -> str: ...


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
            task.argv, env=dict(task.env), remote_dir=task.remote_dir, dry_run=dry_run,
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

- [ ] **Step 2: Write test file**

```python
# tools/workflow-tasks/tests/tasks/test_executors.py
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pytest

from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec


@dataclass(frozen=True)
class _CommandResult:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _RecordingHostRunner:
    def __init__(self, *, return_code: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.return_code = return_code
        self.stdout = stdout
        self.stderr = stderr
        self.commands: list[tuple[list[str], Path | None, dict[str, str], bool]] = []

    def run(self, argv: list[str], *, cwd: Path | None, env: dict[str, str], dry_run: bool) -> _CommandResult:
        self.commands.append((argv, cwd, env, dry_run))
        return _CommandResult(return_code=self.return_code, stdout=self.stdout, stderr=self.stderr)


def test_host_executor_runs_task_with_cwd_env_and_dry_run() -> None:
    runner = _RecordingHostRunner()
    executor = HostCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), env={"A": "B"}, cwd=Path("/repo"))

    result = executor.run(task, dry_run=True)

    assert result.status == "passed"
    assert result.return_code == 0
    assert runner.commands == [(["echo", "hi"], Path("/repo"), {"A": "B"}, True)]


def test_host_executor_marks_nonzero_unexpected_code_as_failed() -> None:
    runner = _RecordingHostRunner(return_code=1, stderr="failed")
    executor = HostCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))

    result = executor.run(task)

    assert result.status == "failed"
    assert result.return_code == 1
    assert result.stderr == "failed"


def test_host_executor_rejects_vm_tasks() -> None:
    executor = HostCommandTaskExecutor(runner=_RecordingHostRunner())
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), target="vm")
    with pytest.raises(ValueError, match="cannot run 'vm' task"):
        executor.run(task)


@dataclass(frozen=True)
class _VmResult:
    return_code: int
    stdout: str
    stderr: str


class _RecordingVmRunner:
    def __init__(self) -> None:
        self.commands: list[tuple[tuple[str, ...], dict[str, str], str | None, bool]] = []

    def run_vm_command(
        self, argv: tuple[str, ...], *, env: dict[str, str], remote_dir: str | None, dry_run: bool
    ) -> _VmResult:
        self.commands.append((argv, env, remote_dir, dry_run))
        return _VmResult(return_code=0, stdout="ok", stderr="")


def test_vm_executor_delegates_to_injected_runner() -> None:
    runner = _RecordingVmRunner()
    executor = VmCommandTaskExecutor(runner=runner)
    task = CommandTaskSpec(
        task_id="vm.x", summary="VM X", target="vm",
        argv=("docker", "ps"), env={"A": "B"}, remote_dir="/home/ubuntu/nanofaas",
    )

    result = executor.run(task, dry_run=True)

    assert result.status == "passed"
    assert runner.commands == [(("docker", "ps"), {"A": "B"}, "/home/ubuntu/nanofaas", True)]


def test_vm_executor_rejects_host_tasks() -> None:
    executor = VmCommandTaskExecutor(runner=_RecordingVmRunner())
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), target="host")
    with pytest.raises(ValueError, match="cannot run 'host' task"):
        executor.run(task)
```

- [ ] **Step 3: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/tasks/test_executors.py -v
```

Expected: 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/tasks/executors.py tools/workflow-tasks/tests/tasks/test_executors.py
git commit -m "feat(workflow-tasks): add task executors"
```

---

## Task 4: Move task rendering and adapters

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/rendering.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/adapters.py`
- Create: `tools/workflow-tasks/tests/tasks/test_rendering.py`
- Create: `tools/workflow-tasks/tests/tasks/test_adapters.py`

- [ ] **Step 1: Write `tasks/rendering.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/tasks/rendering.py
from __future__ import annotations

import shlex
from collections.abc import Mapping
import re

from workflow_tasks.tasks.models import CommandTaskSpec

_ENV_NAME_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")


def _render_env_assignment(name: str, value: str) -> str:
    if not _ENV_NAME_RE.fullmatch(name):
        raise ValueError(f"Invalid environment variable name: {name!r}")
    return f"{name}={shlex.quote(value)}"


def render_shell_command(argv: tuple[str, ...], *, env: Mapping[str, str] | None = None) -> str:
    if not argv:
        raise ValueError("Command argv must not be empty")
    prefixes = [_render_env_assignment(name, value) for name, value in sorted((env or {}).items())]
    command = shlex.join(argv)
    return " ".join([*prefixes, command]) if prefixes else command


def render_task_command(task: CommandTaskSpec) -> str:
    rendered = render_shell_command(task.argv, env=task.env)
    if task.target == "vm" and task.remote_dir:
        return f"cd {shlex.quote(task.remote_dir)} && {rendered}"
    return rendered
```

- [ ] **Step 2: Write `tasks/adapters.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/tasks/adapters.py
from __future__ import annotations

from collections.abc import Mapping
from typing import Protocol

from workflow_tasks.tasks.models import CommandTaskSpec, ExecutionTarget


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

- [ ] **Step 3: Write test files**

```python
# tools/workflow-tasks/tests/tasks/test_rendering.py
from __future__ import annotations

from pathlib import Path
import pytest

from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.tasks.rendering import render_shell_command, render_task_command


def test_render_shell_command_quotes_arguments_and_env() -> None:
    rendered = render_shell_command(argv=("docker", "build", "-t", "local image", "."), env={"A": "one two"})
    assert rendered == "A='one two' docker build -t 'local image' ."


def test_render_shell_command_sorts_env_keys() -> None:
    assert render_shell_command(argv=("echo", "ok"), env={"B": "2", "A": "1"}) == "A=1 B=2 echo ok"


def test_render_shell_command_rejects_invalid_env_key() -> None:
    with pytest.raises(ValueError, match="Invalid environment variable name"):
        render_shell_command(argv=("echo", "ok"), env={"A; echo PWNED #": "x"})


def test_render_shell_command_rejects_empty_argv() -> None:
    with pytest.raises(ValueError, match="Command argv must not be empty"):
        render_shell_command(argv=())


def test_render_vm_task_prefixes_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="x", summary="X", target="vm", argv=("docker", "build", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )
    assert render_task_command(task) == "cd /home/ubuntu/nanofaas && docker build ."


def test_render_vm_task_quotes_remote_dir_with_spaces() -> None:
    task = CommandTaskSpec(
        task_id="x", summary="X", target="vm", argv=("echo", "ok"), remote_dir="/home/ubuntu/my repo",
    )
    assert render_task_command(task) == "cd '/home/ubuntu/my repo' && echo ok"


def test_render_host_task_ignores_cwd() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", target="host", argv=("pytest", "-q"), cwd=Path("/repo"))
    assert render_task_command(task) == "pytest -q"
```

```python
# tools/workflow-tasks/tests/tasks/test_adapters.py
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType

from workflow_tasks.tasks.adapters import operation_to_task_spec


@dataclass(frozen=True)
class _FakeOperation:
    operation_id: str
    summary: str
    argv: tuple[str, ...]
    env: Mapping[str, str]
    execution_target: str


def test_operation_converts_to_task_spec() -> None:
    op = _FakeOperation(
        operation_id="images.build", summary="Build image",
        argv=("docker", "build", "."), env=MappingProxyType({"A": "B"}), execution_target="vm",
    )
    task = operation_to_task_spec(op)
    assert task.task_id == "images.build"
    assert task.summary == "Build image"
    assert task.argv == ("docker", "build", ".")
    assert task.env == {"A": "B"}
    assert task.target == "vm"


def test_host_operation_maps_to_host_target() -> None:
    op = _FakeOperation(
        operation_id="build.jar", summary="Build jar",
        argv=("./gradlew", "jar"), env=MappingProxyType({}), execution_target="host",
    )
    task = operation_to_task_spec(op)
    assert task.target == "host"
    assert task.remote_dir is None


def test_vm_operation_with_remote_dir() -> None:
    op = _FakeOperation(
        operation_id="vm.build", summary="VM build",
        argv=("make",), env=MappingProxyType({}), execution_target="vm",
    )
    task = operation_to_task_spec(op, remote_dir="/home/ubuntu/project")
    assert task.remote_dir == "/home/ubuntu/project"
```

- [ ] **Step 4: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/tasks/test_rendering.py tests/tasks/test_adapters.py -v
```

Expected: 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/tasks/rendering.py \
        tools/workflow-tasks/src/workflow_tasks/tasks/adapters.py \
        tools/workflow-tasks/tests/tasks/test_rendering.py \
        tools/workflow-tasks/tests/tasks/test_adapters.py
git commit -m "feat(workflow-tasks): add task rendering and adapters"
```

---

## Task 5: Move workflow event types and domain models

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/events.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/models.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/__init__.py`
- Create: `tools/workflow-tasks/tests/workflow/test_events.py`
- Create: `tools/workflow-tasks/tests/workflow/test_models.py`

- [ ] **Step 1: Write `workflow/events.py`**

Content identical to `tools/tui-toolkit/src/tui_toolkit/events.py` with no import changes (only stdlib).

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/events.py
from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol


def _utc_now() -> datetime:
    return datetime.now(UTC)


@dataclass(slots=True, frozen=True)
class WorkflowContext:
    flow_id: str = "interactive.console"
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None


@dataclass(slots=True, frozen=True)
class WorkflowEvent:
    kind: str
    flow_id: str
    at: datetime = field(default_factory=_utc_now)
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None
    title: str = ""
    detail: str = ""
    stream: str = "stdout"
    line: str = ""


class WorkflowSink(Protocol):
    def emit(self, event: WorkflowEvent) -> None: ...
    def status(self, label: str) -> AbstractContextManager[None]: ...
```

- [ ] **Step 2: Write `workflow/models.py`**

Extracts only the pure domain models from `controlplane_tool/workflow/workflow_models.py` — NOT `TuiPhaseSnapshot`/`TuiWorkflowSnapshot` (those stay in controlplane_tool) and NOT the `WorkflowContext/Event/Sink` re-exports (now in `events.py`).

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/models.py
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal


def utc_now() -> datetime:
    return datetime.now(UTC)


WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


@dataclass(slots=True, frozen=True)
class WorkflowRun:
    flow_id: str
    flow_run_id: str
    status: str = "pending"
    orchestrator_backend: str = "none"
    started_at: datetime | None = None
    finished_at: datetime | None = None


@dataclass(slots=True, frozen=True)
class TaskDefinition:
    task_id: str
    title: str = ""
    detail: str = ""


@dataclass(slots=True, frozen=True)
class TaskRun:
    flow_id: str
    task_id: str
    task_run_id: str
    status: str = "pending"
    title: str = ""
    detail: str = ""
```

- [ ] **Step 3: Write `workflow/__init__.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/__init__.py
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState

__all__ = [
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
    "TaskDefinition", "TaskRun", "WorkflowRun", "WorkflowState",
]
```

- [ ] **Step 4: Write test files**

```python
# tools/workflow-tasks/tests/workflow/test_events.py
from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime

import pytest

from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink


def test_workflow_context_defaults() -> None:
    ctx = WorkflowContext()
    assert ctx.flow_id == "interactive.console"
    assert ctx.flow_run_id is None
    assert ctx.task_id is None
    assert ctx.parent_task_id is None
    assert ctx.task_run_id is None


def test_workflow_context_is_frozen() -> None:
    ctx = WorkflowContext()
    with pytest.raises(AttributeError):
        ctx.flow_id = "x"  # type: ignore[misc]


def test_workflow_event_minimal_construction() -> None:
    event = WorkflowEvent(kind="task.completed", flow_id="f")
    assert event.kind == "task.completed"
    assert event.flow_id == "f"
    assert event.title == ""
    assert event.detail == ""
    assert event.stream == "stdout"
    assert event.line == ""
    assert isinstance(event.at, datetime)
    assert event.at.tzinfo is UTC


def test_workflow_event_is_frozen() -> None:
    event = WorkflowEvent(kind="x", flow_id="f")
    with pytest.raises(AttributeError):
        event.kind = "y"  # type: ignore[misc]


def test_fake_sink_satisfies_workflow_sink_protocol() -> None:
    class _FakeSink:
        def __init__(self) -> None:
            self.events: list[WorkflowEvent] = []

        def emit(self, event: WorkflowEvent) -> None:
            self.events.append(event)

        @contextmanager
        def status(self, label: str):
            yield

    sink: WorkflowSink = _FakeSink()
    sink.emit(WorkflowEvent(kind="task.completed", flow_id="f"))
    with sink.status("loading"):
        pass
    assert sink.events[0].kind == "task.completed"  # type: ignore[attr-defined]
```

```python
# tools/workflow-tasks/tests/workflow/test_models.py
from __future__ import annotations

from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState


def test_workflow_run_defaults() -> None:
    run = WorkflowRun(flow_id="e2e.k3s", flow_run_id="run-1")
    assert run.status == "pending"
    assert run.orchestrator_backend == "none"
    assert run.started_at is None


def test_task_definition_defaults() -> None:
    td = TaskDefinition(task_id="vm.ensure_running")
    assert td.title == ""
    assert td.detail == ""


def test_task_run_defaults() -> None:
    tr = TaskRun(flow_id="e2e.k3s", task_id="vm.ensure_running", task_run_id="tr-1")
    assert tr.status == "pending"
    assert tr.title == ""


def test_workflow_state_values() -> None:
    from typing import get_args
    assert set(get_args(WorkflowState)) == {"pending", "running", "success", "failed", "cancelled"}
```

- [ ] **Step 5: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/workflow/test_events.py tests/workflow/test_models.py -v
```

Expected: 9 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/workflow/ \
        tools/workflow-tasks/tests/workflow/test_events.py \
        tools/workflow-tasks/tests/workflow/test_models.py
git commit -m "feat(workflow-tasks): add workflow event types and domain models"
```

---

## Task 6: Move context management and event builders

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/context.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/event_builders.py`
- Create: `tools/workflow-tasks/tests/workflow/test_context.py`
- Create: `tools/workflow-tasks/tests/workflow/test_event_builders.py`

- [ ] **Step 1: Write `workflow/context.py`**

Extracted from the ContextVar plumbing in `tui_toolkit/workflow.py` (lines 19–64).

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/context.py
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Generator

from workflow_tasks.workflow.events import WorkflowContext, WorkflowSink

_workflow_sink_var: ContextVar[WorkflowSink | None] = ContextVar(
    "workflow_tasks_sink", default=None,
)
_workflow_sink_shared: WorkflowSink | None = None
_workflow_context_var: ContextVar[WorkflowContext | None] = ContextVar(
    "workflow_tasks_context", default=None,
)
_workflow_context_shared: WorkflowContext | None = None


@contextmanager
def bind_workflow_sink(sink: WorkflowSink) -> Generator[None, None, None]:
    global _workflow_sink_shared
    previous = _workflow_sink_shared
    _workflow_sink_shared = sink
    token = _workflow_sink_var.set(sink)
    try:
        yield
    finally:
        _workflow_sink_var.reset(token)
        _workflow_sink_shared = previous


@contextmanager
def bind_workflow_context(context: WorkflowContext) -> Generator[None, None, None]:
    global _workflow_context_shared
    previous = _workflow_context_shared
    _workflow_context_shared = context
    token = _workflow_context_var.set(context)
    try:
        yield
    finally:
        _workflow_context_var.reset(token)
        _workflow_context_shared = previous


def active_sink() -> WorkflowSink | None:
    return _workflow_sink_var.get() or _workflow_sink_shared


def get_workflow_context() -> WorkflowContext | None:
    return _workflow_context_var.get() or _workflow_context_shared


def has_workflow_sink() -> bool:
    return active_sink() is not None
```

- [ ] **Step 2: Write `workflow/event_builders.py`**

Extracted from `tui_toolkit/workflow.py` (lines 67–158).

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/event_builders.py
from __future__ import annotations

from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent


def _resolve_context_fields(
    *,
    flow_id: str | None,
    flow_run_id: str | None,
    task_id: str | None,
    parent_task_id: str | None,
    task_run_id: str | None,
    context: WorkflowContext | None,
    inherit_task_id: bool = True,
) -> tuple[str, str | None, str | None, str | None, str | None]:
    active = context or WorkflowContext()
    resolved_task_id = task_id if task_id is not None else (active.task_id if inherit_task_id else None)
    resolved_parent = parent_task_id if parent_task_id is not None else active.parent_task_id
    return (
        flow_id or active.flow_id,
        flow_run_id or active.flow_run_id,
        resolved_task_id,
        resolved_parent,
        task_run_id or active.task_run_id,
    )


def build_task_event(
    *,
    kind: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str = "",
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id, context=context,
    )
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=title or resolved[2] or kind,
        detail=detail,
    )


def build_phase_event(
    label: str,
    *,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=None,
        parent_task_id=None, task_run_id=None, context=context,
    )
    return WorkflowEvent(
        kind="phase.started",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=label,
    )


def build_log_event(
    *,
    line: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    stream: str = "stdout",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id, context=context,
    )
    return WorkflowEvent(
        kind="log.line",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        stream=stream, line=line,
    )
```

- [ ] **Step 3: Write test files**

```python
# tools/workflow-tasks/tests/workflow/test_context.py
from __future__ import annotations

from contextlib import contextmanager

from workflow_tasks.workflow.context import (
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
)
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        yield


def test_get_workflow_context_default_is_none() -> None:
    assert get_workflow_context() is None


def test_bind_workflow_context_makes_it_visible() -> None:
    ctx = WorkflowContext(flow_id="bound")
    with bind_workflow_context(ctx):
        assert get_workflow_context() is ctx
    assert get_workflow_context() is None


def test_has_workflow_sink_default_false() -> None:
    assert has_workflow_sink() is False


def test_bind_workflow_sink_makes_it_visible() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        assert has_workflow_sink() is True
    assert has_workflow_sink() is False


def test_context_restores_after_nested_bind() -> None:
    outer = WorkflowContext(flow_id="outer")
    inner = WorkflowContext(flow_id="inner")
    with bind_workflow_context(outer):
        with bind_workflow_context(inner):
            assert get_workflow_context().flow_id == "inner"
        assert get_workflow_context().flow_id == "outer"
    assert get_workflow_context() is None
```

```python
# tools/workflow-tasks/tests/workflow/test_event_builders.py
from __future__ import annotations

from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext


def test_build_task_event_minimal() -> None:
    event = build_task_event(kind="task.completed", title="x")
    assert event.kind == "task.completed"
    assert event.title == "x"
    assert event.flow_id == "interactive.console"


def test_build_task_event_inherits_from_context() -> None:
    ctx = WorkflowContext(flow_id="my-flow", task_id="t1", parent_task_id="root")
    event = build_task_event(kind="task.running", title="run", context=ctx)
    assert event.flow_id == "my-flow"
    assert event.task_id == "t1"
    assert event.parent_task_id == "root"


def test_build_task_event_explicit_overrides_context() -> None:
    ctx = WorkflowContext(flow_id="ctx-flow", task_id="t1")
    event = build_task_event(kind="task.completed", task_id="t2", context=ctx)
    assert event.task_id == "t2"
    assert event.flow_id == "ctx-flow"


def test_build_task_event_falls_back_title_to_task_id() -> None:
    event = build_task_event(kind="task.completed", task_id="my-task")
    assert event.title == "my-task"


def test_build_phase_event() -> None:
    event = build_phase_event("Provisioning")
    assert event.kind == "phase.started"
    assert event.title == "Provisioning"


def test_build_log_event_default_stream_stdout() -> None:
    event = build_log_event(line="hello")
    assert event.kind == "log.line"
    assert event.line == "hello"
    assert event.stream == "stdout"


def test_build_log_event_stderr() -> None:
    event = build_log_event(line="boom", stream="stderr")
    assert event.stream == "stderr"


def test_build_task_event_supports_parent_task_identity() -> None:
    event = build_task_event(
        kind="task.running", flow_id="e2e.k3s",
        task_id="verify.health", parent_task_id="tests.run_checks",
        title="Verifying health",
    )
    assert event.task_id == "verify.health"
    assert event.parent_task_id == "tests.run_checks"


def test_build_log_event_preserves_parent_from_context() -> None:
    event = build_log_event(
        line="ok",
        context=WorkflowContext(
            flow_id="e2e.k3s", task_id="images.build", parent_task_id="tests.run_checks",
        ),
    )
    assert event.task_id == "images.build"
    assert event.parent_task_id == "tests.run_checks"
```

- [ ] **Step 4: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/workflow/test_context.py tests/workflow/test_event_builders.py -v
```

Expected: 14 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/workflow/context.py \
        tools/workflow-tasks/src/workflow_tasks/workflow/event_builders.py \
        tools/workflow-tasks/tests/workflow/test_context.py \
        tools/workflow-tasks/tests/workflow/test_event_builders.py
git commit -m "feat(workflow-tasks): add workflow context management and event builders"
```

---

## Task 7: Move reporting helpers and Prefect integration

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflow/reporting.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/integrations/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/integrations/prefect.py`
- Create: `tools/workflow-tasks/tests/workflow/test_reporting.py`
- Create: `tools/workflow-tasks/tests/integrations/test_prefect.py`

- [ ] **Step 1: Write `workflow/reporting.py`**

Extracted from `tui_toolkit/workflow.py` helpers section. No Rich dependency — dispatches to sink only; no-op if no sink is bound.

```python
# tools/workflow-tasks/src/workflow_tasks/workflow/reporting.py
from __future__ import annotations

from contextlib import contextmanager
from typing import Generator

from workflow_tasks.workflow.context import (
    active_sink,
    bind_workflow_context,
    get_workflow_context,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent


def _emit(event: WorkflowEvent) -> None:
    sink = active_sink()
    if sink is not None:
        sink.emit(event)


def phase(label: str) -> None:
    _emit(build_phase_event(label, context=get_workflow_context()))


def step(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.running", title=label, detail=detail, context=get_workflow_context()))


def success(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.completed", title=label, detail=detail, context=get_workflow_context()))


def warning(label: str) -> None:
    _emit(build_task_event(kind="task.warning", title=label, context=get_workflow_context()))


def skip(label: str) -> None:
    _emit(build_task_event(kind="task.skipped", title=label, context=get_workflow_context()))


def fail(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.failed", title=label, detail=detail, context=get_workflow_context()))


def workflow_log(message: str, *, stream: str = "stdout", context: WorkflowContext | None = None) -> None:
    _emit(build_log_event(line=message, stream=stream, context=context or get_workflow_context()))


@contextmanager
def status(label: str) -> Generator[None, None, None]:
    sink = active_sink()
    if sink is not None:
        with sink.status(label):
            yield
    else:
        yield


def _child_context(
    *, task_id: str, parent_task_id: str | None, context: WorkflowContext | None
) -> WorkflowContext:
    active = context or get_workflow_context() or WorkflowContext()
    resolved_parent = parent_task_id
    if resolved_parent is None:
        resolved_parent = active.task_id or active.parent_task_id
    return WorkflowContext(
        flow_id=active.flow_id,
        flow_run_id=active.flow_run_id,
        task_id=task_id,
        parent_task_id=resolved_parent,
        task_run_id=active.task_run_id,
    )


@contextmanager
def workflow_step(
    *,
    task_id: str,
    title: str,
    parent_task_id: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
) -> Generator[WorkflowContext, None, None]:
    child = _child_context(task_id=task_id, parent_task_id=parent_task_id, context=context)
    _emit(build_task_event(
        kind="task.running", task_id=task_id, parent_task_id=child.parent_task_id,
        title=title, detail=detail, context=child,
    ))
    with bind_workflow_context(child):
        try:
            yield child
        except Exception as exc:
            _emit(build_task_event(
                kind="task.failed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail or str(exc), context=child,
            ))
            raise
        else:
            _emit(build_task_event(
                kind="task.completed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail, context=child,
            ))
```

- [ ] **Step 2: Write `integrations/prefect.py`**

Moved from `controlplane_tool/workflow/workflow_events.py` (`normalize_task_state`) and `controlplane_tool/orchestation/prefect_event_bridge.py` (`PrefectEventBridge`).

```python
# tools/workflow-tasks/src/workflow_tasks/integrations/prefect.py
from __future__ import annotations

from collections.abc import Callable

from workflow_tasks.workflow.event_builders import build_log_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent

_PREFECT_STATE_TO_EVENT_KIND = {
    "cancelled": "task.cancelled",
    "completed": "task.completed",
    "crashed": "task.failed",
    "failed": "task.failed",
    "pending": "task.pending",
    "running": "task.running",
    "scheduled": "task.pending",
}


def normalize_task_state(
    *,
    flow_id: str,
    task_id: str,
    state_name: str,
    flow_run_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    kind = _PREFECT_STATE_TO_EVENT_KIND.get(state_name.strip().lower(), "task.updated")
    active = context or WorkflowContext()
    return build_task_event(
        kind=kind,
        flow_id=flow_id or active.flow_id,
        flow_run_id=flow_run_id or active.flow_run_id,
        task_id=task_id if task_id is not None else active.task_id,
        parent_task_id=parent_task_id if parent_task_id is not None else active.parent_task_id,
        task_run_id=task_run_id or active.task_run_id,
        title=title or task_id or "",
        detail=detail or state_name,
        context=context,
    )


class PrefectEventBridge:
    def __init__(self, emit: Callable[[WorkflowEvent], None] | None = None) -> None:
        self._emit = emit or (lambda event: None)

    def emit_task_state(
        self,
        *,
        flow_id: str,
        task_id: str,
        state_name: str,
        flow_run_id: str | None = None,
        task_run_id: str | None = None,
        title: str | None = None,
        detail: str = "",
    ) -> WorkflowEvent:
        event = normalize_task_state(
            flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
            task_run_id=task_run_id, state_name=state_name, title=title, detail=detail,
        )
        self._emit(event)
        return event

    def emit_log(
        self,
        *,
        flow_id: str,
        line: str,
        flow_run_id: str | None = None,
        task_id: str | None = None,
        task_run_id: str | None = None,
        stream: str = "stdout",
    ) -> WorkflowEvent:
        event = build_log_event(
            flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
            task_run_id=task_run_id, stream=stream, line=line,
        )
        self._emit(event)
        return event
```

- [ ] **Step 3: Write `integrations/__init__.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/integrations/__init__.py
```

- [ ] **Step 4: Write test files**

```python
# tools/workflow-tasks/tests/workflow/test_reporting.py
from __future__ import annotations

from contextlib import contextmanager

import pytest

from workflow_tasks.workflow.context import bind_workflow_context, bind_workflow_sink
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent
from workflow_tasks.workflow.reporting import (
    fail, phase, skip, status, step, success, warning, workflow_log, workflow_step,
)


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []
        self.status_labels: list[str] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        self.status_labels.append(label)
        yield


def test_step_emits_task_running_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        step("Deploy VM")
    assert len(sink.events) == 1
    assert sink.events[0].kind == "task.running"
    assert sink.events[0].title == "Deploy VM"


def test_success_emits_task_completed_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        success("Deploy VM", detail="took 3s")
    assert sink.events[0].kind == "task.completed"
    assert sink.events[0].detail == "took 3s"


def test_fail_emits_task_failed_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        fail("Deploy VM", detail="timeout")
    assert sink.events[0].kind == "task.failed"


def test_phase_emits_phase_started() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        phase("Provisioning")
    assert sink.events[0].kind == "phase.started"
    assert sink.events[0].title == "Provisioning"


def test_warning_emits_task_warning() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        warning("Low disk space")
    assert sink.events[0].kind == "task.warning"


def test_skip_emits_task_skipped() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        skip("Optional step")
    assert sink.events[0].kind == "task.skipped"


def test_workflow_log_emits_log_line() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        workflow_log("hello")
    assert sink.events[0].kind == "log.line"
    assert sink.events[0].line == "hello"


def test_status_delegates_to_sink_status() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with status("loading"):
            pass
    assert sink.status_labels == ["loading"]


def test_status_is_noop_without_sink() -> None:
    with status("loading"):
        pass  # no error, no crash


def test_helpers_are_noop_without_sink() -> None:
    step("no sink")
    success("no sink")
    fail("no sink")
    phase("no sink")


def test_workflow_step_emits_running_then_completed() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with workflow_step(task_id="vm.up", title="Start VM"):
            pass
    assert [e.kind for e in sink.events] == ["task.running", "task.completed"]
    assert all(e.task_id == "vm.up" for e in sink.events)


def test_workflow_step_emits_failed_on_exception() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with pytest.raises(RuntimeError, match="boom"):
            with workflow_step(task_id="vm.up", title="Start VM"):
                raise RuntimeError("boom")
    assert [e.kind for e in sink.events] == ["task.running", "task.failed"]


def test_workflow_step_propagates_parent_task_id_from_context() -> None:
    sink = _FakeSink()
    ctx = WorkflowContext(flow_id="e2e.k3s", task_id="tests.run_checks")
    with bind_workflow_sink(sink), bind_workflow_context(ctx):
        with workflow_step(task_id="verify.health", title="Verifying health"):
            pass
    assert all(e.parent_task_id == "tests.run_checks" for e in sink.events)
```

```python
# tools/workflow-tasks/tests/integrations/test_prefect.py
from __future__ import annotations

from workflow_tasks.integrations.prefect import PrefectEventBridge, normalize_task_state
from workflow_tasks.workflow.events import WorkflowContext


def test_normalize_completed_state() -> None:
    event = normalize_task_state(flow_id="e2e.k8s_vm", task_id="vm.ensure_running", state_name="Completed")
    assert event.kind == "task.completed"
    assert event.task_id == "vm.ensure_running"


def test_normalize_failed_state() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="Failed")
    assert event.kind == "task.failed"


def test_normalize_crashed_maps_to_failed() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="Crashed")
    assert event.kind == "task.failed"


def test_normalize_unknown_state_maps_to_updated() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="SomeUnknownState")
    assert event.kind == "task.updated"


def test_normalize_preserves_parent_task_id_from_args() -> None:
    event = normalize_task_state(
        flow_id="e2e", task_id="vm.up", parent_task_id="tests.run_checks", state_name="Completed",
    )
    assert event.parent_task_id == "tests.run_checks"


def test_normalize_preserves_parent_task_id_from_context() -> None:
    event = normalize_task_state(
        flow_id="e2e", task_id="vm.up", state_name="Completed",
        context=WorkflowContext(flow_id="e2e", task_id="vm.up", parent_task_id="tests.run_checks"),
    )
    assert event.parent_task_id == "tests.run_checks"


def test_prefect_event_bridge_emits_state_event() -> None:
    emitted = []
    bridge = PrefectEventBridge(emit=emitted.append)
    event = bridge.emit_task_state(flow_id="e2e", task_id="vm.up", state_name="Completed")
    assert emitted == [event]
    assert event.kind == "task.completed"


def test_prefect_event_bridge_emits_log_event() -> None:
    emitted = []
    bridge = PrefectEventBridge(emit=emitted.append)
    event = bridge.emit_log(flow_id="e2e", line="docker push ok")
    assert emitted == [event]
    assert event.kind == "log.line"
    assert event.line == "docker push ok"


def test_prefect_event_bridge_noop_without_emit_callback() -> None:
    bridge = PrefectEventBridge()
    event = bridge.emit_task_state(flow_id="e2e", task_id="x", state_name="Completed")
    assert event.kind == "task.completed"
```

- [ ] **Step 5: Run tests**

```bash
cd tools/workflow-tasks && uv run pytest tests/workflow/test_reporting.py tests/integrations/test_prefect.py -v
```

Expected: 22 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/workflow/reporting.py \
        tools/workflow-tasks/src/workflow_tasks/integrations/ \
        tools/workflow-tasks/tests/workflow/test_reporting.py \
        tools/workflow-tasks/tests/integrations/test_prefect.py
git commit -m "feat(workflow-tasks): add workflow reporting helpers and Prefect integration"
```

---

## Task 8: Create public API, boundary tests, and tooling

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/devtools/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/devtools/quality.py`
- Create: `tools/workflow-tasks/.importlinter`
- Create: `tools/workflow-tasks/tests/conftest.py`
- Create: `tools/workflow-tasks/tests/test_package_boundaries.py`
- Create: `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Write `workflow_tasks/__init__.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/__init__.py
"""workflow-tasks — task execution primitives and workflow event infrastructure.

Zero external dependencies. Configure once via bind_workflow_sink(); every
call to step/success/fail routes to the active sink.
"""
from __future__ import annotations

__version__ = "0.1.0"

from workflow_tasks.tasks.adapters import RemoteCommandOperationLike, operation_to_task_spec
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec, ExecutionTarget, TaskResult, TaskStatus
from workflow_tasks.tasks.rendering import render_shell_command, render_task_command
from workflow_tasks.workflow.context import (
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState
from workflow_tasks.workflow.reporting import (
    fail,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)

__all__ = [
    "__version__",
    # tasks
    "CommandTaskSpec", "ExecutionTarget", "TaskResult", "TaskStatus",
    "HostCommandTaskExecutor", "VmCommandTaskExecutor",
    "render_shell_command", "render_task_command",
    "RemoteCommandOperationLike", "operation_to_task_spec",
    # workflow types
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
    "WorkflowState", "WorkflowRun", "TaskDefinition", "TaskRun",
    # workflow runtime
    "bind_workflow_sink", "bind_workflow_context", "get_workflow_context", "has_workflow_sink",
    "build_task_event", "build_phase_event", "build_log_event",
    "phase", "step", "success", "warning", "skip", "fail",
    "workflow_log", "workflow_step", "status",
]
```

- [ ] **Step 2: Write `devtools/quality.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/devtools/quality.py
from __future__ import annotations

import subprocess
import sys

CHECKS = (
    ("ruff", ["ruff", "check", "."]),
    ("basedpyright", ["basedpyright"]),
    ("import-linter", ["lint-imports"]),
)


def main() -> None:
    failures: list[str] = []
    for name, command in CHECKS:
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            failures.append(name)
    if failures:
        raise SystemExit(f"Quality checks failed: {', '.join(failures)}")
    sys.stdout.write("Quality checks passed\n")
```

Add the script entry point to `pyproject.toml`:

```toml
[project.scripts]
workflow-tasks-quality = "workflow_tasks.devtools.quality:main"
```

- [ ] **Step 3: Write `.importlinter`**

```ini
# tools/workflow-tasks/.importlinter
[importlinter]
root_package = workflow_tasks

[importlinter:contract:tasks_are_independent]
name = tasks must not import workflow or integrations
type = forbidden
source_modules = workflow_tasks.tasks
forbidden_modules =
    workflow_tasks.workflow
    workflow_tasks.integrations

[importlinter:contract:pure_types_are_logic_free]
name = pure type modules must not import logic modules
type = forbidden
source_modules =
    workflow_tasks.workflow.events
    workflow_tasks.workflow.models
    workflow_tasks.workflow.context
forbidden_modules =
    workflow_tasks.workflow.reporting
    workflow_tasks.workflow.event_builders

[importlinter:contract:no_external_deps]
name = workflow_tasks must not import tui_toolkit or controlplane_tool
type = forbidden
source_modules = workflow_tasks
forbidden_modules =
    tui_toolkit
    controlplane_tool
```

- [ ] **Step 4: Write `tests/conftest.py`**

```python
# tools/workflow-tasks/tests/conftest.py
from __future__ import annotations

from contextlib import contextmanager

import pytest

from workflow_tasks.workflow.events import WorkflowEvent


class FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []
        self.status_events: list[tuple[str, str]] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        self.status_events.append(("start", label))
        try:
            yield
        finally:
            self.status_events.append(("end", label))


@pytest.fixture
def fake_sink() -> FakeSink:
    return FakeSink()
```

- [ ] **Step 5: Write `tests/test_package_boundaries.py`**

```python
# tools/workflow-tasks/tests/test_package_boundaries.py
from __future__ import annotations

import importlib
import sys


def test_workflow_tasks_does_not_import_tui_toolkit() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("tui_toolkit"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks")
    assert not any(k.startswith("tui_toolkit") for k in sys.modules), (
        "workflow_tasks imported tui_toolkit"
    )


def test_workflow_tasks_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules), (
        "workflow_tasks imported controlplane_tool"
    )


def test_tasks_subpackage_does_not_import_workflow() -> None:
    import workflow_tasks.tasks.models
    import workflow_tasks.tasks.executors
    import workflow_tasks.tasks.rendering
    import workflow_tasks.tasks.adapters
    # If we got here without importing workflow subpackage transitively, we're good.
    # The import-linter contract enforces this at the CI gate.
```

- [ ] **Step 6: Write `tests/test_public_api.py`**

```python
# tools/workflow-tasks/tests/test_public_api.py
from __future__ import annotations

import workflow_tasks


def test_public_api_exports_task_types() -> None:
    assert hasattr(workflow_tasks, "CommandTaskSpec")
    assert hasattr(workflow_tasks, "TaskResult")
    assert hasattr(workflow_tasks, "TaskStatus")
    assert hasattr(workflow_tasks, "ExecutionTarget")


def test_public_api_exports_executors() -> None:
    assert hasattr(workflow_tasks, "HostCommandTaskExecutor")
    assert hasattr(workflow_tasks, "VmCommandTaskExecutor")


def test_public_api_exports_workflow_types() -> None:
    assert hasattr(workflow_tasks, "WorkflowEvent")
    assert hasattr(workflow_tasks, "WorkflowContext")
    assert hasattr(workflow_tasks, "WorkflowSink")


def test_public_api_exports_reporting_helpers() -> None:
    assert hasattr(workflow_tasks, "phase")
    assert hasattr(workflow_tasks, "step")
    assert hasattr(workflow_tasks, "success")
    assert hasattr(workflow_tasks, "fail")
    assert hasattr(workflow_tasks, "workflow_step")


def test_version_is_set() -> None:
    assert workflow_tasks.__version__ == "0.1.0"
```

- [ ] **Step 7: Run full test suite with coverage**

```bash
cd tools/workflow-tasks && uv run pytest -v
```

Expected: all tests PASS, coverage ≥ 90%.

- [ ] **Step 8: Run quality checks**

```bash
cd tools/workflow-tasks && uv run ruff check .
cd tools/workflow-tasks && uv run lint-imports
```

Expected: no errors.

- [ ] **Step 9: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/__init__.py \
        tools/workflow-tasks/src/workflow_tasks/devtools/ \
        tools/workflow-tasks/.importlinter \
        tools/workflow-tasks/tests/conftest.py \
        tools/workflow-tasks/tests/test_package_boundaries.py \
        tools/workflow-tasks/tests/test_public_api.py
git commit -m "feat(workflow-tasks): add public API, boundary tests, and quality tooling"
```

---

## Task 9: Add shims in `tui_toolkit`

**Files:**
- Modify: `tools/tui-toolkit/src/tui_toolkit/events.py`
- Modify: `tools/tui-toolkit/src/tui_toolkit/workflow.py`
- Modify: `tools/tui-toolkit/pyproject.toml`
- Create: `tools/tui-toolkit/.importlinter`

- [ ] **Step 1: Update `tui_toolkit/events.py` to shim**

Replace the entire file content with:

```python
# tools/tui-toolkit/src/tui_toolkit/events.py
"""Shim: WorkflowEvent, WorkflowContext, WorkflowSink moved to workflow_tasks.

Re-exported here for backward compatibility. Will be removed in PR2.
"""
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink

__all__ = ["WorkflowContext", "WorkflowEvent", "WorkflowSink"]
```

- [ ] **Step 2: Update `tui_toolkit/workflow.py` to import types from `workflow_tasks`**

Replace the two import lines at the top of the file:

```python
# OLD (lines 14-14):
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink

# NEW:
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.context import (
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
    active_sink,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
```

Also update the ContextVar plumbing block (lines 19–64): **delete it entirely** — it is now provided by `workflow_tasks.workflow.context`. The `_active_sink` function is now `active_sink` from `workflow_tasks.workflow.context`.

Replace all internal uses of `_active_sink()` with `active_sink()`.

The file after editing retains only: the renderer (`_render_event`), `_emit` (which calls `active_sink()` or `_render_event`), and the user-facing helpers (`header`, `phase`, `step`, `success`, `warning`, `skip`, `fail`, `workflow_log`, `status`, `workflow_step`) — but these now delegate to `workflow_tasks.workflow.reporting` for the sink path and fall back to Rich.

Full updated `tui_toolkit/workflow.py` after edit:

```python
# tools/tui-toolkit/src/tui_toolkit/workflow.py
"""Workflow renderer + Rich-backed helpers.

The event types, context management, builders, and sink-only helpers live in
workflow_tasks. This module adds the Rich fallback renderer for standalone
tui_toolkit usage.
"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Generator

from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

import tui_toolkit.console as _console_mod
from tui_toolkit.context import get_ui
from workflow_tasks.workflow.context import (
    active_sink,
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.reporting import workflow_log


def _render_event(event: WorkflowEvent) -> None:
    theme = get_ui().theme
    _con = _console_mod.console

    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        _con.print(f"{prefix}{escape(event.line)}")
        return
    if event.kind == "phase.started":
        _con.print()
        _con.print(Rule(f"[{theme.accent_strong}]{escape(event.title)}[/]", style=theme.accent_dim))
        _con.print()
        return
    if event.kind == "task.running":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.completed":
        body = f"[bold {theme.success}]{theme.icon_completed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.success, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.warning":
        _con.print(f"  [{theme.warning}]{theme.icon_warning}[/]  [{theme.warning}]{escape(event.title)}[/]")
        return
    if event.kind == "task.updated":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.skipped":
        _con.print(f"  [{theme.muted}]{theme.icon_skipped}  {escape(event.title)}[/]")
        return
    if event.kind == "task.cancelled":
        body = f"[bold {theme.warning}]{theme.icon_cancelled}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.warning, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.failed":
        body = f"[bold {theme.error}]{theme.icon_failed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.error, padding=(0, 2)))
        _con.print()


def _emit(event: WorkflowEvent) -> None:
    sink = active_sink()
    if sink is not None:
        sink.emit(event)
    else:
        _render_event(event)


def header(subtitle: str | None = None) -> None:
    ui = get_ui()
    _con = _console_mod.console
    if ui.brand.ascii_logo:
        _con.print()
        _con.print(Text(ui.brand.ascii_logo, style=ui.theme.brand, justify="center"))
    if subtitle:
        _con.print(
            Panel(
                f"[{ui.theme.muted}]{escape(subtitle)}[/]",
                border_style=ui.theme.accent_dim,
                padding=(0, 4),
            )
        )
    _con.print()


def phase(label: str) -> None:
    _emit(build_phase_event(label, context=get_workflow_context()))


def step(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.running", title=label, detail=detail, context=get_workflow_context()))


def success(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.completed", title=label, detail=detail, context=get_workflow_context()))


def warning(label: str) -> None:
    _emit(build_task_event(kind="task.warning", title=label, context=get_workflow_context()))


def skip(label: str) -> None:
    _emit(build_task_event(kind="task.skipped", title=label, context=get_workflow_context()))


def fail(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.failed", title=label, detail=detail, context=get_workflow_context()))


@contextmanager
def status(label: str) -> Generator[None, None, None]:
    sink = active_sink()
    if sink is not None:
        with sink.status(label):
            yield
        return
    with _console_mod.console.status(
        f"[{get_ui().theme.accent}]{escape(label)}…[/]", spinner="dots"
    ):
        yield
```

**Note:** `workflow_log` is re-exported from `workflow_tasks.workflow.reporting`. `workflow_step` is defined locally in `tui_toolkit/workflow.py` (keeping the same implementation as before, using `_emit` for the Rich fallback — same as `phase`, `step`, etc. above). Add `_child_context` as a local private helper (copy from `workflow_tasks/workflow/reporting.py`) since `tui_toolkit/workflow.py` needs it to implement `workflow_step`.

- [ ] **Step 3: Update `tui_toolkit/pyproject.toml`**

Add `workflow-tasks` to dependencies and dev tools:

```toml
[project]
dependencies = [
    "rich>=13.8",
    "questionary>=2.1.1",
    "prompt-toolkit>=3.0",
    "workflow-tasks",
]

[dependency-groups]
dev = [
    "basedpyright>=1.39.3",
    "grimp>=3.14",
    "import-linter>=2.11",
    "pydeps>=3.0.6",
    "pytest>=8.3.4",
    "pytest-cov>=6.0",
    "ruff>=0.15.12",
]

[tool.uv.sources]
workflow-tasks = { path = "../workflow-tasks", editable = true }

[tool.pytest.ini_options]
testpaths = ["tests"]
addopts = "-q --cov=tui_toolkit --cov-report=term-missing --cov-fail-under=80"

[tool.ruff]
target-version = "py311"
line-length = 100

[tool.ruff.lint]
select = ["F", "SLF"]

[tool.ruff.lint.per-file-ignores]
"tests/**/*.py" = ["F841", "SLF001"]

[tool.basedpyright]
include = ["src/tui_toolkit"]
typeCheckingMode = "basic"
reportMissingTypeStubs = false
reportPrivateUsage = false
```

- [ ] **Step 4: Create `tui_toolkit/.importlinter`**

```ini
# tools/tui-toolkit/.importlinter
[importlinter]
root_package = tui_toolkit

[importlinter:contract:no_controlplane_dep]
name = tui_toolkit must not import controlplane_tool
type = forbidden
source_modules = tui_toolkit
forbidden_modules =
    controlplane_tool
```

Note: `tui_toolkit → workflow_tasks` is intentional during PR1 (shim state) and NOT forbidden here. The final contract banning `workflow_tasks` is added in PR2.

- [ ] **Step 5: Sync and run tui_toolkit tests**

```bash
cd tools/tui-toolkit && uv sync && uv run pytest -v
```

Expected: all existing tui_toolkit tests PASS (events, workflow_events, workflow_render tests exercise the shimmed code).

- [ ] **Step 6: Commit**

```bash
git add tools/tui-toolkit/
git commit -m "feat(tui-toolkit): shim events to workflow_tasks, add dev tooling"
```

---

## Task 10: Add shims in `controlplane_tool` and rename `prefect_bridge`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tasks/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/tasks/executors.py`
- Modify: `tools/controlplane/src/controlplane_tool/tasks/rendering.py`
- Modify: `tools/controlplane/src/controlplane_tool/tasks/adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/tasks/__init__.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow/workflow_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow/workflow_events.py`
- Delete: `tools/controlplane/src/controlplane_tool/orchestation/prefect_event_bridge.py`
- Rename: `tools/controlplane/src/controlplane_tool/tui/prefect_bridge.py` → `event_aggregator.py`
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/.importlinter`

- [ ] **Step 1: Replace `controlplane_tool/tasks/models.py` with shim**

```python
# tools/controlplane/src/controlplane_tool/tasks/models.py
from workflow_tasks.tasks.models import CommandTaskSpec, ExecutionTarget, TaskResult, TaskStatus

__all__ = ["CommandTaskSpec", "ExecutionTarget", "TaskResult", "TaskStatus"]
```

- [ ] **Step 2: Replace `controlplane_tool/tasks/executors.py` with shim**

```python
# tools/controlplane/src/controlplane_tool/tasks/executors.py
from workflow_tasks.tasks.executors import (
    CommandRunResult,
    HostCommandRunner,
    HostCommandTaskExecutor,
    VmCommandResult,
    VmCommandRunner,
    VmCommandTaskExecutor,
)

__all__ = [
    "CommandRunResult", "HostCommandRunner", "HostCommandTaskExecutor",
    "VmCommandResult", "VmCommandRunner", "VmCommandTaskExecutor",
]
```

- [ ] **Step 3: Replace `controlplane_tool/tasks/rendering.py` with shim**

```python
# tools/controlplane/src/controlplane_tool/tasks/rendering.py
from workflow_tasks.tasks.rendering import render_shell_command, render_task_command

__all__ = ["render_shell_command", "render_task_command"]
```

- [ ] **Step 4: Replace `controlplane_tool/tasks/adapters.py` with shim**

```python
# tools/controlplane/src/controlplane_tool/tasks/adapters.py
from workflow_tasks.tasks.adapters import RemoteCommandOperationLike, operation_to_task_spec

__all__ = ["RemoteCommandOperationLike", "operation_to_task_spec"]
```

- [ ] **Step 5: Replace `controlplane_tool/tasks/__init__.py` with shim**

```python
# tools/controlplane/src/controlplane_tool/tasks/__init__.py
from workflow_tasks.tasks.models import CommandTaskSpec, ExecutionTarget, TaskResult, TaskStatus

__all__ = ["CommandTaskSpec", "ExecutionTarget", "TaskResult", "TaskStatus"]
```

- [ ] **Step 6: Update `controlplane_tool/workflow/workflow_models.py`**

Keep `TuiPhaseSnapshot` and `TuiWorkflowSnapshot` (presentation models that stay in controlplane_tool). Shim the domain models to `workflow_tasks`. Re-exports of `WorkflowContext/Event/Sink` now come from `workflow_tasks` via `tui_toolkit` shim — redirect to source:

```python
# tools/controlplane/src/controlplane_tool/workflow/workflow_models.py
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Literal

from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState


def utc_now():
    from datetime import UTC, datetime
    return datetime.now(UTC)


@dataclass(slots=True)
class TuiPhaseSnapshot:
    label: str
    task_id: str | None = None
    parent_task_id: str | None = None
    status: WorkflowState = "pending"
    detail: str = ""
    started_at: float | None = None
    finished_at: float | None = None
    children: list["TuiPhaseSnapshot"] = field(default_factory=list)


@dataclass(slots=True)
class TuiWorkflowSnapshot:
    phases: list[TuiPhaseSnapshot]
    logs: list[str]
    show_logs: bool


__all__ = [
    "utc_now",
    "WorkflowState", "WorkflowRun", "TaskDefinition", "TaskRun",
    "TuiPhaseSnapshot", "TuiWorkflowSnapshot",
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
]
```

- [ ] **Step 7: Update `controlplane_tool/workflow/workflow_events.py`**

```python
# tools/controlplane/src/controlplane_tool/workflow/workflow_events.py
from workflow_tasks.integrations.prefect import PrefectEventBridge, normalize_task_state
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent

__all__ = [
    "build_log_event", "build_phase_event", "build_task_event",
    "normalize_task_state", "PrefectEventBridge",
    "WorkflowContext", "WorkflowEvent",
]
```

- [ ] **Step 8: Delete `orchestation/prefect_event_bridge.py`**

```bash
rm tools/controlplane/src/controlplane_tool/orchestation/prefect_event_bridge.py
```

Update any file importing `PrefectEventBridge` from the old path to import from `workflow_tasks.integrations.prefect`:

```bash
grep -r "from controlplane_tool.orchestation.prefect_event_bridge" tools/controlplane/src/ --include="*.py" -l
```

For each found file, change:
```python
# OLD
from controlplane_tool.orchestation.prefect_event_bridge import PrefectEventBridge
# NEW
from workflow_tasks.integrations.prefect import PrefectEventBridge
```

- [ ] **Step 9: Rename `tui/prefect_bridge.py` → `tui/event_aggregator.py`**

```bash
git mv tools/controlplane/src/controlplane_tool/tui/prefect_bridge.py \
       tools/controlplane/src/controlplane_tool/tui/event_aggregator.py
```

In `event_aggregator.py`, rename the class:

```python
# Change class TuiPrefectBridge → WorkflowEventAggregator
# (search/replace throughout the file)
```

Update all files importing `TuiPrefectBridge`:

```bash
grep -r "TuiPrefectBridge\|prefect_bridge" tools/controlplane/src/ --include="*.py" -l
```

For each found file, update imports:
```python
# OLD
from controlplane_tool.tui.prefect_bridge import TuiPrefectBridge
# NEW
from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
```

Also update `tools/controlplane/tests/test_task_workflow_bridge.py`:
```python
# OLD
from controlplane_tool.tui.prefect_bridge import TuiPrefectBridge
bridge = TuiPrefectBridge()
# NEW
from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
bridge = WorkflowEventAggregator()
```

- [ ] **Step 10: Update `controlplane/pyproject.toml`**

Add `workflow-tasks` to dependencies:

Add `"workflow-tasks"` to the existing `[project] dependencies` list in `tools/controlplane/pyproject.toml` (keep all existing entries). Then add the `uv.sources` entry:

```toml
[tool.uv.sources]
tui-toolkit = { path = "../tui-toolkit", editable = true }
workflow-tasks = { path = "../workflow-tasks", editable = true }
```

- [ ] **Step 11: Update `.importlinter` — add workflow_tasks contract**

Add to `tools/controlplane/.importlinter`:

```ini
[importlinter:contract:workflow_tasks_is_independent]
name = workflow_tasks must not depend on controlplane
type = forbidden
source_modules = workflow_tasks
forbidden_modules =
    controlplane_tool
```

- [ ] **Step 12: Sync and run full controlplane test suite**

```bash
cd tools/controlplane && uv sync && uv run pytest -x -q
```

Expected: all ~835 existing tests PASS.

- [ ] **Step 13: Run quality checks on controlplane**

```bash
cd tools/controlplane && uv run ruff check . && uv run lint-imports
```

Expected: no errors.

- [ ] **Step 14: Commit**

```bash
git add tools/controlplane/
git commit -m "feat(controlplane): shim tasks/workflow to workflow_tasks, rename event_aggregator"
```

---

## Task 11: Add cross-project grimp check

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/devtools/quality.py`

- [ ] **Step 1: Update `quality.py` to add grimp cross-project check**

```python
# tools/controlplane/src/controlplane_tool/devtools/quality.py
from __future__ import annotations

import subprocess
import sys

ENTRYPOINT_IMPORT_MODULES = (
    "controlplane_tool.app.main",
    "controlplane_tool.cli.commands",
    "controlplane_tool.building.gradle_executor",
)

_GRIMP_CHECK = """
import grimp

graph = grimp.build_graph("controlplane_tool", "tui_toolkit", "workflow_tasks")
violations = []

chain = graph.find_shortest_chain(importer="workflow_tasks", imported="tui_toolkit")
if chain:
    violations.append(f"workflow_tasks -> tui_toolkit: {' -> '.join(chain)}")

chain = graph.find_shortest_chain(importer="tui_toolkit", imported="controlplane_tool")
if chain:
    violations.append(f"tui_toolkit -> controlplane_tool: {' -> '.join(chain)}")

if violations:
    for v in violations:
        print(f"VIOLATION: {v}")
    raise SystemExit(1)

print("Cross-project coupling: OK")
"""

CHECKS = (
    ("ruff", ["ruff", "check", "."]),
    ("basedpyright", ["basedpyright"]),
    ("import-linter", ["lint-imports"]),
    (
        "entrypoint-imports",
        [
            sys.executable,
            "-c",
            (
                "import importlib; "
                f"[importlib.import_module(name) for name in {ENTRYPOINT_IMPORT_MODULES!r}]"
            ),
        ],
    ),
    ("cross-project-coupling", [sys.executable, "-c", _GRIMP_CHECK]),
)


def main() -> None:
    failures: list[str] = []
    for name, command in CHECKS:
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            failures.append(name)
    if failures:
        raise SystemExit(f"Quality checks failed: {', '.join(failures)}")
    sys.stdout.write("Quality checks passed\n")
```

- [ ] **Step 2: Run cross-project check**

```bash
cd tools/controlplane && uv run python -c "
import grimp
graph = grimp.build_graph('controlplane_tool', 'tui_toolkit', 'workflow_tasks')
chain = graph.find_shortest_chain(importer='workflow_tasks', imported='tui_toolkit')
print('workflow_tasks → tui_toolkit:', chain or 'NONE (OK)')
chain = graph.find_shortest_chain(importer='tui_toolkit', imported='controlplane_tool')
print('tui_toolkit → controlplane_tool:', chain or 'NONE (OK)')
"
```

Expected:
```
workflow_tasks → tui_toolkit: NONE (OK)
tui_toolkit → controlplane_tool: NONE (OK)
```

- [ ] **Step 3: Run full quality suite**

```bash
cd tools/controlplane && uv run controlplane-quality
```

Expected: `Quality checks passed`.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/devtools/quality.py
git commit -m "feat(controlplane): add cross-project grimp coupling check"
```

---

## Task 12: Final verification

- [ ] **Step 1: Run all three test suites independently**

```bash
cd tools/workflow-tasks && uv run pytest -q
cd tools/tui-toolkit && uv run pytest -q
cd tools/controlplane && uv run pytest -q
```

Expected: all pass with coverage thresholds met (workflow-tasks ≥ 90%, tui-toolkit ≥ 80%).

- [ ] **Step 2: Verify workflow-tasks is self-contained**

```bash
cd tools/workflow-tasks && uv run python -c "
import sys
# install in a clean env by not importing tui_toolkit or controlplane_tool first
import workflow_tasks
has_tui = any(k.startswith('tui_toolkit') for k in sys.modules)
has_cp = any(k.startswith('controlplane_tool') for k in sys.modules)
print('tui_toolkit imported:', has_tui)
print('controlplane_tool imported:', has_cp)
assert not has_tui, 'FAIL: workflow_tasks imported tui_toolkit'
assert not has_cp, 'FAIL: workflow_tasks imported controlplane_tool'
print('OK')
"
```

Expected: both print `False`, then `OK`.

- [ ] **Step 3: Open PR1**

```bash
git push origin HEAD
gh pr create \
  --title "workflow-tasks PR1: library + shims" \
  --body "$(cat <<'EOF'
## Summary
- New standalone library \`tools/workflow-tasks/\` with zero external deps
- Moves task domain models, executors, rendering, adapters from controlplane_tool
- Moves WorkflowEvent/Context/Sink, context management, event builders, reporting from tui_toolkit
- Moves normalize_task_state + PrefectEventBridge to workflow_tasks.integrations.prefect
- Adds backward-compat shims in tui_toolkit and controlplane_tool — all existing tests pass
- Renames TuiPrefectBridge → WorkflowEventAggregator
- Adds ruff, basedpyright, import-linter, pytest-cov to tui-toolkit
- Adds cross-project grimp coupling check to controlplane-tool quality gate

## Test plan
- [ ] workflow-tasks pytest passes with ≥ 90% coverage
- [ ] tui-toolkit pytest passes with ≥ 80% coverage
- [ ] controlplane-tool pytest passes (all ~835 tests)
- [ ] controlplane-quality passes (ruff, basedpyright, import-linter, grimp)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
