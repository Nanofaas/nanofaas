from __future__ import annotations

from typing import Protocol

from controlplane_tool.core.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult


class HostCommandTaskExecutor:
    def __init__(self, shell: ShellBackend | None = None) -> None:
        self._shell = shell or SubprocessShell()

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        if task.target != "host":
            raise ValueError(f"HostCommandTaskExecutor cannot run {task.target!r} task")
        shell_result = self._shell.run(
            list(task.argv),
            cwd=task.cwd,
            env=dict(task.env),
            dry_run=dry_run,
        )
        status = "passed" if shell_result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=shell_result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=shell_result.stdout,
            stderr=shell_result.stderr,
        )


class VmCommandResult(Protocol):
    @property
    def return_code(self) -> int: ...

    @property
    def stdout(self) -> str: ...

    @property
    def stderr(self) -> str: ...


class VmCommandRunner(Protocol):
    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> VmCommandResult: ...


class VmCommandTaskExecutor:
    def __init__(self, runner: VmCommandRunner) -> None:
        self._runner = runner

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        if task.target != "vm":
            raise ValueError(f"VmCommandTaskExecutor cannot run {task.target!r} task")
        result = self._runner.run_vm_command(
            task.argv,
            env=dict(task.env),
            remote_dir=task.remote_dir,
            dry_run=dry_run,
        )
        status = "passed" if result.return_code in task.expected_exit_codes else "failed"
        return TaskResult(
            task_id=task.task_id,
            status=status,
            return_code=result.return_code,
            expected_exit_codes=task.expected_exit_codes,
            stdout=result.stdout,
            stderr=result.stderr,
        )
