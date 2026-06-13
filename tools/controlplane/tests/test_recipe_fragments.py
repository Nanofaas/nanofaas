from __future__ import annotations

from controlplane_tool.scenario.components.recipes import build_scenario_recipe

# Golden snapshot of every scenario's exact component_ids as of 2026-06-11.
# This pins current behavior so the fragment refactor (Task 2) stays byte-for-byte identical.
GOLDEN: dict[str, tuple[str, ...]] = {
    "k3s-junit-curl": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "tests.run_k3s_curl_checks", "tests.run_k8s_junit",
        "cleanup.uninstall_function_runtime", "cleanup.uninstall_control_plane",
        "namespace.uninstall", "vm.down",
    ),
    "loadtest-helm-legacy": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "loadtest.install_k6", "loadtest.run", "experiments.autoscaling",
    ),
    "loadtest-one-vm": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
    ),
    "loadtest-two-vm": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "loadtest-azure": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "loadtest-proxmox": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "cli-stack": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "cli.build_install_dist", "cli.platform_install", "cli.platform_status",
        "cli.fn_apply_selected", "cli.fn_list_selected", "cli.fn_invoke_selected",
        "cli.fn_enqueue_selected", "cli.fn_delete_selected",
        "cleanup.uninstall_control_plane", "namespace.uninstall",
        "cleanup.verify_cli_platform_status_fails", "vm.down",
    ),
}


def test_recipe_component_ids_match_golden() -> None:
    for name, expected in GOLDEN.items():
        assert build_scenario_recipe(name).component_ids == expected, name


def test_all_recipes_have_golden_entry() -> None:
    # Guard against a new scenario being added without updating the golden snapshot.
    from controlplane_tool.scenario.components.recipes import _SCENARIO_RECIPES

    assert set(_SCENARIO_RECIPES) == set(GOLDEN)


def test_loadtest_recipes_are_identical() -> None:
    # The fragment refactor collapses the three loadtest recipes to one shared definition.
    two_vm = build_scenario_recipe("loadtest-two-vm").component_ids
    azure = build_scenario_recipe("loadtest-azure").component_ids
    proxmox = build_scenario_recipe("loadtest-proxmox").component_ids
    assert two_vm == azure == proxmox
