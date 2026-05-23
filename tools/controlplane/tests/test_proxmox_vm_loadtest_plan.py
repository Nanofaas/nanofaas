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


def test_build_proxmox_vm_loadtest_plan_returns_correct_type() -> None:
    from unittest.mock import MagicMock
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan, ProxmoxVmLoadtestPlan
    runner = MagicMock()
    runner.paths.workspace_root = "/workspace"
    request = MagicMock()
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    assert isinstance(plan, ProxmoxVmLoadtestPlan)


def test_proxmox_vm_loadtest_plan_task_ids() -> None:
    from unittest.mock import MagicMock
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
    from controlplane_tool.scenario.two_vm_loadtest_config import LOADTEST_STATIC_TASK_IDS
    runner = MagicMock()
    runner.paths.workspace_root = "/workspace"
    request = MagicMock()
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    assert plan.task_ids == list(LOADTEST_STATIC_TASK_IDS)


def test_proxmox_vm_loadtest_plan_phase_titles_count() -> None:
    from unittest.mock import MagicMock
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
    runner = MagicMock()
    runner.paths.workspace_root = "/workspace"
    request = MagicMock()
    request.vm.name = "proxmox-stack"
    request.vm.cpus = 2
    request.vm.memory = "4G"
    request.vm.disk = "20G"
    request.loadgen_vm.name = "proxmox-loadgen"
    request.loadgen_vm.cpus = 2
    request.loadgen_vm.memory = "2G"
    request.loadgen_vm.disk = "10G"
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    # 2 EnsureVmRunning (pre) + 5 Workflow tasks + 1 cleanup = 8 phases via task_ids
    assert len(plan.phase_titles) == len(plan.task_ids)


def test_e2e_runner_plan_returns_proxmox_vm_loadtest_plan(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    plan = runner.plan(request)
    assert isinstance(plan, ProxmoxVmLoadtestPlan)
    assert "loadgen.run_k6" in plan.task_ids
    assert "vm.stack.ensure_running" in plan.task_ids
