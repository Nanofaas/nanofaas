from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from shellcraft.backend import ShellExecutionResult
from shellcraft.runners import CommandRunner
from workflow_tasks import fail, step, success

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


def _event_label(cell: ImageMatrixCell, phase: ImagePhase) -> str:
    if cell.flavor == "default":
        return f"{cell.target} {cell.arch} {phase}"
    return f"{cell.target} {cell.arch}-{cell.flavor} {phase}"


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


def _raise_run_error(label: str, result: ImageCellResult) -> None:
    raise ImageMatrixRunError(f"{label} failed: {result.detail}")


def run_image_matrix_plan(
    runner: CommandRunner,
    plan: ImageMatrixPlan,
    dry_run: bool,
    fail_fast: bool,
) -> list[ImageCellResult]:
    results: list[ImageCellResult] = []
    for cell in plan.cells:
        build_label = _event_label(cell, "build")
        step(build_label)
        build_result = _cell_result(cell, "build", cell.build_command.run(runner, dry_run=dry_run))
        results.append(build_result)
        if not build_result.ok:
            fail(build_label, build_result.detail)
            if fail_fast:
                _raise_run_error(build_label, build_result)
            continue

        success(build_label)
        if cell.push_command is None:
            continue

        push_label = _event_label(cell, "push")
        step(push_label)
        push_result = _cell_result(cell, "push", cell.push_command.run(runner, dry_run=dry_run))
        results.append(push_result)
        if not push_result.ok:
            fail(push_label, push_result.detail)
            if fail_fast:
                _raise_run_error(push_label, push_result)
            continue
        success(push_label)
    return results
