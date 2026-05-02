"""
Tests for run_models.py — StepResult and RunResult data classes.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.run_models import RunResult, StepResult


def test_step_result_fields() -> None:
    step = StepResult(name="compile", status="passed", detail="ok (123 ms)", duration_ms=123)
    assert step.name == "compile"
    assert step.status == "passed"
    assert step.detail == "ok (123 ms)"
    assert step.duration_ms == 123


def test_step_result_is_frozen() -> None:
    step = StepResult(name="compile", status="passed", detail="ok", duration_ms=0)
    try:
        step.name = "other"  # type: ignore[misc]
        assert False, "Expected FrozenInstanceError"
    except Exception:
        pass  # expected


def test_run_result_fields(tmp_path: Path) -> None:
    steps = [StepResult(name="s1", status="passed", detail="ok", duration_ms=10)]
    result = RunResult(
        profile_name="qa",
        run_dir=tmp_path,
        final_status="passed",
        steps=steps,
    )
    assert result.profile_name == "qa"
    assert result.run_dir == tmp_path
    assert result.final_status == "passed"
    assert len(result.steps) == 1


def test_run_result_is_frozen(tmp_path: Path) -> None:
    result = RunResult(
        profile_name="qa",
        run_dir=tmp_path,
        final_status="passed",
        steps=[],
    )
    try:
        result.final_status = "failed"  # type: ignore[misc]
        assert False, "Expected FrozenInstanceError"
    except Exception:
        pass  # expected


def test_run_result_empty_steps(tmp_path: Path) -> None:
    result = RunResult(profile_name="qa", run_dir=tmp_path, final_status="skipped", steps=[])
    assert result.steps == []
