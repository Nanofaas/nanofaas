from controlplane_tool.e2e_catalog import list_scenarios, resolve_scenario


def test_catalog_lists_expected_suite_names() -> None:
    names = [scenario.name for scenario in list_scenarios()]
    assert names == [
        "docker",
        "buildpack",
        "container-local",
        "k3s-curl",
        "k8s-vm",
        "cli",
        "cli-host",
        "deploy-host",
        "helm-stack",
    ]


def test_k8s_vm_scenario_is_vm_backed() -> None:
    scenario = resolve_scenario("k8s-vm")
    assert scenario.requires_vm is True
