from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.vm_cluster_workflows import (
    control_plane_helm_values,
    control_image,
    function_runtime_helm_values,
    runtime_image,
)


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _effective_namespace(context: ScenarioExecutionContext) -> str:
    if context.namespace:
        return context.namespace
    if context.resolved_scenario is not None and context.resolved_scenario.namespace:
        return context.resolved_scenario.namespace
    return "nanofaas-e2e"


def _kubeconfig_path(context: ScenarioExecutionContext) -> str:
    vm_request = context.vm_request
    home = vm_request.home
    if home:
        return f"{home}/.kube/config"
    if vm_request.user == "root":
        return "/root/.kube/config"
    return f"/home/{vm_request.user}/.kube/config"


def _set_args(values: Mapping[str, str]) -> tuple[str, ...]:
    args: list[str] = []
    for key, value in values.items():
        args.extend(["--set", f"{key}={value}"])
    return tuple(args)


def plan_ensure_namespace(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="k8s.ensure_namespace",
            summary="Ensure Kubernetes namespace exists",
            argv=("kubectl", "create", "namespace", namespace),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


def plan_deploy_control_plane(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    values = control_plane_helm_values(
        namespace=namespace,
        control_plane_image=control_image(context.local_registry),
    )
    return (
        RemoteCommandOperation(
            operation_id="helm.deploy_control_plane",
            summary="Deploy control plane with Helm",
            argv=(
                "helm",
                "upgrade",
                "--install",
                "control-plane",
                "helm/nanofaas",
                "-n",
                namespace,
                "--wait",
                "--timeout",
                "5m",
                *_set_args(values),
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


def plan_deploy_function_runtime(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    values = function_runtime_helm_values(
        function_runtime_image=runtime_image(context.local_registry),
    )
    return (
        RemoteCommandOperation(
            operation_id="helm.deploy_function_runtime",
            summary="Deploy function runtime with Helm",
            argv=(
                "helm",
                "upgrade",
                "--install",
                "function-runtime",
                "helm/nanofaas-runtime",
                "-n",
                namespace,
                "--wait",
                "--timeout",
                "3m",
                *_set_args(values),
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


def plan_wait_control_plane_ready(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="k8s.wait_control_plane_ready",
            summary="Wait for control plane readiness",
            argv=(
                "kubectl",
                "rollout",
                "status",
                "deployment/nanofaas-control-plane",
                "-n",
                namespace,
                "--timeout",
                "180s",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


def plan_wait_function_runtime_ready(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="k8s.wait_function_runtime_ready",
            summary="Wait for function runtime readiness",
            argv=(
                "kubectl",
                "rollout",
                "status",
                "deployment/function-runtime",
                "-n",
                namespace,
                "--timeout",
                "120s",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


K8S_ENSURE_NAMESPACE = ScenarioComponentDefinition(
    component_id="k8s.ensure_namespace",
    summary="Ensure Kubernetes namespace exists",
    planner=plan_ensure_namespace,
)

HELM_DEPLOY_CONTROL_PLANE = ScenarioComponentDefinition(
    component_id="helm.deploy_control_plane",
    summary="Deploy control plane with Helm",
    planner=plan_deploy_control_plane,
)

HELM_DEPLOY_FUNCTION_RUNTIME = ScenarioComponentDefinition(
    component_id="helm.deploy_function_runtime",
    summary="Deploy function runtime with Helm",
    planner=plan_deploy_function_runtime,
)

K8S_WAIT_CONTROL_PLANE_READY = ScenarioComponentDefinition(
    component_id="k8s.wait_control_plane_ready",
    summary="Wait for control plane readiness",
    planner=plan_wait_control_plane_ready,
)

K8S_WAIT_FUNCTION_RUNTIME_READY = ScenarioComponentDefinition(
    component_id="k8s.wait_function_runtime_ready",
    summary="Wait for function runtime readiness",
    planner=plan_wait_function_runtime_ready,
)
