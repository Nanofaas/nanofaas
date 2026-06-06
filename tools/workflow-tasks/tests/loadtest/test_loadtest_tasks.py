from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import pytest

from workflow_tasks.loadtest.models import K6Config, K6RunResult, K6Stage, PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    InstallK6,
    RunK6,
    WriteK6Report,
)


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


def test_install_k6_is_idempotent_and_downloads_binary() -> None:
    runner = _RecordingVmRunner()
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")

    task.run()

    command = runner.commands[0][0][-1]
    assert "which k6 2>/dev/null && exit 0" in command
    assert "api.github.com/repos/grafana/k6/releases/latest" in command
    assert "tar -xz" in command
    assert "/usr/local/bin/k6" in command


def test_install_k6_uses_binary_download_not_apt() -> None:
    runner = _RecordingVmRunner()
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")

    task.run()

    command = runner.commands[0][0][-1]
    assert "github.com/grafana/k6/releases/download" in command
    assert "apt-get install" not in command
    assert "gpg" not in command


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


# ---------------------------------------------------------------------------
# Helpers for new tasks
# ---------------------------------------------------------------------------


class _RecordingFetcher:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Path]] = []

    def fetch_from(self, remote: str, local: Path) -> None:
        self.calls.append((remote, local))


class _RecordingPrometheusClient:
    def __init__(self, points: list[dict] | None = None) -> None:
        self._points = points or [{"timestamp": "2026-01-01T10:00:00Z", "value": 1.0}]
        self.calls: list[tuple[str, TimeWindow, int]] = []

    def query_range(
        self, expr: str, window: TimeWindow, step_seconds: int = 5
    ) -> list[dict]:
        self.calls.append((expr, window, step_seconds))
        return self._points


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


# ---------------------------------------------------------------------------
# FetchVmResults tests
# ---------------------------------------------------------------------------


def test_fetch_vm_results_calls_fetcher(tmp_path: Path) -> None:
    fetcher = _RecordingFetcher()
    task = FetchVmResults(
        task_id="loadgen.fetch_results",
        title="Fetch results",
        fetcher=fetcher,
        remote_source="/remote/results",
        local_dest=tmp_path / "results",
    )
    returned = task.run()
    assert fetcher.calls == [("/remote/results", tmp_path / "results")]
    assert returned == tmp_path / "results"


def test_fetch_vm_results_creates_local_dest(tmp_path: Path) -> None:
    fetcher = _RecordingFetcher()
    dest = tmp_path / "deep" / "nested" / "results"
    task = FetchVmResults(
        task_id="loadgen.fetch_results",
        title="Fetch results",
        fetcher=fetcher,
        remote_source="/remote/results",
        local_dest=dest,
    )
    task.run()
    assert dest.exists()


# ---------------------------------------------------------------------------
# CapturePrometheusSnapshot tests
# ---------------------------------------------------------------------------


def test_capture_prometheus_snapshot_queries_all_metrics(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    queries = (
        PrometheusQuery(name="req_total", expr="sum(http_requests_total)"),
        PrometheusQuery(name="latency", expr="http_req_duration"),
    )
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=queries,
        window=_make_window(),
        output_dir=tmp_path,
    )
    task.run()
    queried_exprs = [call[0] for call in client.calls]
    assert "sum(http_requests_total)" in queried_exprs
    assert "http_req_duration" in queried_exprs


def test_capture_prometheus_snapshot_writes_json(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="req", expr="http_requests_total"),),
        window=_make_window(),
        output_dir=tmp_path,
    )
    dest = task.run()
    assert dest.exists()
    data = json.loads(dest.read_text())
    assert "queries" in data
    assert "req" in data["queries"]


def test_capture_prometheus_snapshot_accepts_callable_window(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    called: list[bool] = []

    def lazy_window() -> TimeWindow:
        called.append(True)
        return _make_window()

    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="req", expr="http_requests_total"),),
        window=lazy_window,
        output_dir=tmp_path,
    )
    task.run()
    assert called == [True]


def test_capture_prometheus_snapshot_raises_when_required_query_fails(tmp_path: Path) -> None:
    class _FailingClient:
        def query_range(self, expr: str, window: TimeWindow, step_seconds: int = 5) -> list[dict]:
            raise RuntimeError("prometheus unreachable")

    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=_FailingClient(),
        queries=(PrometheusQuery(name="critical_metric", expr="some_metric", required=True),),
        window=_make_window(),
        output_dir=tmp_path,
    )
    with pytest.raises(RuntimeError, match="critical_metric"):
        task.run()


def test_capture_prometheus_snapshot_raises_when_required_query_returns_empty(tmp_path: Path) -> None:
    class _EmptyClient:
        def query_range(self, expr: str, window: TimeWindow, step_seconds: int = 5) -> list[dict]:
            return []

    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=_EmptyClient(),
        queries=(PrometheusQuery(name="critical_metric", expr="some_metric", required=True),),
        window=_make_window(),
        output_dir=tmp_path,
    )
    with pytest.raises(RuntimeError, match="critical_metric"):
        task.run()


# ---------------------------------------------------------------------------
# WriteK6Report tests
# ---------------------------------------------------------------------------


def test_write_k6_report_generates_html(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    summary = {
        "metrics": {
            "http_req_duration": {
                "type": "trend",
                "values": {"avg": 123.4, "p(90)": 200.5, "p(95)": 350.2},
            },
            "http_reqs": {
                "type": "counter",
                "values": {"count": 1000, "rate": 10.5},
            },
        }
    }
    (data_dir / "k6-summary.json").write_text(json.dumps(summary), encoding="utf-8")

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    report_path = task.run()
    assert report_path.exists()
    html = report_path.read_text()
    assert "http_req_duration" in html
    assert "http_reqs" in html


def test_write_k6_report_includes_prometheus_section_when_snapshot_present(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    (data_dir / "metrics").mkdir(parents=True)
    (data_dir / "k6-summary.json").write_text(json.dumps({"metrics": {}}), encoding="utf-8")
    snapshot = {
        "queries": {
            "function_dispatch_total": {"points": [{"timestamp": "t", "value": 1.0}]}
        }
    }
    (data_dir / "metrics" / "prometheus-snapshot.json").write_text(
        json.dumps(snapshot), encoding="utf-8"
    )

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    html = task.run().read_text()
    assert "function_dispatch_total" in html


def test_write_k6_report_works_without_prometheus_snapshot(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    (data_dir / "k6-summary.json").write_text(json.dumps({"metrics": {}}), encoding="utf-8")

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    report_path = task.run()
    assert report_path.exists()


class _ClockOffsetPrometheusClient:
    """Prometheus client whose clock is offset from the host (simulates VM drift)."""

    def __init__(self, offset_seconds: float) -> None:
        self._offset = offset_seconds
        self.calls: list[tuple[str, TimeWindow, int]] = []

    def server_time(self) -> float:
        return datetime.now(timezone.utc).timestamp() + self._offset

    def query_range(self, expr: str, window: TimeWindow, step_seconds: int = 5) -> list[dict]:
        self.calls.append((expr, window, step_seconds))
        return [{"timestamp": "t", "value": 1.0}]


def test_capture_prometheus_snapshot_shifts_window_to_prometheus_clock(tmp_path: Path) -> None:
    offset = -1500.0  # Prometheus/VM clock 25 min behind the host (host slept mid-run)
    client = _ClockOffsetPrometheusClient(offset)
    window = _make_window()
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="dispatch", expr="function_dispatch_total", required=True),),
        window=window,
        output_dir=tmp_path,
    )
    task.run()  # must NOT raise: window is aligned so the required query finds data
    queried = client.calls[0][1]
    assert abs(queried.start.timestamp() - (window.start.timestamp() + offset)) <= 60
    assert abs(queried.end.timestamp() - (window.end.timestamp() + offset)) <= 60


def test_capture_prometheus_snapshot_keeps_window_without_server_time(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()  # no server_time() -> unshifted (back-compat)
    window = _make_window()
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="req", expr="http_requests_total"),),
        window=window,
        output_dir=tmp_path,
    )
    task.run()
    assert client.calls[0][1] == window
