from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.components.verification import plan_verify_cli_platform_status_fails


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _namespace(context: ScenarioExecutionContext) -> str:
    if context.namespace:
        return context.namespace
    if context.resolved_scenario is not None and context.resolved_scenario.namespace:
        return context.resolved_scenario.namespace
    return "nanofaas-e2e"


def _control_plane_release(context: ScenarioExecutionContext) -> str:
    if context.release:
        return context.release
    return "control-plane"


def _kubeconfig_path(context: ScenarioExecutionContext) -> str:
    home = context.vm_request.home
    if home:
        return f"{home}/.kube/config"
    if context.vm_request.user == "root":
        return "/root/.kube/config"
    return f"/home/{context.vm_request.user}/.kube/config"


def plan_uninstall_control_plane(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="cleanup.uninstall_control_plane",
            summary="Uninstall control plane with Helm",
            argv=(
                "helm",
                "uninstall",
                _control_plane_release(context),
                "-n",
                namespace,
                "--wait",
                "--timeout",
                "5m",
                "--ignore-not-found",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_uninstall_function_runtime(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="cleanup.uninstall_function_runtime",
            summary="Uninstall function runtime with Helm",
            argv=(
                "helm",
                "uninstall",
                "function-runtime",
                "-n",
                namespace,
                "--wait",
                "--timeout",
                "5m",
                "--ignore-not-found",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_vm_down(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    vm_request = context.vm_request
    if vm_request.lifecycle == "external":
        return (
            RemoteCommandOperation(
                operation_id="vm.down",
                summary="Teardown VM",
                argv=("echo", "Skipping teardown for external VM lifecycle"),
            ),
        )
    return (
        RemoteCommandOperation(
            operation_id="vm.down",
            summary="Teardown VM",
            argv=("multipass", "delete", vm_request.name or "nanofaas-e2e"),
        ),
    )


UNINSTALL_CONTROL_PLANE = ScenarioComponentDefinition(
    component_id="cleanup.uninstall_control_plane",
    summary="Uninstall control plane with Helm",
    planner=plan_uninstall_control_plane,
)

UNINSTALL_FUNCTION_RUNTIME = ScenarioComponentDefinition(
    component_id="cleanup.uninstall_function_runtime",
    summary="Uninstall function runtime with Helm",
    planner=plan_uninstall_function_runtime,
)

VM_DOWN = ScenarioComponentDefinition(
    component_id="vm.down",
    summary="Teardown VM",
    planner=plan_vm_down,
)

VERIFY_CLI_PLATFORM_STATUS_FAILS = ScenarioComponentDefinition(
    component_id="cleanup.verify_cli_platform_status_fails",
    summary="Verify CLI platform status fails after cleanup",
    planner=plan_verify_cli_platform_status_fails,
)
