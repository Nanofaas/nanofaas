from __future__ import annotations

from datetime import datetime, timezone

import pytest
import typer

from controlplane_tool.cli.flow_exit import exit_on_failed_flow
from workflow_tasks.orchestration import FlowRunResult


def _result(status: str, error: str | None = None) -> FlowRunResult:
    now = datetime.now(timezone.utc)
    return FlowRunResult(
        flow_id="e2e.test",
        flow_run_id="run-1",
        orchestrator_backend="none",
        started_at=now,
        finished_at=now,
        status=status,
        error=error,
    )


def test_completed_flow_is_a_noop(capsys) -> None:
    exit_on_failed_flow(_result("completed"))
    assert capsys.readouterr().err == ""


def test_failed_flow_prints_error_and_exits(capsys) -> None:
    with pytest.raises(typer.Exit) as excinfo:
        exit_on_failed_flow(_result("failed", error="RuntimeError: boom\nTraceback ..."))
    assert excinfo.value.exit_code == 1
    err = capsys.readouterr().err
    assert "e2e.test" in err
    assert "boom" in err


def test_failed_flow_without_recorded_error_still_says_so(capsys) -> None:
    with pytest.raises(typer.Exit):
        exit_on_failed_flow(_result("failed", error=None))
    assert "no recorded error" in capsys.readouterr().err
