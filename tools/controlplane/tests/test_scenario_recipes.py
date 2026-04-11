from __future__ import annotations

from controlplane_tool.scenario_components.recipes import build_scenario_recipe


def test_cli_stack_recipe_is_independent_and_self_bootstrapping() -> None:
    recipe = build_scenario_recipe("cli-stack")

    assert recipe.requires_managed_vm is True
    assert recipe.component_ids[:6] == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "k3s.install",
        "k3s.configure_registry",
    ]


def test_helm_stack_recipe_and_cli_stack_recipe_share_components_without_sharing_tail() -> None:
    helm_recipe = build_scenario_recipe("helm-stack")
    cli_recipe = build_scenario_recipe("cli-stack")

    assert helm_recipe.component_ids[:8] == cli_recipe.component_ids[:8]
    assert helm_recipe.component_ids != cli_recipe.component_ids
