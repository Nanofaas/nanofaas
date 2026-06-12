"""Regression for PR #125: lifecycle adapters must propagate VmRequest credentials.

Without them, AzureVmAdapter rebuilt the request from a bare
VmRequest(lifecycle="azure") and the SDK got resource_group/location = None —
masked for months by a stale tofu workspace that skipped the launch path.
"""
from __future__ import annotations

from workflow_tasks.vm.adapters import AzureVmAdapter
from workflow_tasks.vm.models import VmConfig, VmRequest


class _RecordingOrchestrator:
    def __init__(self) -> None:
        self.requests: list[VmRequest] = []

    def ensure_running(self, request: VmRequest) -> None:
        self.requests.append(request)

    def connection_host(self, request: VmRequest) -> str:
        return "10.0.0.1"


def test_azure_adapter_propagates_credentials_into_ensure_running() -> None:
    orch = _RecordingOrchestrator()
    creds = VmRequest(
        lifecycle="azure",
        azure_resource_group="maurino-rg",
        azure_location="westeurope",
    )
    adapter = AzureVmAdapter(orch, credentials=creds)

    adapter.ensure_running(VmConfig(name="stack", cpus=4, memory="12G", disk="30G"))

    request = orch.requests[0]
    assert request.azure_resource_group == "maurino-rg"
    assert request.azure_location == "westeurope"
    assert request.name == "stack"


def test_azure_adapter_without_credentials_yields_bare_request() -> None:
    orch = _RecordingOrchestrator()
    AzureVmAdapter(orch).ensure_running(VmConfig(name="x", cpus=1, memory="1G", disk="10G"))
    assert orch.requests[0].azure_resource_group is None
