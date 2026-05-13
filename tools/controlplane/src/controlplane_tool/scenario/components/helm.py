from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.components.images import control_image, runtime_image
from controlplane_tool.scenario.two_vm_loadtest_config import (
    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
)


def _image_parts(image: str) -> tuple[str, str]:
    repository, separator, tag = image.rpartition(":")
    if not separator:
        return image, "latest"
    return repository, tag


def control_plane_helm_values(
    *,
    namespace: str,
    control_plane_image: str,
    expose_node_port: bool = False,
) -> dict[str, str]:
    repository, tag = _image_parts(control_plane_image)
    callback_url = f"http://control-plane.{namespace}.svc.cluster.local:8080/v1/internal/executions"
    values = {
        "namespace.create": "false",
        "namespace.name": namespace,
        "controlPlane.image.repository": repository,
        "controlPlane.image.tag": tag,
        "controlPlane.image.pullPolicy": "Always",
        "demos.enabled": "false",
        "prometheus.create": "false",
    }
    extra_env = [
        ("NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND", "k8s"),
        ("NANOFAAS_K8S_CALLBACK_URL", callback_url),
        ("SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("SYNC_QUEUE_ADMISSION_ENABLED", "false"),
        ("SYNC_QUEUE_MAX_DEPTH", "1"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
        ("SYNC_QUEUE_MAX_ESTIMATED_WAIT", "2s"),
        ("SYNC_QUEUE_MAX_QUEUE_WAIT", "5s"),
        ("SYNC_QUEUE_RETRY_AFTER_SECONDS", "2"),
        ("SYNC_QUEUE_THROUGHPUT_WINDOW", "10s"),
        ("SYNC_QUEUE_PER_FUNCTION_MIN_SAMPLES", "1"),
    ]
    for index, (name, value) in enumerate(extra_env):
        values[f"controlPlane.extraEnv[{index}].name"] = name
        values[f"controlPlane.extraEnv[{index}].value"] = value
    if expose_node_port:
        values["controlPlane.service.type"] = "NodePort"
        values["controlPlane.service.nodePorts.http"] = str(TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT)
        values["controlPlane.service.nodePorts.actuator"] = str(TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT)
    return values


def function_runtime_helm_values(*, function_runtime_image: str) -> dict[str, str]:
    repository, tag = _image_parts(function_runtime_image)
    return {
        "functionRuntime.image.repository": repository,
        "functionRuntime.image.tag": tag,
        "functionRuntime.image.pullPolicy": "Always",
    }


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


def plan_deploy_control_plane(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _effective_namespace(context)
    values = control_plane_helm_values(
        namespace=namespace,
        control_plane_image=control_image(context.local_registry),
        expose_node_port=context.scenario_name == "two-vm-loadtest",
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
            execution_target="vm",
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
            execution_target="vm",
        ),
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
