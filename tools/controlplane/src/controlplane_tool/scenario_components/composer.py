from __future__ import annotations

from controlplane_tool.scenario_components.bootstrap import (
    K3S_CONFIGURE_REGISTRY,
    K3S_INSTALL,
    REGISTRY_ENSURE_CONTAINER,
    REPO_SYNC_TO_VM,
    VM_ENSURE_RUNNING,
    VM_PROVISION_BASE,
)
from controlplane_tool.scenario_components.cleanup import (
    DELETE_NAMESPACE,
    UNINSTALL_CONTROL_PLANE,
    UNINSTALL_FUNCTION_RUNTIME,
    VERIFY_CLI_PLATFORM_STATUS_FAILS,
    VM_DOWN,
)
from controlplane_tool.scenario_components.cli import (
    CLI_BUILD_INSTALL_DIST,
    CLI_FN_APPLY_SELECTED,
    CLI_FN_DELETE_SELECTED,
    CLI_FN_ENQUEUE_SELECTED,
    CLI_FN_INVOKE_SELECTED,
    CLI_FN_LIST_SELECTED,
    CLI_PLATFORM_INSTALL,
    CLI_PLATFORM_STATUS,
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
    CLI_BUILD_INSTALL_DIST.component_id: CLI_BUILD_INSTALL_DIST,
    CLI_PLATFORM_INSTALL.component_id: CLI_PLATFORM_INSTALL,
    CLI_PLATFORM_STATUS.component_id: CLI_PLATFORM_STATUS,
    CLI_FN_APPLY_SELECTED.component_id: CLI_FN_APPLY_SELECTED,
    CLI_FN_LIST_SELECTED.component_id: CLI_FN_LIST_SELECTED,
    CLI_FN_INVOKE_SELECTED.component_id: CLI_FN_INVOKE_SELECTED,
    CLI_FN_ENQUEUE_SELECTED.component_id: CLI_FN_ENQUEUE_SELECTED,
    CLI_FN_DELETE_SELECTED.component_id: CLI_FN_DELETE_SELECTED,
    UNINSTALL_CONTROL_PLANE.component_id: UNINSTALL_CONTROL_PLANE,
    UNINSTALL_FUNCTION_RUNTIME.component_id: UNINSTALL_FUNCTION_RUNTIME,
    DELETE_NAMESPACE.component_id: DELETE_NAMESPACE,
    VERIFY_CLI_PLATFORM_STATUS_FAILS.component_id: VERIFY_CLI_PLATFORM_STATUS_FAILS,
    VM_DOWN.component_id: VM_DOWN,
    "tests.run_k3s_curl_checks": _component(
        "tests.run_k3s_curl_checks",
        "Run k3s curl checks",
    ),
    "tests.run_k8s_junit": _component(
        "tests.run_k8s_junit",
        "Run Kubernetes JUnit checks",
    ),
    "loadtest.run": _component("loadtest.run", "Run load test"),
    "experiments.autoscaling": _component(
        "experiments.autoscaling",
        "Verify autoscaling experiment",
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
