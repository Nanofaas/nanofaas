# tools/workflow-tasks/tests/vm/test_vm_runners.py
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher


@dataclass
class _FakeResult:
    return_code: int = 0
    stderr: str = ""
    stdout: str = ""


def test_orchestrator_vm_runner_calls_exec_argv() -> None:
    orch = MagicMock()
    orch.exec_argv.return_value = _FakeResult(return_code=0)
    request = MagicMock()

    runner = OrchestratorVmRunner(orch, request)
    runner.run_vm_command(("echo", "hi"), env={"A": "B"}, remote_dir="/home", dry_run=False)

    orch.exec_argv.assert_called_once_with(
        request, ("echo", "hi"), env={"A": "B"}, cwd="/home", dry_run=False
    )


def test_orchestrator_vm_runner_passes_empty_env_as_none() -> None:
    orch = MagicMock()
    orch.exec_argv.return_value = _FakeResult()
    runner = OrchestratorVmRunner(orch, MagicMock())
    runner.run_vm_command(("ls",), env={}, remote_dir=None, dry_run=True)
    _, kwargs = orch.exec_argv.call_args
    assert kwargs["env"] is None


def test_vm_file_fetcher_calls_transfer_from(tmp_path: Path) -> None:
    orch = MagicMock()
    orch.transfer_from.return_value = _FakeResult(return_code=0)
    request = MagicMock()

    fetcher = VmFileFetcher(vm=orch, request=request)
    fetcher.fetch_from("/remote/results", tmp_path)

    orch.transfer_from.assert_called_once_with(
        request, source="/remote/results", destination=tmp_path
    )


def test_vm_file_fetcher_raises_on_nonzero() -> None:
    orch = MagicMock()
    orch.transfer_from.return_value = _FakeResult(return_code=1, stderr="permission denied")

    fetcher = VmFileFetcher(vm=orch, request=MagicMock())
    with pytest.raises(RuntimeError, match="permission denied"):
        fetcher.fetch_from("/remote/results", Path("/local"))
