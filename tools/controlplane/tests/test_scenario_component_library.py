from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_components import bootstrap, helm, images
from controlplane_tool.scenario_components.environment import resolve_scenario_environment
from controlplane_tool.scenario_components.composer import (
    compose_recipe,
    recipe_task_ids,
)
from controlplane_tool.scenario_components.models import ScenarioRecipe
from controlplane_tool.scenario_components.operations import RemoteCommandOperation
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.scenario_models import ResolvedFunction, ResolvedScenario


def _managed_context(
    *,
    scenario: str = "helm-stack",
    namespace: str | None = "nanofaas-e2e",
    resolved_scenario: ResolvedScenario | None = None,
):
    request = E2eRequest(
        scenario=scenario,
        runtime="java",
        namespace=namespace,
        resolved_scenario=resolved_scenario,
        vm=None,
    )
    return resolve_scenario_environment(repo_root=Path("/repo"), request=request)


@pytest.mark.parametrize(
    ("planner", "expected_operation_id", "expected_prefix"),
    [
        (bootstrap.plan_vm_ensure_running, "vm.ensure_running", ("multipass", "launch")),
        (bootstrap.plan_vm_provision_base, "vm.provision_base", ("ansible-playbook",)),
        (bootstrap.plan_repo_sync_to_vm, "repo.sync_to_vm", ("multipass", "transfer", "-r")),
    ],
)
def test_bootstrap_component_planners_return_typed_remote_operations(
    planner,
    expected_operation_id: str,
    expected_prefix: tuple[str, ...],
) -> None:
    context = _managed_context()

    operations = planner(context)

    assert operations
    assert all(isinstance(operation, RemoteCommandOperation) for operation in operations)
    assert operations[0].operation_id == expected_operation_id
    assert operations[0].argv[: len(expected_prefix)] == expected_prefix


def test_registry_component_planner_uses_vm_remote_ansible_operation() -> None:
    context = _managed_context()

    operations = bootstrap.plan_registry_ensure_container(context)

    assert len(operations) == 1
    operation = operations[0]
    assert isinstance(operation, RemoteCommandOperation)
    assert operation.operation_id == "registry.ensure_container"
    assert operation.argv[0] == "ansible-playbook"
    assert "ensure-registry.yml" in operation.argv[-1]
    assert not any(part.startswith("bash") for part in operation.argv)


def test_k3s_component_planners_return_typed_remote_operations() -> None:
    context = _managed_context()

    install_operations = bootstrap.plan_k3s_install(context)
    configure_operations = bootstrap.plan_k3s_configure_registry(context)

    assert len(install_operations) == 1
    assert len(configure_operations) == 1
    assert isinstance(install_operations[0], RemoteCommandOperation)
    assert isinstance(configure_operations[0], RemoteCommandOperation)
    assert install_operations[0].operation_id == "k3s.install"
    assert configure_operations[0].operation_id == "k3s.configure_registry"
    assert install_operations[0].argv[0] == "ansible-playbook"
    assert configure_operations[0].argv[0] == "ansible-playbook"
    assert "provision-k3s.yml" in install_operations[0].argv[-1]
    assert "configure-k3s-registry.yml" in configure_operations[0].argv[-1]


def test_image_component_planners_return_typed_operations_for_selected_functions() -> None:
    resolved_scenario = ResolvedScenario(
        name="demo-java",
        base_scenario="helm-stack",
        runtime="java",
        functions=[
            ResolvedFunction(
                key="word-stats-java",
                family="word-stats",
                runtime="java",
                description="Word stats function",
            )
        ],
        function_keys=["word-stats-java"],
    )
    context = _managed_context(resolved_scenario=resolved_scenario)

    core_operations = images.plan_build_core(context)
    selected_operations = images.plan_build_selected_functions(context)

    assert core_operations
    assert selected_operations
    assert all(isinstance(operation, RemoteCommandOperation) for operation in core_operations)
    assert all(
        isinstance(operation, RemoteCommandOperation) for operation in selected_operations
    )
    assert any(context.local_registry in " ".join(operation.argv) for operation in core_operations)
    assert any("word-stats" in " ".join(operation.argv) for operation in selected_operations)


def test_helm_component_planners_use_namespace_and_helm_values() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    namespace_operations = helm.plan_ensure_namespace(context)
    control_plane_operations = helm.plan_deploy_control_plane(context)
    runtime_operations = helm.plan_deploy_function_runtime(context)
    wait_control_plane_operations = helm.plan_wait_control_plane_ready(context)
    wait_runtime_operations = helm.plan_wait_function_runtime_ready(context)

    assert namespace_operations and control_plane_operations and runtime_operations
    assert wait_control_plane_operations and wait_runtime_operations
    assert all(isinstance(operation, RemoteCommandOperation) for operation in namespace_operations)
    assert all(
        isinstance(operation, RemoteCommandOperation) for operation in control_plane_operations
    )
    assert all(isinstance(operation, RemoteCommandOperation) for operation in runtime_operations)
    assert namespace_operations[0].argv[:3] == ("kubectl", "create", "namespace")
    assert "nanofaas-stack" in namespace_operations[0].argv
    assert "helm" in control_plane_operations[0].argv[0]
    assert "nanofaas-stack" in " ".join(control_plane_operations[0].argv)
    assert "nanofaas-stack" in " ".join(runtime_operations[0].argv)
    assert wait_control_plane_operations[0].argv[:3] == ("kubectl", "rollout", "status")
    assert wait_runtime_operations[0].argv[:3] == ("kubectl", "rollout", "status")


def test_compose_recipe_wires_concrete_component_planners() -> None:
    recipe = build_scenario_recipe("helm-stack")
    components = {component.component_id: component for component in compose_recipe(recipe)}

    assert components["k3s.install"].planner is bootstrap.plan_k3s_install
    assert components["k3s.configure_registry"].planner is bootstrap.plan_k3s_configure_registry
    assert components["helm.deploy_control_plane"].planner is helm.plan_deploy_control_plane
    assert components["images.build_core"].planner is images.plan_build_core


def test_compose_recipe_returns_ordered_component_definitions() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")

    components = compose_recipe(recipe)

    assert tuple(component.component_id for component in components) == recipe.component_ids
    assert components[0].component_id == "vm.ensure_running"
    assert components[-1].component_id == "vm.down"
    assert recipe_task_ids(recipe) == list(recipe.component_ids)


def test_compose_recipe_rejects_unknown_component_ids() -> None:
    recipe = ScenarioRecipe(
        name="unknown-component",
        component_ids=("vm.ensure_running", "missing.component"),
    )

    with pytest.raises(ValueError, match="Unknown scenario component: missing.component"):
        compose_recipe(recipe)


def test_build_scenario_recipe_returns_isolated_component_ids() -> None:
    recipe = build_scenario_recipe("cli-stack")
    task_ids = recipe_task_ids(recipe)

    task_ids.append("extra.component")

    assert "extra.component" not in recipe.component_ids
    assert recipe.component_ids[-1] == "tests.verify_cli_stack_status_fails"
