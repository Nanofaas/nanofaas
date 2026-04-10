from pathlib import Path

import pytest
from pydantic import ValidationError

from controlplane_tool.cli_test_models import CliTestRequest
from controlplane_tool.vm_models import VmRequest


def test_cli_test_request_requires_vm_for_vm_backed_scenarios() -> None:
    with pytest.raises(ValidationError, match="vm configuration is required"):
        CliTestRequest(scenario="vm")


def test_cli_test_request_rejects_function_selection_for_unit() -> None:
    with pytest.raises(ValidationError, match="does not accept function selection"):
        CliTestRequest(
            scenario="unit",
            function_preset="demo-java",
        )


def test_cli_test_request_allows_selection_and_vm_for_non_unit_flows() -> None:
    request = CliTestRequest(
        scenario="deploy-host",
        function_preset="demo-java",
        scenario_file=Path("tools/controlplane/scenarios/k8s-demo-java.toml"),
        vm=VmRequest(lifecycle="multipass"),
    )

    assert request.scenario == "deploy-host"
    assert request.function_preset == "demo-java"


def test_cli_test_request_uses_registry_url_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_REGISTRY_URL", "localhost:5001")

    request = CliTestRequest(scenario="unit")

    assert request.local_registry == "localhost:5001"


def test_cli_test_request_rejects_selection_for_host_platform() -> None:
    with pytest.raises(ValidationError, match="does not accept function selection"):
        CliTestRequest(
            scenario="host-platform",
            function_preset="demo-java",
            vm=VmRequest(lifecycle="multipass"),
        )
