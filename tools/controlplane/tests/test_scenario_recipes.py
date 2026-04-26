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
        "namespace.install",
        "cli.build_install_dist",
        "cli.platform_install",
        "cli.platform_status",
        "cli.fn_apply_selected",
        "cli.fn_list_selected",
        "cli.fn_invoke_selected",
        "cli.fn_enqueue_selected",
        "cli.fn_delete_selected",
        "cleanup.uninstall_control_plane",
        "namespace.uninstall",
        "cleanup.verify_cli_platform_status_fails",
        "vm.down",
    ]:
        assert component_id in recipe.component_ids
    assert "cleanup.uninstall_function_runtime" not in recipe.component_ids
    assert "cleanup.delete_namespace" not in recipe.component_ids

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
    assert recipe.component_ids.index("cli.build_install_dist") < recipe.component_ids.index(
        "cli.platform_install"
    )
    assert recipe.component_ids.index("cli.platform_install") < recipe.component_ids.index(
        "cli.platform_status"
    )
    assert recipe.component_ids.index("cleanup.uninstall_control_plane") < recipe.component_ids.index(
        "cleanup.verify_cli_platform_status_fails"
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
    assert "loadtest.install_k6" in helm_recipe.component_ids
    assert "experiments.autoscaling" in helm_recipe.component_ids
    assert "cli.build_install_dist" in cli_recipe.component_ids
    assert "cli.platform_uninstall" not in cli_recipe.component_ids
    assert "cleanup.uninstall_function_runtime" not in cli_recipe.component_ids
    assert "cleanup.verify_cli_platform_status_fails" in cli_recipe.component_ids
    assert helm_recipe.component_ids != cli_recipe.component_ids
    assert "namespace.install" in helm_recipe.component_ids
    assert "namespace.install" in cli_recipe.component_ids
    assert "k8s.wait_control_plane_ready" not in helm_recipe.component_ids
    assert "k8s.wait_function_runtime_ready" not in helm_recipe.component_ids
    _assert_order(
        helm_recipe.component_ids,
        [
            "namespace.install",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
            "loadtest.install_k6",
            "loadtest.run",
            "experiments.autoscaling",
        ],
    )
