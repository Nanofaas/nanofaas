# C1 — `CommandTask` in libreria (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere alla libreria un `Task` generico `CommandTask` (incapsula `CommandTaskSpec` + un executor host/vm) e un helper `command_task_from_operation`, così che i passi-shell degli scenari possano diventare Task del `Workflow`.

**Architecture:** `CommandTask` è un dataclass conforme al Protocol `Task` (`task_id`, `title`, `run()`). In `run()` delega a `executor.run(spec)` e solleva `RuntimeError` se il `TaskResult` non è `passed`. `command_task_from_operation` converte un `RemoteCommandOperationLike` in `CommandTaskSpec` (via `operation_to_task_spec`) e lo avvolge in un `CommandTask`. Nessun consumatore cambia in C1 (solo aggiunta).

**Tech Stack:** Python 3.11+, dataclasses, pytest, import-linter, uv.

**Comandi:** libreria singolo file con `--no-cov`; full senza. import-linter come nei piani precedenti.

**Baseline pre-esistente:** libreria 1 fail (proxmox `test_ensure_running_allows_slow_proxmox_guest_agent`); controlplane 3 fail. Nessun task li aumenta.

**Contratti rilevanti (verificati):**
- `Task` (Protocol): `{task_id: str, title: str, run() -> Any}`.
- `CommandTaskSpec` (frozen): `task_id, summary, argv, target("host"|"vm"), env, cwd, remote_dir, expected_exit_codes=frozenset({0}), timeout_seconds`.
- `TaskResult` (frozen): `task_id, status("pending"|"running"|"passed"|"failed"|"skipped"), return_code, expected_exit_codes, stdout, stderr`, prop `.ok`.
- Executors (`tasks/executors.py`): `HostCommandTaskExecutor(runner)` / `VmCommandTaskExecutor(runner)` con `.run(spec, *, dry_run=False) -> TaskResult`. `HostCommandTaskExecutor.run` rifiuta spec `target != "host"`; quello VM rifiuta `target != "vm"`.
- `operation_to_task_spec(operation: RemoteCommandOperationLike, *, remote_dir=None) -> CommandTaskSpec` (in `tasks/adapters.py`). `RemoteCommandOperationLike` ha `operation_id, summary, argv, env, execution_target`.

---

### Task 1: `CommandTask` + `command_task_from_operation`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/tasks/command_task.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py` (export `CommandTask`, `command_task_from_operation`)
- Create: `tools/workflow-tasks/tests/tasks/test_command_task.py`

- [ ] **Step 1: Scrivi il modulo**

Crea `tools/workflow-tasks/src/workflow_tasks/tasks/command_task.py`:

```python
from __future__ import annotations

from dataclasses import dataclass

from workflow_tasks.tasks.adapters import RemoteCommandOperationLike, operation_to_task_spec
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult

CommandExecutor = HostCommandTaskExecutor | VmCommandTaskExecutor


@dataclass
class CommandTask:
    """A composable Task that runs a single CommandTaskSpec via an executor.

    Satisfies the workflow_tasks.Task protocol. Raises RuntimeError on failure so
    that Workflow.run() stops the pipeline (and triggers cleanup_tasks).
    """

    task_id: str
    title: str
    spec: CommandTaskSpec
    executor: CommandExecutor

    def run(self) -> TaskResult:
        result = self.executor.run(self.spec)
        if result.status != "passed":
            detail = result.stderr.strip() or result.stdout.strip() or "no output"
            raise RuntimeError(
                f"{self.task_id} failed (exit {result.return_code}): {detail}"
            )
        return result


def command_task_from_operation(
    operation: RemoteCommandOperationLike,
    executor: CommandExecutor,
    *,
    title: str | None = None,
    remote_dir: str | None = None,
) -> CommandTask:
    """Build a CommandTask from a RemoteCommandOperation-like object.

    Converts the operation to a CommandTaskSpec (via operation_to_task_spec) and
    wraps it. title defaults to the operation summary; task_id is the operation_id.
    """
    spec = operation_to_task_spec(operation, remote_dir=remote_dir)
    return CommandTask(
        task_id=spec.task_id,
        title=title if title is not None else spec.summary,
        spec=spec,
        executor=executor,
    )
```

- [ ] **Step 2: Esporta dalla public API**

In `tools/workflow-tasks/src/workflow_tasks/__init__.py`:
1. Aggiungi l'import (vicino agli altri `from workflow_tasks.tasks...`):
   `from workflow_tasks.tasks.command_task import CommandTask, command_task_from_operation`
2. Aggiungi `"CommandTask", "command_task_from_operation"` a `__all__` (nella sezione delle task/command, accanto a `CommandTaskSpec`/`HostCommandTaskExecutor`).

- [ ] **Step 3: Test di libreria**

Crea `tools/workflow-tasks/tests/tasks/test_command_task.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType

import pytest

from workflow_tasks import CommandTask, Task, command_task_from_operation
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


class _ScriptedHostRunner:
    """HostCommandRunner returning a fixed return_code/stdout/stderr."""

    def __init__(self, return_code: int = 0, stdout: str = "", stderr: str = "") -> None:
        self._rc, self._out, self._err = return_code, stdout, stderr
        self.calls: list[tuple] = []

    def run(self, argv, *, cwd, env, dry_run):
        self.calls.append((tuple(argv), dict(env), cwd, dry_run))
        return TaskResult(task_id="x", status="passed", return_code=self._rc, stdout=self._out, stderr=self._err)


def _host_spec(**kw) -> CommandTaskSpec:
    base = dict(task_id="t.run", summary="run it", argv=("echo", "hi"), target="host")
    base.update(kw)
    return CommandTaskSpec(**base)


def test_command_task_satisfies_task_protocol() -> None:
    task = CommandTask(task_id="t", title="T", spec=_host_spec(), executor=HostCommandTaskExecutor(_ScriptedHostRunner()))
    assert isinstance(task, Task)
    assert task.task_id == "t" and task.title == "T"


def test_command_task_run_passes_on_expected_exit() -> None:
    runner = _ScriptedHostRunner(return_code=0, stdout="ok")
    task = CommandTask(task_id="t", title="T", spec=_host_spec(), executor=HostCommandTaskExecutor(runner))
    result = task.run()
    assert result.status == "passed"
    assert runner.calls[0][0] == ("echo", "hi")


def test_command_task_run_raises_on_failure() -> None:
    runner = _ScriptedHostRunner(return_code=2, stderr="boom")
    task = CommandTask(task_id="t.fail", title="T", spec=_host_spec(), executor=HostCommandTaskExecutor(runner))
    with pytest.raises(RuntimeError, match="t.fail failed .exit 2.: boom"):
        task.run()


@dataclass
class _Op:
    operation_id: str
    summary: str
    argv: tuple[str, ...]
    env: dict
    execution_target: str


def test_command_task_from_operation_builds_vm_task() -> None:
    op = _Op(operation_id="helm.deploy", summary="Deploy helm", argv=("helm", "upgrade"), env={"K": "V"}, execution_target="vm")
    task = command_task_from_operation(op, VmCommandTaskExecutor(_ScriptedVmRunner()), remote_dir="/repo")
    assert task.task_id == "helm.deploy"
    assert task.title == "Deploy helm"           # defaults to summary
    assert task.spec.target == "vm"
    assert task.spec.remote_dir == "/repo"
    assert task.spec.argv == ("helm", "upgrade")


def test_command_task_from_operation_title_override() -> None:
    op = _Op(operation_id="x", summary="s", argv=("a",), env={}, execution_target="host")
    task = command_task_from_operation(op, HostCommandTaskExecutor(_ScriptedHostRunner()), title="Custom")
    assert task.title == "Custom"


class _ScriptedVmRunner:
    def run_vm_command(self, argv, *, env, remote_dir, dry_run):
        return TaskResult(task_id="x", status="passed", return_code=0)
```

NOTA: verifica le firme reali dei runner Protocol in `tasks/executors.py`
(`HostCommandRunner.run(argv, *, cwd, env, dry_run)` e
`VmCommandRunner.run_vm_command(argv, *, env, remote_dir, dry_run)`) e che restituiscano un
oggetto con `.return_code/.stdout/.stderr`. Gli executor calcolano `status` da
`return_code in expected_exit_codes`; i runner fittizi sopra restituiscono un `TaskResult` che
espone quelle proprietà — se l'executor si aspetta un tipo diverso (solo `.return_code/.stdout/.stderr`),
sostituisci con un piccolo oggetto/namedtuple con quei tre attributi. Adatta i fake al contratto
reale, mantenendo i 5 test significativi.

- [ ] **Step 4: Esegui i test di libreria**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/tasks/test_command_task.py -v --no-cov`
Expected: PASS (5 test).

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/tasks/command_task.py tools/workflow-tasks/src/workflow_tasks/__init__.py tools/workflow-tasks/tests/tasks/test_command_task.py
git commit -m "feat(workflow-tasks): add CommandTask + command_task_from_operation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Public-API test + verifica finale

**Files:**
- Modify (se necessario): `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Aggiorna il test della public API (se enumera gli export)**

Leggi `tools/workflow-tasks/tests/test_public_api.py`. Se asserisce l'insieme esatto di nomi
esportati / `__all__`, aggiungi `CommandTask` e `command_task_from_operation`. Se invece importa
selettivamente, aggiungi un'asserzione minima:

```python
def test_command_task_is_public() -> None:
    import workflow_tasks
    assert hasattr(workflow_tasks, "CommandTask")
    assert hasattr(workflow_tasks, "command_task_from_operation")
```
(Se `test_public_api.py` non esiste o non verifica gli export, salta questo step e basta la
verifica finale.)

- [ ] **Step 2: Verifica finale completa**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4`
Expected: passa (coverage ≥70 gate), solo il fail proxmox pre-esistente.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4`
Expected: solo i 3 baseline (C1 non tocca controlplane).
Run i due `lint-imports` → 0 broken.

- [ ] **Step 3: Commit (se Step 1 ha modificato file)**

```bash
git add tools/workflow-tasks/tests/test_public_api.py
git commit -m "test(workflow-tasks): assert CommandTask is part of the public API

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- C1 è puramente additivo: nessun consumatore cambia, nessun comportamento cambia.
- `CommandTask` sarà usato da C2+ per riscrivere gli scenari recipe come Workflow.
- Non serve un boundary test dedicato: `workflow_tasks.tasks` è già coperto dal contratto
  `tasks must not import workflow/integrations` e dal `no_external_deps` (la libreria non importa
  controlplane). Verifica solo che `lint-imports` resti verde.
