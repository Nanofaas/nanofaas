from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

from workflow_tasks.shell import ShellExecutionResult
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
from controlplane_tool.infra.vm.vm_models import VmRequest


def _azure_request() -> E2eRequest:
    return E2eRequest(
        scenario="loadtest-azure",
        runtime="java",
        vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure",
            user="azureuser",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
        loadgen_vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure-loadgen",
            user="azureuser",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
    )


def _ok() -> ShellExecutionResult:
    return ShellExecutionResult(command=[], return_code=0, stdout="")


def _write_default_k6_asset(repo_root: Path) -> Path:
    script_path = repo_root / "tools" / "controlplane" / "assets" / "k6" / "two-vm-function-invoke.js"
    script_path.parent.mkdir(parents=True)
    script_path.write_text("export default function () {}\n", encoding="utf-8")
    return script_path


def test_runner_accepts_azure_vm_orchestrator(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "1.2.3.4",
    )

    result = runner.run_k6(_azure_request())

    assert mock_orch.transfer_to.called
    assert mock_orch.exec_argv.called
    assert result.target_function == "echo-test"


def test_runner_transfers_script_to_loadgen_vm(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "1.2.3.4",
    )
    runner.run_k6(_azure_request())

    transfer_calls = mock_orch.transfer_to.call_args_list
    transferred_destinations = [str(call.kwargs.get("destination", "")) for call in transfer_calls]
    assert any("script.js" in d for d in transferred_destinations)


def test_runner_executes_k6_with_control_plane_url(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda vm: "10.0.0.1" if vm.name == "nanofaas-azure" else "10.0.0.2",
    )
    runner.run_k6(_azure_request())

    exec_calls = mock_orch.exec_argv.call_args_list
    all_exec_args = " ".join(str(c) for c in exec_calls)
    assert "http://10.0.0.1:30080" in all_exec_args


def test_azure_vm_loadtest_plan_task_ids_canonical() -> None:
    """B3c: azure now routes run() through run_loadtest_flow and GAINS the canonical
    provisioning prelude + in-prelude functions.register. The task_ids are derived
    from the real recipe (NOT a stub), pinning the new provisioning behavior.

    Azure has NO NAT publish step (direct public host), so unlike proxmox there is
    no vm.stack.publish_ports. The unified _destroy_tasks tears down BOTH loadgen
    and stack (behavior change: the legacy skeleton destroyed only loadgen).
    """
    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_runner import E2eRunner

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(_azure_request())
    ids = plan.task_ids

    required = [
        # Canonical execution-order emission via run_loadtest_flow names the
        # stack-ensure step "vm.stack.ensure_running" (matching two-vm/proxmox).
        "vm.stack.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core.control_image",
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "functions.register",
        "vm.loadgen.ensure_running",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "loadgen.fetch_results",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        # Behavior change: cleanup destroys BOTH loadgen AND stack.
        "vm.loadgen.destroy",
        "vm.stack.destroy",
    ]
    for step_id in required:
        assert step_id in ids, step_id

    # Azure is a direct public host: NO NAT publish-port step.
    assert "vm.stack.publish_ports" not in ids
    # cli.fn_apply_selected is substituted by REST functions.register.
    assert "cli.fn_apply_selected" not in ids
    # Load-bearing invariant: provisioning + registration precede the loadgen body.
    assert ids.index("functions.register") < ids.index("loadgen.install_k6")
    assert ids.index("cli.build_install_dist") < ids.index("functions.register")


def test_azure_loadgen_install_uses_runplaybook_not_bash() -> None:
    """The loadgen install step must be the ansible RunPlaybook, not bash InstallK6.

    B3c: azure now routes run() through run_loadtest_flow, so the loadgen body is
    built by the shared driver (loadtest_flow._build_loadgen_body), which calls
    build_loadgen_body_tasks (install_k6_task / ansible-based install internally).
    Asserting against the driver source preserves the invariant (build_loadgen_body_tasks
    present, InstallK6 construction absent).
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow

    source = inspect.getsource(loadtest_flow._build_loadgen_body)
    assert "build_loadgen_body_tasks(" in source
    assert "InstallK6(" not in source
