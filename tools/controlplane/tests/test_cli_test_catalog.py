from controlplane_tool.cli_test_catalog import (
    list_cli_test_scenarios,
    resolve_cli_test_scenario,
)


def test_cli_test_catalog_exposes_unit_vm_host_platform_and_deploy_host() -> None:
    names = [scenario.name for scenario in list_cli_test_scenarios()]
    assert names == ["unit", "vm", "cli-stack", "host-platform", "deploy-host"]


def test_vm_cli_scenario_requires_vm_and_install_dist() -> None:
    scenario = resolve_cli_test_scenario("vm")

    assert scenario.requires_vm is True
    assert scenario.accepts_function_selection is True
    assert scenario.gradle_task == ":nanofaas-cli:installDist"
    assert scenario.legacy_e2e_scenario == "cli"


def test_cli_stack_cli_scenario_is_canonical_vm_validation_flow() -> None:
    scenario = resolve_cli_test_scenario("cli-stack")

    assert scenario.requires_vm is True
    assert scenario.accepts_function_selection is True
    assert scenario.gradle_task == ":nanofaas-cli:installDist"
    assert scenario.legacy_e2e_scenario == "cli-stack"


def test_host_platform_cli_scenario_reports_selection_disabled() -> None:
    scenario = resolve_cli_test_scenario("host-platform")

    assert scenario.requires_vm is True
    assert scenario.accepts_function_selection is False
    assert scenario.gradle_task == ":nanofaas-cli:installDist"
