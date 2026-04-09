from pathlib import Path

import pytest

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.vm_models import VmRequest


def _sample_request() -> E2eRequest:
    return E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_flow_catalog_resolves_k8s_vm_to_executable_flow_definition() -> None:
    definition = resolve_flow_definition(
        "e2e.k8s-vm",
        repo_root=Path("/repo"),
        request=_sample_request(),
    )

    assert definition.flow_id == "e2e.k8s_vm"
    assert "vm.ensure_running" in definition.task_ids


def test_flow_catalog_exposes_task_ids_without_executable_placeholder() -> None:
    task_ids = resolve_flow_task_ids("e2e.k8s-vm")

    assert task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "k3s.install",
        "registry.ensure_container",
        "k3s.configure_registry",
        "images.build_core",
        "tests.run_k8s_e2e",
    ]


def test_requestless_runtime_scenario_definition_is_not_silently_executable() -> None:
    with pytest.raises(ValueError):
        resolve_flow_definition("e2e.k8s-vm", repo_root=Path("/repo"))


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
    task_ids = resolve_flow_task_ids("e2e.all", scenarios=["k3s-curl", "k8s-vm"])

    assert task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "k3s.install",
        "registry.ensure_container",
        "k3s.configure_registry",
        "images.build_core",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "tests.run_k3s_curl",
        "tests.run_k8s_e2e",
    ]
