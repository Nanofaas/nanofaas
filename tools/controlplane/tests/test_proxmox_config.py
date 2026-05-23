from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from controlplane_tool.workspace.proxmox_config import (
    load_proxmox_config,
    proxmox_config_exists,
    proxmox_config_path,
)


def _write_proxmox_toml(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def test_load_proxmox_config_parses_required_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'host = "192.168.1.100"\nnode = "pve"\npassword = "secret"\n')

    cfg = load_proxmox_config(tmp_path)

    assert cfg.host == "192.168.1.100"
    assert cfg.node == "pve"
    assert cfg.password == "secret"


def test_load_proxmox_config_applies_defaults(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'host = "pve.local"\nnode = "pve"\npassword = "pass"\n')

    cfg = load_proxmox_config(tmp_path)

    assert cfg.user == "root@pam"
    assert cfg.template_id is None
    assert cfg.ssh_key_path is None
    assert cfg.vm_name == "nanofaas-proxmox"
    assert cfg.loadgen_name == "nanofaas-proxmox-loadgen"


def test_load_proxmox_config_reads_optional_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(
        cfg_path,
        """
host = "192.168.1.10"
node = "pve2"
password = "topsecret"
user = "admin@pam"
template_id = 200
ssh_key_path = "/home/user/.ssh/id_ed25519"
vm_name = "custom-stack"
loadgen_name = "custom-loadgen"
""",
    )

    cfg = load_proxmox_config(tmp_path)

    assert cfg.user == "admin@pam"
    assert cfg.template_id == 200
    assert cfg.ssh_key_path == "/home/user/.ssh/id_ed25519"
    assert cfg.vm_name == "custom-stack"
    assert cfg.loadgen_name == "custom-loadgen"


def test_load_proxmox_config_raises_when_file_missing(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_proxmox_config(tmp_path)


def test_load_proxmox_config_raises_when_host_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'node = "pve"\npassword = "secret"\n')

    with pytest.raises(ValidationError):
        load_proxmox_config(tmp_path)


def test_load_proxmox_config_raises_when_node_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'host = "192.168.1.1"\npassword = "secret"\n')

    with pytest.raises(ValidationError):
        load_proxmox_config(tmp_path)


def test_load_proxmox_config_raises_when_password_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'host = "192.168.1.1"\nnode = "pve"\n')

    with pytest.raises(ValidationError):
        load_proxmox_config(tmp_path)


def test_proxmox_config_exists_returns_true_when_present(tmp_path):
    cfg_path = tmp_path / "profiles" / "proxmox.toml"
    _write_proxmox_toml(cfg_path, 'host = "pve"\nnode = "pve"\npassword = "x"\n')

    assert proxmox_config_exists(tmp_path) is True


def test_proxmox_config_exists_returns_false_when_absent(tmp_path):
    assert proxmox_config_exists(tmp_path) is False


def test_proxmox_config_path_points_to_profiles_dir(tmp_path):
    path = proxmox_config_path(tmp_path)
    assert path == tmp_path / "profiles" / "proxmox.toml"
