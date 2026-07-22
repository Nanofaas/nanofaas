"""Tests for AzureVmProvider."""
from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmRequest


def _make_provider() -> object:
    from workflow_tasks.vm.azure import AzureVmProvider
    return AzureVmProvider(repo_root=Path("/repo"))


def _make_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="azure",
        name="test-vm",
        user="ubuntu",
        azure_resource_group="rg-test",
        azure_location="eastus",
        azure_ssh_key_path="/home/user/.ssh/id_ed25519",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_azure_client_mock() -> MagicMock:
    client = MagicMock()
    vm = MagicMock()
    vm.wait_for_ip.return_value = "10.0.0.1"
    client.get_vm.return_value = vm
    return client, vm


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_root(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="root")
    assert provider.remote_home(req) == "/root"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_custom(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(home="/custom/home")
    assert provider.remote_home(req) == "/custom/home"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_project_dir(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_project_dir(req) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_vm_name_uses_name_field(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(name="custom-vm")
    assert provider._vm_name(req) == "custom-vm"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_vm_name_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="azure", azure_resource_group="rg")
    assert provider._vm_name(req) == "nanofaas-azure"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ssh_key_from_request(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path="/home/user/.ssh/id_ed25519")
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_ed25519")


@patch("workflow_tasks.vm.azure.AzureClient")
@patch("workflow_tasks.vm.azure._find_ssh_private_key_path", return_value=Path("/home/user/.ssh/id_rsa"))
def test_ssh_key_fallback(mock_find, mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=None)
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_rsa")


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ssh_private_key_path_public(mock_client_cls, tmp_path) -> None:
    key = tmp_path / "id_ed25519"
    key.write_text("x")
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=str(key))
    assert provider.ssh_private_key_path(req) == key


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ssh_private_key_path_strips_pub_suffix(mock_client_cls, tmp_path) -> None:
    # ssh_key_path configures the tofu PUBLIC key; ansible/scp need the
    # matching private key next to it.
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=str(tmp_path / "id_rsa.pub"))
    assert provider.ssh_private_key_path(req) == tmp_path / "id_rsa"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_connection_host(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    host = provider.connection_host(req)
    assert host == "10.0.0.1"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_teardown_success(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    vm_mock.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_teardown_vm_not_found_is_ignored(mock_client_cls) -> None:
    from azure_vm.exceptions import VmNotFoundError
    client_mock, vm_mock = _make_azure_client_mock()
    vm_mock.delete.side_effect = VmNotFoundError("gone")
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ensure_running(mock_client_cls) -> None:
    client_mock = MagicMock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.ensure_running(req)
    client_mock.ensure_running.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ensure_running_forwards_request_disk_as_gibibytes(mock_client_cls) -> None:
    client_mock = MagicMock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()

    provider.ensure_running(_make_request(disk="128G"))

    assert client_mock.ensure_running.call_args.kwargs["disk_size_gb"] == 128


@patch("workflow_tasks.vm.azure.subprocess.run")
def test_release_facts_are_read_from_authoritative_azure_state(mock_run) -> None:
    process = MagicMock()
    process.returncode = 0
    process.stdout = json.dumps(
        {
            "location": "westeurope",
            "vmSize": "Standard_D4s_v5",
            "diskSizeGb": 128,
            "imagePublisher": "Canonical",
            "imageOffer": "ubuntu-24_04-lts",
            "imageSku": "server",
            "imageVersion": "24.04.202505280",
        }
    )
    process.stderr = ""
    mock_run.return_value = process
    provider = _make_provider()
    request = _make_request(
        name="nanofaas-azure-release",
        azure_resource_group="nanofaas-rg",
        azure_location="westeurope",
    )

    facts = provider.release_vm_facts(request)

    assert facts.location == "westeurope"
    assert facts.vm_size == "Standard_D4s_v5"
    assert facts.disk_size_gb == 128
    assert facts.image_urn == (
        "Canonical:ubuntu-24_04-lts:server:24.04.202505280"
    )
    command = mock_run.call_args.args[0]
    assert command[:6] == [
        "az",
        "vm",
        "show",
        "--resource-group",
        "nanofaas-rg",
        "--name",
    ]
    assert command[6] == "nanofaas-azure-release"
    assert command[-2:] == ["--output", "json"]


@patch("workflow_tasks.vm.azure.subprocess.run")
def test_release_nsg_rules_are_restricted_to_explicit_sources(mock_run) -> None:
    sources = ("198.51.100.42/32", "203.0.113.0/24")
    process = MagicMock()
    process.returncode = 0
    process.stdout = json.dumps(list(sources))
    process.stderr = ""
    mock_run.return_value = process
    provider = _make_provider()
    request = _make_request(
        name="nanofaas-azure-release",
        azure_resource_group="nanofaas-rg",
    )

    provider.restrict_inbound_sources(
        request,
        ports=(30080, 30081, 30090),
        source_cidrs=sources,
    )

    assert mock_run.call_count == 3
    for index, (call, port) in enumerate(
        zip(mock_run.call_args_list, (30080, 30081, 30090), strict=True)
    ):
        command = call.args[0]
        assert command[:5] == ["az", "network", "nsg", "rule", "create"]
        assert command[command.index("--nsg-name") + 1] == "nanofaas-azure-release-nsg"
        assert command[command.index("--name") + 1] == f"Port{port}"
        assert command[command.index("--destination-port-ranges") + 1] == str(port)
        assert command[command.index("--priority") + 1] == str(1010 + index)
        source_index = command.index("--source-address-prefixes")
        assert tuple(command[source_index + 1 : source_index + 3]) == sources
        assert "*" not in command
        # Single-source rules echo back via the singular field: the query
        # must carry the fallback or the verification always fails.
        assert command[command.index("--query") + 1] == (
            "sourceAddressPrefixes || [sourceAddressPrefix]"
        )


@patch("workflow_tasks.vm.azure.subprocess.run")
def test_release_nsg_restriction_fails_closed_on_mismatched_azure_response(mock_run) -> None:
    process = MagicMock()
    process.returncode = 0
    process.stdout = json.dumps(["*"])
    process.stderr = ""
    mock_run.return_value = process
    provider = _make_provider()

    with pytest.raises(RuntimeError, match="NSG source restriction mismatch"):
        provider.restrict_inbound_sources(
            _make_request(),
            ports=(30080,),
            source_cidrs=("198.51.100.42/32",),
        )


@pytest.mark.parametrize("source", ("0.0.0.0/0", "::/0"))
@patch("workflow_tasks.vm.azure.subprocess.run")
def test_release_nsg_restriction_rejects_unbounded_source(mock_run, source: str) -> None:
    provider = _make_provider()

    with pytest.raises(ValueError, match="must be bounded"):
        provider.restrict_inbound_sources(
            _make_request(),
            ports=(30080,),
            source_cidrs=(source,),
        )
    mock_run.assert_not_called()


@patch("workflow_tasks.vm.azure.AzureClient")
def test_exec_argv(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    exec_result = MagicMock()
    exec_result.returncode = 0
    exec_result.stdout = "output"
    exec_result.stderr = ""
    vm_mock.exec_structured.return_value = exec_result
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.exec_argv(req, ["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "output"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_to(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")
    vm_mock.transfer.assert_called_once_with("/local/file", "/remote/file")
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.subprocess.run")
@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_from(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_from(req, source="/remote/file", destination=Path("/local/file"))
    assert result.return_code == 0
    assert "scp" in result.command


@patch("workflow_tasks.vm.azure.subprocess.run")
@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_from_no_ssh_key(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=None)
    # patch _find_ssh_private_key_path to return None so no -i flag
    with patch("workflow_tasks.vm.azure._find_ssh_private_key_path", return_value=None):
        result = provider.transfer_from(req, source="/remote/file", destination=Path("/local"))
    assert result.return_code == 0
    assert "-i" not in result.command
