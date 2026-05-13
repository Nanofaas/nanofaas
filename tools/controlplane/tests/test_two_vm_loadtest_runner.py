from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
from controlplane_tool.infra.vm.vm_models import VmRequest


def _write_default_k6_asset(repo_root: Path) -> Path:
    script_path = repo_root / "tools" / "controlplane" / "assets" / "k6" / "two-vm-function-invoke.js"
    script_path.parent.mkdir(parents=True)
    script_path.write_text("export default function () {}\n", encoding="utf-8")
    return script_path


def test_two_vm_loadtest_runner_executes_k6_on_loadgen_vm(tmp_path: Path) -> None:
    _write_default_k6_asset(tmp_path)
    shell = RecordingShell()
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        shell=shell,
        runs_root=tmp_path / "runs",
        host_resolver=lambda vm: "10.0.0.2" if vm.name == "nanofaas-e2e-loadgen" else "10.0.0.1",
    )

    result = runner.run_k6(request)

    rendered = [" ".join(command) for command in shell.commands]
    assert any(command.startswith("multipass transfer ") and "script.js" in command for command in rendered)
    assert any(command.startswith("multipass exec nanofaas-e2e-loadgen ") for command in rendered)
    assert any("NANOFAAS_URL=http://10.0.0.1:30080" in command for command in rendered)
    assert any("NANOFAAS_FUNCTION=word-stats-java" in command for command in rendered)
    assert any(command.endswith(f" {result.k6_summary_path}") for command in rendered)
    assert result.k6_summary_path.name == "k6-summary.json"
    assert result.target_function == "word-stats-java"


def test_two_vm_loadtest_runner_transfers_custom_payload(tmp_path: Path) -> None:
    _write_default_k6_asset(tmp_path)
    payload = tmp_path / "payload.json"
    payload.write_text('{"message":"hello"}\n', encoding="utf-8")
    shell = RecordingShell()
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
        k6_payload=payload,
    )
    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        shell=shell,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "10.0.0.1",
    )

    runner.run_k6(request)

    rendered = [" ".join(command) for command in shell.commands]
    assert any(str(payload) in command and "payload.json" in command for command in rendered)
    assert any("NANOFAAS_PAYLOAD=/home/ubuntu/two-vm-loadtest/payloads/payload.json" in command for command in rendered)


def test_two_vm_loadtest_runner_isolates_payload_from_script_and_summary(tmp_path: Path) -> None:
    _write_default_k6_asset(tmp_path)
    payload = tmp_path / "script.js"
    payload.write_text('{"message":"collision"}\n', encoding="utf-8")
    shell = RecordingShell()
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
        k6_payload=payload,
    )
    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        shell=shell,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "10.0.0.1",
    )

    runner.run_k6(request)

    rendered = [" ".join(command) for command in shell.commands]
    assert any("/two-vm-loadtest/scripts/script.js" in command for command in rendered)
    assert any("/two-vm-loadtest/payloads/script.js" in command for command in rendered)
    assert any("/two-vm-loadtest/results/k6-summary.json" in command for command in rendered)
