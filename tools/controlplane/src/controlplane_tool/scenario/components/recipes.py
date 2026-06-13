from __future__ import annotations

from workflow_tasks.components.models import ScenarioRecipe

# ── Reusable recipe fragments ────────────────────────────────────────────────
# Provisioning shared by every managed-VM scenario, up to and including the
# namespace Helm release (the last step before scenarios diverge).
BASE_PROVISION: tuple[str, ...] = (
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core",
    "images.build_selected_functions",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
)

# Deploy nanofaas via Helm (control-plane + function-runtime). Used by every
# scenario except cli-stack, which installs through the CLI instead.
HELM_DEPLOY: tuple[str, ...] = (
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
)

# Full Helm-based stack prelude (provision + deploy).
STACK_PRELUDE: tuple[str, ...] = BASE_PROVISION + HELM_DEPLOY

# Shared tail for the loadgen-based loadtest scenarios: build the CLI, register
# functions, then run the k6 load test from the loadgen VM and tear down.
LOADTEST_TAIL: tuple[str, ...] = (
    "cli.build_install_dist",
    "cli.fn_apply_selected",
    "loadgen.ensure_running",
    "loadgen.provision_base",
    "loadgen.install_k6",
    "loadgen.run_k6",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "loadgen.down",
    "vm.down",
)

# The three loadtest scenarios share one recipe shape (they differ only by
# lifecycle/connectivity at execution time, not by component list).
_LOADTEST_COMPONENT_IDS: tuple[str, ...] = STACK_PRELUDE + LOADTEST_TAIL


def _loadtest_recipe(name: str) -> ScenarioRecipe:
    return ScenarioRecipe(
        name=name,
        component_ids=_LOADTEST_COMPONENT_IDS,
        requires_managed_vm=True,
    )


_SCENARIO_RECIPES: dict[str, ScenarioRecipe] = {
    "k3s-junit-curl": ScenarioRecipe(
        name="k3s-junit-curl",
        component_ids=STACK_PRELUDE
        + (
            "tests.run_k3s_curl_checks",
            "tests.run_k8s_junit",
            "cleanup.uninstall_function_runtime",
            "cleanup.uninstall_control_plane",
            "namespace.uninstall",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
    "loadtest-helm-legacy": ScenarioRecipe(
        name="loadtest-helm-legacy",
        component_ids=STACK_PRELUDE
        + (
            "loadtest.install_k6",
            "loadtest.run",
            "experiments.autoscaling",
        ),
        requires_managed_vm=True,
    ),
    "loadtest-one-vm": ScenarioRecipe(
        name="loadtest-one-vm",
        component_ids=STACK_PRELUDE,
        requires_managed_vm=True,
    ),
    "loadtest-two-vm": _loadtest_recipe("loadtest-two-vm"),
    "loadtest-azure": _loadtest_recipe("loadtest-azure"),
    "loadtest-proxmox": _loadtest_recipe("loadtest-proxmox"),
    "cli-stack": ScenarioRecipe(
        name="cli-stack",
        component_ids=BASE_PROVISION
        + (
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
