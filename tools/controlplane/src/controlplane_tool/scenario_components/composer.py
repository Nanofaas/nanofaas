"""
composer.py — Assembles scenario recipes from registered components.
"""
from __future__ import annotations

from controlplane_tool.scenario_components.models import (
    ScenarioComponentDefinition,
    ScenarioRecipe,
    _planner_not_implemented,
)
from controlplane_tool.scenario_components.registry import ComponentRegistry

_registry = ComponentRegistry()


def _load_all_components() -> None:
    """Import all component modules and register their constants."""
    from controlplane_tool.scenario_components.bootstrap import (
        VM_ENSURE_RUNNING, VM_PROVISION_BASE, REPO_SYNC_TO_VM,
        REGISTRY_ENSURE_CONTAINER, K3S_INSTALL, K3S_CONFIGURE_REGISTRY,
        LOADTEST_INSTALL_K6,
    )
    from controlplane_tool.scenario_components.cleanup import (
        UNINSTALL_CONTROL_PLANE, UNINSTALL_FUNCTION_RUNTIME,
        VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
    )
    from controlplane_tool.scenario_components.cli import (
        CLI_BUILD_INSTALL_DIST, CLI_PLATFORM_INSTALL, CLI_PLATFORM_STATUS,
        CLI_FN_APPLY_SELECTED, CLI_FN_LIST_SELECTED, CLI_FN_INVOKE_SELECTED,
        CLI_FN_ENQUEUE_SELECTED, CLI_FN_DELETE_SELECTED,
    )
    from controlplane_tool.scenario_components.helm import (
        HELM_DEPLOY_CONTROL_PLANE, HELM_DEPLOY_FUNCTION_RUNTIME,
    )
    from controlplane_tool.scenario_components.images import BUILD_CORE, BUILD_SELECTED_FUNCTIONS
    from controlplane_tool.scenario_components.namespace import (
        NAMESPACE_INSTALL,
        NAMESPACE_UNINSTALL,
    )
    from controlplane_tool.scenario_components.verification import (
        plan_run_k3s_curl_checks, plan_run_k8s_junit,
        plan_loadtest_run, plan_autoscaling_experiment,
    )

    for comp in [
        VM_ENSURE_RUNNING, VM_PROVISION_BASE, REPO_SYNC_TO_VM,
        REGISTRY_ENSURE_CONTAINER, K3S_INSTALL, K3S_CONFIGURE_REGISTRY,
        LOADTEST_INSTALL_K6,
        NAMESPACE_INSTALL,
        HELM_DEPLOY_CONTROL_PLANE, HELM_DEPLOY_FUNCTION_RUNTIME,
        CLI_BUILD_INSTALL_DIST, CLI_PLATFORM_INSTALL, CLI_PLATFORM_STATUS,
        CLI_FN_APPLY_SELECTED, CLI_FN_LIST_SELECTED, CLI_FN_INVOKE_SELECTED,
        CLI_FN_ENQUEUE_SELECTED, CLI_FN_DELETE_SELECTED,
        BUILD_CORE, BUILD_SELECTED_FUNCTIONS,
        UNINSTALL_FUNCTION_RUNTIME, UNINSTALL_CONTROL_PLANE,
        NAMESPACE_UNINSTALL, VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
    ]:
        _registry.register(comp)

    def _component(component_id: str, summary: str, planner=_planner_not_implemented) -> ScenarioComponentDefinition:
        return ScenarioComponentDefinition(component_id=component_id, summary=summary, planner=planner)

    for comp in [
        _component("tests.run_k3s_curl_checks", "Run k3s curl checks", plan_run_k3s_curl_checks),
        _component("tests.run_k8s_junit", "Run Kubernetes JUnit checks", plan_run_k8s_junit),
        _component("loadtest.run", "Run k6 load test", plan_loadtest_run),
        _component("experiments.autoscaling", "Verify autoscaling experiment", plan_autoscaling_experiment),
    ]:
        _registry.register(comp)


# Eagerly load all components once at module init
_load_all_components()

# ── public API (unchanged) ───────────────────────────────────────────────────

def compose_recipe(recipe: ScenarioRecipe) -> list[ScenarioComponentDefinition]:
    return [_registry.get(component_id) for component_id in recipe.component_ids]


def recipe_task_ids(recipe: ScenarioRecipe) -> list[str]:
    return list(recipe.component_ids)
