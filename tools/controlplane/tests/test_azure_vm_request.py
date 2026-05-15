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
    assert request.azure_vm_size == "Standard_B2s"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_azure_fields_have_defaults():
    request = VmRequest(lifecycle="azure", azure_resource_group="rg", azure_location="west")
    assert request.azure_vm_size == "Standard_B2s"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_rejects_unknown_lifecycle():
    with pytest.raises(ValidationError):
        VmRequest(lifecycle="foobar")
