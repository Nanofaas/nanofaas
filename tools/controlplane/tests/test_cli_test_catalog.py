from controlplane_tool.cli_validation.cli_test_catalog import (
    list_cli_test_scenarios,
    resolve_cli_test_scenario,
)


def test_cli_test_catalog_exposes_unit_cli_stack_host_platform_and_deploy_host() -> None:
    names = [scenario.name for scenario in list_cli_test_scenarios()]
    assert names == ["unit", "cli-stack", "host-platform", "deploy-host"]


def test_cli_test_catalog_does_not_expose_vm_scenario() -> None:
    """vm must be removed from the cli-test catalog — cli-stack is the canonical VM-backed path."""
    names = [s.name for s in list_cli_test_scenarios()]
    assert "vm" not in names, f"vm scenario still in catalog: {names}"


def test_cli_stack_cli_scenario_is_canonical_vm_validation_flow() -> None:
    scenario = resolve_cli_test_scenario("cli-stack")

    assert scenario.requires_vm is True
    assert scenario.accepts_function_selection is True
    assert scenario.gradle_task == ":nanofaas-cli:installDist"
    assert scenario.legacy_e2e_scenario == "cli-stack"


def test_cli_test_catalog_does_not_expose_vm_scenario() -> None:
    """vm must be removed from the cli-test catalog — cli-stack is the canonical VM-backed path."""
    names = [s.name for s in list_cli_test_scenarios()]
    assert "vm" not in names, f"vm scenario still in catalog: {names}"


def test_host_platform_cli_scenario_reports_selection_disabled() -> None:
    scenario = resolve_cli_test_scenario("host-platform")

    assert scenario.requires_vm is True
    assert scenario.accepts_function_selection is False
    assert scenario.gradle_task == ":nanofaas-cli:installDist"
