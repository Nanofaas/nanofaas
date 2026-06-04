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
