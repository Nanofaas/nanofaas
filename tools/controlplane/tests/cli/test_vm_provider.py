from __future__ import annotations

from controlplane_tool.cli.vm_provider import vm_request_for_role
from controlplane_tool.config import EnvironmentConfig


def test_azure_stack_request_uses_cloud_defaults_and_node_ports() -> None:
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "azure",
            "roles": {"stack": {}},
            "azure": {
                "resource_group": "nanofaas-rg",
                "location": "westeurope",
                "image_urn": "Canonical:ubuntu-24_04-lts:server:latest",
                "ssh_key_path": "~/.ssh/id_ed25519",
            },
        }
    )

    request = vm_request_for_role(environment, "stack", loadtest=True)

    assert request.lifecycle == "azure"
    assert request.name == "nanofaas-azure"
    assert request.user == "azureuser"
    assert request.azure_vm_size == "Standard_D4s_v5"
    assert request.azure_resource_group == "nanofaas-rg"
    assert request.azure_location == "westeurope"
    assert request.azure_open_ports == (30080, 30081, 30090)


def test_azure_loadgen_request_uses_separate_size_and_no_node_ports() -> None:
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "azure",
            "roles": {"stack": {}, "loadgen": {"name": "load", "user": "runner"}},
            "azure": {
                "resource_group": "nanofaas-rg",
                "location": "westeurope",
                "vm_size": "Standard_D8s_v5",
                "loadgen_vm_size": "Standard_B2s",
            },
        }
    )

    stack = vm_request_for_role(environment, "stack", loadtest=True)
    loadgen = vm_request_for_role(environment, "loadgen", loadtest=True)

    assert stack.azure_vm_size == "Standard_D8s_v5"
    assert loadgen.azure_vm_size == "Standard_B2s"
    assert loadgen.name == "load"
    assert loadgen.user == "runner"
    assert loadgen.azure_open_ports is None


def test_azure_release_stack_does_not_render_wildcard_nodeport_rules() -> None:
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "azure",
            "roles": {
                "stack": {"name": "nanofaas-azure-release"},
                "loadgen": {"name": "nanofaas-azure-release-loadgen"},
            },
            "azure": {
                "resource_group": "nanofaas-rg",
                "location": "westeurope",
                "operator_source_cidr": "203.0.113.0/24",
            },
        }
    )

    request = vm_request_for_role(environment, "stack", loadtest=True)

    assert request.azure_open_ports is None


def test_proxmox_request_reads_secret_from_named_environment_variable(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_PVE_PASSWORD", "secret")
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "proxmox",
            "roles": {"stack": {"cpus": 6, "memory": "16G", "disk": "60G"}},
            "proxmox": {
                "host": "pve.example",
                "node": "pve1",
                "user": "root@pam",
                "password_env": "NANOFAAS_PVE_PASSWORD",
                "template_id": 9000,
                "ssh_key_path": "~/.ssh/id_ed25519",
            },
        }
    )

    request = vm_request_for_role(environment, "stack")

    assert request.lifecycle == "proxmox"
    assert request.name == "nanofaas-proxmox"
    assert request.proxmox_host == "pve.example"
    assert request.proxmox_node == "pve1"
    assert request.proxmox_user == "root@pam"
    assert request.proxmox_password == "secret"
    assert request.proxmox_template_id == 9000
    assert (request.cpus, request.memory, request.disk) == (6, "16G", "60G")


def test_proxmox_loadgen_has_distinct_default_name(monkeypatch) -> None:
    monkeypatch.setenv("PROXMOX_PASSWORD", "secret")
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "proxmox",
            "roles": {"stack": {}, "loadgen": {}},
            "proxmox": {"host": "pve.example", "node": "pve1"},
        }
    )

    assert vm_request_for_role(environment, "stack").name == "nanofaas-proxmox"
    assert vm_request_for_role(environment, "loadgen").name == "nanofaas-proxmox-loadgen"
