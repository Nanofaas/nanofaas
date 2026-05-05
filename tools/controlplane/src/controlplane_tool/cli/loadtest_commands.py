from __future__ import annotations

import json
from pathlib import Path

import typer
from pydantic import ValidationError

from controlplane_tool.orchestation.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.loadtest.loadtest_catalog import list_load_profiles, resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, MetricsGate, effective_required_metrics
from controlplane_tool.loadtest.loadtest_runner import LoadtestRunner
from controlplane_tool.loadtest.metrics_contract import CORE_REQUIRED_METRICS
from controlplane_tool.core.models import (
    ControlPlaneConfig,
    LoadtestConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    ScenarioSelectionConfig,
    TestsConfig,
)
from controlplane_tool.workspace.paths import default_tool_paths, resolve_workspace_path
from controlplane_tool.workspace.profiles import load_profile as load_saved_profile, profile_path
from controlplane_tool.scenario.scenario_loader import load_scenario_file, resolve_scenario_spec
from controlplane_tool.scenario.scenario_models import ResolvedScenario, ScenarioSpec

loadtest_app = typer.Typer(
    help="Load generation, Prometheus validation, and benchmark reporting workflows.",
    no_args_is_help=True,
)


def _scenario_selection_for(scenario: ResolvedScenario) -> ScenarioSelectionConfig:
    scenario_file: str | None = None
    if scenario.source_path is not None:
        try:
            scenario_file = str(
                scenario.source_path.resolve().relative_to(default_tool_paths().workspace_root.resolve())
            )
        except ValueError:
            scenario_file = str(scenario.source_path)

    return ScenarioSelectionConfig(
        base_scenario=scenario.base_scenario,
        function_preset=scenario.function_preset,
        functions=[] if scenario.function_preset else list(scenario.function_keys),
        scenario_file=scenario_file,
        namespace=scenario.namespace,
        local_registry=scenario.local_registry,
    )


def _default_profile_for_scenario(name: str, scenario: ResolvedScenario) -> Profile:
    return Profile(
        name=name,
        control_plane=ControlPlaneConfig(implementation=scenario.runtime, build_mode="jvm"),
        modules=[],
        tests=TestsConfig(
            enabled=True,
            api=False,
            e2e_mockk8s=False,
            metrics=True,
            load_profile=scenario.load.load_profile_name or "quick",
        ),
        metrics=MetricsConfig(required=list(CORE_REQUIRED_METRICS)),
        report=ReportConfig(title=f"Loadtest run ({name})"),
        scenario=_scenario_selection_for(scenario),
        loadtest=LoadtestConfig(
            default_load_profile=scenario.load.load_profile_name or "quick",
            scenario_file=(
                str(scenario.source_path.relative_to(default_tool_paths().workspace_root))
                if scenario.source_path is not None
                and scenario.source_path.is_absolute()
                and scenario.source_path.is_relative_to(default_tool_paths().workspace_root)
                else (
                    str(scenario.source_path)
                    if scenario.source_path is not None
                    else None
                )
            ),
            function_preset=scenario.function_preset,
        ),
    )


def _configured_scenario_path(path: str | None) -> Path | None:
    if not path:
        return None
    return resolve_workspace_path(Path(path))


def _resolve_scenario(profile: Profile, scenario_file: Path | None) -> ResolvedScenario:
    if scenario_file is not None:
        return load_scenario_file(resolve_workspace_path(scenario_file))

    configured_loadtest_scenario = _configured_scenario_path(profile.loadtest.scenario_file)
    if configured_loadtest_scenario is not None:
        return load_scenario_file(configured_loadtest_scenario)

    configured_profile_scenario = _configured_scenario_path(profile.scenario.scenario_file)
    if configured_profile_scenario is not None:
        return load_scenario_file(configured_profile_scenario)

    return resolve_scenario_spec(
        ScenarioSpec(
            name=f"{profile.name}-loadtest",
            base_scenario=profile.scenario.base_scenario or "k3s-junit-curl",
            runtime=profile.control_plane.implementation,
            function_preset=profile.loadtest.function_preset
            or profile.scenario.function_preset
            or "metrics-smoke",
            functions=list(profile.scenario.functions),
            namespace=profile.scenario.namespace,
            local_registry=profile.scenario.local_registry,
        )
    )


def build_loadtest_request(
    *,
    profile: Profile | None = None,
    saved_profile: str | None = None,
    scenario_file: Path | None = None,
    load_profile_name: str | None = None,
    run_name: str | None = None,
) -> LoadtestRequest:
    if profile is not None:
        active_profile = profile
        scenario = _resolve_scenario(active_profile, scenario_file)
    elif saved_profile is not None:
        active_profile = load_saved_profile(saved_profile)
        scenario = _resolve_scenario(active_profile, scenario_file)
        active_profile = active_profile.model_copy(update={"name": saved_profile}, deep=True)
    elif scenario_file is not None:
        scenario = load_scenario_file(resolve_workspace_path(scenario_file))
        active_profile = _default_profile_for_scenario(run_name or scenario.name, scenario)
    else:
        active_profile = _default_profile_for_scenario(
            run_name or "loadtest",
            resolve_scenario_spec(
                ScenarioSpec(
                    name=run_name or "loadtest",
                    base_scenario="k3s-junit-curl",
                    runtime="java",
                    function_preset="metrics-smoke",
                )
            ),
        )
        scenario = _resolve_scenario(active_profile, scenario_file=None)

    profile_name = run_name or active_profile.name or scenario.name

    resolved_load_profile_name = (
        load_profile_name
        or active_profile.loadtest.default_load_profile
        or active_profile.tests.load_profile
        or scenario.load.load_profile_name
        or "quick"
    )
    active_profile = active_profile.model_copy(
        update={
            "name": profile_name,
            "tests": active_profile.tests.model_copy(
                update={
                    "enabled": True,
                    "metrics": True,
                    "load_profile": resolved_load_profile_name,
                }
            ),
            "scenario": _scenario_selection_for(scenario),
            "loadtest": active_profile.loadtest.model_copy(
                update={
                    "default_load_profile": resolved_load_profile_name,
                    "scenario_file": (
                        active_profile.loadtest.scenario_file
                        or active_profile.scenario.scenario_file
                    ),
                    "function_preset": (
                        active_profile.loadtest.function_preset
                        or active_profile.scenario.function_preset
                    ),
                }
            ),
        },
        deep=True,
    )

    return LoadtestRequest(
        name=profile_name,
        profile=active_profile,
        scenario=scenario,
        load_profile=resolve_load_profile(resolved_load_profile_name),
        metrics_gate=MetricsGate(
            mode=active_profile.loadtest.metrics_gate_mode,
            required_metrics=effective_required_metrics(active_profile),
        ),
    )


def _build_request_or_exit(
    *,
    saved_profile: str | None,
    scenario_file: Path | None,
    load_profile_name: str | None,
    run_name: str | None,
) -> LoadtestRequest:
    if saved_profile is not None and not profile_path(saved_profile).exists():
        typer.echo(f"Profile not found: {saved_profile}", err=True)
        raise typer.Exit(code=2)

    try:
        return build_loadtest_request(
            saved_profile=saved_profile,
            scenario_file=scenario_file,
            load_profile_name=load_profile_name,
            run_name=run_name,
        )
    except FileNotFoundError as exc:
        resolved_path = (
            resolve_workspace_path(scenario_file)
            if scenario_file is not None
            else (
                Path(exc.filename).resolve()
                if exc.filename
                else None
            )
        )
        if resolved_path is not None:
            typer.echo(f"Scenario file not found: {resolved_path}", err=True)
        else:
            typer.echo("Scenario file not found.", err=True)
        raise typer.Exit(code=2) from exc
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid loadtest request: {first_error}", err=True)
        raise typer.Exit(code=2) from exc
    except ValueError as exc:
        typer.echo(f"Invalid loadtest request: {exc}", err=True)
        raise typer.Exit(code=2) from exc


def render_loadtest_plan(
    request: LoadtestRequest,
    *,
    flow_task_ids: list[str] | None = None,
) -> list[str]:
    lines = [
        f"Loadtest: {request.name}",
        f"Scenario: {request.scenario.name}",
        f"Load profile: {request.load_profile.name}",
        f"Execution semantics: {request.execution_description}",
        "Targets: " + ", ".join(request.targets.targets if request.targets is not None else []),
        "k6 stages:",
    ]
    lines.extend(
        f"  - {stage.duration} -> {stage.target} target VUs"
        for stage in request.load_profile.stages
    )
    if request.metrics_gate.required_metrics:
        lines.append("Metrics gate: " + ", ".join(request.metrics_gate.required_metrics))
    if request.scenario.source_path is not None:
        lines.append(f"Scenario file: {request.scenario.source_path}")
    if flow_task_ids:
        lines.append("Flow tasks:")
        lines.extend(f"  - {task_id}" for task_id in flow_task_ids)
    return lines


def run_loadtest_request(
    request: LoadtestRequest,
    *,
    dry_run: bool,
    runner: LoadtestRunner | None = None,
) -> None:
    if dry_run:
        for line in render_loadtest_plan(
            request,
            flow_task_ids=resolve_flow_task_ids(f"loadtest.{request.load_profile.name}"),
        ):
            typer.echo(line)
        return

    if runner is not None:
        result = runner.run(request)
    else:
        from controlplane_tool.orchestation.prefect_runtime import run_local_flow

        flow = resolve_flow_definition(
            f"loadtest.{request.load_profile.name}",
            request=request,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed" or flow_result.result is None:
            raise typer.Exit(code=1)
        result = flow_result.result
    typer.echo(f"Run status: {result.final_status}")
    typer.echo(f"Summary: {result.run_dir / 'summary.json'}")
    typer.echo(f"Report: {result.run_dir / 'report.html'}")
    if result.final_status != "passed":
        raise typer.Exit(code=1)


def _latest_run_for(name: str) -> Path | None:
    runs = sorted(default_tool_paths().runs_dir.glob(f"*-{name}"))
    if not runs:
        return None
    return runs[-1]


def install_loadtest_commands(app: typer.Typer) -> None:
    @loadtest_app.command("list-profiles")
    def list_profiles_command() -> None:
        for profile in list_load_profiles():
            typer.echo(f"{profile.name}\t{profile.description}")

    @loadtest_app.command("show-profile")
    def show_profile_command(name: str) -> None:
        try:
            profile = resolve_load_profile(name)
        except KeyError as exc:
            typer.echo(str(exc), err=True)
            raise typer.Exit(code=2) from exc
        typer.echo(f"Profile: {profile.name}")
        typer.echo(profile.description)
        typer.echo(f"Summary window: {profile.summary_window_seconds}s")
        for stage in profile.stages:
            typer.echo(f"- {stage.duration} -> {stage.target}")

    @loadtest_app.command("run")
    def run_command(
        scenario_file: Path | None = typer.Option(None, "--scenario-file"),
        load_profile_name: str | None = typer.Option(None, "--load-profile"),
        saved_profile: str | None = typer.Option(None, "--saved-profile"),
        dry_run: bool = typer.Option(False, "--dry-run"),
    ) -> None:
        request = _build_request_or_exit(
            saved_profile=saved_profile,
            scenario_file=scenario_file,
            load_profile_name=load_profile_name,
            run_name=saved_profile,
        )
        run_loadtest_request(request, dry_run=dry_run)

    @loadtest_app.command("inspect")
    def inspect_command(
        saved_profile: str = typer.Option(..., "--saved-profile"),
    ) -> None:
        run_dir = _latest_run_for(saved_profile)
        if run_dir is None:
            typer.echo(f"No runs found for profile: {saved_profile}", err=True)
            raise typer.Exit(code=2)
        summary_path = run_dir / "summary.json"
        report_path = run_dir / "report.html"
        typer.echo(f"Run: {run_dir}")
        if summary_path.exists():
            payload = json.loads(summary_path.read_text(encoding="utf-8"))
            typer.echo(f"Status: {payload.get('final_status', 'unknown')}")
            loadtest = payload.get("loadtest", {})
            if isinstance(loadtest, dict):
                if loadtest.get("scenario"):
                    typer.echo(f"Scenario: {loadtest['scenario']}")
                if loadtest.get("load_profile"):
                    typer.echo(f"Load profile: {loadtest['load_profile']}")
        typer.echo(f"Summary: {summary_path}")
        typer.echo(f"Report: {report_path}")

    app.add_typer(loadtest_app, name="loadtest")
