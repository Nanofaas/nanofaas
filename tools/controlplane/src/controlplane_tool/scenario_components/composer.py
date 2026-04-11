from __future__ import annotations

from controlplane_tool.scenario_components.bootstrap import (
    K3S_CONFIGURE_REGISTRY,
    K3S_INSTALL,
    REGISTRY_ENSURE_CONTAINER,
    REPO_SYNC_TO_VM,
    VM_ENSURE_RUNNING,
    VM_PROVISION_BASE,
)
from controlplane_tool.scenario_components.helm import (
    HELM_DEPLOY_CONTROL_PLANE,
    HELM_DEPLOY_FUNCTION_RUNTIME,
    K8S_ENSURE_NAMESPACE,
    K8S_WAIT_CONTROL_PLANE_READY,
    K8S_WAIT_FUNCTION_RUNTIME_READY,
)
from controlplane_tool.scenario_components.images import (
    BUILD_CORE,
    BUILD_SELECTED_FUNCTIONS,
)
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition, ScenarioRecipe


def _component(component_id: str, summary: str) -> ScenarioComponentDefinition:
    return ScenarioComponentDefinition(component_id=component_id, summary=summary)


_COMPONENT_LIBRARY: dict[str, ScenarioComponentDefinition] = {
    VM_ENSURE_RUNNING.component_id: VM_ENSURE_RUNNING,
    VM_PROVISION_BASE.component_id: VM_PROVISION_BASE,
    REPO_SYNC_TO_VM.component_id: REPO_SYNC_TO_VM,
    REGISTRY_ENSURE_CONTAINER.component_id: REGISTRY_ENSURE_CONTAINER,
    BUILD_CORE.component_id: BUILD_CORE,
    BUILD_SELECTED_FUNCTIONS.component_id: BUILD_SELECTED_FUNCTIONS,
    K3S_INSTALL.component_id: K3S_INSTALL,
    K3S_CONFIGURE_REGISTRY.component_id: K3S_CONFIGURE_REGISTRY,
    K8S_ENSURE_NAMESPACE.component_id: K8S_ENSURE_NAMESPACE,
    HELM_DEPLOY_CONTROL_PLANE.component_id: HELM_DEPLOY_CONTROL_PLANE,
    HELM_DEPLOY_FUNCTION_RUNTIME.component_id: HELM_DEPLOY_FUNCTION_RUNTIME,
    K8S_WAIT_CONTROL_PLANE_READY.component_id: K8S_WAIT_CONTROL_PLANE_READY,
    K8S_WAIT_FUNCTION_RUNTIME_READY.component_id: K8S_WAIT_FUNCTION_RUNTIME_READY,
    "tests.run_k3s_curl_checks": _component(
        "tests.run_k3s_curl_checks",
        "Run k3s curl checks",
    ),
    "tests.run_k8s_junit": _component(
        "tests.run_k8s_junit",
        "Run Kubernetes JUnit checks",
    ),
    "helm.uninstall_function_runtime": _component(
        "helm.uninstall_function_runtime",
        "Uninstall function runtime",
    ),
    "helm.uninstall_control_plane": _component(
        "helm.uninstall_control_plane",
        "Uninstall control plane",
    ),
    "k8s.delete_namespace": _component(
        "k8s.delete_namespace",
        "Delete Kubernetes namespace",
    ),
    "vm.down": _component("vm.down", "Stop VM"),
    "loadtest.run": _component("loadtest.run", "Run load test"),
    "experiments.autoscaling": _component(
        "experiments.autoscaling",
        "Verify autoscaling experiment",
    ),
    "tests.build_cli_stack_cli": _component(
        "tests.build_cli_stack_cli",
        "Build CLI for CLI stack",
    ),
    "tests.install_cli_stack_platform": _component(
        "tests.install_cli_stack_platform",
        "Install nanofaas platform with CLI",
    ),
    "tests.status_cli_stack_platform": _component(
        "tests.status_cli_stack_platform",
        "Check nanofaas platform status",
    ),
    "tests.apply_cli_stack_functions": _component(
        "tests.apply_cli_stack_functions",
        "Apply CLI stack functions",
    ),
    "tests.list_cli_stack_functions": _component(
        "tests.list_cli_stack_functions",
        "List CLI stack functions",
    ),
    "tests.invoke_cli_stack_functions": _component(
        "tests.invoke_cli_stack_functions",
        "Invoke CLI stack functions",
    ),
    "tests.enqueue_cli_stack_functions": _component(
        "tests.enqueue_cli_stack_functions",
        "Enqueue CLI stack functions",
    ),
    "tests.delete_cli_stack_functions": _component(
        "tests.delete_cli_stack_functions",
        "Delete CLI stack functions",
    ),
    "tests.uninstall_cli_stack_platform": _component(
        "tests.uninstall_cli_stack_platform",
        "Uninstall nanofaas platform with CLI",
    ),
    "tests.verify_cli_stack_status_fails": _component(
        "tests.verify_cli_stack_status_fails",
        "Verify platform status fails after uninstall",
    ),
}


def compose_recipe(recipe: ScenarioRecipe) -> list[ScenarioComponentDefinition]:
    components: list[ScenarioComponentDefinition] = []
    for component_id in recipe.component_ids:
        try:
            components.append(_COMPONENT_LIBRARY[component_id])
        except KeyError as exc:  # pragma: no cover - defensive guard
            raise ValueError(f"Unknown scenario component: {component_id}") from exc
    return components


def recipe_task_ids(recipe: ScenarioRecipe) -> list[str]:
    return list(recipe.component_ids)
