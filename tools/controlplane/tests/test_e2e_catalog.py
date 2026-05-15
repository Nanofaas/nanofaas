from controlplane_tool.scenario.catalog import list_scenarios, resolve_scenario


def test_catalog_lists_expected_suite_names() -> None:
    names = [scenario.name for scenario in list_scenarios()]
    assert names == [
        "docker",
        "buildpack",
        "container-local",
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "deploy-host",
        "helm-stack",
        "two-vm-loadtest",
        "azure-vm-loadtest",
    ]


def test_k3s_junit_curl_scenario_is_vm_backed() -> None:
    scenario = resolve_scenario("k3s-junit-curl")
    assert scenario.requires_vm is True


def test_container_local_selection_mode_is_single() -> None:
    scenario = resolve_scenario("container-local")
    assert scenario.selection_mode == "single"


def test_k3s_junit_curl_selection_mode_is_multi() -> None:
    scenario = resolve_scenario("k3s-junit-curl")
    assert scenario.selection_mode == "multi"


def test_two_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("two-vm-loadtest")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes


def test_azure_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("azure-vm-loadtest")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes
