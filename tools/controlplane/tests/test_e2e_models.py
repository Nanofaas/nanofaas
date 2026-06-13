from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest


def test_e2e_request_tracks_scenario_runtime_and_vm_config() -> None:
    request = E2eRequest(
        scenario="validate-k3s",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    assert request.scenario == "validate-k3s"
    assert request.vm is not None
    assert request.vm.name == "nanofaas-e2e"
    assert request.cleanup_vm is True


def test_vm_backed_scenario_allows_missing_vm_config() -> None:
    request = E2eRequest(scenario="validate-k3s")

    assert request.vm is None


def test_local_scenario_accepts_absent_vm_config() -> None:
    request = E2eRequest(scenario="validate-docker-pool")
    assert request.vm is None


def test_e2e_request_uses_registry_url_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_REGISTRY_URL", "localhost:5001")
    request = E2eRequest(scenario="validate-docker-pool")

    assert request.local_registry == "localhost:5001"


def test_e2e_request_accepts_function_preset() -> None:
    request = E2eRequest(
        scenario="validate-k3s",
        runtime="java",
        function_preset="demo-java",
        vm=VmRequest(lifecycle="multipass"),
    )
    assert request.function_preset == "demo-java"


def test_e2e_request_allows_cleanup_vm_to_be_disabled() -> None:
    request = E2eRequest(
        scenario="validate-k3s",
        runtime="java",
        vm=VmRequest(lifecycle="multipass"),
        cleanup_vm=False,
    )

    assert request.cleanup_vm is False
