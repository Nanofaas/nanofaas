"""Shared image/value builders for scenario component planners.

This module still carries a legacy compatibility bridge for the current
E2eRunner prelude path, but the reusable planning logic now lives in the
scenario_components package.
"""

from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.scenario_tasks import (
    build_core_images_vm_script,
    build_function_images_vm_script,
    helm_upgrade_install_vm_script,
    kubectl_create_namespace_vm_script,
    kubectl_rollout_status_vm_script,
)
from controlplane_tool.shell_backend import ShellExecutionResult
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


def control_image(local_registry: str) -> str:
    return f"{local_registry}/nanofaas/control-plane:e2e"


def runtime_image(local_registry: str) -> str:
    return f"{local_registry}/nanofaas/function-runtime:e2e"


def image_parts(image: str) -> tuple[str, str]:
    repository, separator, tag = image.rpartition(":")
    if not separator:
        return image, "latest"
    return repository, tag


def function_image_specs(
    resolved_scenario: ResolvedScenario | None,
    fallback_runtime_image: str,
) -> list[tuple[str, str, str]]:
    if resolved_scenario is None:
        return []

    function_specs: list[tuple[str, str, str]] = []
    for function in resolved_scenario.functions:
        if function.runtime == "fixture" or function.family is None:
            continue
        image = function.image or fallback_runtime_image
        function_specs.append((image, function.runtime, function.family))
    return function_specs


def control_plane_helm_values(*, namespace: str, control_plane_image: str) -> dict[str, str]:
    repository, tag = image_parts(control_plane_image)
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
    return values


def function_runtime_helm_values(*, function_runtime_image: str) -> dict[str, str]:
    repository, tag = image_parts(function_runtime_image)
    return {
        "functionRuntime.image.repository": repository,
        "functionRuntime.image.tag": tag,
        "functionRuntime.image.pullPolicy": "Always",
    }


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
    create_namespace_script: str
    deploy_control_plane_script: str
    deploy_function_runtime_script: str
    wait_control_plane_script: str
    wait_function_runtime_script: str


def build_vm_cluster_prelude_plan(
    *,
    vm: VmOrchestrator,
    vm_request: VmRequest,
    namespace: str,
    local_registry: str,
    runtime: str,
    resolved_scenario: ResolvedScenario | None,
) -> VmClusterPreludePlan:
    # Legacy bridge until E2eRunner is moved onto the component library.
    remote_dir = vm.remote_project_dir(vm_request)
    kubeconfig_path = vm.kubeconfig_path(vm_request)
    resolved_control_image = control_image(local_registry)
    resolved_runtime_image = runtime_image(local_registry)
    selected_function_specs = function_image_specs(resolved_scenario, resolved_runtime_image)

    build_selected_functions_script = (
        build_function_images_vm_script(
            remote_dir=remote_dir,
            functions=selected_function_specs,
            sudo=True,
            push=True,
        )
        if selected_function_specs
        else None
    )

    return VmClusterPreludePlan(
        ensure_running=vm.ensure_running(vm_request, dry_run=True),
        install_dependencies=vm.install_dependencies(vm_request, install_helm=True, dry_run=True),
        sync_project=vm.sync_project(vm_request, dry_run=True),
        ensure_registry=vm.ensure_registry_container(
            vm_request,
            registry=local_registry,
            dry_run=True,
        ),
        build_core_script=build_core_images_vm_script(
            remote_dir=remote_dir,
            control_image=resolved_control_image,
            runtime_image=resolved_runtime_image,
            runtime=runtime,
            mode="docker",
            sudo=True,
            build_jars=True,
        ),
        build_selected_functions_script=build_selected_functions_script,
        install_k3s=vm.install_k3s(vm_request, dry_run=True),
        configure_registry=vm.configure_k3s_registry(
            vm_request,
            registry=local_registry,
            dry_run=True,
        ),
        create_namespace_script=kubectl_create_namespace_vm_script(
            remote_dir=remote_dir,
            namespace=namespace,
            kubeconfig_path=kubeconfig_path,
        ),
        deploy_control_plane_script=helm_upgrade_install_vm_script(
            remote_dir=remote_dir,
            release="control-plane",
            chart="helm/nanofaas",
            namespace=namespace,
            values=control_plane_helm_values(
                namespace=namespace,
                control_plane_image=resolved_control_image,
            ),
            kubeconfig_path=kubeconfig_path,
            timeout="5m",
        ),
        deploy_function_runtime_script=helm_upgrade_install_vm_script(
            remote_dir=remote_dir,
            release="function-runtime",
            chart="helm/nanofaas-runtime",
            namespace=namespace,
            values=function_runtime_helm_values(
                function_runtime_image=resolved_runtime_image,
            ),
            kubeconfig_path=kubeconfig_path,
            timeout="3m",
        ),
        wait_control_plane_script=kubectl_rollout_status_vm_script(
            remote_dir=remote_dir,
            namespace=namespace,
            deployment="nanofaas-control-plane",
            kubeconfig_path=kubeconfig_path,
            timeout=180,
        ),
        wait_function_runtime_script=kubectl_rollout_status_vm_script(
            remote_dir=remote_dir,
            namespace=namespace,
            deployment="function-runtime",
            kubeconfig_path=kubeconfig_path,
            timeout=120,
        ),
    )
