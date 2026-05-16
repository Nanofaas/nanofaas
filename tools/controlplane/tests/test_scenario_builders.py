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


def _make_azure_request() -> E2eRequest:
    return E2eRequest(
        scenario="azure-vm-loadtest",
        runtime="java",
        vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
        loadgen_vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure-loadgen",
            azure_resource_group="my-rg",
            azure_location="westeurope",
            azure_vm_size="Standard_B1s",
        ),
    )


def test_azure_vm_loadtest_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="vm.ensure_running")
    plan = AzureVmLoadtestPlan(
        scenario=MagicMock(),
        request=_make_azure_request(),
        steps=[step],
        runner=MagicMock(),
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["vm.ensure_running"]


def test_build_azure_vm_loadtest_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import (
        AzureVmLoadtestPlan,
        build_azure_vm_loadtest_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = _make_azure_request()

    plan = build_azure_vm_loadtest_plan(runner, request)

    assert isinstance(plan, AzureVmLoadtestPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids


def test_e2e_runner_plan_returns_two_vm_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return TwoVmLoadtestPlan for two-vm-loadtest."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_request())

    assert isinstance(plan, TwoVmLoadtestPlan)
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids


def test_e2e_runner_plan_returns_azure_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return AzureVmLoadtestPlan for azure-vm-loadtest."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_azure_request())

    assert isinstance(plan, AzureVmLoadtestPlan)
    assert "functions.register" in plan.task_ids


def test_build_scenario_flow_uses_plan_task_ids_for_two_vm(tmp_path: Path) -> None:
    """build_scenario_flow must show functions.register (not cli.fn_apply_selected) for two-vm-loadtest."""
    from controlplane_tool.scenario.scenario_flows import build_scenario_flow

    flow = build_scenario_flow(
        "two-vm-loadtest",
        repo_root=Path("/repo"),
        request=_make_request(),
    )

    assert "functions.register" in flow.task_ids
    assert "cli.fn_apply_selected" not in flow.task_ids


def test_build_scenario_flow_uses_plan_task_ids_for_azure_vm(tmp_path: Path) -> None:
    """build_scenario_flow must show functions.register (not cli.fn_apply_selected) for azure-vm-loadtest."""
    from controlplane_tool.scenario.scenario_flows import build_scenario_flow

    flow = build_scenario_flow(
        "azure-vm-loadtest",
        repo_root=Path("/repo"),
        request=_make_azure_request(),
    )

    assert "functions.register" in flow.task_ids
    assert "cli.fn_apply_selected" not in flow.task_ids


def _make_k3s_request() -> E2eRequest:
    return E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_k3s_junit_curl_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="vm.ensure_running")
    plan = K3sJunitCurlPlan(
        scenario=MagicMock(), request=_make_k3s_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["vm.ensure_running"]


def test_build_k3s_junit_curl_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import (
        K3sJunitCurlPlan,
        build_k3s_junit_curl_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_k3s_junit_curl_plan(runner, _make_k3s_request())

    assert isinstance(plan, K3sJunitCurlPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "tests.run_k3s_curl_checks" in plan.task_ids
    assert "tests.run_k8s_junit" in plan.task_ids
    assert "vm.down" in plan.task_ids


def _make_helm_stack_request() -> E2eRequest:
    return E2eRequest(
        scenario="helm-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_helm_stack_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="loadtest.install_k6")
    plan = HelmStackPlan(
        scenario=MagicMock(), request=_make_helm_stack_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["loadtest.install_k6"]


def test_build_helm_stack_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.helm_stack import (
        HelmStackPlan,
        build_helm_stack_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_helm_stack_plan(runner, _make_helm_stack_request())

    assert isinstance(plan, HelmStackPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "helm.deploy_control_plane" in plan.task_ids
    assert "loadtest.install_k6" in plan.task_ids
    assert "loadtest.run" in plan.task_ids


def _make_cli_stack_request() -> E2eRequest:
    return E2eRequest(
        scenario="cli-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        namespace="nanofaas-cli-stack-e2e",
    )


def test_cli_stack_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="cli.build_install_dist")
    plan = CliStackPlan(
        scenario=MagicMock(), request=_make_cli_stack_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["cli.build_install_dist"]


def test_build_cli_stack_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import (
        CliStackPlan,
        build_cli_stack_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_cli_stack_plan(runner, _make_cli_stack_request())

    assert isinstance(plan, CliStackPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "cli.build_install_dist" in plan.task_ids
    assert "cli.fn_list_selected" in plan.task_ids
    assert "cli.fn_invoke_selected.echo-test" in plan.task_ids
    assert "vm.down" in plan.task_ids
    # cli-stack uses CLI fn apply, not REST API (loadtest-only remap)
    fn_apply_tasks = [t for t in plan.task_ids if t.startswith("cli.fn_apply_selected")]
    assert fn_apply_tasks, "cli-stack must include CLI fn apply tasks"
    assert "functions.register" not in plan.task_ids


def test_cli_stack_plan_uses_cli_fn_apply_not_rest_api(tmp_path: Path) -> None:
    """cli-stack must use CLI fn apply, not the REST API registration.

    Regression test: plan_recipe_steps must NOT remap cli.fn_apply_selected to
    functions.register for cli-stack — that remap is only for loadtest scenarios.
    """
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_cli_stack_plan(runner, _make_cli_stack_request())

    # Check that at least one fn_apply_selected task exists (e.g., cli.fn_apply_selected.echo-test)
    fn_apply_tasks = [t for t in plan.task_ids if t.startswith("cli.fn_apply_selected")]
    assert fn_apply_tasks, (
        "cli-stack must use the CLI for fn apply (not REST API)"
    )
    assert "functions.register" not in plan.task_ids, (
        "functions.register must not appear in cli-stack — it's a loadtest-only step"
    )


def test_e2e_runner_plan_returns_k3s_junit_curl_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return K3sJunitCurlPlan for k3s-junit-curl."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_k3s_request())

    assert isinstance(plan, K3sJunitCurlPlan)
    assert "vm.ensure_running" in plan.task_ids
    assert "tests.run_k3s_curl_checks" in plan.task_ids


def test_e2e_runner_plan_returns_helm_stack_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return HelmStackPlan for helm-stack."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_helm_stack_request())

    assert isinstance(plan, HelmStackPlan)
    assert "loadtest.install_k6" in plan.task_ids


def test_e2e_runner_plan_returns_cli_stack_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return CliStackPlan for cli-stack."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_cli_stack_request())

    assert isinstance(plan, CliStackPlan)
    assert "cli.build_install_dist" in plan.task_ids


def test_two_vm_loadtest_plan_run_forwards_event_listener() -> None:
    """Builder run() must accept and forward event_listener to _execute_steps."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep
    from unittest.mock import MagicMock

    captured: dict = {}

    mock_runner = MagicMock()
    mock_runner._execute_steps.side_effect = (
        lambda plan, event_listener=None: captured.update({"event_listener": event_listener})
    )

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="test.step")
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(), request=_make_request(), steps=[step], runner=mock_runner
    )
    listener = lambda event: None  # noqa: E731

    plan.run(event_listener=listener)

    assert captured["event_listener"] is listener
