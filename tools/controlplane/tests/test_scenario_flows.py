from __future__ import annotations

from pathlib import Path

import pytest

import controlplane_tool.scenario_flows as scenario_flows_mod
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.scenario_flows import build_scenario_flow
from controlplane_tool.vm_models import VmRequest


def test_k3s_junit_curl_flow_uses_reusable_vm_build_and_deploy_tasks() -> None:
    flow = build_scenario_flow(
        "k3s-junit-curl",
        repo_root=Path("/repo"),
        request=E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        ),
    )

    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core",
        "images.build_selected_functions",
        "k3s.install",
        "k3s.configure_registry",
        "k8s.ensure_namespace",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "k8s.wait_control_plane_ready",
        "k8s.wait_function_runtime_ready",
        "tests.run_k3s_curl_checks",
        "tests.run_k8s_junit",
        "helm.uninstall_function_runtime",
        "helm.uninstall_control_plane",
        "k8s.delete_namespace",
        "vm.down",
    ]


def test_cli_vm_flow_reuses_build_and_helm_deploy_tasks() -> None:
    flow = build_scenario_flow("cli", repo_root=Path("/repo"))

    assert "helm.deploy_control_plane" in flow.task_ids


def test_cli_stack_flow_uses_dedicated_cli_stack_task_order() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))

    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "k3s.install",
        "k3s.configure_registry",
        "images.build_core",
        "images.build_selected_functions",
        "tests.build_cli_stack_cli",
        "tests.install_cli_stack_platform",
        "tests.status_cli_stack_platform",
        "tests.apply_cli_stack_functions",
        "tests.list_cli_stack_functions",
        "tests.invoke_cli_stack_functions",
        "tests.enqueue_cli_stack_functions",
        "tests.delete_cli_stack_functions",
        "tests.uninstall_cli_stack_platform",
        "tests.verify_cli_stack_status_fails",
    ]


def test_cli_stack_flow_uses_dedicated_vm_runner(monkeypatch) -> None:
    called: dict[str, object] = {}

    class _Runner:
        def __init__(self, *args, **kwargs):  # noqa: ANN002,ANN003
            called["init"] = kwargs

        def run(self, scenario_file=None):  # noqa: ANN001
            called["scenario_file"] = scenario_file
            return "ok"

    monkeypatch.setattr("controlplane_tool.cli_stack_runner.CliStackRunner", _Runner)

    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))

    assert flow.run() == "ok"
    assert called["init"]["namespace"] == "nanofaas-e2e"


def test_cli_stack_flow_task_ids_are_derived_from_the_recipe() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))
    recipe = build_scenario_recipe("cli-stack")

    assert flow.task_ids == recipe.component_ids


def test_k3s_junit_curl_flow_task_ids_are_derived_from_the_recipe() -> None:
    flow = build_scenario_flow(
        "k3s-junit-curl",
        repo_root=Path("/repo"),
        request=E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        ),
    )
    recipe = build_scenario_recipe("k3s-junit-curl")

    assert flow.task_ids == recipe.component_ids


def test_cli_stack_flow_no_longer_needs_a_preexisting_vm_request() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))

    assert flow.flow_id == "e2e.cli_stack"
    assert flow.task_ids[0] == "vm.ensure_running"


def test_k3s_junit_curl_flow_requires_request_for_executable_definition() -> None:
    with pytest.raises(ValueError):
        build_scenario_flow("k3s-junit-curl", repo_root=Path("/repo"))


def test_request_backed_scenario_flow_forwards_event_listener(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        ) or "ok",
    )
    request = E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    listener = lambda event: None  # noqa: ARG005,E731

    flow = build_scenario_flow(
        "k3s-junit-curl",
        repo_root=Path("/repo"),
        request=request,
        event_listener=listener,
    )

    assert flow.run() == "ok"
    assert called["request"] is request
    assert called["event_listener"] is listener


def test_runner_modules_no_longer_inline_docker_and_helm_orchestration() -> None:
    repo_root = Path(__file__).resolve().parents[3]
    for relative_path in [
        "tools/controlplane/src/controlplane_tool/e2e_runner.py",
        "tools/controlplane/src/controlplane_tool/cli_vm_runner.py",
        "tools/controlplane/src/controlplane_tool/k3s_curl_runner.py",
    ]:
        source = (repo_root / relative_path).read_text(encoding="utf-8")
        assert "docker build" not in source
        assert "docker push" not in source
        assert "helm upgrade --install" not in source


def test_helm_stack_flow_shares_k3s_junit_curl_prefix() -> None:
    flow = build_scenario_flow("helm-stack", repo_root=Path("/repo"))

    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core",
        "images.build_selected_functions",
        "k3s.install",
        "k3s.configure_registry",
        "k8s.ensure_namespace",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "k8s.wait_control_plane_ready",
        "k8s.wait_function_runtime_ready",
        "loadtest.run",
        "experiments.autoscaling",
    ]


def test_helm_stack_flow_routes_through_python_e2e_runner(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        ) or "ok",
    )

    flow = build_scenario_flow("helm-stack", repo_root=Path("/repo"))

    assert flow.run() == "ok"
    assert called["request"].scenario == "helm-stack"


def test_helm_stack_flow_preserves_noninteractive_flag(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        ) or "ok",
    )

    flow = build_scenario_flow("helm-stack", repo_root=Path("/repo"), noninteractive=False)

    assert flow.run() == "ok"
    assert called["request"].helm_noninteractive is False
