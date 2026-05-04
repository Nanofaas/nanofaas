from pathlib import Path
from unittest.mock import MagicMock
from controlplane_tool.scenario.scenario_planner import ScenarioPlanner
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest


def test_scenario_planner_local_steps_returns_list() -> None:
    vm = MagicMock()
    shell = MagicMock()
    paths = MagicMock()
    paths.workspace_root = Path("/repo")
    planner = ScenarioPlanner(paths=paths, vm=vm, shell=shell, manifest_root=Path("/repo/runs/manifests"))
    request = E2eRequest(scenario="docker", runtime="java")

    steps = planner.local_steps(request)

    assert isinstance(steps, list)


def test_helm_stack_tail_exposes_k6_install_before_loadtest() -> None:
    vm = MagicMock()
    vm.vm_name.return_value = "nanofaas-e2e"
    vm.remote_project_dir.return_value = "/home/ubuntu/nanofaas"
    vm.kubeconfig_path.return_value = "/home/ubuntu/.kube/config"
    shell = MagicMock()
    paths = MagicMock()
    paths.workspace_root = Path("/repo")
    planner = ScenarioPlanner(paths=paths, vm=vm, shell=shell, manifest_root=Path("/repo/runs/manifests"))
    request = E2eRequest(
        scenario="helm-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    steps = planner.helm_stack_tail_steps(request)

    assert [step.step_id for step in steps[:2]] == ["loadtest.install_k6", "loadtest.run"]
    assert [step.summary for step in steps[:2]] == [
        "Install k6 for load testing",
        "Run k6 loadtest via controlplane runner",
    ]
    assert "install-k6.yml" in steps[0].command[-1]
    assert steps[1].action is not None
