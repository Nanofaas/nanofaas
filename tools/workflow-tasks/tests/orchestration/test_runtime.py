from __future__ import annotations

from datetime import datetime

from workflow_tasks.orchestration import (
    FlowRunResult,
    LocalFlowDefinition,
    run_local_flow,
)


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


def test_flow_run_result_constructors() -> None:
    now = datetime.now()
    ok = FlowRunResult.completed(
        flow_id="f", flow_run_id="r", orchestrator_backend="none",
        started_at=now, finished_at=now, result=42,
    )
    assert ok.status == "completed"
    assert ok.result == 42
    bad = FlowRunResult.failed(
        flow_id="f", flow_run_id="r", orchestrator_backend="none",
        started_at=now, finished_at=now, error="nope",
    )
    assert bad.status == "failed"
    assert bad.error == "nope"


def test_local_flow_definition_holds_callable() -> None:
    definition = LocalFlowDefinition(flow_id="f", task_ids=["a", "b"], run=lambda: "done")
    assert definition.flow_id == "f"
    assert definition.task_ids == ["a", "b"]
    assert definition.run() == "done"
