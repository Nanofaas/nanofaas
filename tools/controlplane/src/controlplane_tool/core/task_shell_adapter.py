from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


class ShellCommandTaskRunner:
    """Adapter from the pure task runner protocol to the controlplane shell backend."""

    def __init__(self, shell: ShellBackend | None = None) -> None:
        self._shell = shell or SubprocessShell()

    def run(
        self,
        argv: list[str],
        *,
        cwd: Path | None,
        env: dict[str, str],
        dry_run: bool,
    ) -> ShellExecutionResult:
        return self._shell.run(
            argv,
            cwd=cwd,
            env=env,
            dry_run=dry_run,
        )


def task_result_to_shell_result(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    dry_run: bool = False,
) -> ShellExecutionResult:
    return_code = (
        result.return_code
        if result.return_code is not None
        else 0
        if result.status == "passed"
        else 1
    )
    return ShellExecutionResult(
        command=list(task.argv),
        return_code=return_code,
        stdout=result.stdout,
        stderr=result.stderr,
        dry_run=dry_run,
        env=dict(task.env),
    )
