from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_components import bootstrap, cleanup, helm, images
from controlplane_tool.scenario_components import namespace as namespace_components
from controlplane_tool.scenario_components.environment import resolve_scenario_environment
from controlplane_tool.scenario_components.executor import operation_to_plan_step
from controlplane_tool.scenario_components.composer import (
    compose_recipe,
    recipe_task_ids,
)
from controlplane_tool.scenario_components.models import ScenarioRecipe
from controlplane_tool.scenario_components.operations import RemoteCommandOperation
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.scenario_models import ResolvedFunction, ResolvedScenario
from controlplane_tool.vm_models import VmRequest


def _managed_context(
    *,
    scenario: str = "helm-stack",
    namespace: str | None = "nanofaas-e2e",
    runtime: str = "java",
    resolved_scenario: ResolvedScenario | None = None,
):
    request = E2eRequest(
        scenario=scenario,
        runtime=runtime,
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
        (bootstrap.plan_repo_sync_to_vm, "repo.sync_to_vm", ("rsync", "-az", "--delete", "--delete-excluded")),
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


def test_bootstrap_component_planners_cover_external_vm_branch() -> None:
    request = E2eRequest(
        scenario="docker",
        runtime="java",
        vm=VmRequest(lifecycle="external", host="10.0.0.10", user="ubuntu"),
    )
    context = resolve_scenario_environment(repo_root=Path("/repo"), request=request)

    ensure_operations = bootstrap.plan_vm_ensure_running(context)
    sync_operations = bootstrap.plan_repo_sync_to_vm(context)

    assert ensure_operations[0].argv[:3] == ("ssh", "ubuntu@10.0.0.10", "true")
    assert sync_operations[0].argv[:4] == ("rsync", "-az", "--delete", "--delete-excluded")
    assert "--exclude=.venv/" in sync_operations[0].argv
    assert "--exclude=node_modules/" in sync_operations[0].argv
    assert "/repo/" in sync_operations[0].argv
    assert "ubuntu@10.0.0.10:/home/ubuntu/nanofaas/" in sync_operations[0].argv[-1]


def test_repo_sync_to_vm_excludes_local_generated_artifacts_for_managed_vm() -> None:
    operations = bootstrap.plan_repo_sync_to_vm(_managed_context())

    operation = operations[0]
    assert operation.argv[:4] == ("rsync", "-az", "--delete", "--delete-excluded")
    assert "--delete-excluded" in operation.argv
    assert "--exclude=.venv/" in operation.argv
    assert "--exclude=node_modules/" in operation.argv
    assert "--exclude=.git/" in operation.argv
    assert "ubuntu@<multipass-ip:nanofaas-e2e>:/home/ubuntu/nanofaas/" in operation.argv[-1]


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


def test_loadtest_component_planner_installs_k6_with_ansible() -> None:
    operations = bootstrap.plan_loadtest_install_k6(_managed_context())

    assert len(operations) == 1
    operation = operations[0]
    assert operation.operation_id == "loadtest.install_k6"
    assert operation.summary == "Install k6 for load testing"
    assert operation.argv[0] == "ansible-playbook"
    assert "install-k6.yml" in operation.argv[-1]


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


def test_image_component_planners_build_javascript_functions_from_examples_directory() -> None:
    resolved_scenario = ResolvedScenario(
        name="demo-javascript",
        base_scenario="helm-stack",
        runtime="java",
        functions=[
            ResolvedFunction(
                key="word-stats-javascript",
                family="word-stats",
                runtime="javascript",
                description="Word stats function",
            )
        ],
        function_keys=["word-stats-javascript"],
    )
    context = _managed_context(resolved_scenario=resolved_scenario)

    selected_operations = images.plan_build_selected_functions(context)

    assert any(
        "examples/javascript/word-stats/Dockerfile" in " ".join(operation.argv)
        for operation in selected_operations
    )


def test_image_component_planner_uses_rust_branch_for_core_builds() -> None:
    context = _managed_context(runtime="rust")

    core_operations = images.plan_build_core(context)

    assert core_operations[0].argv[:5] == (
        "cargo",
        "build",
        "--release",
        "--manifest-path",
        "control-plane-rust/Cargo.toml",
    )
    assert any("control-plane-rust/Dockerfile" in " ".join(operation.argv) for operation in core_operations)


def test_helm_component_planners_use_namespace_and_helm_values() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    control_plane_operations = helm.plan_deploy_control_plane(context)
    runtime_operations = helm.plan_deploy_function_runtime(context)

    assert control_plane_operations and runtime_operations
    assert all(
        isinstance(operation, RemoteCommandOperation) for operation in control_plane_operations
    )
    assert all(isinstance(operation, RemoteCommandOperation) for operation in runtime_operations)
    assert "--wait" in control_plane_operations[0].argv
    assert "--wait" in runtime_operations[0].argv
    assert "--create-namespace" not in control_plane_operations[0].argv
    assert "--create-namespace" not in runtime_operations[0].argv
    assert "--set" in control_plane_operations[0].argv
    assert "namespace.create=false" in control_plane_operations[0].argv
    assert "helm" in control_plane_operations[0].argv[0]
    assert "nanofaas-stack" in " ".join(control_plane_operations[0].argv)
    assert "nanofaas-stack" in " ".join(runtime_operations[0].argv)


def test_cli_stack_cleanup_uses_cli_release_name() -> None:
    context = _managed_context(scenario="cli-stack")

    operations = cleanup.plan_uninstall_control_plane(context)

    assert operations[0].argv[:3] == ("helm", "uninstall", "nanofaas-cli-stack-e2e")


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
    assert recipe.component_ids[-1] == "vm.down"


def test_operation_executor_translates_typed_operations_to_plan_steps() -> None:
    request = E2eRequest(scenario="cli-stack", cleanup_vm=False)
    operation = RemoteCommandOperation(
        operation_id="vm.down",
        summary="Tear down VM",
        argv=("multipass", "delete", "nanofaas-e2e"),
    )

    step = operation_to_plan_step(operation, request=request)

    assert step.summary == "Tear down VM"
    assert step.command == ["echo", "Skipping VM teardown (--no-cleanup-vm)"]


def test_operation_executor_inverts_cli_cleanup_status_check() -> None:
    request = E2eRequest(scenario="cli-stack")
    operation = RemoteCommandOperation(
        operation_id="cleanup.verify_cli_platform_status_fails",
        summary="Verify CLI platform status fails after cleanup",
        argv=("nanofaas-cli", "platform", "status", "-n", "nanofaas-cli-stack-e2e"),
        execution_target="vm",
    )

    step = operation_to_plan_step(
        operation,
        request=request,
        on_remote_exec=lambda argv, env: (_ for _ in ()).throw(RuntimeError("expected failure")),  # noqa: ARG005
    )

    step.action()


def test_helm_stack_tail_runs_loadtest_inside_vm() -> None:
    from controlplane_tool.scenario_components.verification import plan_loadtest_run

    context = _managed_context()

    loadtest_operation = plan_loadtest_run(context)[0]

    assert loadtest_operation.execution_target == "vm"
    assert loadtest_operation.argv[:4] == (
        "uv",
        "run",
        "--project",
        "tools/controlplane",
    )


def test_helm_stack_tail_keeps_autoscaling_on_host() -> None:
    from controlplane_tool.scenario_components.verification import plan_autoscaling_experiment

    context = _managed_context()

    autoscaling_operation = plan_autoscaling_experiment(context)[0]

    assert autoscaling_operation.execution_target == "host"
    assert autoscaling_operation.argv[:4] == (
        "uv",
        "run",
        "--project",
        "/repo/tools/controlplane",
    )


def test_namespace_component_installs_namespace_release_in_default_namespace() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    operations = namespace_components.plan_install_namespace(context)

    assert len(operations) == 1
    assert operations[0].operation_id == "namespace.install"
    assert operations[0].argv == (
        "helm",
        "upgrade",
        "--install",
        "nanofaas-stack-namespace",
        "helm/nanofaas-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        "2m",
        "--set",
        "namespace.name=nanofaas-stack",
    )


def test_namespace_component_uninstalls_namespace_release_last() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    operations = namespace_components.plan_uninstall_namespace(context)

    assert len(operations) == 1
    assert operations[0].operation_id == "namespace.uninstall"
    assert operations[0].argv == (
        "helm",
        "uninstall",
        "nanofaas-stack-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        "5m",
        "--ignore-not-found",
    )
