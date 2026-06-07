from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

from workflow_tasks.loadtest.loadgen_sequence import (
    LoadgenBodyInputs,
    build_loadgen_body_tasks,
    make_loadtest_k6_config,
)
from workflow_tasks.loadtest.models import K6RunResult, PrometheusQuery


def _remote_paths(payload: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        script_path="/home/ubuntu/run/script.js",
        summary_path="/home/ubuntu/run/k6-summary.json",
        payload_path=payload,
    )


class _FakeRunner:
    pass


class _FakeFetcher:
    pass


class _FakeClient:
    pass


class _FakeShell:
    pass


def test_make_k6_config_maps_fields_and_env() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(),
        control_plane_url="http://10.0.0.5:8080",
        target_function="echo",
        stages=[("30s", 5), ("1m", 10)],
        vus=None,
        duration=None,
    )
    assert cfg.script_path == Path("/home/ubuntu/run/script.js")
    assert cfg.target_url == "http://10.0.0.5:8080"
    assert cfg.summary_output_path == Path("/home/ubuntu/run/k6-summary.json")
    assert [(s.duration, s.target) for s in cfg.stages] == [("30s", 5), ("1m", 10)]
    assert cfg.env["NANOFAAS_URL"] == "http://10.0.0.5:8080"
    assert cfg.env["NANOFAAS_FUNCTION"] == "echo"
    assert "NANOFAAS_PAYLOAD" not in cfg.env
    assert cfg.payload_path is None


def test_make_k6_config_includes_payload_when_present() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(payload="/home/ubuntu/run/payload.json"),
        control_plane_url="http://h:8080",
        target_function="echo",
        stages=[],
        vus=4,
        duration="2m",
    )
    assert cfg.env["NANOFAAS_PAYLOAD"] == "/home/ubuntu/run/payload.json"
    assert cfg.payload_path == Path("/home/ubuntu/run/payload.json")
    assert cfg.vus == 4
    assert cfg.duration == "2m"


def _inputs(tmp_path) -> LoadgenBodyInputs:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(),
        control_plane_url="http://h:8080",
        target_function="echo",
        stages=[("30s", 5)],
        vus=None,
        duration=None,
    )
    return LoadgenBodyInputs(
        task_ids=("loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
                  "metrics.prometheus_snapshot", "loadtest.write_report"),
        titles=("Install k6 on loadgen VM", "Run k6 loadtest", "Fetch k6 results from loadgen VM",
                "Capture Prometheus snapshots", "Write loadtest report"),
        runner=_FakeRunner(),
        fetcher=_FakeFetcher(),
        prometheus_client=_FakeClient(),
        prometheus_queries=(PrometheusQuery(name="q", expr="up", required=True),),
        k6_config=cfg,
        remote_dir="/home/ubuntu",
        remote_summary_path="/home/ubuntu/run/k6-summary.json",
        run_dir=tmp_path / "run",
        repo_root=tmp_path,
        shell=_FakeShell(),
        install_host="1.2.3.4",
        install_user="ubuntu",
        install_private_key=None,
        install_port=None,
    )


def test_build_loadgen_body_tasks_ids_and_titles(tmp_path) -> None:
    tasks = build_loadgen_body_tasks(_inputs(tmp_path))
    assert [t.task_id for t in tasks] == [
        "loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
        "metrics.prometheus_snapshot", "loadtest.write_report",
    ]
    assert [t.title for t in tasks] == [
        "Install k6 on loadgen VM", "Run k6 loadtest", "Fetch k6 results from loadgen VM",
        "Capture Prometheus snapshots", "Write loadtest report",
    ]


def test_build_loadgen_body_window_reads_run_k6_result(tmp_path) -> None:
    tasks = build_loadgen_body_tasks(_inputs(tmp_path))
    run_k6 = tasks[1]
    prom = tasks[3]
    started = datetime(2026, 6, 6, 12, 0, 0, tzinfo=timezone.utc)
    ended = datetime(2026, 6, 6, 12, 5, 0, tzinfo=timezone.utc)
    run_k6._result = K6RunResult(  # noqa: SLF001
        summary_path=Path("/x"), started_at=started, ended_at=ended, passed=True
    )
    window = prom.window()
    assert window.start == started
    assert window.end == ended
