from __future__ import annotations

from pathlib import Path

import questionary
import typer

from controlplane_tool.e2e_catalog import list_scenarios
from controlplane_tool.function_catalog import list_function_presets
from controlplane_tool.loadtest_catalog import list_load_profiles
from controlplane_tool.models import (
    ControlPlaneConfig,
    LoadtestConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    ScenarioSelectionConfig,
    TestsConfig,
)
from controlplane_tool.metrics_contract import CORE_REQUIRED_METRICS
from controlplane_tool.module_catalog import module_choices
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.profiles import save_profile

DEFAULT_REQUIRED_METRICS: tuple[str, ...] = CORE_REQUIRED_METRICS


def _prompt_scenario_selection() -> ScenarioSelectionConfig:
    save_defaults = questionary.confirm(
        "Save default E2E selection in this profile?",
        default=False,
    ).ask()
    if save_defaults is None:
        raise typer.Exit(code=1)
    if not save_defaults:
        return ScenarioSelectionConfig()

    selection_mode = questionary.select(
        "Default E2E selection type:",
        choices=[
            questionary.Choice("Function preset", value="preset"),
            questionary.Choice("Explicit function CSV", value="functions"),
            questionary.Choice("Scenario file", value="scenario-file"),
        ],
        default="preset",
    ).ask()
    if selection_mode is None:
        raise typer.Exit(code=1)

    if selection_mode == "scenario-file":
        scenario_files = sorted(default_tool_paths().scenarios_dir.glob("*.toml"))
        scenario_file = questionary.select(
            "Scenario file:",
            choices=[
                questionary.Choice(
                    path.name,
                    value=str(path.relative_to(default_tool_paths().workspace_root)),
                )
                for path in scenario_files
            ],
            default="tools/controlplane/scenarios/k8s-demo-java.toml",
        ).ask()
        if scenario_file is None:
            raise typer.Exit(code=1)
        return ScenarioSelectionConfig(scenario_file=scenario_file)

    base_scenario = questionary.select(
        "Base E2E scenario:",
        choices=[
            questionary.Choice(scenario.name, value=scenario.name)
            for scenario in list_scenarios()
        ],
        default="k8s-vm",
    ).ask()
    if base_scenario is None:
        raise typer.Exit(code=1)

    if selection_mode == "preset":
        preset_name = questionary.select(
            "Function preset:",
            choices=[
                questionary.Choice(preset.name, value=preset.name)
                for preset in list_function_presets()
            ],
            default="demo-java",
        ).ask()
        if preset_name is None:
            raise typer.Exit(code=1)
        return ScenarioSelectionConfig(
            base_scenario=base_scenario,
            function_preset=preset_name,
        )

    function_csv = questionary.text(
        "Function CSV:",
        default="word-stats-java,json-transform-java",
    ).ask()
    if function_csv is None:
        raise typer.Exit(code=1)
    functions = [item.strip() for item in function_csv.split(",") if item.strip()]
    return ScenarioSelectionConfig(base_scenario=base_scenario, functions=functions)


def build_profile_interactive(profile_name: str) -> Profile:
    runtime = questionary.select(
        "Control plane implementation:",
        choices=[
            questionary.Choice("Java", value="java"),
            questionary.Choice("Rust", value="rust"),
        ],
        default="java",
    ).ask()
    if runtime is None:
        raise typer.Exit(code=1)

    build_mode = "rust"
    if runtime == "java":
        selected_mode = questionary.select(
            "Java build mode:",
            choices=[
                questionary.Choice("Native", value="native"),
                questionary.Choice("JVM", value="jvm"),
            ],
            default="native",
        ).ask()
        if selected_mode is None:
            raise typer.Exit(code=1)
        build_mode = selected_mode

    available_modules = module_choices()
    selected_modules = questionary.checkbox(
        "Select control-plane modules:",
        choices=[
            questionary.Choice(
                title=f"{module.name} - {module.description}",
                value=module.key,
            )
            for module in available_modules
        ],
    ).ask()
    if selected_modules is None:
        raise typer.Exit(code=1)

    tests_enabled = questionary.confirm("Run tests after build?", default=True).ask()
    if tests_enabled is None:
        raise typer.Exit(code=1)

    tests = TestsConfig(enabled=tests_enabled)
    selected_load_profile = "quick"
    if tests_enabled:
        tests.api = bool(questionary.confirm("Run API tests?", default=True).ask())
        tests.e2e_mockk8s = bool(
            questionary.confirm(
                "Run mock Kubernetes E2E tests?", default=True
            ).ask()
        )
        tests.metrics = bool(
            questionary.confirm(
                "Run metrics + k6 load tests with Prometheus?", default=True
            ).ask()
        )
        load_profile = questionary.select(
            "Loadtest profile:",
            choices=[
                questionary.Choice(profile.name.capitalize(), value=profile.name)
                for profile in list_load_profiles()
            ],
            default="quick",
        ).ask()
        if load_profile is None:
            raise typer.Exit(code=1)
        tests.load_profile = load_profile
        selected_load_profile = load_profile

    scenario = _prompt_scenario_selection()
    loadtest = LoadtestConfig(default_load_profile=selected_load_profile)
    if scenario.scenario_file:
        loadtest.scenario_file = scenario.scenario_file
    elif scenario.function_preset:
        loadtest.function_preset = scenario.function_preset

    return Profile(
        name=profile_name,
        control_plane=ControlPlaneConfig(implementation=runtime, build_mode=build_mode),
        modules=list(selected_modules),
        tests=tests,
        metrics=MetricsConfig(
            required=list(DEFAULT_REQUIRED_METRICS),
            prometheus_url=None,
            strict_required=False,
        ),
        report=ReportConfig(title=f"Control Plane run ({profile_name})"),
        scenario=scenario,
        loadtest=loadtest,
    )


def build_and_save_profile(profile_name: str) -> tuple[Profile, Path]:
    profile = build_profile_interactive(profile_name=profile_name)
    destination = save_profile(profile)
    return profile, destination
