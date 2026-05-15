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


class _RecordingCommandRunner:
    def __init__(
        self,
        *,
        return_code: int = 0,
        stdout: str = "",
        stderr: str = "",
    ) -> None:
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


@dataclass(frozen=True)
class _VmResult:
    return_code: int
    stdout: str
    stderr: str


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
    ) -> _VmResult:
        self.commands.append((argv, env, remote_dir, dry_run))
        return _VmResult(return_code=0, stdout="ok", stderr="")


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
