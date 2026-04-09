from __future__ import annotations

from pathlib import Path

import typer
from pydantic import ValidationError

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.build_tasks import CommandExecutionResult
from controlplane_tool.gradle_planner import (
    build_gradle_command,
    plan_module_matrix_commands,
)
from controlplane_tool.infra_flows import build_gradle_action_flow
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.shell_backend import SubprocessShell

CLI_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}

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


def _combined_extra_gradle_args(
    ctx: typer.Context,
    extra_gradle_arg: list[str] | None,
) -> list[str]:
    combined: list[str] = list(extra_gradle_arg or [])
    combined.extend(ctx.args)
    return combined


def _run_gradle_action(
    *,
    ctx: typer.Context,
    action: str,
    profile: str,
    modules: str | None,
    dry_run: bool,
    extra_gradle_arg: list[str] | None,
) -> None:
    try:
        flow = build_gradle_action_flow(
            action=action,
            profile=profile,
            modules=modules,
            extra_gradle_args=_combined_extra_gradle_args(ctx, extra_gradle_arg),
            dry_run=dry_run,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid build request: {first_error}", err=True)
        raise typer.Exit(code=2)

    result = flow_result.result
    if flow_result.status != "completed" or result is None:
        raise typer.Exit(code=1)
    if result.dry_run:
        typer.echo(" ".join(result.command))
        return
    if result.return_code != 0:
        raise typer.Exit(code=result.return_code)


def _run_matrix_action(
    *,
    ctx: typer.Context,
    task: str,
    modules: str | None,
    max_combinations: int,
    dry_run: bool,
    extra_gradle_arg: list[str] | None,
) -> None:
    try:
        commands, return_code = GradleCommandExecutor().execute_matrix(
            task=task,
            modules=modules,
            max_combinations=max_combinations,
            extra_gradle_args=_combined_extra_gradle_args(ctx, extra_gradle_arg),
            dry_run=dry_run,
        )
    except (ValidationError, ValueError) as exc:
        message = (
            exc.errors()[0]["msg"]
            if isinstance(exc, ValidationError) and exc.errors()
            else str(exc)
        )
        typer.echo(f"Invalid matrix request: {message}", err=True)
        raise typer.Exit(code=2)

    for command in commands:
        typer.echo(" ".join(command))

    if return_code != 0:
        raise typer.Exit(code=return_code)


def install_cli_commands(app: typer.Typer) -> None:
    @app.command("jar", context_settings=CLI_CONTEXT_SETTINGS)
    def jar_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="jar",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("build", context_settings=CLI_CONTEXT_SETTINGS)
    def build_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="build",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("run", context_settings=CLI_CONTEXT_SETTINGS)
    def run_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="run",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("image", context_settings=CLI_CONTEXT_SETTINGS)
    def image_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="image",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("native", context_settings=CLI_CONTEXT_SETTINGS)
    def native_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="native",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("test", context_settings=CLI_CONTEXT_SETTINGS)
    def test_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="test",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("inspect", context_settings=CLI_CONTEXT_SETTINGS)
    def inspect_command(
        ctx: typer.Context,
        profile: str = typer.Option(..., "--profile", help="Named control-plane profile."),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module selector override.",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle command only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_gradle_action(
            ctx=ctx,
            action="inspect",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("matrix", context_settings=CLI_CONTEXT_SETTINGS)
    def matrix_command(
        ctx: typer.Context,
        task: str = typer.Option(
            ":control-plane:bootJar",
            "--task",
            help="Gradle task to run for each module combination.",
        ),
        modules: str | None = typer.Option(
            None,
            "--modules",
            help="Explicit module list override as CSV.",
        ),
        max_combinations: int = typer.Option(
            0,
            "--max-combinations",
            min=0,
            help="Run only the first N combinations (0 = all).",
        ),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned Gradle commands only."),
        extra_gradle_arg: list[str] | None = typer.Option(
            None,
            "--extra-gradle-arg",
            help="Additional Gradle argument. Repeatable.",
        ),
    ) -> None:
        _run_matrix_action(
            ctx=ctx,
            task=task,
            modules=modules,
            max_combinations=max_combinations,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )
