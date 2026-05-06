from __future__ import annotations

from pathlib import Path

import pytest

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
    shell = ScriptedShell(return_code_map={("false",): 1}, stderr_map={("false",): "failed"})
    executor = HostCommandTaskExecutor(shell=shell)
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))

    result = executor.run(task)

    assert result.status == "failed"
    assert result.return_code != 0
    assert "failed" in result.stderr


def test_host_executor_rejects_vm_tasks() -> None:
    executor = HostCommandTaskExecutor(shell=RecordingShell())
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), target="vm")

    with pytest.raises(ValueError, match="cannot run 'vm' task"):
        executor.run(task)
