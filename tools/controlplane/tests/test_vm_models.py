import pytest
from pydantic import ValidationError

from controlplane_tool.vm_models import VmRequest


def test_external_vm_request_requires_host() -> None:
    request = VmRequest(lifecycle="external", host="vm.example.test")
    assert request.lifecycle == "external"
    assert request.host == "vm.example.test"


def test_external_vm_request_without_host_fails() -> None:
    with pytest.raises(ValidationError, match="host"):
        VmRequest(lifecycle="external")
