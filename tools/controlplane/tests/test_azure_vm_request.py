from __future__ import annotations

import pytest
from pydantic import ValidationError

from controlplane_tool.infra.vm.vm_models import VmRequest


def test_vm_request_accepts_azure_lifecycle():
    request = VmRequest(
        lifecycle="azure",
        name="nanofaas-azure",
        user="azureuser",
        azure_resource_group="my-rg",
        azure_location="westeurope",
    )
    assert request.lifecycle == "azure"
    assert request.azure_resource_group == "my-rg"
    assert request.azure_location == "westeurope"
    assert request.azure_vm_size == "Standard_D4s_v5"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_azure_fields_have_defaults():
    request = VmRequest(lifecycle="azure", azure_resource_group="rg", azure_location="west")
    assert request.azure_vm_size == "Standard_D4s_v5"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_rejects_unknown_lifecycle():
    with pytest.raises(ValidationError):
        VmRequest(lifecycle="foobar")


def test_azure_stack_request_opens_nodeports():
    from controlplane_tool.cli.e2e_commands import _resolve_run_request

    request = _resolve_run_request(
        scenario="azure-vm-loadtest", runtime="java", lifecycle="azure",
        name=None, host=None, user="azureuser", home=None,
        cpus=4, memory="12G", disk="30G", cleanup_vm=True,
        namespace=None, local_registry=None, function_preset=None,
        functions_csv=None, scenario_file=None, saved_profile=None,
        azure_resource_group="rg", azure_location="westeurope",
    )

    # The operator machine registers functions and snapshots Prometheus through
    # these NodePorts; the loadgen VM reaches the stack via its public IP.
    assert request.vm.azure_open_ports == (30080, 30081, 30090)
    assert request.loadgen_vm.azure_open_ports is None
