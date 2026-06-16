from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Literal

from shellcraft.backend import ShellExecutionResult
from shellcraft.runners import CommandRunner, PlannedCommand
from workflow_tasks import workflow_step

from controlplane_tool.building.image_plan import ImageFlavor, ImageMatrixCell, ImageMatrixPlan

ImagePhase = Literal["build", "push"]


class ImageMatrixRunError(RuntimeError):
    pass


@dataclass(frozen=True)
class ImageCellResult:
    target: str
    arch: str
    flavor: ImageFlavor
    image: str
    phase: ImagePhase
    ok: bool
    return_code: int
    detail: str = ""


class _ImagePhaseFailed(RuntimeError):
    def __init__(self, result: ImageCellResult) -> None:
        super().__init__(result.detail)
        self.result = result


def _event_label(cell: ImageMatrixCell, phase: ImagePhase) -> str:
    if cell.flavor == "default":
        return f"{cell.target} {cell.arch} {phase}"
    return f"{cell.target} {cell.arch}-{cell.flavor} {phase}"


def _task_id_part(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")


def _event_task_id(cell: ImageMatrixCell, phase: ImagePhase) -> str:
    parts = ["images", _task_id_part(cell.target), _task_id_part(cell.arch)]
    if cell.flavor != "default":
        parts.append(_task_id_part(cell.flavor))
    parts.append(phase)
    return ".".join(parts)


def _failure_detail(result: ShellExecutionResult) -> str:
    return result.stderr.strip() or result.stdout.strip() or f"exit code {result.return_code}"


def _cell_result(
    cell: ImageMatrixCell,
    phase: ImagePhase,
    result: ShellExecutionResult,
) -> ImageCellResult:
    ok = result.return_code == 0
    return ImageCellResult(
        target=cell.target,
        arch=cell.arch,
        flavor=cell.flavor,
        image=cell.image,
        phase=phase,
        ok=ok,
        return_code=result.return_code,
        detail="" if ok else _failure_detail(result),
    )


def _exception_result(cell: ImageMatrixCell, phase: ImagePhase, exc: Exception) -> ImageCellResult:
    detail = str(exc).strip() or exc.__class__.__name__
    return ImageCellResult(
        target=cell.target,
        arch=cell.arch,
        flavor=cell.flavor,
        image=cell.image,
        phase=phase,
        ok=False,
        return_code=1,
        detail=detail,
    )


def _raise_run_error(label: str, result: ImageCellResult) -> None:
    raise ImageMatrixRunError(f"{label} failed: {result.detail}")


def _command_for_phase(cell: ImageMatrixCell, phase: ImagePhase) -> PlannedCommand:
    if phase == "build":
        return cell.build_command
    if cell.push_command is None:
        raise ValueError("push phase requested for a cell without a push command")
    return cell.push_command


def _run_phase(
    runner: CommandRunner,
    cell: ImageMatrixCell,
    phase: ImagePhase,
    *,
    dry_run: bool,
) -> ImageCellResult:
    label = _event_label(cell, phase)
    command = _command_for_phase(cell, phase)
    with workflow_step(task_id=_event_task_id(cell, phase), title=label):
        try:
            shell_result = command.run(runner, dry_run=dry_run)
        except Exception as exc:
            raise _ImagePhaseFailed(_exception_result(cell, phase, exc)) from exc
        result = _cell_result(cell, phase, shell_result)
        if not result.ok:
            raise _ImagePhaseFailed(result)
        return result


def run_image_matrix_plan(
    runner: CommandRunner,
    plan: ImageMatrixPlan,
    dry_run: bool,
    fail_fast: bool,
) -> list[ImageCellResult]:
    results: list[ImageCellResult] = []
    for cell in plan.cells:
        build_label = _event_label(cell, "build")
        try:
            build_result = _run_phase(runner, cell, "build", dry_run=dry_run)
        except _ImagePhaseFailed as exc:
            results.append(exc.result)
            if fail_fast:
                _raise_run_error(build_label, exc.result)
            continue
        results.append(build_result)
        if cell.push_command is None:
            continue

        push_label = _event_label(cell, "push")
        try:
            push_result = _run_phase(runner, cell, "push", dry_run=dry_run)
        except _ImagePhaseFailed as exc:
            results.append(exc.result)
            if fail_fast:
                _raise_run_error(push_label, exc.result)
            continue
        results.append(push_result)
    return results
