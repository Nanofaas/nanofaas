from pathlib import Path

import pytest

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.vm_models import VmRequest


def _sample_request() -> E2eRequest:
    return E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_flow_catalog_resolves_k3s_junit_curl_to_executable_flow_definition() -> None:
    definition = resolve_flow_definition(
        "e2e.k3s-junit-curl",
        repo_root=Path("/repo"),
        request=_sample_request(),
    )

    assert definition.flow_id == "e2e.k3s_junit_curl"
    assert "vm.ensure_running" in definition.task_ids


def test_flow_catalog_exposes_task_ids_without_executable_placeholder() -> None:
    task_ids = resolve_flow_task_ids("e2e.k3s-junit-curl")

    for component_id in [
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
    ]:
        assert component_id in task_ids

    assert task_ids.index("vm.ensure_running") < task_ids.index("vm.provision_base")
    assert task_ids.index("vm.provision_base") < task_ids.index("repo.sync_to_vm")
    assert task_ids.index("k8s.wait_control_plane_ready") < task_ids.index(
        "k8s.wait_function_runtime_ready"
    )
    assert task_ids.index("helm.uninstall_function_runtime") < task_ids.index(
        "helm.uninstall_control_plane"
    )
    assert task_ids.index("k8s.delete_namespace") < task_ids.index("vm.down")


def test_flow_catalog_resolves_cli_stack_task_ids() -> None:
    recipe = build_scenario_recipe("cli-stack")
    task_ids = resolve_flow_task_ids("e2e.cli-stack")

    assert len(task_ids) == len(recipe.component_ids)
    assert task_ids[0] == "vm.ensure_running"
    assert task_ids[-1] == "tests.verify_cli_stack_status_fails"
    assert task_ids.index("tests.build_cli_stack_cli") < task_ids.index(
        "tests.install_cli_stack_platform"
    )
    assert task_ids.index("tests.uninstall_cli_stack_platform") < task_ids.index(
        "tests.verify_cli_stack_status_fails"
    )


def test_flow_catalog_resolves_k3s_junit_curl_from_recipe() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")
    task_ids = resolve_flow_task_ids("e2e.k3s-junit-curl")

    assert len(task_ids) == len(recipe.component_ids)
    assert task_ids[0] == recipe.component_ids[0]
    assert task_ids[-1] == recipe.component_ids[-1]


def test_requestless_runtime_scenario_definition_is_not_silently_executable() -> None:
    with pytest.raises(ValueError):
        resolve_flow_definition("e2e.k3s-junit-curl", repo_root=Path("/repo"))


def test_requestless_loadtest_definition_is_not_silently_executable() -> None:
    with pytest.raises(ValueError):
        resolve_flow_definition("loadtest.quick")


def test_flow_catalog_e2e_all_allows_empty_selection_without_failure() -> None:
    flow = resolve_flow_definition(
        "e2e.all",
        runner=object(),
        scenarios=[],
        only=[],
        skip=[],
    )

    assert flow.flow_id == "e2e.all"
    assert flow.task_ids == []


def test_flow_catalog_e2e_all_task_ids_do_not_duplicate_shared_vm_bootstrap() -> None:
    task_ids = resolve_flow_task_ids("e2e.all", scenarios=["k3s-junit-curl"])

    assert task_ids.count("vm.ensure_running") == 1
    assert task_ids.count("vm.down") == 1
    assert task_ids.index("vm.ensure_running") < task_ids.index("k8s.ensure_namespace")
    assert task_ids.index("tests.run_k3s_curl_checks") < task_ids.index("vm.down")
