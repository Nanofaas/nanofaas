from pathlib import Path

import pytest

import controlplane_tool.flow_catalog as flow_catalog_mod
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.scenario_components.composer import compose_recipe
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
    recipe = build_scenario_recipe("k3s-junit-curl")

    assert task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_flow_catalog_resolves_cli_stack_task_ids() -> None:
    recipe = build_scenario_recipe("cli-stack")
    task_ids = resolve_flow_task_ids("e2e.cli-stack")

    assert task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_flow_catalog_resolves_container_local_task_ids_from_legacy_mapping() -> None:
    assert resolve_flow_task_ids("e2e.container-local") == ["tests.run_container_local"]


def test_flow_catalog_resolves_k3s_junit_curl_from_recipe() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")
    task_ids = resolve_flow_task_ids("e2e.k3s-junit-curl")

    assert task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_flow_catalog_helm_stack_task_ids_follow_recipe_composition(monkeypatch) -> None:
    monkeypatch.setattr(
        flow_catalog_mod,
        "scenario_task_ids",
        lambda scenario: ["first.component", "second.component"],
        raising=False,
    )

    assert resolve_flow_task_ids("e2e.helm-stack") == [
        "first.component",
        "second.component",
    ]


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
