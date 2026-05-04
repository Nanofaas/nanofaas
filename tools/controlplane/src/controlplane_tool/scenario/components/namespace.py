from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation

NAMESPACE_RELEASE_NAMESPACE = "default"


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


def namespace_release_name(namespace: str) -> str:
    return f"{namespace}-namespace"


def plan_install_namespace(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="namespace.install",
            summary="Install namespace Helm release",
            argv=(
                "helm",
                "upgrade",
                "--install",
                namespace_release_name(namespace),
                "helm/nanofaas-namespace",
                "-n",
                NAMESPACE_RELEASE_NAMESPACE,
                "--wait",
                "--timeout",
                "2m",
                "--set",
                f"namespace.name={namespace}",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_uninstall_namespace(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="namespace.uninstall",
            summary="Uninstall namespace Helm release",
            argv=(
                "helm",
                "uninstall",
                namespace_release_name(namespace),
                "-n",
                NAMESPACE_RELEASE_NAMESPACE,
                "--wait",
                "--timeout",
                "5m",
                "--ignore-not-found",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


NAMESPACE_INSTALL = ScenarioComponentDefinition(
    component_id="namespace.install",
    summary="Install namespace Helm release",
    planner=plan_install_namespace,
)

NAMESPACE_UNINSTALL = ScenarioComponentDefinition(
    component_id="namespace.uninstall",
    summary="Uninstall namespace Helm release",
    planner=plan_uninstall_namespace,
)
