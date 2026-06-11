from __future__ import annotations

from pathlib import Path as _Path

import typer
from pydantic import ValidationError
from shellcraft.runners import CommandRunner

from controlplane_tool.building import image_matrix
from controlplane_tool.building.gradle_executor import GradleCommandExecutor
from controlplane_tool.core.models import BuildAction, ProfileName
from controlplane_tool.cli.flow_exit import exit_on_failed_flow
from controlplane_tool.orchestation.flow_catalog import resolve_flow_definition
from workflow_tasks.orchestration import run_local_flow
from workflow_tasks.shell import SubprocessShell

CLI_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}


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
    action: BuildAction,
    profile: ProfileName,
    modules: str | None,
    dry_run: bool,
    extra_gradle_arg: list[str] | None,
) -> None:
    try:
        flow = resolve_flow_definition(
            f"building.{action}",
            profile=profile,
            modules=modules,
            extra_gradle_args=_combined_extra_gradle_args(ctx, extra_gradle_arg),
            dry_run=dry_run,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid building request: {first_error}", err=True)
        raise typer.Exit(code=2)

    exit_on_failed_flow(flow_result)
    result = flow_result.result
    if result is None:
        raise typer.Exit(code=1)
    if result.dry_run:
        typer.echo(" ".join(result.command))
        return
    if result.return_code != 0:
        stdout = getattr(result, "stdout", "") or ""
        stderr = getattr(result, "stderr", "") or ""
        if stdout:
            typer.echo(str(stdout).rstrip())
        if stderr:
            typer.echo(str(stderr).rstrip(), err=True)
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
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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

    @app.command("building", context_settings=CLI_CONTEXT_SETTINGS)
    def build_command(
        ctx: typer.Context,
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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
            action="building",
            profile=profile,
            modules=modules,
            dry_run=dry_run,
            extra_gradle_arg=extra_gradle_arg,
        )

    @app.command("run", context_settings=CLI_CONTEXT_SETTINGS)
    def run_command(
        ctx: typer.Context,
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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

    @app.command("images", context_settings=CLI_CONTEXT_SETTINGS)
    def images_command(
        tag: str | None = typer.Option(None, "--tag", help="Image tag (default: version from build.gradle)."),
        only: str = typer.Option("all", "--only", help="Comma-separated target names or 'all'."),
        arch: str = typer.Option("amd64", "--arch", help="amd64 | arm64 | multi."),
        arch_suffix: bool = typer.Option(False, "--arch-suffix/--no-arch-suffix", help="Append -<arch> to the tag."),
        push: bool = typer.Option(True, "--push/--no-push", help="Push images after building."),
        runtime: str = typer.Option("docker", "--runtime", help="Container runtime CLI."),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned build/push commands only."),
    ) -> None:
        repo_root = _Path.cwd()
        resolved_tag = tag or image_matrix.resolve_current_version(repo_root)
        try:
            targets = image_matrix.select_targets(only)
        except ValueError as exc:
            raise typer.BadParameter(str(exc)) from exc
        runner = CommandRunner(shell=SubprocessShell(), repo_root=repo_root)
        image_matrix.run_image_matrix(
            runner=runner, repo_root=repo_root, targets=targets, tag=resolved_tag,
            arch=arch, use_arch_suffix=arch_suffix, push=push, runtime=runtime, dry_run=dry_run,
        )

    @app.command("native", context_settings=CLI_CONTEXT_SETTINGS)
    def native_command(
        ctx: typer.Context,
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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
        profile: ProfileName = typer.Option(..., "--profile", help="Named control-plane profile."),
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
