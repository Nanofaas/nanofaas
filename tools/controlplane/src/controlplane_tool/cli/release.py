from __future__ import annotations

from pathlib import Path

import typer

from controlplane_tool.release.run import (
    Amd64ReleasePlan,
    CredentialFiles,
    build_amd64_release_plan,
    git_state,
    run_amd64_release,
)
from controlplane_tool.release.versioning import normalize_version, prepare_version
from controlplane_tool.workspace.paths import default_tool_paths


def _release_plan(
    version: str,
    *,
    environment: Path,
    release_config: Path | None,
    run_dir: Path | None,
    credentials: CredentialFiles | None,
) -> Amd64ReleasePlan:
    paths = default_tool_paths()
    plain_version, _ = normalize_version(version)
    return build_amd64_release_plan(
        repo_root=paths.workspace_root,
        version=version,
        environment_path=environment,
        release_config_path=release_config or paths.tool_root / "release.yaml",
        run_dir=run_dir or paths.runs_dir / "releases" / plain_version,
        credentials=credentials,
    )


def _bad_parameter(error: Exception) -> typer.BadParameter:
    return typer.BadParameter(str(error))


def install_release_commands(app: typer.Typer) -> None:
    release = typer.Typer(help="Prepare and run the guarded Azure image release workflow.")

    @release.command("prepare")
    def prepare_command(version: str = typer.Argument(...)) -> None:
        paths = default_tool_paths()
        source = git_state(paths.workspace_root)
        if not source.clean:
            raise typer.BadParameter("release preparation requires a clean Git tree")
        try:
            updated = prepare_version(paths.workspace_root, version)
        except (FileNotFoundError, PermissionError, RuntimeError, ValueError) as error:
            raise _bad_parameter(error) from error
        for path in updated:
            typer.echo(path.relative_to(paths.workspace_root))
        typer.echo("Commit the prepared version changes before release plan or run.")

    @release.command("plan")
    def plan_command(
        version: str = typer.Argument(...),
        environment: Path = typer.Option(..., "--environment", exists=True, dir_okay=False),
        release_config: Path | None = typer.Option(
            None, "--release-config", exists=True, dir_okay=False
        ),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
    ) -> None:
        try:
            plan = _release_plan(
                version,
                environment=environment,
                release_config=release_config,
                run_dir=run_dir,
                credentials=None,
            )
        except (FileNotFoundError, PermissionError, RuntimeError, ValueError) as error:
            raise _bad_parameter(error) from error
        typer.echo(plan.render(), nl=False)

    @release.command("run")
    def run_command(
        version: str = typer.Argument(...),
        environment: Path = typer.Option(..., "--environment", exists=True, dir_okay=False),
        release_config: Path | None = typer.Option(
            None, "--release-config", exists=True, dir_okay=False
        ),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
        ghcr_token_file: Path = typer.Option(..., "--ghcr-token-file", exists=True, dir_okay=False),
        cosign_key_file: Path = typer.Option(..., "--cosign-key-file", exists=True, dir_okay=False),
        cosign_password_file: Path = typer.Option(
            ..., "--cosign-password-file", exists=True, dir_okay=False
        ),
        resume: bool = typer.Option(False, "--resume"),
        keep: bool = typer.Option(False, "--keep"),
        provision: bool = typer.Option(False, "--provision"),
    ) -> None:
        if not provision and not resume:
            raise typer.BadParameter(
                "fresh release run requires explicit --provision acknowledgement"
            )
        try:
            plan = _release_plan(
                version,
                environment=environment,
                release_config=release_config,
                run_dir=run_dir,
                credentials=CredentialFiles(
                    ghcr_token=ghcr_token_file,
                    cosign_key=cosign_key_file,
                    cosign_password=cosign_password_file,
                ),
            )
            decision = run_amd64_release(plan, resume=resume, keep=keep)
        except (FileNotFoundError, PermissionError, RuntimeError, ValueError) as error:
            raise _bad_parameter(error) from error
        if not decision.passed:
            raise typer.Exit(code=1)
        typer.echo("AMD64 regression gate passed; ARM64 build and publication remain deferred.")

    app.add_typer(release, name="release")
