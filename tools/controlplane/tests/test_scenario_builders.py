from __future__ import annotations

from controlplane_tool.scenario.scenario_flows import scenario_task_ids


def test_two_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register, not cli.fn_apply_selected."""
    ids = scenario_task_ids("two-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_azure_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register for azure-vm-loadtest."""
    ids = scenario_task_ids("azure-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_two_vm_loadtest_task_ids_order() -> None:
    """functions.register must appear between cli.build_install_dist and loadgen.ensure_running."""
    ids = scenario_task_ids("two-vm-loadtest")
    build_dist_idx = ids.index("cli.build_install_dist")
    register_idx = ids.index("functions.register")
    loadgen_idx = ids.index("loadgen.ensure_running")
    assert build_dist_idx < register_idx < loadgen_idx


from pathlib import Path
from unittest.mock import MagicMock

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol


def _make_request() -> E2eRequest:
    return E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )


def test_two_vm_loadtest_plan_satisfies_protocol() -> None:
    """TwoVmLoadtestPlan must satisfy the ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="test.step")
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(),
        request=_make_request(),
        steps=[step],
        runner=MagicMock(),
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["test.step"]


def test_two_vm_loadtest_plan_task_ids_skips_empty_step_ids() -> None:
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    steps = [
        ScenarioPlanStep(summary="a", command=["echo"], step_id="a.step"),
        ScenarioPlanStep(summary="b", command=["echo"], step_id=""),
        ScenarioPlanStep(summary="c", command=["echo"], step_id="c.step"),
    ]
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(), request=_make_request(), steps=steps, runner=MagicMock()
    )
    assert plan.task_ids == ["a.step", "c.step"]


def test_build_two_vm_loadtest_plan_returns_correct_type(tmp_path: Path) -> None:
    """build_two_vm_loadtest_plan returns TwoVmLoadtestPlan with non-empty task_ids."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import (
        TwoVmLoadtestPlan,
        build_two_vm_loadtest_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = _make_request()

    plan = build_two_vm_loadtest_plan(runner, request)

    assert isinstance(plan, TwoVmLoadtestPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids
    assert "loadgen.run_k6" in plan.task_ids
    assert "vm.down" in plan.task_ids
