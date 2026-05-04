"""Legacy bridge for VM-backed scenario prelude planning.

The reusable helpers live in the scenario component modules. This module keeps
the current `E2eRunner` compatibility surface until the runner is migrated.
"""

from __future__ import annotations

import shlex
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any, cast

from controlplane_tool.scenario.components import bootstrap as bootstrap_components
from controlplane_tool.scenario.components import helm as helm_components
from controlplane_tool.scenario.components import images as image_components
from controlplane_tool.scenario.components import namespace as namespace_components
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.operations import RemoteCommandOperation
from controlplane_tool.scenario.scenario_models import ResolvedScenario
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest


def control_image(local_registry: str) -> str:
    return image_components.control_image(local_registry)


def runtime_image(local_registry: str) -> str:
    return image_components.runtime_image(local_registry)


def image_parts(image: str) -> tuple[str, str]:
    repository, separator, tag = image.rpartition(":")
    if not separator:
        return image, "latest"
    return repository, tag


def function_image_specs(
    resolved_scenario: ResolvedScenario | None,
    fallback_runtime_image: str,
) -> list[tuple[str, str, str]]:
    return image_components.function_image_specs(resolved_scenario, fallback_runtime_image)


def control_plane_helm_values(*, namespace: str, control_plane_image: str) -> dict[str, str]:
    return helm_components.control_plane_helm_values(
        namespace=namespace,
        control_plane_image=control_plane_image,
    )


def function_runtime_helm_values(*, function_runtime_image: str) -> dict[str, str]:
    return helm_components.function_runtime_helm_values(
        function_runtime_image=function_runtime_image,
    )


@dataclass(frozen=True)
class VmClusterPreludePlan:
    ensure_running: ShellExecutionResult
    install_dependencies: ShellExecutionResult
    sync_project: ShellExecutionResult
    ensure_registry: ShellExecutionResult
    build_core_script: str
    build_selected_functions_script: str | None
    install_k3s: ShellExecutionResult
    configure_registry: ShellExecutionResult
    install_namespace_script: str
    deploy_control_plane_script: str
    deploy_function_runtime_script: str


def _shell_result(operation: RemoteCommandOperation) -> ShellExecutionResult:
    return ShellExecutionResult(
        command=list(operation.argv),
        return_code=0,
        stdout="",
        stderr="",
        env=dict(operation.env),
        dry_run=True,
    )


def _render_operation(operation: RemoteCommandOperation, *, remote_dir: str | None = None) -> str:
    prefixes = [f"{name}={shlex.quote(value)}" for name, value in operation.env.items()]
    command = shlex.join(list(operation.argv))
    rendered = " ".join([*prefixes, command]) if prefixes else command
    if remote_dir is None:
        return rendered
    return f"cd {shlex.quote(remote_dir)} && {rendered}"


def _render_operations(
    operations: Iterable[RemoteCommandOperation],
    *,
    remote_dir: str | None = None,
) -> str:
    rendered = [_render_operation(operation, remote_dir=remote_dir) for operation in operations]
    return " && ".join(rendered)


def build_vm_cluster_prelude_plan(
    *,
    vm: VmOrchestrator,
    vm_request: VmRequest,
    namespace: str,
    local_registry: str,
    runtime: str,
    resolved_scenario: ResolvedScenario | None,
) -> VmClusterPreludePlan:
    scenario_context = ScenarioExecutionContext(
        repo_root=vm.repo_root,
        request=cast(Any, vm_request),
        scenario_name="legacy",
        runtime=runtime,
        namespace=namespace,
        local_registry=local_registry,
        resolved_scenario=resolved_scenario,
        vm_request=vm_request,
        cleanup_vm=True,
    )
    remote_dir = vm.remote_project_dir(vm_request)
    bootstrap_plan = {
        operation.operation_id: operation
        for operation in (
            *bootstrap_components.plan_vm_ensure_running(scenario_context),
            *bootstrap_components.plan_vm_provision_base(scenario_context),
            *bootstrap_components.plan_repo_sync_to_vm(scenario_context),
            *bootstrap_components.plan_registry_ensure_container(scenario_context),
            *bootstrap_components.plan_k3s_install(scenario_context),
            *bootstrap_components.plan_k3s_configure_registry(scenario_context),
        )
    }
    image_plan = {
        operation.operation_id: operation
        for operation in (
            *image_components.plan_build_core(scenario_context),
            *image_components.plan_build_selected_functions(scenario_context),
        )
    }
    helm_plan = {
        operation.operation_id: operation
        for operation in (
            *helm_components.plan_deploy_control_plane(scenario_context),
            *helm_components.plan_deploy_function_runtime(scenario_context),
        )
    }
    namespace_plan = {
        operation.operation_id: operation
        for operation in namespace_components.plan_install_namespace(scenario_context)
    }
    selected_function_operations = tuple(
        operation
        for operation in image_plan.values()
        if operation.operation_id.startswith("images.build_selected_functions.")
    )

    return VmClusterPreludePlan(
        ensure_running=_shell_result(bootstrap_plan["vm.ensure_running"]),
        install_dependencies=_shell_result(bootstrap_plan["vm.provision_base"]),
        sync_project=_shell_result(bootstrap_plan["repo.sync_to_vm"]),
        ensure_registry=_shell_result(bootstrap_plan["registry.ensure_container"]),
        build_core_script=_render_operations(
            (
                image_plan["images.build_core.boot_jars"],
                image_plan["images.build_core.control_image"],
                image_plan["images.build_core.runtime_image"],
                image_plan["images.build_core.push_control_image"],
                image_plan["images.build_core.push_runtime_image"],
            ),
            remote_dir=remote_dir,
        ),
        build_selected_functions_script=(
            _render_operations(selected_function_operations, remote_dir=remote_dir)
            if selected_function_operations
            else None
        ),
        install_k3s=_shell_result(bootstrap_plan["k3s.install"]),
        configure_registry=_shell_result(bootstrap_plan["k3s.configure_registry"]),
        install_namespace_script=_render_operations(
            (namespace_plan["namespace.install"],),
            remote_dir=remote_dir,
        ),
        deploy_control_plane_script=_render_operations(
            (helm_plan["helm.deploy_control_plane"],),
            remote_dir=remote_dir,
        ),
        deploy_function_runtime_script=_render_operations(
            (helm_plan["helm.deploy_function_runtime"],),
            remote_dir=remote_dir,
        ),
    )
