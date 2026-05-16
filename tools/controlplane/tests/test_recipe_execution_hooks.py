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


def test_register_functions_spec_built_from_resolved_scenario() -> None:
    """FunctionSpec list must include name + image for each selected function."""
    from controlplane_tool.scenario.tasks.functions import FunctionSpec
    from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions

    resolved = MagicMock()
    resolved.functions = [
        MagicMock(key="word-stats-java", image="registry/word-stats-java:e2e"),
        MagicMock(key="json-transform-java", image="registry/json-transform-java:e2e"),
    ]
    local_registry = "localhost:5000"
    runtime_image_default = f"{local_registry}/nanofaas/function-runtime:e2e"

    specs = [
        FunctionSpec(
            name=fn_key,
            image=function_image(fn_key, resolved, runtime_image_default),
        )
        for fn_key in selected_functions(resolved)
    ]

    assert len(specs) == 2
    assert specs[0].name == "word-stats-java"
    assert specs[0].image == "registry/word-stats-java:e2e"
    assert specs[1].name == "json-transform-java"
    assert specs[1].image == "registry/json-transform-java:e2e"
    assert specs[0].execution_mode == "DEPLOYMENT"
    assert specs[0].timeout_ms == 5000


def test_register_functions_step_has_correct_step_id() -> None:
    """Replacement step for cli.fn_apply_selected must have step_id=functions.register."""
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    def _stub_action() -> None:
        pass

    step = ScenarioPlanStep(
        summary="Register selected functions via REST API",
        command=["python", "-c", "# RegisterFunctions via REST"],
        step_id="functions.register",
        action=_stub_action,
    )
    assert step.step_id == "functions.register"
    assert step.action is not None


def test_scenario_plan_protocol_is_satisfied_by_existing_dataclass() -> None:
    """Existing ScenarioPlan dataclass must satisfy the new ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo", "x"], step_id="test.step")
    plan = ScenarioPlan(
        scenario=MagicMock(),
        request=MagicMock(),
        steps=[step],
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["test.step"]


def test_scenario_plan_task_ids_skips_empty_step_ids() -> None:
    """Steps without step_id are excluded from task_ids."""
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    steps = [
        ScenarioPlanStep(summary="a", command=["echo"], step_id="a.step"),
        ScenarioPlanStep(summary="b", command=["echo"], step_id=""),
        ScenarioPlanStep(summary="c", command=["echo"], step_id="c.step"),
    ]
    plan = ScenarioPlan(scenario=MagicMock(), request=MagicMock(), steps=steps)
    assert plan.task_ids == ["a.step", "c.step"]
