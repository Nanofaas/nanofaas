from __future__ import annotations

import hashlib
import json
from contextlib import nullcontext
from datetime import UTC, datetime
from pathlib import Path
import subprocess

import typer
import yaml
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.workflow.context import bind_workflow_sink

from controlplane_tool.cli import diagnostics
from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.cli.execution import build_role_bindings, resolve_loadtest_urls
from controlplane_tool.cli.preflight import PreflightError, preflight_control_plane
from controlplane_tool.cli.progress import ConsoleProgressSink
from controlplane_tool.cli.provisioning import provision_environment
from controlplane_tool.plans.offload import build_offload_plan
from controlplane_tool.plans.offload_loadtest import build_offload_loadtest_plan
from controlplane_tool.plans.cli import build_cli_plan
from controlplane_tool.plans.loadtest import build_loadtest_plan
from controlplane_tool.plans.validate import build_validate_plan
from controlplane_tool.workspace.paths import default_tool_paths


def _read(path: Path) -> dict[str, object]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"configuration must be an object: {path}")
    return data


def _scenario(path: Path) -> ScenarioConfig:
    return ScenarioConfig.model_validate(_read(path))


def _environment(path: Path | None) -> EnvironmentConfig:
    return (
        EnvironmentConfig.model_validate(_read(path))
        if path
        else EnvironmentConfig(provider="local")
    )


def _workflow(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    control_plane_url: str = "http://127.0.0.1:8080",
    prometheus_url: str = "http://127.0.0.1:9090",
    run_dir: Path | None = None,
    dry_run: bool = False,
):
    bindings, fetcher = build_role_bindings(environment)
    paths = default_tool_paths()
    if scenario.workflow == "validate":
        return build_validate_plan(scenario, bindings, repo_root=paths.workspace_root)
    if scenario.workflow == "offload":
        return build_offload_plan(scenario, bindings, repo_root=paths.workspace_root)
    if scenario.workflow == "offload-loadtest":
        return build_offload_loadtest_plan(
            scenario,
            environment,
            bindings,
            run_dir=run_dir or paths.runs_dir / "latest",
            repo_root=paths.workspace_root,
            fetcher=fetcher,
            dry_run=dry_run,
        )
    if scenario.workflow == "cli":
        return build_cli_plan(
            scenario,
            bindings,
            endpoint=control_plane_url,
            repo_root=paths.workspace_root,
        )
    return build_loadtest_plan(
        scenario,
        environment,
        bindings,
        control_plane_url=control_plane_url,
        prometheus_client=HttpPrometheusClient(prometheus_url),
        run_dir=run_dir or paths.runs_dir / "latest",
        fetcher=fetcher,
        repo_root=paths.workspace_root,
    )


def _render(workflow) -> None:
    for index, task in enumerate(workflow.tasks, start=1):
        typer.echo(f"{index:02d}  {task.task_id}  {task.title}")


def _slice(workflow, *, only: str | None, start: str | None, until: str | None):
    ids = [task.task_id for task in workflow.tasks]
    selected = ids
    sliced = any((only, start, until))
    if only:
        selected = [only]
    else:
        if start:
            selected = selected[ids.index(start) :]
        if until:
            selected = selected[: selected.index(until) + 1]
    unknown = set(selected) - set(ids)
    if unknown:
        raise ValueError(f"unknown task: {', '.join(sorted(unknown))}")
    workflow.tasks = [task for task in workflow.tasks if task.task_id in selected]
    if sliced:
        selected_set = set(selected)

        def acquired_by_selection(task) -> bool:
            cleanup_id = task.task_id
            candidates = {
                cleanup_id.replace(".delete.", ".register."),
                cleanup_id.replace(".delete.", ".apply."),
                cleanup_id.replace(".uninstall.", ".deploy."),
            }
            return not candidates.isdisjoint(selected_set)

        workflow.cleanup_tasks = [
            task for task in workflow.cleanup_tasks if acquired_by_selection(task)
        ]
    return workflow


def _git_commit(repo_root: Path) -> str | None:
    try:
        result = subprocess.run(
            ("git", "rev-parse", "HEAD"),
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    return result.stdout.strip() if result.returncode == 0 else None


def _git_provenance(repo_root: Path) -> dict[str, object]:
    commit = _git_commit(repo_root)
    try:
        status = subprocess.run(
            ("git", "status", "--porcelain=v1"),
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=False,
        )
        diff = subprocess.run(
            ("git", "diff", "--binary", "HEAD"),
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=False,
        )
        untracked = subprocess.run(
            ("git", "ls-files", "--others", "--exclude-standard", "-z"),
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return {"git_commit": commit, "git_dirty": None, "git_diff_sha256": None, "git_status": []}
    if status.returncode != 0 or diff.returncode != 0 or untracked.returncode != 0:
        return {"git_commit": commit, "git_dirty": None, "git_diff_sha256": None, "git_status": []}
    digest = hashlib.sha256((status.stdout + "\0" + diff.stdout).encode("utf-8"))
    for relative_path in filter(None, untracked.stdout.split("\0")):
        digest.update(b"\0untracked\0")
        digest.update(relative_path.encode("utf-8"))
        try:
            digest.update((repo_root / relative_path).read_bytes())
        except OSError:
            digest.update(b"\0unavailable")
    return {
        "git_commit": commit,
        "git_dirty": bool(status.stdout.strip()),
        "git_diff_sha256": digest.hexdigest(),
        "git_status": status.stdout.splitlines(),
    }


def _write_run_metadata(
    run_dir: Path,
    *,
    status: str,
    error: str | None,
    started_at: datetime,
    scenario_path: Path,
    scenario: ScenarioConfig,
    environment_path: Path | None,
    environment: EnvironmentConfig,
    sink: ConsoleProgressSink,
    provenance: dict[str, object],
) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    metadata = {
        "schema_version": 1,
        "status": status,
        "error": error,
        "started_at": started_at.isoformat(),
        "ended_at": datetime.now(UTC).isoformat(),
        **provenance,
        "scenario": {
            "source": str(scenario_path),
            "config": scenario.model_dump(mode="json", by_alias=True),
        },
        "environment": {
            "source": str(environment_path) if environment_path else None,
            "config": environment.model_dump(mode="json", by_alias=True),
        },
        "tasks": sink.records,
    }
    (run_dir / "run-metadata.json").write_text(
        json.dumps(metadata, indent=2), encoding="utf-8"
    )


def install_product_commands(app: typer.Typer) -> None:
    @app.command("run")
    def run_command(
        scenario: Path = typer.Argument(..., exists=True),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        provision: bool = typer.Option(False, "--provision"),
        keep: bool = typer.Option(False, "--keep"),
        only: str | None = typer.Option(None, "--only"),
        start: str | None = typer.Option(None, "--from"),
        until: str | None = typer.Option(None, "--until"),
        control_plane_url: str | None = typer.Option(None, "--control-plane-url"),
        prometheus_url: str | None = typer.Option(None, "--prometheus-url"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
    ) -> None:
        scenario_config = _scenario(scenario)
        environment_config = _environment(environment)
        if provision and environment_config.provider == "local":
            raise typer.BadParameter("--provision requires a non-local environment")
        paths = default_tool_paths()
        effective_run_dir = run_dir
        if (
            scenario_config.workflow in ("loadtest", "offload-loadtest")
            and effective_run_dir is None
        ):
            effective_run_dir = paths.runs_dir / "latest"
        sink = ConsoleProgressSink()
        started_at = datetime.now(UTC)
        provenance = _git_provenance(paths.workspace_root)
        try:
            with bind_workflow_sink(sink):
                provisioning = (
                    provision_environment(
                        scenario_config,
                        environment_config,
                        repo_root=paths.workspace_root,
                        keep=keep,
                    )
                    if provision
                    else nullcontext()
                )
                with provisioning:
                    if scenario_config.workflow == "loadtest":
                        control_plane_url, prometheus_url = resolve_loadtest_urls(
                            environment_config,
                            control_plane_url=control_plane_url,
                            prometheus_url=prometheus_url,
                        )
                    effective_control_plane_url = (
                        control_plane_url or "http://127.0.0.1:8080"
                    )
                    try:
                        preflight_control_plane(
                            scenario_config,
                            environment_config,
                            base_url=effective_control_plane_url,
                        )
                    except PreflightError as exc:
                        typer.echo(f"Error: {exc}", err=True)
                        raise typer.Exit(1) from None
                    workflow = _slice(
                        _workflow(
                            scenario_config,
                            environment_config,
                            control_plane_url=effective_control_plane_url,
                            prometheus_url=prometheus_url or "http://127.0.0.1:9090",
                            run_dir=effective_run_dir,
                        ),
                        only=only,
                        start=start,
                        until=until,
                    )
                    workflow.keep_infrastructure = keep
                    workflow.run()
        except BaseException as exc:
            if effective_run_dir is not None:
                try:
                    _write_run_metadata(
                        effective_run_dir,
                        status="failed",
                        error=str(exc),
                        started_at=started_at,
                        scenario_path=scenario,
                        scenario=scenario_config,
                        environment_path=environment,
                        environment=environment_config,
                        sink=sink,
                        provenance=provenance,
                    )
                except OSError:
                    pass
            raise
        else:
            if effective_run_dir is not None:
                _write_run_metadata(
                    effective_run_dir,
                    status="passed",
                    error=None,
                    started_at=started_at,
                    scenario_path=scenario,
                    scenario=scenario_config,
                    environment_path=environment,
                    environment=environment_config,
                    sink=sink,
                    provenance=provenance,
                )

    @app.command("plan")
    def plan_command(
        scenario: Path = typer.Argument(..., exists=True),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        only: str | None = typer.Option(None, "--only"),
        start: str | None = typer.Option(None, "--from"),
        until: str | None = typer.Option(None, "--until"),
        control_plane_url: str | None = typer.Option(None, "--control-plane-url"),
        prometheus_url: str | None = typer.Option(None, "--prometheus-url"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
    ) -> None:
        scenario_config = _scenario(scenario)
        environment_config = _environment(environment)
        if scenario_config.workflow == "loadtest":
            control_plane_url, prometheus_url = resolve_loadtest_urls(
                environment_config,
                control_plane_url=control_plane_url,
                prometheus_url=prometheus_url,
                dry_run=True,
            )
        _render(
            _slice(
                _workflow(
                    scenario_config,
                    environment_config,
                    control_plane_url=control_plane_url or "http://127.0.0.1:8080",
                    prometheus_url=prometheus_url or "http://127.0.0.1:9090",
                    run_dir=run_dir,
                    dry_run=True,
                ),
                only=only,
                start=start,
                until=until,
            )
        )

    @app.command("list")
    def list_command() -> None:
        for path in sorted((default_tool_paths().tool_root / "scenarios-v2").glob("*.yaml")):
            typer.echo(path)

    @app.command("inspect")
    def inspect_command(scenario: Path = typer.Argument(..., exists=True)) -> None:
        typer.echo(json.dumps(_scenario(scenario).model_dump(by_alias=True), indent=2))

    @app.command("doctor")
    def doctor_command() -> None:
        missing = diagnostics.missing_executables()
        if missing:
            raise typer.BadParameter(f"missing executables: {', '.join(missing)}")
        typer.echo("ok")
