from __future__ import annotations

from controlplane_tool.scenario_components.models import ScenarioComponentDefinition, ScenarioRecipe


def _component(component_id: str, summary: str) -> ScenarioComponentDefinition:
    return ScenarioComponentDefinition(component_id=component_id, summary=summary)


_COMPONENT_LIBRARY: dict[str, ScenarioComponentDefinition] = {
    "vm.ensure_running": _component("vm.ensure_running", "Ensure VM is running"),
    "vm.provision_base": _component("vm.provision_base", "Provision base VM dependencies"),
    "repo.sync_to_vm": _component("repo.sync_to_vm", "Sync repository into VM"),
    "registry.ensure_container": _component(
        "registry.ensure_container",
        "Ensure local registry container is running",
    ),
    "images.build_core": _component("images.build_core", "Build core images"),
    "images.build_selected_functions": _component(
        "images.build_selected_functions",
        "Build selected function images",
    ),
    "k3s.install": _component("k3s.install", "Install k3s"),
    "k3s.configure_registry": _component(
        "k3s.configure_registry",
        "Configure k3s registry access",
    ),
    "k8s.ensure_namespace": _component(
        "k8s.ensure_namespace",
        "Ensure Kubernetes namespace exists",
    ),
    "helm.deploy_control_plane": _component(
        "helm.deploy_control_plane",
        "Deploy control plane with Helm",
    ),
    "helm.deploy_function_runtime": _component(
        "helm.deploy_function_runtime",
        "Deploy function runtime with Helm",
    ),
    "k8s.wait_control_plane_ready": _component(
        "k8s.wait_control_plane_ready",
        "Wait for control plane readiness",
    ),
    "k8s.wait_function_runtime_ready": _component(
        "k8s.wait_function_runtime_ready",
        "Wait for function runtime readiness",
    ),
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
