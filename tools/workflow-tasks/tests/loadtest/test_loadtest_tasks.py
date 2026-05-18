from __future__ import annotations

from dataclasses import dataclass

from pathlib import Path

import pytest

from workflow_tasks.loadtest.models import K6Config, K6RunResult, K6Stage
from workflow_tasks.loadtest.tasks import InstallK6, RunK6


@dataclass
class _VmResult:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _RecordingVmRunner:
    def __init__(self, return_code: int = 0) -> None:
        self.return_code = return_code
        self.commands: list[tuple[tuple[str, ...], dict, str | None, bool]] = []

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> _VmResult:
        self.commands.append((argv, env, remote_dir, dry_run))
        return _VmResult(return_code=self.return_code)


def _make_k6_config(tmp_path: Path) -> K6Config:
    return K6Config(
        script_path=Path("/remote/scripts/test.js"),
        target_url="http://10.0.0.1:8080",
        summary_output_path=Path("/remote/results/summary.json"),
        stages=(K6Stage(duration="30s", target=5),),
        env={"NANOFAAS_FUNCTION": "my-fn"},
    )


def test_install_k6_runs_bash_install_command() -> None:
    runner = _RecordingVmRunner()
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")
    task.run()
    assert len(runner.commands) == 1
    argv, _, remote_dir, _ = runner.commands[0]
    assert argv[0] == "bash"
    assert "k6" in argv[-1]
    assert remote_dir == "/home/ubuntu"


def test_install_k6_raises_on_nonzero_exit() -> None:
    runner = _RecordingVmRunner(return_code=1)
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")
    with pytest.raises(RuntimeError):
        task.run()


def test_run_k6_passes_summary_export_flag(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    assert "--summary-export" in argv
    assert str(config.summary_output_path) in argv


def test_run_k6_injects_env_vars_as_e_flags(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    argv_str = " ".join(argv)
    assert "NANOFAAS_FUNCTION=my-fn" in argv_str


def test_run_k6_returns_k6_run_result_with_timing(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    result = task.run()
    assert isinstance(result, K6RunResult)
    assert result.summary_path == config.summary_output_path
    assert result.started_at <= result.ended_at
    assert result.passed is True


def test_run_k6_marks_failed_on_nonzero_exit(tmp_path: Path) -> None:
    runner = _RecordingVmRunner(return_code=1)
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    result = task.run()
    assert result.passed is False


def test_run_k6_result_property_raises_before_run(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    with pytest.raises(RuntimeError, match="not been called"):
        _ = task.result


def test_run_k6_result_property_returns_after_run(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    assert task.result.passed is True


def test_run_k6_passes_vus_flag_when_set(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = K6Config(
        script_path=Path("/remote/scripts/test.js"),
        target_url="http://10.0.0.1:8080",
        summary_output_path=Path("/remote/results/summary.json"),
        vus=10,
    )
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    assert "--vus" in argv
    assert "10" in argv
    assert "--stage" not in argv


def test_run_k6_passes_duration_flag_when_set(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = K6Config(
        script_path=Path("/remote/scripts/test.js"),
        target_url="http://10.0.0.1:8080",
        summary_output_path=Path("/remote/results/summary.json"),
        duration="2m",
    )
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    assert "--duration" in argv
    assert "2m" in argv
    assert "--stage" not in argv


def test_run_k6_injects_payload_path_when_set(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = K6Config(
        script_path=Path("/remote/scripts/test.js"),
        target_url="http://10.0.0.1:8080",
        summary_output_path=Path("/remote/results/summary.json"),
        payload_path=Path("/remote/payloads/data.json"),
    )
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv_str = " ".join(runner.commands[0][0])
    assert "NANOFAAS_PAYLOAD=/remote/payloads/data.json" in argv_str
