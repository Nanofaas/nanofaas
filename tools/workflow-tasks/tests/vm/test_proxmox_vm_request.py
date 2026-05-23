"""Tests for VmRequest proxmox lifecycle fields."""
from __future__ import annotations

import pytest
from workflow_tasks.vm.models import VmRequest


def test_proxmox_lifecycle_accepted() -> None:
    req = VmRequest(lifecycle="proxmox", proxmox_host="192.168.1.100", proxmox_node="pve")
    assert req.lifecycle == "proxmox"


def test_proxmox_fields_all_optional_except_host_and_node() -> None:
    req = VmRequest(lifecycle="proxmox", proxmox_host="10.0.0.1", proxmox_node="node1")
    assert req.proxmox_host == "10.0.0.1"
    assert req.proxmox_node == "node1"
    assert req.proxmox_user is None
    assert req.proxmox_password is None
    assert req.proxmox_template_id is None
    assert req.proxmox_ssh_key_path is None


def test_proxmox_all_fields_set() -> None:
    req = VmRequest(
        lifecycle="proxmox",
        proxmox_host="192.168.1.100",
        proxmox_node="pve",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=100,
        proxmox_ssh_key_path="/home/user/.ssh/id_rsa",
    )
    assert req.proxmox_template_id == 100
    assert req.proxmox_user == "root@pam"
