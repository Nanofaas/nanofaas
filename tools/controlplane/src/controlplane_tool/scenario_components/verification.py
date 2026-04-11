from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.cli_platform_workflow import platform_status_command
from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _namespace(context: ScenarioExecutionContext) -> str:
    if context.namespace:
        return context.namespace
    if context.resolved_scenario is not None and context.resolved_scenario.namespace:
        return context.resolved_scenario.namespace
    return "nanofaas-e2e"


def _kubeconfig_path(context: ScenarioExecutionContext) -> str:
    home = context.vm_request.home
    if home:
        return f"{home}/.kube/config"
    if context.vm_request.user == "root":
        return "/root/.kube/config"
    return f"/home/{context.vm_request.user}/.kube/config"


def plan_verify_cli_platform_status_fails(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="cleanup.verify_cli_platform_status_fails",
            summary="Verify CLI platform status fails after cleanup",
            argv=tuple(platform_status_command(namespace)),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )
