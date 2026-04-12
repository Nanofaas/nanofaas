from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

import controlplane_tool.scenario_flows as scenario_flows_mod
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.e2e_runner import E2eRunner
from controlplane_tool.scenario_components.composer import compose_recipe
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.scenario_flows import build_scenario_flow
from controlplane_tool.vm_models import VmRequest


def _assert_order(task_ids: list[str], ordered_ids: list[str]) -> None:
    positions = [task_ids.index(component_id) for component_id in ordered_ids]
    assert positions == sorted(positions)


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
    recipe = build_scenario_recipe("k3s-junit-curl")

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_cli_vm_flow_reuses_build_and_helm_deploy_tasks() -> None:
    flow = build_scenario_flow("cli", repo_root=Path("/repo"))

    assert flow.task_ids == ["tests.run_cli"]


def test_cli_stack_flow_uses_dedicated_cli_stack_task_order() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))
    recipe = build_scenario_recipe("cli-stack")

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_cli_stack_flow_routes_through_e2e_runner(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        ) or "ok",
    )

    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))

    assert flow.run() == "ok"
    assert called["request"].scenario == "cli-stack"
    assert called["request"].vm is not None


def test_cli_stack_flow_task_ids_are_derived_from_the_recipe() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))
    recipe = build_scenario_recipe("cli-stack")

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_cli_stack_flow_task_ids_follow_recipe_composition(monkeypatch) -> None:
    monkeypatch.setattr(
        scenario_flows_mod,
        "build_scenario_recipe",
        lambda name: SimpleNamespace(name=name),
        raising=False,
    )
    monkeypatch.setattr(
        scenario_flows_mod,
        "compose_recipe",
        lambda recipe: [
            SimpleNamespace(component_id="first.component"),
            SimpleNamespace(component_id="second.component"),
        ],
        raising=False,
    )

    assert scenario_flows_mod.scenario_task_ids("cli-stack") == [
        "first.component",
        "second.component",
    ]


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

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_k3s_junit_curl_request_without_vm_gets_managed_vm_context() -> None:
    plan = E2eRunner(repo_root=Path("/repo")).plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=None,
        )
    )

    assert plan.request.vm is not None
    assert plan.request.vm.lifecycle == "multipass"


def test_cli_stack_flow_no_longer_needs_a_preexisting_vm_request() -> None:
    flow = build_scenario_flow("cli-stack", repo_root=Path("/repo"))

    assert flow.flow_id == "e2e.cli_stack"
    assert "vm.ensure_running" in flow.task_ids


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


def test_helm_stack_flow_shares_k3s_junit_curl_prefix() -> None:
    flow = build_scenario_flow("helm-stack", repo_root=Path("/repo"))
    recipe = build_scenario_recipe("helm-stack")

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]


def test_helm_stack_flow_task_ids_follow_recipe_composition(monkeypatch) -> None:
    monkeypatch.setattr(
        scenario_flows_mod,
        "build_scenario_recipe",
        lambda name: SimpleNamespace(name=name),
        raising=False,
    )
    monkeypatch.setattr(
        scenario_flows_mod,
        "compose_recipe",
        lambda recipe: [
            SimpleNamespace(component_id="first.component"),
            SimpleNamespace(component_id="second.component"),
        ],
        raising=False,
    )

    assert scenario_flows_mod.scenario_task_ids("helm-stack") == [
        "first.component",
        "second.component",
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
