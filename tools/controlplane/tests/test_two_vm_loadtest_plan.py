from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

from controlplane_tool.scenario.catalog import resolve_scenario
from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan


def test_two_vm_loadtest_plan_has_expected_task_ids() -> None:
    """task_ids must include all phases: stack provisioning + loadgen + cleanup."""
    runner = MagicMock()
    runner.paths.workspace_root = Path("/repo")
    request = MagicMock()

    scenario = resolve_scenario("two-vm-loadtest")
    plan = TwoVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)

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
    After the B2 refactor, the body is built by build_loadgen_body_tasks (which
    internally calls install_k6_task); the run() method must not use InstallK6 directly.
    """
    import inspect

    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    source = inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)
    assert "build_loadgen_body_tasks(" in source
    assert "InstallK6(" not in source


def test_two_vm_run_uploads_k6_script_to_loadgen() -> None:
    """run() must upload the k6 script before RunK6, else k6 finds no script."""
    import inspect

    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    source = inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)
    assert "prepare_loadgen" in source


def test_two_vm_run_registers_functions_on_control_plane() -> None:
    """run() must register the selected functions, else k6 invokes a non-existent
    function (400) and the required Prometheus dispatch metrics have no data."""
    import inspect

    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    source = inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)
    assert "RegisterFunctions" in source
