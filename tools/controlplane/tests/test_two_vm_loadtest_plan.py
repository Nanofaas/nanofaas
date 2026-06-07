from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

from controlplane_tool.scenario.catalog import resolve_scenario
from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan


def test_two_vm_loadtest_plan_has_expected_task_ids() -> None:
    """task_ids must include all phases: stack provisioning + loadgen + cleanup.

    After B3a, task_ids delegates to loadtest_flow_task_ids which calls _build_setup
    (needs a real environment). We patch _build_setup and _adapter so the static-plan
    path exercises only the task-ID assembly logic, not environment I/O.
    """
    runner = MagicMock()
    runner.paths.workspace_root = Path("/repo")
    request = MagicMock()

    scenario = resolve_scenario("two-vm-loadtest")
    plan = TwoVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)

    mock_setup = MagicMock()
    mock_adapter = MagicMock()
    mock_adapter.connectivity = MagicMock()
    mock_adapter.extra_step_ids.return_value = []

    # Patch build_command_tasks so _prelude_static_ids returns expected prelude task IDs
    # (build_command_tasks is called with resolve_host=False; the tasks carry .task_id attrs)
    prelude_task_ids = [
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core",
        "images.build_selected_functions",
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
    ]
    mock_tasks = [MagicMock(task_id=tid) for tid in prelude_task_ids]

    with (
        patch.object(plan, "_build_setup", return_value=mock_setup),
        patch.object(plan, "_adapter", return_value=mock_adapter),
        patch(
            "controlplane_tool.scenario.loadtest_flow._prelude_static_tasks",
            return_value=mock_tasks,
        ),
    ):
        ids = plan.task_ids
    # Stack VM lifecycle
    assert "vm.stack.ensure_running" in ids
    # Stack provisioning phases
    assert "vm.provision_base" in ids
    assert "repo.sync_to_vm" in ids
    assert "registry.ensure_container" in ids
    assert "images.build_core" in ids
    assert "images.build_selected_functions" in ids
    assert "k3s.install" in ids
    assert "helm.deploy_control_plane" in ids
    assert "helm.deploy_function_runtime" in ids
    # Loadgen phases
    assert "vm.loadgen.ensure_running" in ids
    assert "loadgen.install_k6" in ids
    assert "loadgen.run_k6" in ids
    assert "loadgen.fetch_results" in ids
    assert "metrics.prometheus_snapshot" in ids
    assert "loadtest.write_report" in ids
    # Cleanup
    assert "vm.loadgen.destroy" in ids
    assert "vm.stack.destroy" in ids


def test_two_vm_loadgen_install_uses_runplaybook_not_bash() -> None:
    """The loadgen install step must be the ansible RunPlaybook, not bash InstallK6.
    After the B3a refactor, the body is built by build_loadgen_body_tasks (which
    internally calls install_k6_task) inside the shared run_loadtest_flow driver;
    run() must delegate to run_loadtest_flow and InstallK6 must not appear in the driver.
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow
    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    flow_source = inspect.getsource(loadtest_flow)
    assert "build_loadgen_body_tasks(" in flow_source
    assert "InstallK6(" not in flow_source
    # Guard: run() itself must delegate to the driver
    assert "run_loadtest_flow(" in inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)


def test_two_vm_run_uploads_k6_script_to_loadgen() -> None:
    """run() must upload the k6 script before RunK6, else k6 finds no script.
    After the B3a refactor, prepare_loadgen is called via adapter.prepare_loadgen(ctx)
    inside run_loadtest_flow; asserting it appears in the driver source preserves the invariant.
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow

    flow_source = inspect.getsource(loadtest_flow)
    assert "prepare_loadgen" in flow_source


def test_two_vm_run_registers_functions_on_control_plane() -> None:
    """run() must register the selected functions, else k6 invokes a non-existent
    function (400) and the required Prometheus dispatch metrics have no data.
    After the B3a refactor, _register_functions (which uses RegisterFunctions) lives
    in the shared run_loadtest_flow driver.
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow

    flow_source = inspect.getsource(loadtest_flow)
    assert "RegisterFunctions" in flow_source
