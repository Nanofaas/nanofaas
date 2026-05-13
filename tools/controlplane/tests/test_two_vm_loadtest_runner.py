from datetime import datetime, timezone
from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result, TwoVmLoadtestRunner
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


def test_two_vm_loadtest_runner_captures_prometheus_snapshots(
    tmp_path: Path,
    monkeypatch,
) -> None:
    captured: dict[str, object] = {}

    def capture(**kwargs):
        captured.update(kwargs)
        return tmp_path / "metrics" / "prometheus-snapshots.json"

    monkeypatch.setattr("controlplane_tool.e2e.two_vm_loadtest_runner.capture_prometheus_snapshots", capture)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    started_at = datetime(2026, 5, 13, 10, 0, tzinfo=timezone.utc)
    ended_at = datetime(2026, 5, 13, 10, 1, tzinfo=timezone.utc)
    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        shell=RecordingShell(),
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "10.0.0.1",
    )

    result_path = runner.capture_prometheus_snapshots(
        request,
        TwoVmK6Result(
            run_dir=tmp_path,
            k6_summary_path=tmp_path / "k6-summary.json",
            target_function="word-stats-java",
            started_at=started_at,
            ended_at=ended_at,
        ),
    )

    assert result_path == tmp_path / "metrics" / "prometheus-snapshots.json"
    assert captured["prometheus_url"] == "http://10.0.0.1:30090"
    assert captured["output_dir"] == tmp_path
    assert captured["start"] == started_at
    assert captured["end"] == ended_at
