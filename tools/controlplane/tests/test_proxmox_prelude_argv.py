from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.catalog import resolve_scenario
from controlplane_tool.scenario.scenarios import proxmox_vm_loadtest as proxmox_plan


class FakeProxmoxOrch:
    """Deterministic proxmox orchestrator stand-in for argv characterization."""

    def __init__(self, repo_root):
        self.repo_root = repo_root

    def remote_project_dir(self, request):
        return f"/home/{request.user or 'ubuntu'}/nanofaas"

    def ssh_endpoint(self, request):
        return "203.0.113.7", 2222

    def ssh_private_key_path(self, request):
        return Path("/keys/proxmox_ed25519")


def _plan():
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack", user="ubuntu"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen", user="ubuntu"),
    )
    return proxmox_plan.ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )


def _prelude_argv() -> dict[str, list[str]]:
    plan = _plan()
    orch = FakeProxmoxOrch(Path("/repo"))
    stack_request = plan._requests()[0]  # noqa: SLF001
    tasks = plan._build_prelude_tasks(orch, stack_request, resolve_host=True)  # noqa: SLF001
    return {t.task_id: list(t.spec.argv) for t in tasks if getattr(t, "spec", None) is not None}


def test_proxmox_prelude_ansible_targets_nat_endpoint() -> None:
    by_id = _prelude_argv()
    provision = by_id["vm.provision_base"]
    assert provision[0] == "ansible-playbook"
    assert "203.0.113.7," in provision           # NAT host as inventory
    assert "ansible_port=2222" in provision       # mapped SSH port
    assert "/keys/proxmox_ed25519" in provision   # proxmox key
    assert any(arg.endswith("provision-base.yml") for arg in provision)


def test_proxmox_prelude_repo_sync_uses_nat_rsync() -> None:
    by_id = _prelude_argv()
    rsync = by_id["repo.sync_to_vm"]
    assert rsync[0] == "rsync"
    assert any("203.0.113.7" in arg for arg in rsync)
    assert any("2222" in arg for arg in rsync)


def test_proxmox_prelude_registers_functions_via_rest_not_cli() -> None:
    by_id = _prelude_argv()
    # functions.register is a CallableTask (no spec.argv) — it must be present as a task_id,
    # and the cli.fn_apply_selected.* CLI commands must NOT appear.
    plan = _plan()
    orch = FakeProxmoxOrch(Path("/repo"))
    tasks = plan._build_prelude_tasks(orch, plan._requests()[0], resolve_host=True)  # noqa: SLF001
    ids = [t.task_id for t in tasks]
    assert "functions.register" in ids
    assert not any(i.startswith("cli.fn_apply_selected") for i in ids)
