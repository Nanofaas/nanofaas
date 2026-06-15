from __future__ import annotations

from pathlib import Path

import pytest

from shellcraft.runners import CommandRunner
from workflow_tasks import bind_workflow_sink
from workflow_tasks.shell import RecordingShell, ScriptedShell

from controlplane_tool.building.image_plan import ImageMatrixPlan, plan_image_matrix
from controlplane_tool.building.image_workflow import (
    ImageMatrixRunError,
    run_image_matrix_plan,
)


def _watchdog_plan(*, arches: tuple[str, ...] = ("amd64",)) -> ImageMatrixPlan:
    return plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog"],
        tag="v1.2.3",
        arches=arches,
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )


def test_run_image_matrix_plan_executes_build_then_push_for_watchdog_dry_run(fake_sink) -> None:
    plan = _watchdog_plan()
    cell = plan.cells[0]
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink):
        results = run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert shell.commands == [
        cell.build_command.command,
        cell.push_command.command,
    ]
    assert [(result.phase, result.ok, result.return_code) for result in results] == [
        ("build", True, 0),
        ("push", True, 0),
    ]
    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
    ]
    assert [event.title for event in fake_sink.events] == [
        "watchdog amd64 build",
        "watchdog amd64 build",
        "watchdog amd64 push",
        "watchdog amd64 push",
    ]


def test_run_image_matrix_plan_raises_on_build_failure_when_fail_fast(fake_sink) -> None:
    plan = _watchdog_plan()
    cell = plan.cells[0]
    shell = ScriptedShell(
        return_code_map={tuple(cell.build_command.command): 9},
        stderr_map={tuple(cell.build_command.command): "docker build failed"},
    )
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink), pytest.raises(ImageMatrixRunError, match="docker build failed"):
        run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert shell.commands == [cell.build_command.command]
    assert [event.kind for event in fake_sink.events] == ["task.running", "task.failed"]
    assert fake_sink.events[-1].title == "watchdog amd64 build"
    assert fake_sink.events[-1].detail == "docker build failed"


def test_run_image_matrix_plan_collects_failures_when_not_fail_fast(fake_sink) -> None:
    plan = _watchdog_plan(arches=("amd64", "arm64"))
    failed_cell = plan.cells[0]
    successful_cell = plan.cells[1]
    shell = ScriptedShell(
        return_code_map={tuple(failed_cell.build_command.command): 9},
        stderr_map={tuple(failed_cell.build_command.command): "docker build failed"},
    )
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink):
        results = run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=False)

    assert shell.commands == [
        failed_cell.build_command.command,
        successful_cell.build_command.command,
        successful_cell.push_command.command,
    ]
    assert [(result.arch, result.phase, result.ok, result.return_code, result.detail) for result in results] == [
        ("amd64", "build", False, 9, "docker build failed"),
        ("arm64", "build", True, 0, ""),
        ("arm64", "push", True, 0, ""),
    ]
    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.failed",
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
    ]
