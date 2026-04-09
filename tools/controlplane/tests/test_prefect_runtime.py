from __future__ import annotations

from datetime import datetime

from controlplane_tool.prefect_runtime import run_local_flow


def test_run_local_flow_returns_normalized_run_metadata() -> None:
    def sample_flow() -> str:
        return "ok"

    result = run_local_flow("sample.flow", sample_flow)

    assert result.result == "ok"
    assert result.status == "completed"
    assert result.flow_id == "sample.flow"
    assert result.flow_run_id
    assert result.orchestrator_backend in {"none", "prefect-local"}
    assert isinstance(result.started_at, datetime)
    assert isinstance(result.finished_at, datetime)
    assert result.started_at <= result.finished_at


def test_run_local_flow_failure_suppresses_prefect_console_noise(capsys) -> None:
    def broken_flow() -> str:
        raise RuntimeError("boom")

    result = run_local_flow("sample.broken", broken_flow)

    captured = capsys.readouterr()
    assert result.status == "failed"
    assert result.error == "boom"
    assert "Beginning flow run" not in captured.out
    assert "Beginning flow run" not in captured.err
    assert "EventsWorker" not in captured.out
    assert "EventsWorker" not in captured.err
