import pytest
from pydantic import ValidationError

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.vm_models import VmRequest


def test_e2e_request_tracks_scenario_runtime_and_vm_config() -> None:
    request = E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    assert request.scenario == "k8s-vm"
    assert request.vm is not None
    assert request.vm.name == "nanofaas-e2e"


def test_vm_backed_scenario_requires_vm_config() -> None:
    with pytest.raises(ValidationError, match="vm"):
        E2eRequest(scenario="k8s-vm")


def test_local_scenario_accepts_absent_vm_config() -> None:
    request = E2eRequest(scenario="docker")
    assert request.vm is None


def test_e2e_request_accepts_function_preset() -> None:
    request = E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        function_preset="demo-java",
        vm=VmRequest(lifecycle="multipass"),
    )
    assert request.function_preset == "demo-java"
