from __future__ import annotations

from controlplane_tool.scenario_components.models import ScenarioRecipe


_SCENARIO_RECIPES: dict[str, ScenarioRecipe] = {
    "k3s-junit-curl": ScenarioRecipe(
        name="k3s-junit-curl",
        component_ids=(
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
            "k8s.wait_control_plane_ready",
            "k8s.wait_function_runtime_ready",
            "tests.run_k3s_curl_checks",
            "tests.run_k8s_junit",
            "cleanup.uninstall_function_runtime",
            "cleanup.uninstall_control_plane",
            "cleanup.delete_namespace",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
    "helm-stack": ScenarioRecipe(
        name="helm-stack",
        component_ids=(
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
            "k8s.wait_control_plane_ready",
            "k8s.wait_function_runtime_ready",
            "loadtest.run",
            "experiments.autoscaling",
        ),
        requires_managed_vm=True,
    ),
    "cli-stack": ScenarioRecipe(
        name="cli-stack",
        component_ids=(
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
            "cli.build_install_dist",
            "cli.platform_install",
            "cli.platform_status",
            "cli.fn_apply_selected",
            "cli.fn_list_selected",
            "cli.fn_invoke_selected",
            "cli.fn_enqueue_selected",
            "cli.fn_delete_selected",
            "cleanup.uninstall_control_plane",
            "cleanup.delete_namespace",
            "cleanup.verify_cli_platform_status_fails",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
}


def build_scenario_recipe(name: str) -> ScenarioRecipe:
    try:
        recipe = _SCENARIO_RECIPES[name]
    except KeyError as exc:
        raise ValueError(f"Unsupported scenario recipe: {name}") from exc

    return ScenarioRecipe(
        name=recipe.name,
        component_ids=tuple(recipe.component_ids),
        requires_managed_vm=recipe.requires_managed_vm,
    )
