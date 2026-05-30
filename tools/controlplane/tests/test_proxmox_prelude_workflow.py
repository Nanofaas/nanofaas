"""Oracle for the proxmox-vm-loadtest prelude Workflow conversion (C4.3a).

Pins that the honest-Task ``Workflow`` prelude built by
``build_proxmox_vm_loadtest_plan`` reproduces EXACTLY — same task_ids in the
same order, and same argv/env per command — what the legacy recipe engine
(``plan_recipe_steps(..., component_ids=_PROXMOX_LOADTEST_PRELUDE_COMPONENTS)``)
produced, INCLUDING the three proxmox rewrites:

1. ansible steps (vm.provision_base / registry.ensure_container / k3s.install /
   k3s.configure_registry): the ``-i`` inventory is rewritten to the proxmox SSH
   endpoint ``host,`` + ``-e ansible_port=PORT`` + ``--private-key KEY``.
2. repo.sync_to_vm: replaced by an rsync command through the proxmox SSH endpoint.
3. cli.fn_apply_selected: replaced by a single ``functions.register`` task
   (RegisterFunctions via REST, with publish_port NAT). It is a CallableTask, so
   only its task_id position is asserted.
"""
from __future__ import annotations

from pathlib import Path

import pytest

# Deterministic proxmox SSH endpoint / key used by BOTH the legacy recipe path
# and the new Workflow path (patched into proxmox_vm_adapter below).
_HOST = "203.0.113.5"
_SSH_PORT = 2222
_KEY = Path("/keys/proxmox_id")
_CP_HOST = "203.0.113.5"
_CP_PORT = 30080


class _FakeProxmoxVmOrchestrator:
    def __init__(self, repo_root=None, **_kw):
        self.repo_root = repo_root

    def remote_project_dir(self, request):
        return f"/home/{request.user or 'ubuntu'}/nanofaas"

    def ssh_endpoint(self, request):
        return (_HOST, _SSH_PORT)

    def ssh_private_key_path(self, request):
        return _KEY

    def wait_for_ssh(self, request, *, timeout=120.0):
        return None

    def connection_host(self, request):
        return _HOST

    def publish_port(self, request, *, service, guest_port):
        return (_CP_HOST, _CP_PORT)

    def teardown(self, request):
        return None


@pytest.fixture()
def patched_proxmox(monkeypatch):
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        _FakeProxmoxVmOrchestrator,
    )
    return _FakeProxmoxVmOrchestrator


def _rewrite_ansible(argv: list[str]) -> list[str]:
    rewritten = list(argv)
    if "-i" in rewritten:
        rewritten[rewritten.index("-i") + 1] = f"{_HOST},"
    rewritten.extend(["-e", f"ansible_port={_SSH_PORT}"])
    if "--private-key" in rewritten:
        rewritten[rewritten.index("--private-key") + 1] = str(_KEY)
    else:
        rewritten.extend(["--private-key", str(_KEY)])
    return rewritten


def _build(tmp_path):
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(
        repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path
    )
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    return runner, request


def _recipe_steps(runner, request):
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import (
        _PROXMOX_LOADTEST_PRELUDE_COMPONENTS,
    )

    return plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "proxmox-vm-loadtest",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
        multipass_client=runner._multipass_client,
        component_ids=_PROXMOX_LOADTEST_PRELUDE_COMPONENTS,
    )


def test_prelude_task_ids_match_recipe_ids(patched_proxmox, tmp_path):
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import (
        build_proxmox_vm_loadtest_plan,
    )

    runner, request = _build(tmp_path)
    recipe_steps = _recipe_steps(runner, request)
    recipe_ids = [s.step_id for s in recipe_steps if s.step_id]

    plan = build_proxmox_vm_loadtest_plan(runner, request)
    assert plan.prelude_task_ids == recipe_ids


_ANSIBLE_IDS = {
    "vm.provision_base",
    "registry.ensure_container",
    "k3s.install",
    "k3s.configure_registry",
}


def test_prelude_commands_match_recipe(patched_proxmox, tmp_path):
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import (
        build_proxmox_vm_loadtest_plan,
    )

    runner, request = _build(tmp_path)
    recipe_steps = _recipe_steps(runner, request)
    recipe_by_id = {s.step_id: s for s in recipe_steps if s.step_id}

    plan = build_proxmox_vm_loadtest_plan(runner, request)
    tasks_by_id = {t.task_id: t for t in plan.prelude_tasks}

    for step_id, step in recipe_by_id.items():
        if step_id == "vm.ensure_running":
            # Handled separately as EnsureVmRunning; not a CommandTask.
            assert step_id not in tasks_by_id
            continue
        if step_id == "functions.register":
            # CallableTask: only identity is asserted (no CommandTask spec).
            task = tasks_by_id[step_id]
            assert getattr(task, "spec", None) is None
            continue

        task = tasks_by_id[step_id]
        spec = task.spec
        if step_id in _ANSIBLE_IDS:
            expected_argv = _rewrite_ansible(list(step.command))
        elif step_id == "repo.sync_to_vm":
            from workflow_tasks.vm.multipass import (
                repo_rsync_command,
                repo_sync_ssh_rsh,
            )

            expected_argv = repo_rsync_command(
                source=runner.paths.workspace_root,
                user=request.vm.user,
                host=_HOST,
                destination=f"/home/{request.vm.user or 'ubuntu'}/nanofaas",
                ssh_rsh=repo_sync_ssh_rsh(_KEY, port=_SSH_PORT),
            )
        else:
            # vm-target / non-rewritten host steps: verbatim recipe command.
            expected_argv = list(step.command)

        assert list(spec.argv) == expected_argv, f"argv mismatch for {step_id}"
        assert dict(spec.env) == dict(step.env), f"env mismatch for {step_id}"
