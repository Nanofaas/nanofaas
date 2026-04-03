from __future__ import annotations

from pathlib import Path

import typer
from pydantic import ValidationError

from controlplane_tool.cli_test_catalog import (
    list_cli_test_scenarios,
    resolve_cli_test_scenario,
)
from controlplane_tool.cli_test_models import CliTestRequest
from controlplane_tool.cli_test_runner import CliTestPlan, CliTestRunner
from controlplane_tool.models import ScenarioSelectionConfig
from controlplane_tool.paths import default_tool_paths, resolve_workspace_path
from controlplane_tool.profiles import load_profile, profile_path
from controlplane_tool.scenario_loader import (
    load_scenario_file,
    overlay_scenario_selection,
    resolve_scenario_spec,
)
from controlplane_tool.scenario_models import ResolvedScenario, ScenarioSpec
from controlplane_tool.vm_models import VmRequest

CLI_TEST_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}

cli_test_app = typer.Typer(
    help="nanofaas-cli validation orchestration commands.",
    no_args_is_help=True,
)


def _runner() -> CliTestRunner:
    return CliTestRunner(default_tool_paths().workspace_root)


def _parse_csv(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def _configured_scenario_path(path: str | None) -> Path | None:
    if not path:
        return None
    return resolve_workspace_path(Path(path))


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


def _resolved_from_config(
    config: ScenarioSelectionConfig,
    *,
    name: str,
    default_base_scenario: str,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    return resolve_scenario_spec(
        ScenarioSpec(
            name=name,
            base_scenario=config.base_scenario or default_base_scenario,
            runtime=runtime,
            function_preset=config.function_preset,
            functions=list(config.functions),
            namespace=namespace if namespace is not None else config.namespace,
            local_registry=local_registry or config.local_registry,
        )
    )


def _reload_with_overrides(
    scenario: ResolvedScenario,
    *,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    return overlay_scenario_selection(
        scenario,
        function_preset=scenario.function_preset,
        functions=[] if scenario.function_preset else list(scenario.function_keys),
        runtime=runtime,
        namespace=namespace,
        local_registry=local_registry,
    )


def _render_plan(plan: CliTestPlan) -> None:
    typer.echo(f"Scenario: {plan.scenario.name}")
    typer.echo(f"Description: {plan.scenario.description}")
    typer.echo(f"Gradle Task: {plan.scenario.gradle_task}")
    typer.echo(f"Requires VM: {plan.scenario.requires_vm}")
    if plan.request.scenario_source:
        typer.echo(f"Scenario Source: {plan.request.scenario_source}")
    typer.echo(f"Runtime: {plan.request.runtime}")
    if plan.request.resolved_scenario is not None:
        typer.echo(
            "Resolved Functions: "
            + ", ".join(plan.request.resolved_scenario.function_keys)
        )
    if plan.request.vm is not None:
        typer.echo(f"VM lifecycle: {plan.request.vm.lifecycle}")
    for index, step in enumerate(plan.steps, start=1):
        typer.echo(f"Step {index}: {step.summary}")
        typer.echo(f"  Command: {' '.join(step.command)}")


def _handle_validation(action) -> None:
    try:
        action()
    except FileNotFoundError as exc:
        typer.echo(f"Invalid cli-test request: {exc}", err=True)
        raise typer.Exit(code=2)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid cli-test request: {first_error}", err=True)
        raise typer.Exit(code=2)
    except ValueError as exc:
        typer.echo(f"Invalid cli-test request: {exc}", err=True)
        raise typer.Exit(code=2)


def _has_explicit_selection(
    *,
    function_preset: str | None,
    explicit_functions: list[str],
    scenario_file: Path | None,
) -> bool:
    return bool(function_preset or explicit_functions or scenario_file is not None)


def _load_profile_or_raise(name: str | None):
    if name is None:
        return None
    if not profile_path(name).exists():
        raise FileNotFoundError(f"Profile not found: {name}")
    return load_profile(name)


def _load_scenario_or_raise(path: Path | None) -> ResolvedScenario | None:
    if path is None:
        return None
    resolved_path = resolve_workspace_path(path)
    if not resolved_path.exists():
        raise FileNotFoundError(f"Scenario file not found: {resolved_path}")
    return load_scenario_file(resolved_path)


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
    keep_vm: bool,
    namespace: str | None,
    local_registry: str | None,
    function_preset: str | None,
    functions_csv: str | None,
    scenario_file: Path | None,
    saved_profile: str | None,
) -> CliTestRequest:
    explicit_functions = _parse_csv(functions_csv)
    if function_preset and explicit_functions:
        raise ValueError("function selection must use only one of --function-preset or --functions")

    profile = _load_profile_or_raise(saved_profile)
    profile_selection = profile.scenario if profile is not None else ScenarioSelectionConfig()
    profile_runtime = profile.control_plane.implementation if profile is not None else None
    profile_cli_test_scenario = (
        profile.cli_test.default_scenario if profile is not None else None
    )
    effective_scenario = scenario or profile_cli_test_scenario
    if effective_scenario is None:
        raise ValueError(
            "scenario is required unless provided by --saved-profile with cli_test.default_scenario"
        )

    scenario_definition = resolve_cli_test_scenario(effective_scenario)
    explicit_file_path = _configured_scenario_path(str(scenario_file)) if scenario_file is not None else None
    has_explicit_selection = _has_explicit_selection(
        function_preset=function_preset,
        explicit_functions=explicit_functions,
        scenario_file=explicit_file_path,
    )

    if not scenario_definition.accepts_function_selection and has_explicit_selection:
        raise ValueError(f"scenario '{effective_scenario}' does not accept function selection")

    profile_file_path = (
        _configured_scenario_path(profile_selection.scenario_file)
        if scenario_definition.accepts_function_selection
        else None
    )
    explicit_file_scenario = (
        _load_scenario_or_raise(explicit_file_path)
        if scenario_definition.accepts_function_selection
        else None
    )
    profile_file_scenario = (
        _load_scenario_or_raise(profile_file_path)
        if scenario_definition.accepts_function_selection
        else None
    )

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

    request_scenario_file = explicit_file_path
    resolved_scenario = None
    scenario_source = None

    if scenario_definition.accepts_function_selection:
        default_base_scenario = scenario_definition.legacy_e2e_scenario
        if default_base_scenario is None:
            raise ValueError(f"scenario '{scenario}' has no selection base scenario")

        if function_preset or explicit_functions:
            if explicit_file_scenario is not None:
                resolved_scenario = overlay_scenario_selection(
                    explicit_file_scenario,
                    function_preset=function_preset,
                    functions=explicit_functions,
                    runtime=effective_runtime,
                    namespace=effective_namespace,
                    local_registry=effective_registry,
                )
                scenario_source = "scenario file + CLI override"
            elif profile_file_scenario is not None:
                resolved_scenario = overlay_scenario_selection(
                    profile_file_scenario,
                    function_preset=function_preset,
                    functions=explicit_functions,
                    runtime=effective_runtime,
                    namespace=effective_namespace,
                    local_registry=effective_registry,
                )
                scenario_source = f"saved profile: {saved_profile} + CLI override"
                request_scenario_file = profile_file_path
            else:
                resolved_scenario = _resolved_from_config(
                    ScenarioSelectionConfig(
                        base_scenario=profile_selection.base_scenario or default_base_scenario,
                        function_preset=function_preset,
                        functions=explicit_functions,
                        namespace=effective_namespace,
                        local_registry=effective_registry,
                    ),
                    name=f"{effective_scenario}-cli-test",
                    default_base_scenario=default_base_scenario,
                    runtime=effective_runtime,
                    namespace=effective_namespace,
                    local_registry=effective_registry,
                )
                scenario_source = "explicit CLI override"
        elif explicit_file_scenario is not None:
            resolved_scenario = _reload_with_overrides(
                explicit_file_scenario,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = f"scenario file: {explicit_file_scenario.source_path}"
        elif profile_file_scenario is not None:
            resolved_scenario = _reload_with_overrides(
                profile_file_scenario,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = f"saved profile: {saved_profile}"
            request_scenario_file = profile_file_path
        elif profile_selection.function_preset or profile_selection.functions:
            resolved_scenario = _resolved_from_config(
                profile_selection,
                name=f"profile-{saved_profile or 'default'}",
                default_base_scenario=default_base_scenario,
                runtime=effective_runtime,
                namespace=effective_namespace,
                local_registry=effective_registry,
            )
            scenario_source = f"saved profile: {saved_profile}"

    vm = None
    if scenario_definition.requires_vm:
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

    return CliTestRequest(
        scenario=effective_scenario,
        runtime=effective_runtime,
        function_preset=resolved_scenario.function_preset if resolved_scenario else None,
        functions=[] if resolved_scenario is None or resolved_scenario.function_preset else list(resolved_scenario.function_keys),
        scenario_file=request_scenario_file,
        saved_profile=saved_profile,
        scenario_source=scenario_source,
        resolved_scenario=resolved_scenario,
        vm=vm,
        keep_vm=keep_vm,
        namespace=effective_namespace,
        local_registry=effective_registry,
    )


@cli_test_app.command("list", context_settings=CLI_TEST_CONTEXT_SETTINGS)
def cli_test_list() -> None:
    for scenario in list_cli_test_scenarios():
        typer.echo(
            f"{scenario.name}\t{scenario.gradle_task}\tvm={str(scenario.requires_vm).lower()}\t{scenario.description}"
        )


@cli_test_app.command("inspect", context_settings=CLI_TEST_CONTEXT_SETTINGS)
def cli_test_inspect(
    scenario: str = typer.Argument(..., help="CLI validation scenario name."),
) -> None:
    resolved = resolve_cli_test_scenario(scenario)
    typer.echo(f"Scenario: {resolved.name}")
    typer.echo(f"Description: {resolved.description}")
    typer.echo(f"Gradle Task: {resolved.gradle_task}")
    typer.echo(f"Requires VM: {resolved.requires_vm}")
    typer.echo(f"Accepts Function Selection: {resolved.accepts_function_selection}")
    if resolved.legacy_e2e_scenario is not None:
        typer.echo(f"Legacy E2E Scenario: {resolved.legacy_e2e_scenario}")


@cli_test_app.command("run", context_settings=CLI_TEST_CONTEXT_SETTINGS)
def cli_test_run(
    scenario: str | None = typer.Argument(None, help="CLI validation scenario name."),
    runtime: str | None = typer.Option(None, "--runtime"),
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    keep_vm: bool = typer.Option(False, "--keep-vm"),
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
            keep_vm=keep_vm,
            namespace=namespace,
            local_registry=local_registry,
            function_preset=function_preset,
            functions_csv=functions,
            scenario_file=scenario_file,
            saved_profile=saved_profile,
        )
        runner = _runner()
        if dry_run:
            _render_plan(runner.plan(request))
            return
        runner.run(request)
        typer.echo(f"Completed cli-test scenario: {request.scenario}")

    _handle_validation(_action)


def install_cli_test_commands(app: typer.Typer) -> None:
    app.add_typer(cli_test_app, name="cli-test")
