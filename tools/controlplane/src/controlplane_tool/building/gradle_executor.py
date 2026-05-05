from __future__ import annotations

from pathlib import Path

from controlplane_tool.app.paths import default_tool_paths
from controlplane_tool.building.gradle_planner import (
    build_gradle_command,
    plan_module_matrix_commands,
)
from controlplane_tool.building.requests import BuildRequest
from controlplane_tool.building.tasks import CommandExecutionResult
from controlplane_tool.core.shell_backend import SubprocessShell


class GradleCommandExecutor:
    def __init__(self, repo_root: Path | None = None) -> None:
        self.repo_root = repo_root or default_tool_paths().workspace_root
        self._shell = SubprocessShell()

    def _build_command(
        self,
        action: str,
        profile: str,
        modules: str | None,
        extra_gradle_args: list[str],
    ) -> list[str]:
        request = BuildRequest(
            action=action,
            profile=profile,
            modules=modules,
            extra_gradle_args=extra_gradle_args,
        )
        return build_gradle_command(
            repo_root=self.repo_root,
            request=request,
            extra_gradle_args=extra_gradle_args,
        )

    def execute(
        self,
        action: str,
        profile: str,
        modules: str | None,
        extra_gradle_args: list[str],
        dry_run: bool,
    ) -> CommandExecutionResult:
        command = self._build_command(action, profile, modules, extra_gradle_args)
        if dry_run:
            return CommandExecutionResult(command=command, return_code=0, dry_run=True)

        completed = self._shell.run(command, cwd=self.repo_root, dry_run=False)
        return CommandExecutionResult(
            command=command,
            return_code=completed.return_code,
            dry_run=False,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )

    def execute_matrix(
        self,
        task: str,
        modules: str | None,
        max_combinations: int,
        extra_gradle_args: list[str],
        dry_run: bool,
    ) -> tuple[list[list[str]], int]:
        commands = plan_module_matrix_commands(
            repo_root=self.repo_root,
            task=task,
            max_combinations=max_combinations,
            modules_csv=modules,
            extra_gradle_args=extra_gradle_args,
        )
        if dry_run:
            return commands, 0

        for command in commands:
            completed = self._shell.run(command, cwd=self.repo_root, dry_run=False)
            if completed.return_code != 0:
                return commands, completed.return_code
        return commands, 0
