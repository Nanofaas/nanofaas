from controlplane_tool.e2e_catalog import list_scenarios, resolve_scenario


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
