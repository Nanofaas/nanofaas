from __future__ import annotations

import pytest
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _make_k6_result(fn: str):
    from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result
    return TwoVmK6Result(
        run_dir=Path("/tmp"),
        k6_summary_path=Path(f"/tmp/{fn}.json"),
        target_function=fn,
        started_at=_utcnow(),
        ended_at=_utcnow(),
    )


def test_on_loadgen_run_k6_calls_matrix_not_single() -> None:
    """Regression: RunK6Matrix must iterate ALL targets, not just [0]."""
    from controlplane_tool.scenario.tasks.loadtest import RunK6Matrix

    fn_keys = ["word-stats-java", "json-transform-java", "word-stats-python"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    task = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    result = task.run()

    assert runner.run_k6_for_function.call_count == 3
    called_fns = [c.args[1] for c in runner.run_k6_for_function.call_args_list]
    assert called_fns == fn_keys
    assert len(result.results) == 3


def test_run_k6_matrix_first_result_available_for_prometheus() -> None:
    """_on_prometheus_snapshot uses first result — must not fail when matrix has multiple."""
    from controlplane_tool.scenario.tasks.loadtest import RunK6Matrix

    fn_keys = ["word-stats-java", "json-transform-java"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    matrix_result = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    ).run()

    assert matrix_result.results[0].target_function == "word-stats-java"
    assert matrix_result.window is not None
