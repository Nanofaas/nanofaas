"""
composer.py — Assembles scenario recipes from registered components.
"""
from __future__ import annotations

from workflow_tasks.components.models import (
    ScenarioComponentDefinition,
    ScenarioRecipe,
    _planner_not_implemented,
)
from workflow_tasks.components.registry import ComponentRegistry

_registry = ComponentRegistry()


def _load_all_components() -> None:
    """Import all component modules and register their constants."""
    from workflow_tasks.components.bootstrap import (
        VM_ENSURE_RUNNING, VM_PROVISION_BASE, REPO_SYNC_TO_VM,
        REGISTRY_ENSURE_CONTAINER, K3S_INSTALL, K3S_CONFIGURE_REGISTRY,
        LOADTEST_INSTALL_K6,
    )
    from workflow_tasks.components.cleanup import (
        UNINSTALL_CONTROL_PLANE, UNINSTALL_FUNCTION_RUNTIME,
        VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
    )
    from controlplane_tool.scenario.components.cli import (
        CLI_BUILD_INSTALL_DIST, CLI_PLATFORM_INSTALL, CLI_PLATFORM_STATUS,
        CLI_FN_APPLY_SELECTED, CLI_FN_LIST_SELECTED, CLI_FN_INVOKE_SELECTED,
        CLI_FN_ENQUEUE_SELECTED, CLI_FN_DELETE_SELECTED,
    )
    from workflow_tasks.components.helm import (
        HELM_DEPLOY_CONTROL_PLANE, HELM_DEPLOY_FUNCTION_RUNTIME,
    )
    from workflow_tasks.components.images import BUILD_CORE, BUILD_SELECTED_FUNCTIONS
    from workflow_tasks.components.namespace import (
        NAMESPACE_INSTALL,
        NAMESPACE_UNINSTALL,
    )
    from controlplane_tool.scenario.components.two_vm_loadtest import (
        LOADGEN_DOWN,
        LOADGEN_ENSURE_RUNNING,
        LOADGEN_INSTALL_K6,
        LOADGEN_PROVISION_BASE,
        LOADGEN_RUN_K6,
        LOADTEST_WRITE_REPORT,
        METRICS_PROMETHEUS_SNAPSHOT,
    )
    from workflow_tasks.components.verification import (
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
        LOADGEN_ENSURE_RUNNING, LOADGEN_PROVISION_BASE,
        LOADGEN_INSTALL_K6, LOADGEN_RUN_K6,
        METRICS_PROMETHEUS_SNAPSHOT, LOADTEST_WRITE_REPORT,
        LOADGEN_DOWN,
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
