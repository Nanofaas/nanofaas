"""Tests for proxmox-vm-loadtest scenario registration."""
from __future__ import annotations

import pytest


def test_proxmox_vm_loadtest_in_scenario_catalog() -> None:
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("proxmox-vm-loadtest")
    assert scenario.name == "proxmox-vm-loadtest"
    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True


def test_proxmox_vm_loadtest_in_vm_backed_scenarios() -> None:
    from controlplane_tool.core.models import VM_BACKED_SCENARIOS
    assert "proxmox-vm-loadtest" in VM_BACKED_SCENARIOS
