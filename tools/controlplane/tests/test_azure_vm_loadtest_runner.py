from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
from controlplane_tool.infra.vm.vm_models import VmRequest


def _azure_request() -> E2eRequest:
    return E2eRequest(
        scenario="azure-vm-loadtest",
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
    assert result.target_function == "word-stats-java"


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
