from __future__ import annotations

import json
from pathlib import Path
import shutil

import typer
import yaml
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.executors import HostCommandTaskExecutor

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.core.task_shell_adapter import ShellCommandTaskRunner
from controlplane_tool.plans.cli import build_cli_plan
from controlplane_tool.plans.validate import build_validate_plan


def _read(path: Path) -> dict[str, object]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"configuration must be an object: {path}")
    return data


def _scenario(path: Path) -> ScenarioConfig:
    return ScenarioConfig.model_validate(_read(path))


def _environment(path: Path | None) -> EnvironmentConfig:
    return EnvironmentConfig.model_validate(_read(path)) if path else EnvironmentConfig(provider="local")


def _local_bindings(environment: EnvironmentConfig) -> RoleBindings:
    if environment.provider != "local":
        raise ValueError(f"provider {environment.provider!r} requires a remote executor")
    executor = HostCommandTaskExecutor(ShellCommandTaskRunner())
    return RoleBindings(host=executor, stack=executor, loadgen=executor)


def _workflow(scenario: ScenarioConfig, environment: EnvironmentConfig):
    bindings = _local_bindings(environment)
    if scenario.workflow == "validate":
        return build_validate_plan(scenario, bindings)
    if scenario.workflow == "cli":
        return build_cli_plan(scenario, bindings)
    raise ValueError("loadtest requires Prometheus and artifact options")


def _render(workflow) -> None:
    for index, task in enumerate(workflow.tasks, start=1):
        typer.echo(f"{index:02d}  {task.task_id}  {task.title}")


def _slice(workflow, *, only: str | None, start: str | None, until: str | None):
    ids = [task.task_id for task in workflow.tasks]
    selected = ids
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
    return workflow


def install_product_commands(app: typer.Typer) -> None:
    @app.command("run")
    def run_command(
        scenario: Path = typer.Argument(..., exists=True),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        keep: bool = typer.Option(False, "--keep"),
        only: str | None = typer.Option(None, "--only"),
        start: str | None = typer.Option(None, "--from"),
        until: str | None = typer.Option(None, "--until"),
    ) -> None:
        workflow = _slice(
            _workflow(_scenario(scenario), _environment(environment)),
            only=only, start=start, until=until,
        )
        workflow.keep_infrastructure = keep
        workflow.run()

    @app.command("plan")
    def plan_command(
        scenario: Path = typer.Argument(..., exists=True),
        environment: Path | None = typer.Option(None, "--environment", exists=True),
        only: str | None = typer.Option(None, "--only"),
        start: str | None = typer.Option(None, "--from"),
        until: str | None = typer.Option(None, "--until"),
    ) -> None:
        _render(_slice(
            _workflow(_scenario(scenario), _environment(environment)),
            only=only, start=start, until=until,
        ))

    @app.command("list")
    def list_command() -> None:
        for path in sorted(Path("tools/controlplane/scenarios-v2").glob("*.yaml")):
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
