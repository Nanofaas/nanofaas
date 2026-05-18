from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from workflow_tasks.loadtest.models import (
    K6Config,
    K6RunResult,
    K6Stage,
    PrometheusQuery,
    TimeWindow,
)


def test_k6_stage_is_frozen() -> None:
    stage = K6Stage(duration="30s", target=10)
    assert stage.duration == "30s"
    assert stage.target == 10


def test_k6_config_defaults() -> None:
    config = K6Config(
        script_path=Path("/scripts/test.js"),
        target_url="http://localhost:8080",
        summary_output_path=Path("/results/summary.json"),
    )
    assert config.stages == ()
    assert config.env == {}
    assert config.vus is None
    assert config.duration is None
    assert config.payload_path is None


def test_k6_run_result_passed_flag() -> None:
    now = datetime.now(timezone.utc)
    result = K6RunResult(
        summary_path=Path("/results/summary.json"),
        started_at=now,
        ended_at=now,
        passed=True,
    )
    assert result.passed is True


def test_time_window_stores_start_end() -> None:
    start = datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc)
    end = datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc)
    window = TimeWindow(start=start, end=end)
    assert window.start == start
    assert window.end == end


def test_prometheus_query_defaults() -> None:
    q = PrometheusQuery(name="requests_total", expr="sum(http_requests_total)")
    assert q.required is False


def test_k6_config_env_is_isolated_from_caller() -> None:
    original_env = {"NANOFAAS_FUNCTION": "my-fn"}
    config = K6Config(
        script_path=Path("/scripts/test.js"),
        target_url="http://localhost:8080",
        summary_output_path=Path("/results/summary.json"),
        env=original_env,
    )
    original_env["INJECTED"] = "bad"
    assert "INJECTED" not in config.env
