from controlplane_tool.scenario.catalog import list_scenarios, resolve_scenario


def test_catalog_lists_expected_suite_names() -> None:
    names = [scenario.name for scenario in list_scenarios()]
    assert names == [
        "validate-docker-pool",
        "validate-buildpack-pool",
        "validate-container-local",
        "validate-k3s",
        "cli",
        "cli-stack",
        "cli-host",
        "validate-deploy-host",
        "loadtest-helm-legacy",
        "loadtest-one-vm",
        "loadtest-two-vm",
        "loadtest-azure",
        "loadtest-proxmox",
    ]


def test_k3s_junit_curl_scenario_is_vm_backed() -> None:
    scenario = resolve_scenario("validate-k3s")
    assert scenario.requires_vm is True


def test_container_local_selection_mode_is_single() -> None:
    scenario = resolve_scenario("validate-container-local")
    assert scenario.selection_mode == "single"


def test_k3s_junit_curl_selection_mode_is_multi() -> None:
    scenario = resolve_scenario("validate-k3s")
    assert scenario.selection_mode == "multi"


def test_two_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("loadtest-two-vm")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes


def test_azure_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("loadtest-azure")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes
