from __future__ import annotations

import json
from pathlib import Path
import shutil

import typer
import yaml
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.workflow.context import bind_workflow_sink

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.cli.execution import build_role_bindings
from controlplane_tool.cli.progress import ConsoleProgressSink
from controlplane_tool.cli.provisioning import provision_environment
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
    return EnvironmentConfig.model_validate(_read(path)) if path else EnvironmentConfig(provider="local")


def _workflow(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    control_plane_url: str = "http://127.0.0.1:8080",
    prometheus_url: str = "http://127.0.0.1:9090",
    run_dir: Path | None = None,
):
    bindings, fetcher = build_role_bindings(environment)
    paths = default_tool_paths()
    if scenario.workflow == "validate":
        return build_validate_plan(scenario, bindings, repo_root=paths.workspace_root)
    if scenario.workflow == "cli":
        return build_cli_plan(scenario, bindings, repo_root=paths.workspace_root)
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
            selected = selected[ids.index(start):]
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
        control_plane_url: str = typer.Option("http://127.0.0.1:8080", "--control-plane-url"),
        prometheus_url: str = typer.Option("http://127.0.0.1:9090", "--prometheus-url"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
    ) -> None:
        scenario_config = _scenario(scenario)
        environment_config = _environment(environment)
        if provision and environment_config.provider == "local":
            raise typer.BadParameter("--provision requires a non-local environment")
        workflow = _slice(
            _workflow(
                scenario_config,
                environment_config,
                control_plane_url=control_plane_url,
                prometheus_url=prometheus_url,
                run_dir=run_dir,
            ),
            only=only, start=start, until=until,
        )
        workflow.keep_infrastructure = keep
        with bind_workflow_sink(ConsoleProgressSink()):
            if provision:
                provision_environment(
                    scenario_config,
                    environment_config,
                    repo_root=default_tool_paths().workspace_root,
                )
            workflow.run()

    @app.command("plan")
    def plan_command(
        scenario: Path = typer.Argument(..., exists=True),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        only: str | None = typer.Option(None, "--only"),
        start: str | None = typer.Option(None, "--from"),
        until: str | None = typer.Option(None, "--until"),
        control_plane_url: str = typer.Option("http://127.0.0.1:8080", "--control-plane-url"),
        prometheus_url: str = typer.Option("http://127.0.0.1:9090", "--prometheus-url"),
        run_dir: Path | None = typer.Option(None, "--run-dir"),
    ) -> None:
        _render(_slice(
            _workflow(
                _scenario(scenario),
                _environment(environment),
                control_plane_url=control_plane_url,
                prometheus_url=prometheus_url,
                run_dir=run_dir,
            ),
            only=only, start=start, until=until,
        ))

    @app.command("list")
    def list_command() -> None:
        for path in sorted((default_tool_paths().tool_root / "scenarios-v2").glob("*.yaml")):
            typer.echo(path)

    @app.command("inspect")
    def inspect_command(scenario: Path = typer.Argument(..., exists=True)) -> None:
        typer.echo(json.dumps(_scenario(scenario).model_dump(by_alias=True), indent=2))

    @app.command("doctor")
    def doctor_command() -> None:
        missing = [name for name in ("docker", "ssh") if shutil.which(name) is None]
        if missing:
            raise typer.BadParameter(f"missing executables: {', '.join(missing)}")
        typer.echo("ok")
