from __future__ import annotations

import pytest
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock
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
