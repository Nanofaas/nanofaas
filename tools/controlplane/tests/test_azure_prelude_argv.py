from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.catalog import resolve_scenario
from controlplane_tool.scenario.scenarios import azure_vm_loadtest as azure_plan


class FakeAzureOrch:
    """Deterministic azure orchestrator stand-in for argv characterization."""

    def __init__(self, repo_root):
        self.repo_root = repo_root

    def remote_project_dir(self, request):
        return f"/home/{request.user or 'azureuser'}/nanofaas"

    def connection_host(self, request):
        return "20.0.0.42"

    def ssh_private_key_path(self, request):
        return Path("/keys/azure_id_rsa")


def _plan():
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="azure-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="azure", name="azure-stack", user="azureuser"),
        loadgen_vm=VmRequest(lifecycle="azure", name="azure-loadgen", user="azureuser"),
    )
    return azure_plan.AzureVmLoadtestPlan(
        scenario=resolve_scenario("azure-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )


def _prelude_tasks(resolve_host: bool = True):
    plan = _plan()
    orch = FakeAzureOrch(Path("/repo"))
    stack_request = plan._requests()[0]  # noqa: SLF001
    return plan._build_prelude_tasks(orch, stack_request, resolve_host=resolve_host)  # noqa: SLF001


def _prelude_argv(resolve_host: bool = True) -> dict[str, list[str]]:
    tasks = _prelude_tasks(resolve_host=resolve_host)
    return {t.task_id: list(t.spec.argv) for t in tasks if getattr(t, "spec", None) is not None}


def _prelude_ids(resolve_host: bool = True) -> list[str]:
    return ["vm.ensure_running"] + [t.task_id for t in _prelude_tasks(resolve_host=resolve_host)]


def test_azure_prelude_task_ids_canonical_order() -> None:
    ids = _prelude_ids()

    # vm.ensure_running is prepended (executed separately by the unified driver).
    assert ids[0] == "vm.ensure_running"

    # The provisioning prelude ids appear in canonical order, with the
    # cli.fn_apply_selected component substituted by functions.register.
    expected_after_ensure = [
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "functions.register",
    ]
    for step_id in expected_after_ensure:
        assert step_id in ids, step_id

    # functions.register substitutes cli.fn_apply_selected (CLI registration gone).
    assert "functions.register" in ids
    assert not any(i.startswith("cli.fn_apply_selected") for i in ids)

    # Ordering invariant: provisioning precedes registration.
    assert ids.index("vm.provision_base") < ids.index("repo.sync_to_vm")
    assert ids.index("repo.sync_to_vm") < ids.index("functions.register")
    assert ids.index("cli.build_install_dist") < ids.index("functions.register")


def test_azure_prelude_ansible_targets_public_host() -> None:
    by_id = _prelude_argv()
    provision = by_id["vm.provision_base"]
    assert provision[0] == "ansible-playbook"
    assert "20.0.0.42," in provision            # public host as inventory
    assert "/keys/azure_id_rsa" in provision     # azure key
    # Azure has NO NAT port: no ansible_port plumbing.
    assert not any(arg.startswith("ansible_port=") for arg in provision)
    assert any(arg.endswith("provision-base.yml") for arg in provision)


def test_azure_prelude_repo_sync_uses_public_rsync() -> None:
    by_id = _prelude_argv()
    rsync = by_id["repo.sync_to_vm"]
    assert rsync[0] == "rsync"
    assert any("20.0.0.42" in arg for arg in rsync)


def test_azure_prelude_registers_functions_via_rest_not_cli() -> None:
    plan = _plan()
    orch = FakeAzureOrch(Path("/repo"))
    tasks = plan._build_prelude_tasks(orch, plan._requests()[0], resolve_host=True)  # noqa: SLF001
    ids = [t.task_id for t in tasks]
    assert "functions.register" in ids
    assert not any(i.startswith("cli.fn_apply_selected") for i in ids)
