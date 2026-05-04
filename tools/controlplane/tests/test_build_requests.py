from controlplane_tool.building.requests import BuildRequest, resolve_modules_selector


def test_profile_name_container_local_maps_to_expected_modules() -> None:
    request = BuildRequest(action="jar", profile="container-local")
    assert resolve_modules_selector(request) == "container-deployment-provider"


def test_profile_name_core_maps_to_none_selector() -> None:
    request = BuildRequest(action="test", profile="core")
    assert resolve_modules_selector(request) == "none"
