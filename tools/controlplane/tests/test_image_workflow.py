from __future__ import annotations

from pathlib import Path

import pytest

from shellcraft.backend import ShellExecutionResult
from shellcraft.runners import CommandRunner
from workflow_tasks import WorkflowContext, bind_workflow_context, bind_workflow_sink
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
    assert [event.task_id for event in fake_sink.events] == [
        "images.watchdog.amd64.build",
        "images.watchdog.amd64.build",
        "images.watchdog.amd64.push",
        "images.watchdog.amd64.push",
    ]


def test_run_image_matrix_plan_emits_distinct_child_task_id_for_each_phase(fake_sink) -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["control-plane"],
        tag="v1.2.3",
        arches=("amd64",),
        flavors=("native",),
        push=True,
        runtime="docker",
    )
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    context = WorkflowContext(flow_id="workflow.images", task_id="images.publish")

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert [event.task_id for event in fake_sink.events] == [
        "images.control_plane.amd64.native.build",
        "images.control_plane.amd64.native.build",
        "images.control_plane.amd64.native.push",
        "images.control_plane.amd64.native.push",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "images.publish",
        "images.publish",
        "images.publish",
        "images.publish",
    ]
    assert [event.title for event in fake_sink.events] == [
        "control-plane amd64-native build",
        "control-plane amd64-native build",
        "control-plane amd64-native push",
        "control-plane amd64-native push",
    ]


def test_run_image_matrix_plan_forwards_dry_run_to_build_and_push() -> None:
    class DryRunRecordingShell:
        def __init__(self) -> None:
            self.calls: list[tuple[list[str], bool]] = []

        def run(self, command, *, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            _ = cwd, env
            self.calls.append((list(command), dry_run))
            return ShellExecutionResult(command=list(command), return_code=0, dry_run=dry_run)

    plan = _watchdog_plan()
    cell = plan.cells[0]
    shell = DryRunRecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert shell.calls == [
        (cell.build_command.command, True),
        (cell.push_command.command, True),
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


def test_run_image_matrix_plan_raises_on_push_failure_when_fail_fast(fake_sink) -> None:
    plan = _watchdog_plan()
    cell = plan.cells[0]
    shell = ScriptedShell(
        return_code_map={tuple(cell.push_command.command): 17},
        stderr_map={tuple(cell.push_command.command): "docker push failed"},
    )
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink), pytest.raises(ImageMatrixRunError, match="docker push failed"):
        run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert shell.commands == [cell.build_command.command, cell.push_command.command]
    assert [(event.kind, event.title, event.detail) for event in fake_sink.events] == [
        ("task.running", "watchdog amd64 build", ""),
        ("task.completed", "watchdog amd64 build", ""),
        ("task.running", "watchdog amd64 push", ""),
        ("task.failed", "watchdog amd64 push", "docker push failed"),
    ]


def test_run_image_matrix_plan_records_build_exception_when_not_fail_fast(fake_sink) -> None:
    class RaisingShell:
        def __init__(self) -> None:
            self.commands: list[list[str]] = []

        def run(self, command, *, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            _ = cwd, env, dry_run
            self.commands.append(list(command))
            raise RuntimeError("docker daemon unavailable")

    plan = _watchdog_plan(arches=("amd64", "arm64"))
    failed_cell = plan.cells[0]
    shell = RaisingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink):
        results = run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=False)

    assert shell.commands == [failed_cell.build_command.command, plan.cells[1].build_command.command]
    assert [
        (result.arch, result.phase, result.ok, result.return_code, result.detail)
        for result in results
    ] == [
        ("amd64", "build", False, 1, "docker daemon unavailable"),
        ("arm64", "build", False, 1, "docker daemon unavailable"),
    ]
    assert [(event.kind, event.title, event.detail) for event in fake_sink.events] == [
        ("task.running", "watchdog amd64 build", ""),
        ("task.failed", "watchdog amd64 build", "docker daemon unavailable"),
        ("task.running", "watchdog arm64 build", ""),
        ("task.failed", "watchdog arm64 build", "docker daemon unavailable"),
    ]


def test_run_image_matrix_plan_raises_build_exception_as_run_error_when_fail_fast(fake_sink) -> None:
    class RaisingShell:
        def run(self, command, *, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            _ = command, cwd, env, dry_run
            raise RuntimeError("docker daemon unavailable")

    plan = _watchdog_plan()
    runner = CommandRunner(shell=RaisingShell(), repo_root=Path("/repo"))

    with bind_workflow_sink(fake_sink), pytest.raises(ImageMatrixRunError, match="docker daemon unavailable"):
        run_image_matrix_plan(runner, plan, dry_run=True, fail_fast=True)

    assert [(event.kind, event.title, event.detail) for event in fake_sink.events] == [
        ("task.running", "watchdog amd64 build", ""),
        ("task.failed", "watchdog amd64 build", "docker daemon unavailable"),
    ]


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
