from __future__ import annotations

from dataclasses import dataclass

import pytest

from workflow_tasks import CommandTask, Task, command_task_from_operation
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec


@dataclass
class _RunResult:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _HostRunner:
    def __init__(self, rc=0, stdout="", stderr=""):
        self._r = _RunResult(rc, stdout, stderr)
        self.calls: list[tuple] = []

    def run(self, argv, *, cwd, env, dry_run):
        self.calls.append((tuple(argv), dict(env)))
        return self._r


class _VmRunner:
    def __init__(self, rc=0, stdout="", stderr=""):
        self._r = _RunResult(rc, stdout, stderr)

    def run_vm_command(self, argv, *, env, remote_dir, dry_run):
        return self._r


def _host_spec(**kw) -> CommandTaskSpec:
    base = dict(task_id="t.run", summary="run it", argv=("echo", "hi"), target="host")
    base.update(kw)
    return CommandTaskSpec(**base)


@dataclass
class _Op:
    operation_id: str
    summary: str
    argv: tuple[str, ...]
    env: dict
    execution_target: str


def test_command_task_satisfies_task_protocol() -> None:
    task = CommandTask("t", "T", _host_spec(), HostCommandTaskExecutor(_HostRunner()))
    assert isinstance(task, Task)


def test_command_task_run_passes_on_expected_exit() -> None:
    runner = _HostRunner(rc=0, stdout="ok")
    task = CommandTask("t", "T", _host_spec(), HostCommandTaskExecutor(runner))
    result = task.run()
    assert result.status == "passed"
    assert runner.calls[0][0] == ("echo", "hi")


def test_command_task_run_raises_on_failure() -> None:
    task = CommandTask("t.fail", "T", _host_spec(), HostCommandTaskExecutor(_HostRunner(rc=2, stderr="boom")))
    with pytest.raises(RuntimeError, match=r"t\.fail failed \(exit 2\): boom"):
        task.run()


def test_command_task_from_operation_builds_vm_task() -> None:
    op = _Op("helm.deploy", "Deploy helm", ("helm", "upgrade"), {"K": "V"}, "vm")
    task = command_task_from_operation(op, VmCommandTaskExecutor(_VmRunner()), remote_dir="/repo")
    assert task.task_id == "helm.deploy"
    assert task.title == "Deploy helm"
    assert task.spec.target == "vm"
    assert task.spec.remote_dir == "/repo"
    assert task.spec.argv == ("helm", "upgrade")


def test_command_task_from_operation_title_override() -> None:
    op = _Op("x", "s", ("a",), {}, "host")
    task = command_task_from_operation(op, HostCommandTaskExecutor(_HostRunner()), title="Custom")
    assert task.title == "Custom"
