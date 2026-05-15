from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from controlplane_tool.workspace.azure_config import (
    azure_config_exists,
    azure_config_path,
    load_azure_config,
)


def _write_azure_toml(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def test_load_azure_config_parses_required_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "my-rg"\nlocation = "westeurope"\n')

    cfg = load_azure_config(tmp_path)

    assert cfg.resource_group == "my-rg"
    assert cfg.location == "westeurope"


def test_load_azure_config_applies_defaults(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "rg"\nlocation = "eastus"\n')

    cfg = load_azure_config(tmp_path)

    assert cfg.vm_size == "Standard_B2s"
    assert cfg.loadgen_vm_size == "Standard_B1s"
    assert cfg.image_urn is None
    assert cfg.ssh_key_path is None
    assert cfg.vm_name == "nanofaas-azure"
    assert cfg.loadgen_name == "nanofaas-azure-loadgen"


def test_load_azure_config_reads_optional_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, '''
resource_group = "my-rg"
location = "westeurope"
vm_size = "Standard_D2s_v3"
loadgen_vm_size = "Standard_B2s"
image_urn = "Canonical:ubuntu:24_04:latest"
ssh_key_path = "/home/user/.ssh/id_ed25519"
vm_name = "custom-stack"
loadgen_name = "custom-loadgen"
''')

    cfg = load_azure_config(tmp_path)

    assert cfg.vm_size == "Standard_D2s_v3"
    assert cfg.loadgen_vm_size == "Standard_B2s"
    assert cfg.image_urn == "Canonical:ubuntu:24_04:latest"
    assert cfg.ssh_key_path == "/home/user/.ssh/id_ed25519"
    assert cfg.vm_name == "custom-stack"
    assert cfg.loadgen_name == "custom-loadgen"


def test_load_azure_config_raises_when_file_missing(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_azure_config(tmp_path)


def test_load_azure_config_raises_when_resource_group_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'location = "westeurope"\n')

    with pytest.raises(ValidationError):
        load_azure_config(tmp_path)


def test_load_azure_config_raises_when_location_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "my-rg"\n')

    with pytest.raises(ValidationError):
        load_azure_config(tmp_path)


def test_azure_config_exists_returns_true_when_present(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "rg"\nlocation = "west"\n')

    assert azure_config_exists(tmp_path) is True


def test_azure_config_exists_returns_false_when_absent(tmp_path):
    assert azure_config_exists(tmp_path) is False


def test_azure_config_path_points_to_profiles_dir(tmp_path):
    path = azure_config_path(tmp_path)
    assert path == tmp_path / "profiles" / "azure.toml"
