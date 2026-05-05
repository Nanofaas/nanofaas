from __future__ import annotations

from pathlib import Path

import typer
from pydantic import ValidationError

from controlplane_tool.scenario.catalog import list_scenarios, resolve_scenario
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, ScenarioPlan
from controlplane_tool.orchestation.flow_catalog import resolve_flow_definition, resolve_flow_task_ids
from controlplane_tool.functions.catalog import function_runtime_allowlist_for_scenario
from controlplane_tool.core.models import ScenarioSelectionConfig
from controlplane_tool.workspace.paths import default_tool_paths
from controlplane_tool.orchestation.prefect_runtime import run_local_flow
from controlplane_tool.workspace.profiles import load_profile
from controlplane_tool.scenario.scenario_loader import load_scenario_file
from controlplane_tool.scenario.selection_resolution import (
    configured_scenario_path,
    overlay_selected_scenario,
    parse_function_csv,
    resolved_scenario_from_config,
)
from controlplane_tool.scenario.scenario_models import ResolvedScenario
from controlplane_tool.infra.vm.vm_models import VmRequest

E2E_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}

e2e_app = typer.Typer(
    help="End-to-end scenario orchestration commands.",
    no_args_is_help=True,
)


def _runner() -> E2eRunner:
    return E2eRunner(default_tool_paths().workspace_root)


def _build_vm_request(
    *,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
) -> VmRequest:
    return VmRequest(
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
    )


def _build_request(
    *,
    scenario: str,
    runtime: str,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
    cleanup_vm: bool,
    namespace: str | None,
    local_registry: str,
    function_preset: str | None = None,
    functions: list[str] | None = None,
    scenario_file: Path | None = None,
    saved_profile: str | None = None,
    scenario_source: str | None = None,
    resolved_scenario: ResolvedScenario | None = None,
) -> E2eRequest:
    vm = None
    if scenario in {"k3s-junit-curl", "cli", "cli-stack", "cli-host", "helm-stack"}:
        vm = _build_vm_request(
            lifecycle=lifecycle,
            name=name,
            host=host,
            user=user,
            home=home,
            cpus=cpus,
            memory=memory,
            disk=disk,
        )
    return E2eRequest(
        scenario=scenario,
        runtime=runtime,
        function_preset=function_preset,
        functions=functions or [],
        scenario_file=scenario_file,
        saved_profile=saved_profile,
        scenario_source=scenario_source,
        resolved_scenario=resolved_scenario,
        vm=vm,
        cleanup_vm=cleanup_vm,
        namespace=namespace,
        local_registry=local_registry,
    )


def _render_plan(plan: ScenarioPlan, *, flow_task_ids: list[str] | None = None) -> None:
    def _display_command(command: list[str]) -> str:
        rendered = " ".join(command)
        if "docker " in rendered or "helm " in rendered:
            return "<delegated to shared scenario task>"
        return rendered

    typer.echo(f"Scenario: {plan.scenario.name}")
    typer.echo(f"Description: {plan.scenario.description}")
    if plan.request.scenario_source:
        typer.echo(f"Scenario Source: {plan.request.scenario_source}")
    typer.echo(f"Runtime: {plan.request.runtime}")
    if plan.request.resolved_scenario is not None:
        typer.echo(
            "Resolved Functions: "
            + ", ".join(plan.request.resolved_scenario.function_keys)
        )
        if plan.request.resolved_scenario.load.targets:
            typer.echo(
                "Load Targets: "
                + ", ".join(plan.request.resolved_scenario.load.targets)
            )
    if plan.request.vm is not None:
        typer.echo(f"VM lifecycle: {plan.request.vm.lifecycle}")
    if flow_task_ids:
        typer.echo("Flow Tasks:")
        for task_id in flow_task_ids:
            typer.echo(f"  - {task_id}")
    for index, step in enumerate(plan.steps, start=1):
        typer.echo(f"Step {index}: {step.summary}")
        typer.echo(f"  Command: {_display_command(step.command)}")


def _handle_validation(action) -> None:
    try:
        action()
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid E2E request: {first_error}", err=True)
        raise typer.Exit(code=2)
    except ValueError as exc:
        typer.echo(f"Invalid E2E request: {exc}", err=True)
        raise typer.Exit(code=2)


def _default_selection_for(scenario: str) -> ScenarioSelectionConfig:
    if scenario in {"container-local", "deploy-host"}:
        return ScenarioSelectionConfig(base_scenario=scenario, functions=["word-stats-java"])
    if scenario == "helm-stack":
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-loadtest")
    if scenario == "cli-stack":
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-java")
    return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-java")


def _validate_scenario_function_selection(scenario: ResolvedScenario) -> None:
    allowed_runtimes = function_runtime_allowlist_for_scenario(scenario.base_scenario)
    if allowed_runtimes is None:
        return

    invalid_runtimes = sorted(
        {
            function.runtime
            for function in scenario.functions
            if function.runtime not in allowed_runtimes
        }
    )
    if not invalid_runtimes:
        return

    raise ValueError(
        f"scenario '{scenario.base_scenario}' does not support selected function runtimes: "
        + ", ".join(invalid_runtimes)
    )


def _validate_scenario_selection_contract(
    definition, scenario: ResolvedScenario
) -> None:
    if definition.selection_mode == "single" and len(scenario.function_keys) != 1:
        raise ValueError(
            f"scenario '{definition.name}' supports exactly one selected function, "
            f"got {len(scenario.function_keys)}"
        )


def _resolve_run_request(
    *,
    scenario: str | None,
    runtime: str | None,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
    cleanup_vm: bool,
    namespace: str | None,
    local_registry: str | None,
    function_preset: str | None,
    functions_csv: str | None,
    scenario_file: Path | None,
    saved_profile: str | None,
) -> E2eRequest:
    explicit_functions = parse_function_csv(functions_csv)
    if function_preset and explicit_functions:
        raise ValueError("function selection must use only one of --function-preset or --functions")

    profile = load_profile(saved_profile) if saved_profile else None
    profile_selection = profile.scenario if profile is not None else ScenarioSelectionConfig()
    profile_runtime = profile.control_plane.implementation if profile is not None else None

    profile_file_scenario = (
        load_scenario_file(configured_scenario_path(profile_selection.scenario_file))
        if configured_scenario_path(profile_selection.scenario_file) is not None
        else None
    )
    explicit_file_scenario = (
        load_scenario_file(configured_scenario_path(scenario_file))
        if scenario_file is not None
        else None
    )

    effective_scenario = (
        scenario
        or (explicit_file_scenario.base_scenario if explicit_file_scenario is not None else None)
        or (profile_file_scenario.base_scenario if profile_file_scenario is not None else None)
        or profile_selection.base_scenario
    )
    if effective_scenario is None:
        raise ValueError("scenario is required unless provided by --scenario-file or --saved-profile")

    effective_runtime = (
        runtime
        or (explicit_file_scenario.runtime if explicit_file_scenario is not None else None)
        or (profile_file_scenario.runtime if profile_file_scenario is not None else None)
        or profile_runtime
        or "java"
    )
    effective_namespace = (
        namespace
        or (explicit_file_scenario.namespace if explicit_file_scenario is not None else None)
        or (profile_file_scenario.namespace if profile_file_scenario is not None else None)
        or profile_selection.namespace
    )
    effective_registry = (
        local_registry
        or (explicit_file_scenario.local_registry if explicit_file_scenario is not None else None)
        or (profile_file_scenario.local_registry if profile_file_scenario is not None else None)
        or profile_selection.local_registry
        or "localhost:5000"
    )

    resolved_scenario: ResolvedScenario
    scenario_source: str
    request_scenario_file = (
        configured_scenario_path(scenario_file)
        if scenario_file is not None
        else None
    )

    if function_preset or explicit_functions:
        if explicit_file_scenario is not None:
            resolved_scenario = overlay_selected_scenario(
                explicit_file_scenario,
                base_scenario=effective_scenario,
                function_preset=function_preset,
                functions=explicit_functions,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = "scenario file + CLI override"
        elif profile_file_scenario is not None:
            resolved_scenario = overlay_selected_scenario(
                profile_file_scenario,
                base_scenario=effective_scenario,
                function_preset=function_preset,
                functions=explicit_functions,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = f"saved profile: {saved_profile} + CLI override"
            request_scenario_file = configured_scenario_path(profile_selection.scenario_file)
        else:
            resolved_scenario = resolved_scenario_from_config(
                ScenarioSelectionConfig(
                    base_scenario=effective_scenario,
                    function_preset=function_preset,
                    functions=explicit_functions,
                    namespace=effective_namespace,
                    local_registry=effective_registry,
                ),
                name=f"{effective_scenario}-cli",
                base_scenario=effective_scenario,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = "explicit CLI override"
    elif explicit_file_scenario is not None:
        resolved_scenario = overlay_selected_scenario(
            explicit_file_scenario,
            base_scenario=effective_scenario,
            runtime=effective_runtime,
            namespace=effective_namespace,
            local_registry=effective_registry,
        )
        scenario_source = f"scenario file: {explicit_file_scenario.source_path}"
    elif profile_file_scenario is not None:
        resolved_scenario = overlay_selected_scenario(
            profile_file_scenario,
            base_scenario=effective_scenario,
            runtime=effective_runtime,
            namespace=effective_namespace,
            local_registry=effective_registry,
        )
        scenario_source = f"saved profile: {saved_profile}"
        request_scenario_file = configured_scenario_path(profile_selection.scenario_file)
    elif profile_selection.function_preset or profile_selection.functions:
        resolved_scenario = resolved_scenario_from_config(
            profile_selection,
            name=f"profile-{saved_profile or 'default'}",
            base_scenario=effective_scenario,
            runtime=effective_runtime,
            namespace=effective_namespace,
            local_registry=effective_registry,
        )
        scenario_source = f"saved profile: {saved_profile}"
    else:
        default_selection = _default_selection_for(effective_scenario)
        resolved_scenario = resolved_scenario_from_config(
            default_selection,
            name=f"{effective_scenario}-default",
            base_scenario=effective_scenario,
            runtime=effective_runtime,
            namespace=effective_namespace,
            local_registry=effective_registry,
        )
        scenario_source = "built-in default"

    scenario_definition = resolve_scenario(resolved_scenario.base_scenario)
    _validate_scenario_function_selection(resolved_scenario)
    _validate_scenario_selection_contract(scenario_definition, resolved_scenario)

    return _build_request(
        scenario=resolved_scenario.base_scenario,
        runtime=effective_runtime,
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
        cleanup_vm=cleanup_vm,
        namespace=effective_namespace,
        local_registry=effective_registry,
        function_preset=resolved_scenario.function_preset,
        functions=[] if resolved_scenario.function_preset else list(resolved_scenario.function_keys),
        scenario_file=request_scenario_file,
        saved_profile=saved_profile,
        scenario_source=scenario_source,
        resolved_scenario=resolved_scenario,
    )


@e2e_app.command("list", context_settings=E2E_CONTEXT_SETTINGS)
def e2e_list() -> None:
    for scenario in list_scenarios():
        typer.echo(f"{scenario.name}\t{scenario.description}")


@e2e_app.command("run", context_settings=E2E_CONTEXT_SETTINGS)
def e2e_run(
    scenario: str | None = typer.Argument(None, help="Scenario name."),
    runtime: str | None = typer.Option(None, "--runtime"),
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    cleanup_vm: bool = typer.Option(True, "--cleanup-vm/--no-cleanup-vm"),
    namespace: str | None = typer.Option(None, "--namespace"),
    local_registry: str | None = typer.Option(None, "--local-registry"),
    function_preset: str | None = typer.Option(None, "--function-preset"),
    functions: str | None = typer.Option(None, "--functions"),
    scenario_file: Path | None = typer.Option(None, "--scenario-file"),
    saved_profile: str | None = typer.Option(None, "--saved-profile"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> None:
        request = _resolve_run_request(
            scenario=scenario,
            runtime=runtime,
            lifecycle=lifecycle,
            name=name,
            host=host,
            user=user,
            home=home,
            cpus=cpus,
            memory=memory,
            disk=disk,
            cleanup_vm=cleanup_vm,
            namespace=namespace,
            local_registry=local_registry,
            function_preset=function_preset,
            functions_csv=functions,
            scenario_file=scenario_file,
            saved_profile=saved_profile,
        )
        runner = _runner()
        flow_name = f"e2e.{request.scenario}"
        if dry_run:
            _render_plan(
                runner.plan(request),
                flow_task_ids=resolve_flow_task_ids(flow_name),
            )
            return
        flow = resolve_flow_definition(
            flow_name,
            repo_root=default_tool_paths().workspace_root,
            request=request,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed":
            raise typer.Exit(code=1)
        typer.echo(f"Completed scenario: {request.scenario}")

    _handle_validation(_action)


@e2e_app.command("all", context_settings=E2E_CONTEXT_SETTINGS)
def e2e_all(
    only: str | None = typer.Option(None, "--only", help="CSV scenario filter."),
    skip: str | None = typer.Option(None, "--skip", help="CSV scenario skip list."),
    runtime: str = typer.Option("java", "--runtime"),
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    cleanup_vm: bool = typer.Option(True, "--cleanup-vm/--no-cleanup-vm"),
    namespace: str | None = typer.Option(None, "--namespace"),
    local_registry: str = typer.Option("localhost:5000", "--local-registry"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> None:
        selected_names = parse_function_csv(only)
        skipped_names = parse_function_csv(skip)
        candidate_names = selected_names or [scenario.name for scenario in list_scenarios()]
        active_names = [scenario_name for scenario_name in candidate_names if scenario_name not in skipped_names]
        needs_vm = any(resolve_scenario(scenario_name).requires_vm for scenario_name in active_names)

        vm_request = None
        if needs_vm:
            vm_request = _build_vm_request(
                lifecycle=lifecycle,
                name=name,
                host=host,
                user=user,
                home=home,
                cpus=cpus,
                memory=memory,
                disk=disk,
            )

        runner = _runner()
        plans = runner.plan_all(
            only=selected_names,
            skip=skipped_names,
            runtime=runtime,
            vm_request=vm_request,
            cleanup_vm=cleanup_vm,
            namespace=namespace,
            local_registry=local_registry,
        )
        if dry_run:
            for plan in plans:
                _render_plan(
                    plan,
                    flow_task_ids=resolve_flow_task_ids(f"e2e.{plan.scenario.name}"),
                )
            return
        flow = resolve_flow_definition(
            "e2e.all",
            runner=runner,
            only=selected_names,
            skip=skipped_names,
            runtime=runtime,
            vm_request=vm_request,
            cleanup_vm=cleanup_vm,
            namespace=namespace,
            local_registry=local_registry,
            scenarios=[plan.scenario.name for plan in plans],
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed":
            raise typer.Exit(code=1)
        typer.echo(f"Completed scenarios: {', '.join(plan.scenario.name for plan in plans)}")

    _handle_validation(_action)


def install_e2e_commands(app: typer.Typer) -> None:
    app.add_typer(e2e_app, name="e2e")
