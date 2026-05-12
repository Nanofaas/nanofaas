from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

import controlplane_tool.cli_validation.cli_host_runner as cli_host_runner_mod
import controlplane_tool.scenario.scenario_flows as scenario_flows_mod
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenario_flows import build_scenario_flow
from controlplane_tool.infra.vm.vm_models import VmRequest


def _assert_order(task_ids: list[str], ordered_ids: list[str]) -> None:
    positions = [task_ids.index(component_id) for component_id in ordered_ids]
    assert positions == sorted(positions)


def _make_resolved_scenario(function_keys: list[str]):
    from controlplane_tool.scenario.scenario_models import ResolvedFunction, ResolvedScenario

    functions = [
        ResolvedFunction(
            key=key,
            family=key.split("-", 1)[0],
            runtime="javascript" if "javascript" in key else "java",
            description=f"Resolved function {key}",
            image=f"localhost:5000/nanofaas/{key}:e2e",
        )
        for key in function_keys
    ]
    return ResolvedScenario(
        name="test-selection",
        base_scenario="container-local" if len(function_keys) == 1 else "deploy-host",
        runtime="java",
        functions=functions,
        function_keys=function_keys,
    )


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
    assert called["request"].namespace == "nanofaas-cli-stack-e2e"


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


def test_cli_stack_flow_requestless_execution_preserves_scenario_file_namespace(
    monkeypatch,
    tmp_path: Path,
) -> None:
    called: dict[str, object] = {}
    resolved_scenario = _make_resolved_scenario(["word-stats-javascript"]).model_copy(
        update={"base_scenario": "cli-stack", "namespace": "scenario-namespace"}
    )
    scenario_file = tmp_path / "cli-stack.toml"

    monkeypatch.setattr(
        scenario_flows_mod,
        "_resolve_scenario",
        lambda path: resolved_scenario if path == scenario_file else None,  # noqa: ARG005
        raising=False,
    )
    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        )
        or "ok",
    )

    flow = build_scenario_flow(
        "cli-stack",
        repo_root=Path("/repo"),
        scenario_file=scenario_file,
        namespace=None,
    )

    assert flow.run() == "ok"
    assert called["request"].scenario_file == scenario_file
    assert called["request"].resolved_scenario == resolved_scenario
    assert called["request"].namespace == "scenario-namespace"


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


def test_cli_host_flow_resolves_namespace_and_release_via_shared_defaults(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeRunner:
        def __init__(self, *args, **kwargs):  # noqa: ANN001
            captured["namespace"] = kwargs["namespace"]
            captured["release"] = kwargs["release"]

        def run(self, scenario_file=None):  # noqa: ANN001
            return "ok"

    monkeypatch.setattr(cli_host_runner_mod, "CliHostPlatformRunner", FakeRunner)

    flow = build_scenario_flow(
        "cli-host",
        repo_root=Path("/repo"),
        namespace=None,
        release=None,
    )

    assert flow.run() == "ok"
    assert captured["namespace"] == "nanofaas-host-cli-e2e"
    assert captured["release"] == "nanofaas-host-cli-e2e"


def test_container_local_flow_passes_resolved_scenario_to_runner(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeRunner:
        def __init__(self, *args, **kwargs):  # noqa: ANN001
            return None

        def run(self, scenario_file=None, *, resolved_scenario=None):  # noqa: ANN001
            captured["scenario_file"] = scenario_file
            captured["resolved_scenario"] = resolved_scenario
            return "ok"

    monkeypatch.setattr(scenario_flows_mod, "ContainerLocalE2eRunner", FakeRunner, raising=False)

    request = E2eRequest(
        scenario="container-local",
        resolved_scenario=_make_resolved_scenario(["word-stats-javascript"]),
    )
    flow = build_scenario_flow("container-local", repo_root=Path("/repo"), request=request)

    assert flow.run() == "ok"
    assert captured["scenario_file"] is None
    assert captured["resolved_scenario"] is request.resolved_scenario


def test_deploy_host_flow_passes_resolved_scenario_to_runner(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeRunner:
        def __init__(self, *args, **kwargs):  # noqa: ANN001
            return None

        def run(self, scenario_file=None, *, resolved_scenario=None, skip_cli_build=False):  # noqa: ANN001
            captured["scenario_file"] = scenario_file
            captured["resolved_scenario"] = resolved_scenario
            captured["skip_cli_build"] = skip_cli_build
            return "ok"

    monkeypatch.setattr(scenario_flows_mod, "DeployHostE2eRunner", FakeRunner, raising=False)

    request = E2eRequest(
        scenario="deploy-host",
        resolved_scenario=_make_resolved_scenario(
            ["word-stats-javascript", "json-transform-javascript"]
        ),
    )
    flow = build_scenario_flow("deploy-host", repo_root=Path("/repo"), request=request)

    assert flow.run() == "ok"
    assert captured["scenario_file"] is None
    assert captured["resolved_scenario"] is request.resolved_scenario
    assert captured["skip_cli_build"] is False


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


def test_two_vm_loadtest_recipe_reuses_helm_stack_platform_prefix() -> None:
    helm_recipe = build_scenario_recipe("helm-stack")
    recipe = build_scenario_recipe("two-vm-loadtest")
    platform_prefix = (
        "vm.ensure_running",
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
    )
    tail = (
        "loadgen.ensure_running",
        "loadgen.provision_base",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    )

    assert helm_recipe.component_ids[: len(platform_prefix)] == platform_prefix
    assert recipe.component_ids == platform_prefix + tail


def test_two_vm_loadtest_request_backed_flow_task_ids_derive_from_recipe() -> None:
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    flow = build_scenario_flow("two-vm-loadtest", repo_root=Path("/repo"), request=request)
    recipe = build_scenario_recipe("two-vm-loadtest")

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
