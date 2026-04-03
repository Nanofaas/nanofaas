from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import subprocess

import typer
from pydantic import ValidationError

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.gradle_planner import build_gradle_command
from controlplane_tool.paths import default_tool_paths

CLI_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}


@dataclass(frozen=True)
class CommandExecutionResult:
    command: list[str]
    return_code: int
    dry_run: bool


class GradleCommandExecutor:
    def __init__(self, repo_root: Path | None = None) -> None:
        self.repo_root = repo_root or default_tool_paths().workspace_root

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

        completed = subprocess.run(
            command,
            cwd=self.repo_root,
            check=False,
        )
        return CommandExecutionResult(
            command=command,
            return_code=completed.returncode,
            dry_run=False,
        )


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
        result = GradleCommandExecutor().execute(
            action=action,
            profile=profile,
            modules=modules,
            extra_gradle_args=_combined_extra_gradle_args(ctx, extra_gradle_arg),
            dry_run=dry_run,
        )
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid build request: {first_error}", err=True)
        raise typer.Exit(code=2)

    if result.dry_run:
        typer.echo(" ".join(result.command))
        return
    if result.return_code != 0:
        raise typer.Exit(code=result.return_code)


def install_cli_commands(app: typer.Typer) -> None:
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
