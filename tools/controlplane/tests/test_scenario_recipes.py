from __future__ import annotations

from controlplane_tool.scenario_components.recipes import build_scenario_recipe


def _assert_order(task_ids: list[str], ordered_ids: list[str]) -> None:
    positions = [task_ids.index(component_id) for component_id in ordered_ids]
    assert positions == sorted(positions)


def test_cli_stack_recipe_is_independent_and_self_bootstrapping() -> None:
    recipe = build_scenario_recipe("cli-stack")

    assert recipe.requires_managed_vm is True
    for component_id in [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "k3s.install",
        "k3s.configure_registry",
        "images.build_core",
        "images.build_selected_functions",
        "tests.build_cli_stack_cli",
        "tests.install_cli_stack_platform",
        "tests.status_cli_stack_platform",
        "tests.apply_cli_stack_functions",
        "tests.list_cli_stack_functions",
        "tests.invoke_cli_stack_functions",
        "tests.enqueue_cli_stack_functions",
        "tests.delete_cli_stack_functions",
        "tests.uninstall_cli_stack_platform",
        "tests.verify_cli_stack_status_fails",
    ]:
        assert component_id in recipe.component_ids

    _assert_order(
        recipe.component_ids,
        [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "k3s.install",
            "k3s.configure_registry",
        ],
    )
    assert recipe.component_ids.index("tests.build_cli_stack_cli") < recipe.component_ids.index(
        "tests.install_cli_stack_platform"
    )
    assert recipe.component_ids.index("tests.install_cli_stack_platform") < recipe.component_ids.index(
        "tests.status_cli_stack_platform"
    )
    assert recipe.component_ids.index("tests.uninstall_cli_stack_platform") < recipe.component_ids.index(
        "tests.verify_cli_stack_status_fails"
    )


def test_helm_stack_recipe_and_cli_stack_recipe_share_components_without_sharing_tail() -> None:
    helm_recipe = build_scenario_recipe("helm-stack")
    cli_recipe = build_scenario_recipe("cli-stack")

    _assert_order(
        helm_recipe.component_ids,
        [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
        ],
    )
    _assert_order(
        cli_recipe.component_ids,
        [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
        ],
    )
    assert "loadtest.run" in helm_recipe.component_ids
    assert "experiments.autoscaling" in helm_recipe.component_ids
    assert "tests.build_cli_stack_cli" in cli_recipe.component_ids
    assert "tests.verify_cli_stack_status_fails" in cli_recipe.component_ids
    assert helm_recipe.component_ids != cli_recipe.component_ids
