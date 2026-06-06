from __future__ import annotations

from pathlib import Path

from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.connectivity import MultipassConnectivity


def _runner() -> E2eRunner:
    return E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _request: "10.0.0.9",
    )


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_multipass_connectivity_resolves_host_placeholder() -> None:
    conn = MultipassConnectivity(runner=_runner(), request=_request())
    op = RemoteCommandOperation(
        operation_id="vm.provision_base",
        summary="Provision",
        argv=("ansible-playbook", "-i", "<multipass-ip:nanofaas-e2e>,", "provision-base.yml"),
    )

    resolved = conn.resolve_host_operation(op)

    assert "10.0.0.9," in resolved.argv
    assert "<multipass-ip:nanofaas-e2e>," not in resolved.argv
    assert resolved.operation_id == "vm.provision_base"


def test_multipass_connectivity_vm_runner_wraps_orchestrator() -> None:
    runner = _runner()
    conn = MultipassConnectivity(runner=runner, request=_request())

    vm_runner = conn.vm_runner(VmRequest(lifecycle="multipass", name="nanofaas-e2e"))

    # OrchestratorVmRunner exposes run_vm_command (the VmCommandRunner protocol).
    assert hasattr(vm_runner, "run_vm_command")


def test_multipass_connectivity_remote_dir_matches_orchestrator() -> None:
    runner = _runner()
    conn = MultipassConnectivity(runner=runner, request=_request())
    vm_request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    assert conn.remote_dir(vm_request) == runner.vm.remote_project_dir(vm_request)
