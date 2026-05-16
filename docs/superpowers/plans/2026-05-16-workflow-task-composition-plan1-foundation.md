# Workflow Task Composition — Piano 1: Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere `Task` Protocol e `Workflow` class a `workflow_tasks.core`, poi costruire il catalogo di task concreti in `controlplane_tool/scenario/tasks/` — incluso il fix del bug `[0]` su `RunK6Matrix` e la rimozione del coupling CLI con `RegisterFunctions`.

**Architecture:** `workflow_tasks.core` espone interfacce generiche (`Task` Protocol, `Workflow`); `controlplane_tool/scenario/tasks/` implementa task concreti che wrappano le infrastrutture esistenti (`VmOrchestrator`, `AzureVmOrchestrator`, `ControlPlaneApi`). Nessuna modifica al recipe system in questo piano — la coesistenza è temporanea e verrà risolta nel Piano 2.

**Tech Stack:** Python 3.11+, `workflow_tasks` (workflow_step, build_task_event), `VmOrchestrator`, `AzureVmOrchestrator`, `ControlPlaneApi`, `TwoVmLoadtestRunner`, pytest.

---

## File Structure

**Creati in `tools/workflow-tasks/`:**
- `src/workflow_tasks/core/__init__.py` — re-export `Task`, `Workflow`
- `src/workflow_tasks/core/task.py` — `Task` Protocol
- `src/workflow_tasks/core/workflow.py` — `Workflow` class
- `tests/core/__init__.py`
- `tests/core/test_task.py`
- `tests/core/test_workflow.py`

**Modificati in `tools/workflow-tasks/`:**
- `src/workflow_tasks/__init__.py` — aggiunge `Task`, `Workflow` al public API
- `tests/test_public_api.py` — verifica nuovi export

**Creati in `tools/controlplane/`:**
- `src/controlplane_tool/scenario/tasks/__init__.py`
- `src/controlplane_tool/scenario/tasks/vm.py` — `EnsureVmRunning`, `ProvisionBase`, `SyncProject`, `TeardownVm`
- `src/controlplane_tool/scenario/tasks/k8s.py` — `InstallK3s`, `ConfigureK3sRegistry`, `EnsureRegistry`, `HelmInstall`, `HelmUninstall`, `NamespaceInstall`
- `src/controlplane_tool/scenario/tasks/loadtest.py` — `InstallK6`, `RunK6Matrix`, `CapturePrometheus`, `WriteLoadtestReport`
- `src/controlplane_tool/scenario/tasks/functions.py` — `RegisterFunctions`
- `src/controlplane_tool/scenario/tasks/cli.py` — `BuildCliDist`, `CliApplyFunction`
- `tests/tasks/__init__.py`
- `tests/tasks/test_vm_tasks.py`
- `tests/tasks/test_loadtest_tasks.py`
- `tests/tasks/test_functions_tasks.py`

**Modificati in `tools/controlplane/`:**
- `src/controlplane_tool/e2e/two_vm_loadtest_runner.py` — aggiunge `run_k6_for_function()`

---

## Task 1: `Task` Protocol in `workflow_tasks.core`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/core/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/core/task.py`
- Create: `tools/workflow-tasks/tests/core/__init__.py`
- Create: `tools/workflow-tasks/tests/core/test_task.py`

- [ ] **Step 1: Crea la directory core e i file vuoti**

```bash
mkdir -p tools/workflow-tasks/src/workflow_tasks/core
mkdir -p tools/workflow-tasks/tests/core
touch tools/workflow-tasks/src/workflow_tasks/core/__init__.py
touch tools/workflow-tasks/tests/core/__init__.py
```

- [ ] **Step 2: Scrivi il test (fallirà)**

`tools/workflow-tasks/tests/core/test_task.py`:
```python
from __future__ import annotations
from typing import Any
from workflow_tasks.core.task import Task


def test_task_protocol_is_satisfied_by_dataclass() -> None:
    from dataclasses import dataclass

    @dataclass
    class MyTask:
        task_id: str = "my.task"
        title: str = "My Task"

        def run(self) -> None:
            pass

    task = MyTask()
    assert isinstance(task, Task)


def test_task_protocol_requires_task_id() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoId:
        title: str = "x"

        def run(self) -> None:
            pass

    assert not isinstance(NoId(), Task)


def test_task_protocol_requires_title() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoTitle:
        task_id: str = "x"

        def run(self) -> None:
            pass

    assert not isinstance(NoTitle(), Task)


def test_task_protocol_requires_run() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoRun:
        task_id: str = "x"
        title: str = "x"

    assert not isinstance(NoRun(), Task)


def test_task_run_can_return_value() -> None:
    from dataclasses import dataclass

    @dataclass
    class ValueTask:
        task_id: str = "value.task"
        title: str = "Value Task"

        def run(self) -> int:
            return 42

    task = ValueTask()
    result = task.run()
    assert result == 42
```

- [ ] **Step 3: Esegui il test per verificare che fallisce**

```bash
cd tools/workflow-tasks && uv run pytest tests/core/test_task.py -v 2>&1 | head -20
```
Expected: `ModuleNotFoundError: No module named 'workflow_tasks.core'`

- [ ] **Step 4: Implementa `task.py`**

`tools/workflow-tasks/src/workflow_tasks/core/task.py`:
```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class Task(Protocol):
    """Protocol that all composable workflow tasks must satisfy.

    Implementations are typically dataclasses with explicit constructor
    parameters. The task_id must be stable across runs (used for TUI
    phase tracking and workflow_step context).
    """

    task_id: str
    title: str

    def run(self) -> Any: ...
```

- [ ] **Step 5: Aggiorna `core/__init__.py`**

`tools/workflow-tasks/src/workflow_tasks/core/__init__.py`:
```python
from workflow_tasks.core.task import Task

__all__ = ["Task"]
```

- [ ] **Step 6: Esegui il test per verificare che passa**

```bash
cd tools/workflow-tasks && uv run pytest tests/core/test_task.py -v
```
Expected: `5 passed`

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/core/ tools/workflow-tasks/tests/core/
git commit -m "feat(workflow-tasks): add Task Protocol to core"
```

---

## Task 2: `Workflow` class in `workflow_tasks.core`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/core/workflow.py`
- Create: `tools/workflow-tasks/tests/core/test_workflow.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Modify: `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Scrivi i test (falliranno)**

`tools/workflow-tasks/tests/core/test_workflow.py`:
```python
from __future__ import annotations

import pytest
from dataclasses import dataclass
from workflow_tasks.core.workflow import Workflow


@dataclass
class _OkTask:
    task_id: str
    title: str
    calls: list[str]

    def run(self) -> None:
        self.calls.append(self.task_id)


@dataclass
class _FailTask:
    task_id: str
    title: str
    calls: list[str]

    def run(self) -> None:
        self.calls.append(self.task_id)
        raise RuntimeError(f"{self.task_id} failed")


def test_workflow_runs_tasks_in_order() -> None:
    calls: list[str] = []
    workflow = Workflow(tasks=[
        _OkTask(task_id="a", title="A", calls=calls),
        _OkTask(task_id="b", title="B", calls=calls),
        _OkTask(task_id="c", title="C", calls=calls),
    ])
    workflow.run()
    assert calls == ["a", "b", "c"]


def test_workflow_stops_on_first_failure() -> None:
    calls: list[str] = []
    workflow = Workflow(tasks=[
        _OkTask(task_id="a", title="A", calls=calls),
        _FailTask(task_id="b", title="B", calls=calls),
        _OkTask(task_id="c", title="C", calls=calls),
    ])
    with pytest.raises(RuntimeError, match="b failed"):
        workflow.run()
    assert calls == ["a", "b"]
    assert "c" not in calls


def test_workflow_cleanup_tasks_always_run() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[
            _OkTask(task_id="a", title="A", calls=calls),
            _FailTask(task_id="b", title="B", calls=calls),
        ],
        cleanup_tasks=[
            _OkTask(task_id="cleanup", title="Cleanup", calls=calls),
        ],
    )
    with pytest.raises(RuntimeError, match="b failed"):
        workflow.run()
    assert "cleanup" in calls


def test_workflow_cleanup_runs_after_success_too() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[_OkTask(task_id="a", title="A", calls=calls)],
        cleanup_tasks=[_OkTask(task_id="cleanup", title="Cleanup", calls=calls)],
    )
    workflow.run()
    assert calls == ["a", "cleanup"]


def test_workflow_task_ids_includes_all_tasks() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[
            _OkTask(task_id="a", title="A", calls=calls),
            _OkTask(task_id="b", title="B", calls=calls),
        ],
        cleanup_tasks=[
            _OkTask(task_id="cleanup", title="Cleanup", calls=calls),
        ],
    )
    assert workflow.task_ids == ["a", "b", "cleanup"]


def test_workflow_cleanup_error_raised_after_main_error() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[_FailTask(task_id="main", title="Main", calls=calls)],
        cleanup_tasks=[_FailTask(task_id="cleanup", title="Cleanup", calls=calls)],
    )
    with pytest.raises(RuntimeError) as exc_info:
        workflow.run()
    assert "main failed" in str(exc_info.value)
    assert "cleanup failed" in str(exc_info.value)


def test_workflow_with_no_tasks_runs_cleanly() -> None:
    workflow = Workflow(tasks=[])
    workflow.run()  # should not raise
```

- [ ] **Step 2: Esegui per verificare che fallisce**

```bash
cd tools/workflow-tasks && uv run pytest tests/core/test_workflow.py -v 2>&1 | head -10
```
Expected: `ModuleNotFoundError: No module named 'workflow_tasks.core.workflow'`

- [ ] **Step 3: Implementa `workflow.py`**

`tools/workflow-tasks/src/workflow_tasks/core/workflow.py`:
```python
from __future__ import annotations

from dataclasses import dataclass, field

from workflow_tasks.core.task import Task
from workflow_tasks.workflow.reporting import workflow_step


@dataclass
class Workflow:
    """Sequential task executor with optional always-run cleanup tasks.

    tasks run in order; execution stops at the first failure.
    cleanup_tasks always run, even after a failure in tasks.
    """

    tasks: list[Task]
    cleanup_tasks: list[Task] = field(default_factory=list)

    @property
    def task_ids(self) -> list[str]:
        """Stable list of task IDs in execution order. Used by TUI for dry-run planning."""
        return [t.task_id for t in self.tasks + self.cleanup_tasks]

    def run(self) -> None:
        main_error: BaseException | None = None

        for task in self.tasks:
            try:
                with workflow_step(task_id=task.task_id, title=task.title):
                    task.run()
            except BaseException as exc:
                main_error = exc
                break

        cleanup_errors: list[str] = []
        for task in self.cleanup_tasks:
            try:
                with workflow_step(task_id=task.task_id, title=task.title):
                    task.run()
            except BaseException as exc:
                cleanup_errors.append(str(exc))

        if main_error is not None:
            if cleanup_errors:
                combined = f"{main_error}\n\nCleanup errors:\n" + "\n".join(cleanup_errors)
                raise RuntimeError(combined) from main_error
            raise main_error

        if cleanup_errors:
            raise RuntimeError("Cleanup failed:\n" + "\n".join(cleanup_errors))
```

- [ ] **Step 4: Aggiorna `core/__init__.py`**

`tools/workflow-tasks/src/workflow_tasks/core/__init__.py`:
```python
from workflow_tasks.core.task import Task
from workflow_tasks.core.workflow import Workflow

__all__ = ["Task", "Workflow"]
```

- [ ] **Step 5: Aggiorna `workflow_tasks/__init__.py`** — aggiungi `Task` e `Workflow` al public API

Apri `tools/workflow-tasks/src/workflow_tasks/__init__.py` e aggiungi dopo le import esistenti:

```python
from workflow_tasks.core.task import Task
from workflow_tasks.core.workflow import Workflow
```

E aggiungi `"Task"` e `"Workflow"` alla lista `__all__`.

- [ ] **Step 6: Aggiorna `test_public_api.py`** — aggiungi asserzione per i nuovi export

Aggiungi alla fine di `tools/workflow-tasks/tests/test_public_api.py`:

```python
def test_public_api_exports_task_and_workflow() -> None:
    assert hasattr(workflow_tasks, "Task")
    assert hasattr(workflow_tasks, "Workflow")
```

- [ ] **Step 7: Esegui tutti i test di workflow-tasks**

```bash
cd tools/workflow-tasks && uv run pytest -v
```
Expected: tutti i test passano, coverage ≥ 90%

- [ ] **Step 8: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/core/workflow.py \
        tools/workflow-tasks/src/workflow_tasks/__init__.py \
        tools/workflow-tasks/tests/core/test_workflow.py \
        tools/workflow-tasks/tests/test_public_api.py
git commit -m "feat(workflow-tasks): add Workflow class to core"
```

---

## Task 3: VM task classes in `controlplane_tool`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/vm.py`
- Create: `tools/controlplane/tests/tasks/__init__.py`
- Create: `tools/controlplane/tests/tasks/test_vm_tasks.py`

- [ ] **Step 1: Crea le directory**

```bash
mkdir -p tools/controlplane/src/controlplane_tool/scenario/tasks
mkdir -p tools/controlplane/tests/tasks
touch tools/controlplane/src/controlplane_tool/scenario/tasks/__init__.py
touch tools/controlplane/tests/tasks/__init__.py
```

- [ ] **Step 2: Scrivi i test**

`tools/controlplane/tests/tasks/test_vm_tasks.py`:
```python
from __future__ import annotations

import pytest
from unittest.mock import MagicMock, patch
from controlplane_tool.scenario.tasks.vm import (
    EnsureVmRunning,
    ProvisionBase,
    SyncProject,
    TeardownVm,
)
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.vm_models import VmRequest


def _ok() -> ShellExecutionResult:
    return ShellExecutionResult(command=["echo", "ok"], return_code=0, stdout="ok")


def _fail() -> ShellExecutionResult:
    return ShellExecutionResult(command=["echo", "fail"], return_code=1, stdout="", stderr="fail")


def _vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="test-vm")


def test_ensure_vm_running_task_id_and_title() -> None:
    vm = MagicMock()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    assert task.task_id == "vm.ensure_running"
    assert task.title == "Ensure VM is running"


def test_ensure_vm_running_calls_vm_ensure_running() -> None:
    vm = MagicMock()
    vm.ensure_running.return_value = _ok()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.ensure_running.assert_called_once_with(_vm_request())


def test_ensure_vm_running_raises_on_failure() -> None:
    vm = MagicMock()
    vm.ensure_running.return_value = _fail()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    with pytest.raises(RuntimeError, match="fail"):
        task.run()


def test_provision_base_calls_install_dependencies() -> None:
    vm = MagicMock()
    vm.install_dependencies.return_value = _ok()
    task = ProvisionBase(
        task_id="vm.provision_base",
        title="Provision base dependencies",
        vm=vm,
        request=_vm_request(),
        install_helm=True,
    )
    task.run()
    vm.install_dependencies.assert_called_once_with(_vm_request(), install_helm=True)


def test_sync_project_calls_sync_project() -> None:
    vm = MagicMock()
    vm.sync_project.return_value = _ok()
    task = SyncProject(
        task_id="repo.sync_to_vm",
        title="Sync project to VM",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.sync_project.assert_called_once_with(_vm_request())


def test_teardown_vm_calls_teardown() -> None:
    vm = MagicMock()
    vm.teardown.return_value = _ok()
    task = TeardownVm(
        task_id="vm.down",
        title="Teardown VM",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.teardown.assert_called_once_with(_vm_request())


def test_teardown_vm_is_satisfied_by_task_protocol() -> None:
    from workflow_tasks.core.task import Task
    vm = MagicMock()
    vm.teardown.return_value = _ok()
    task = TeardownVm(task_id="vm.down", title="Teardown VM", vm=vm, request=_vm_request())
    assert isinstance(task, Task)
```

- [ ] **Step 3: Esegui per verificare che fallisce**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_vm_tasks.py -v 2>&1 | head -10
```
Expected: `ModuleNotFoundError: No module named 'controlplane_tool.scenario.tasks'`

- [ ] **Step 4: Implementa `vm.py`**

`tools/controlplane/src/controlplane_tool/scenario/tasks/vm.py`:
```python
from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


def _check(result: ShellExecutionResult) -> None:
    if result.return_code != 0:
        raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class EnsureVmRunning:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.ensure_running(self.request))


@dataclass
class ProvisionBase:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    install_helm: bool = False

    def run(self) -> None:
        _check(self.vm.install_dependencies(self.request, install_helm=self.install_helm))


@dataclass
class SyncProject:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.sync_project(self.request))


@dataclass
class TeardownVm:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.teardown(self.request))
```

- [ ] **Step 5: Esegui i test**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_vm_tasks.py -v
```
Expected: `7 passed`

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/tasks/ \
        tools/controlplane/tests/tasks/
git commit -m "feat(controlplane): add VM task classes to scenario/tasks"
```

---

## Task 4: K8s task classes

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/k8s.py`

- [ ] **Step 1: Implementa `k8s.py`**

`tools/controlplane/src/controlplane_tool/scenario/tasks/k8s.py`:
```python
from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


def _check(result: ShellExecutionResult) -> None:
    if result.return_code != 0:
        raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class InstallK3s:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.install_k3s(self.request))


@dataclass
class EnsureRegistry:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest
    registry: str = "localhost:5000"
    container_name: str = "nanofaas-e2e-registry"

    def run(self) -> None:
        _check(self.vm.ensure_registry_container(
            self.request,
            registry=self.registry,
            container_name=self.container_name,
        ))


@dataclass
class ConfigureK3sRegistry:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest
    registry: str = "localhost:5000"

    def run(self) -> None:
        _check(self.vm.configure_k3s_registry(self.request, registry=self.registry))


@dataclass
class HelmInstall:
    """Run a remote helm upgrade --install command via exec_argv on the VM."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        result = self.vm.exec_argv(self.request, self.argv)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class HelmUninstall:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        result = self.vm.exec_argv(self.request, self.argv)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class NamespaceInstall:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        result = self.vm.exec_argv(self.request, self.argv)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")
```

- [ ] **Step 2: Verifica che le classi soddisfino il Task Protocol**

```bash
cd tools/controlplane && python -c "
from workflow_tasks.core.task import Task
from controlplane_tool.scenario.tasks.k8s import InstallK3s, EnsureRegistry, ConfigureK3sRegistry
from unittest.mock import MagicMock
from controlplane_tool.infra.vm.vm_models import VmRequest
vm = MagicMock(); req = VmRequest(lifecycle='multipass')
t = InstallK3s(task_id='k3s.install', title='Install k3s', vm=vm, request=req)
assert isinstance(t, Task), 'InstallK3s must satisfy Task Protocol'
print('OK: all k8s tasks satisfy Task Protocol')
"
```
Expected: `OK: all k8s tasks satisfy Task Protocol`

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/tasks/k8s.py
git commit -m "feat(controlplane): add k8s task classes (InstallK3s, HelmInstall, ...)"
```

---

## Task 5: `RunK6Matrix` — fix bug singolo target + `run_k6_for_function`

Questo task corregge il bug per cui k6 testava solo `function_keys[0]`.

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/loadtest.py`
- Create: `tools/controlplane/tests/tasks/test_loadtest_tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`

- [ ] **Step 1: Aggiungi `target_override` a `run_k6` e `run_k6_for_function` a `TwoVmLoadtestRunner`**

In `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`:

**1a.** Modifica la firma di `run_k6` (riga ~119) per accettare un override opzionale:

```python
def run_k6(self, request: E2eRequest, *, target_override: str | None = None) -> TwoVmK6Result:
```

**1b.** All'interno di `run_k6`, sostituisci la riga che chiama `two_vm_target_function`:

```python
# prima:
target_function = two_vm_target_function(request)
# dopo:
target_function = target_override if target_override is not None else two_vm_target_function(request)
```

**1c.** Aggiungi `run_k6_for_function` dopo `run_k6`:

```python
def run_k6_for_function(self, request: E2eRequest, fn_key: str) -> TwoVmK6Result:
    """Run k6 against a single named function. Called by RunK6Matrix for each target."""
    return self.run_k6(request, target_override=fn_key)
```

- [ ] **Step 2: Scrivi i test per `RunK6Matrix`**

`tools/controlplane/tests/tasks/test_loadtest_tasks.py`:
```python
from __future__ import annotations

import pytest
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock, patch
from controlplane_tool.scenario.tasks.loadtest import RunK6Matrix, InstallK6


@dataclass
class _MockK6Result:
    run_dir: Path
    k6_summary_path: Path
    target_function: str
    started_at: object
    ended_at: object


def _make_runner(fn_keys: list[str]) -> MagicMock:
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [
        _MockK6Result(
            run_dir=Path("/tmp"),
            k6_summary_path=Path(f"/tmp/{fn}.json"),
            target_function=fn,
            started_at=None,
            ended_at=None,
        )
        for fn in fn_keys
    ]
    return runner


def test_run_k6_matrix_iterates_all_targets() -> None:
    """Regression test for the [0] bug — must run against every target."""
    fn_keys = ["word-stats-java", "json-transform-java", "word-stats-python"]
    runner = _make_runner(fn_keys)
    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys

    task = RunK6Matrix(
        task_id="loadtest.run_k6_matrix",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    result = task.run()

    assert runner.run_k6_for_function.call_count == 3
    called_fns = [call.args[1] for call in runner.run_k6_for_function.call_args_list]
    assert called_fns == fn_keys
    assert len(result.results) == 3


def test_run_k6_matrix_single_target_still_runs_once() -> None:
    fn_keys = ["word-stats-java"]
    runner = _make_runner(fn_keys)
    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys

    task = RunK6Matrix(
        task_id="loadtest.run_k6_matrix",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    result = task.run()

    assert runner.run_k6_for_function.call_count == 1
    assert len(result.results) == 1


def test_run_k6_matrix_task_id_and_title() -> None:
    runner = MagicMock()
    request = MagicMock()
    request.resolved_scenario.function_keys = []

    task = RunK6Matrix(
        task_id="loadtest.run_k6_matrix",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    assert task.task_id == "loadtest.run_k6_matrix"
    assert task.title == "Run k6 against all targets"


def test_install_k6_task_calls_exec_argv() -> None:
    vm = MagicMock()
    request = MagicMock()
    result = MagicMock()
    result.return_code = 0
    vm.exec_argv.return_value = result

    task = InstallK6(
        task_id="loadgen.install_k6",
        title="Install k6 on loadgen VM",
        vm=vm,
        request=request,
        remote_dir="/home/ubuntu/nanofaas",
    )
    task.run()
    vm.exec_argv.assert_called_once()
```

- [ ] **Step 3: Esegui per verificare che fallisce**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_loadtest_tasks.py -v 2>&1 | head -10
```
Expected: `ModuleNotFoundError: No module named 'controlplane_tool.scenario.tasks.loadtest'`

- [ ] **Step 4: Implementa `loadtest.py`**

`tools/controlplane/src/controlplane_tool/scenario/tasks/loadtest.py`:
```python
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result, TwoVmLoadtestRunner
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


@dataclass
class K6MatrixResult:
    results: list[TwoVmK6Result]

    @property
    def window(self) -> tuple[object, object] | None:
        if not self.results:
            return None
        return self.results[0].started_at, self.results[-1].ended_at


@dataclass
class InstallK6:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str

    def run(self) -> None:
        result = self.vm.exec_argv(
            self.request,
            ("bash", "-lc", "which k6 || (curl -fsSL https://pkg.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt-get update -qq && sudo apt-get install -y k6)"),
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class RunK6Matrix:
    """Run k6 against ALL selected function targets. Fixes the [0]-truncation bug."""

    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest

    def run(self) -> K6MatrixResult:
        resolved = self.request.resolved_scenario
        targets: list[str] = (
            list(resolved.function_keys)
            if resolved is not None and resolved.function_keys
            else (self.request.functions or ["word-stats-java"])
        )
        results: list[TwoVmK6Result] = []
        for fn_key in targets:
            results.append(self.runner.run_k6_for_function(self.request, fn_key))
        return K6MatrixResult(results=results)


@dataclass
class CapturePrometheus:
    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest
    k6_matrix_result: K6MatrixResult

    def run(self) -> Path:
        if not self.k6_matrix_result.results:
            raise RuntimeError("CapturePrometheus requires at least one k6 result")
        first = self.k6_matrix_result.results[0]
        return self.runner.capture_prometheus_snapshots(self.request, first)


@dataclass
class WriteLoadtestReport:
    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest
    k6_matrix_result: K6MatrixResult
    prometheus_snapshot_path: Path

    def run(self) -> None:
        if not self.k6_matrix_result.results:
            raise RuntimeError("WriteLoadtestReport requires at least one k6 result")
        first = self.k6_matrix_result.results[0]
        self.runner.write_report(self.request, first, self.prometheus_snapshot_path)
```

- [ ] **Step 5: Esegui i test**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_loadtest_tasks.py -v
```
Expected: `4 passed`

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/tasks/loadtest.py \
        tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py \
        tools/controlplane/tests/tasks/test_loadtest_tasks.py
git commit -m "fix: add RunK6Matrix task iterating all targets — fixes [0] bug"
```

---

## Task 6: `RegisterFunctions` — REST-based, no CLI

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/functions.py`
- Create: `tools/controlplane/tests/tasks/test_functions_tasks.py`

- [ ] **Step 1: Scrivi i test**

`tools/controlplane/tests/tasks/test_functions_tasks.py`:
```python
from __future__ import annotations

import json
import pytest
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
from controlplane_tool.scenario.tasks.functions import FunctionSpec, RegisterFunctions


class _CapturingHandler(BaseHTTPRequestHandler):
    captured: list[dict] = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        _CapturingHandler.captured.append(json.loads(body))
        self.send_response(201)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args: object) -> None:
        pass


def _start_server() -> tuple[HTTPServer, str]:
    server = HTTPServer(("127.0.0.1", 0), _CapturingHandler)
    port = server.server_address[1]
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://127.0.0.1:{port}"


def test_register_functions_posts_to_rest_api() -> None:
    _CapturingHandler.captured.clear()
    server, url = _start_server()
    try:
        specs = [
            FunctionSpec(name="word-stats-java", image="registry/word-stats-java:e2e"),
            FunctionSpec(name="json-transform-java", image="registry/json-transform-java:e2e"),
        ]
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=url,
            specs=specs,
        )
        task.run()
        assert len(_CapturingHandler.captured) == 2
        names = [b["name"] for b in _CapturingHandler.captured]
        assert "word-stats-java" in names
        assert "json-transform-java" in names
    finally:
        server.shutdown()


def test_register_functions_body_matches_expected_schema() -> None:
    _CapturingHandler.captured.clear()
    server, url = _start_server()
    try:
        specs = [FunctionSpec(name="my-fn", image="registry/my-fn:e2e")]
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=url,
            specs=specs,
        )
        task.run()
        body = _CapturingHandler.captured[0]
        assert body["name"] == "my-fn"
        assert body["image"] == "registry/my-fn:e2e"
        assert "timeoutMs" in body
        assert "executionMode" in body
    finally:
        server.shutdown()


def test_register_functions_raises_on_http_error() -> None:
    class _ErrorHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"internal error")

        def log_message(self, *args: object) -> None:
            pass

    server = HTTPServer(("127.0.0.1", 0), _ErrorHandler)
    port = server.server_address[1]
    Thread(target=server.serve_forever, daemon=True).start()
    try:
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=f"http://127.0.0.1:{port}",
            specs=[FunctionSpec(name="fn", image="img")],
        )
        with pytest.raises(Exception):
            task.run()
    finally:
        server.shutdown()


def test_register_functions_no_cli_dependency() -> None:
    import inspect
    from controlplane_tool.scenario.tasks import functions
    source = inspect.getsource(functions)
    assert "nanofaas-cli" not in source
    assert "fn apply" not in source
    assert "subprocess" not in source
```

- [ ] **Step 2: Esegui per verificare che fallisce**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_functions_tasks.py -v 2>&1 | head -10
```
Expected: `ModuleNotFoundError`

- [ ] **Step 3: Implementa `functions.py`**

`tools/controlplane/src/controlplane_tool/scenario/tasks/functions.py`:
```python
from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass
class FunctionSpec:
    name: str
    image: str
    execution_mode: str = "DEPLOYMENT"
    timeout_ms: int = 5000
    concurrency: int = 2
    queue_size: int = 20
    max_retries: int = 3

    def to_body(self) -> dict[str, object]:
        return {
            "name": self.name,
            "image": self.image,
            "executionMode": self.execution_mode,
            "timeoutMs": self.timeout_ms,
            "concurrency": self.concurrency,
            "queueSize": self.queue_size,
            "maxRetries": self.max_retries,
        }


@dataclass
class RegisterFunctions:
    """Register functions via POST /v1/functions REST API. No CLI dependency."""

    task_id: str
    title: str
    control_plane_url: str
    specs: list[FunctionSpec] = field(default_factory=list)

    def run(self) -> None:
        url = f"{self.control_plane_url.rstrip('/')}/v1/functions"
        for spec in self.specs:
            body = json.dumps(spec.to_body()).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=body,
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=30):
                    pass
            except urllib.error.HTTPError as exc:
                raise RuntimeError(
                    f"Failed to register function '{spec.name}': HTTP {exc.code}"
                ) from exc
```

- [ ] **Step 4: Esegui i test**

```bash
cd tools/controlplane && uv run pytest tests/tasks/test_functions_tasks.py -v
```
Expected: `4 passed`

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/tasks/functions.py \
        tools/controlplane/tests/tasks/test_functions_tasks.py
git commit -m "fix: add RegisterFunctions task using REST API — removes CLI coupling"
```

---

## Task 7: CLI task classes (per scenario `cli-stack`)

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/tasks/cli.py`

- [ ] **Step 1: Implementa `cli.py`**

`tools/controlplane/src/controlplane_tool/scenario/tasks/cli.py`:
```python
from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


@dataclass
class BuildCliDist:
    """Build nanofaas-cli installDist inside the VM."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str

    def run(self) -> None:
        result = self.vm.exec_argv(
            self.request,
            ("./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"),
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class CliApplyFunction:
    """Apply a single function spec via nanofaas-cli fn apply."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str
    cli_binary: str
    function_name: str
    image: str
    namespace: str
    kubeconfig: str

    def run(self) -> None:
        import json, shlex
        spec = json.dumps({
            "name": self.function_name,
            "image": self.image,
            "timeoutMs": 5000,
            "concurrency": 2,
            "queueSize": 20,
            "maxRetries": 3,
            "executionMode": "DEPLOYMENT",
        }, separators=(",", ":"))
        manifest = f"/tmp/{self.function_name}.json"
        command = (
            f"printf '%s' {shlex.quote(spec)} > {shlex.quote(manifest)} && "
            f"{shlex.quote(self.cli_binary)} fn apply -f {shlex.quote(manifest)}"
        )
        result = self.vm.exec_argv(
            self.request,
            ("bash", "-lc", command),
            env={
                "KUBECONFIG": self.kubeconfig,
                "NANOFAAS_NAMESPACE": self.namespace,
                "NANOFAAS_FUNCTION_IMAGE": self.image,
            },
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")
```

- [ ] **Step 2: Verifica che le classi soddisfino il Task Protocol**

```bash
cd tools/controlplane && python -c "
from workflow_tasks.core.task import Task
from controlplane_tool.scenario.tasks.cli import BuildCliDist, CliApplyFunction
from unittest.mock import MagicMock
from controlplane_tool.infra.vm.vm_models import VmRequest
vm = MagicMock(); req = VmRequest(lifecycle='multipass')
t = BuildCliDist(task_id='cli.build', title='Build CLI', vm=vm, request=req, remote_dir='/tmp')
assert isinstance(t, Task)
print('OK: CLI tasks satisfy Task Protocol')
"
```
Expected: `OK: CLI tasks satisfy Task Protocol`

- [ ] **Step 3: Esegui la suite completa di controlplane**

```bash
cd tools/controlplane && uv run pytest tests/tasks/ -v
```
Expected: tutti i test nella directory `tasks/` passano.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/tasks/cli.py \
        tools/controlplane/src/controlplane_tool/scenario/tasks/k8s.py
git commit -m "feat(controlplane): add CLI and k8s task classes to scenario/tasks"
```

---

## Verifica finale

- [ ] **Esegui la suite completa di workflow-tasks**

```bash
cd tools/workflow-tasks && uv run pytest -v
```
Expected: tutti i test passano, coverage ≥ 90%.

- [ ] **Esegui la suite completa di controlplane**

```bash
cd tools/controlplane && uv run pytest -v 2>&1 | tail -20
```
Expected: nessuna regressione — i test del recipe system esistente continuano a passare (coesistenza temporanea).

---

## Note sul Piano 2

Il Piano 2 (scenario builders + rimozione recipe system) prenderà il catalogo di task definito qui e costruirà:
- `ScenarioPlan` Protocol
- Builder functions per ogni scenario (`two_vm_loadtest`, `azure_vm_loadtest`, `k3s_junit_curl`, `helm_stack`, `cli_stack`)
- Semplificazione di `e2e_runner.py`
- Aggiornamento di `flow_catalog.py`
- Rimozione di `scenario/components/`, `scenario_planner.py` e test obsoleti

Il Piano 3 (futuro) sposterà i task concreti in sub-package di `workflow_tasks` con dipendenze dirette e Protocol injection, completando la visione architetturale.
