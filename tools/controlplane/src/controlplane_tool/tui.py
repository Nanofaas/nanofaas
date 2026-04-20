from __future__ import annotations

from pathlib import Path

import questionary
import typer

from controlplane_tool.cli_test_catalog import list_cli_test_scenarios
from controlplane_tool.e2e_catalog import list_scenarios
from controlplane_tool.function_catalog import list_function_presets
from controlplane_tool.loadtest_catalog import list_load_profiles
from controlplane_tool.models import (
    CliTestConfig,
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
from controlplane_tool.tui_widgets import _checkbox_values, _select_value

DEFAULT_REQUIRED_METRICS: tuple[str, ...] = CORE_REQUIRED_METRICS


def _choice(title: str, value: str, description: str) -> questionary.Choice:
    return questionary.Choice(title, value, description=description)


def _required_select_value(
    message: str,
    *,
    choices: list[questionary.Choice],
    default: str | None = None,
) -> str:
    try:
        return _select_value(message, choices=choices, default=default)
    except KeyboardInterrupt as exc:
        raise typer.Exit(code=1) from exc


def _required_checkbox_values(
    message: str,
    *,
    choices: list[questionary.Choice],
    default_values: list[str] | None = None,
) -> list[str]:
    try:
        result = _checkbox_values(message, choices=choices, default_values=default_values)
    except KeyboardInterrupt as exc:
        raise typer.Exit(code=1) from exc
    return list(result)


_IMPLEMENTATION_CHOICES = [
    _choice(
        "Java",
        "java",
        "Use the Java control-plane implementation, with support for both JVM and GraalVM native build paths.",
    ),
    _choice(
        "Rust",
        "rust",
        "Use the Rust control-plane implementation and keep the profile aligned with the Rust toolchain.",
    ),
]

_JAVA_BUILD_MODE_CHOICES = [
    _choice(
        "Native",
        "native",
        "Build the Java control-plane as a GraalVM native image for low-latency execution checks.",
    ),
    _choice(
        "JVM",
        "jvm",
        "Build the Java control-plane as a standard JVM application for the fastest local iteration cycle.",
    ),
]

_E2E_SELECTION_MODE_CHOICES = [
    _choice(
        "Function preset",
        "preset",
        "Store a named function preset so future E2E and load-test workflows can reuse a curated function bundle.",
    ),
    _choice(
        "Explicit function CSV",
        "functions",
        "Store an explicit comma-separated function list when you need a custom combination outside the preset catalog.",
    ),
    _choice(
        "Scenario file",
        "scenario-file",
        "Point the profile at a scenario manifest file and let that file define the full E2E selection.",
    ),
]


def _scenario_file_choices() -> list[questionary.Choice]:
    workspace_root = default_tool_paths().workspace_root
    scenario_root = default_tool_paths().scenarios_dir
    return [
        _choice(
            path.name,
            str(path.relative_to(workspace_root)),
            f"Reuse the scenario manifest '{path.name}' from {scenario_root.name} without prompting for presets or explicit functions.",
        )
        for path in sorted(scenario_root.glob("*.toml"))
    ]


def _base_scenario_choices() -> list[questionary.Choice]:
    choices: list[questionary.Choice] = []
    for scenario in list_scenarios():
        runtimes = ", ".join(scenario.supported_runtimes)
        description = (
            f"{scenario.description} Requires VM={'yes' if scenario.requires_vm else 'no'}; "
            f"supported runtimes={runtimes}; selection mode={scenario.selection_mode}."
        )
        choices.append(_choice(scenario.name, scenario.name, description))
    return choices


def _function_preset_choices() -> list[questionary.Choice]:
    choices: list[questionary.Choice] = []
    for preset in list_function_presets():
        description = (
            f"{preset.description} Includes {len(preset.functions)} function definition(s) from the repository catalog."
        )
        choices.append(_choice(preset.name, preset.name, description))
    return choices


def _cli_test_scenario_choices() -> list[questionary.Choice]:
    choices: list[questionary.Choice] = []
    for scenario in list_cli_test_scenarios():
        description = (
            f"{scenario.description} Requires VM={'yes' if scenario.requires_vm else 'no'}; "
            f"function selection={'yes' if scenario.accepts_function_selection else 'no'}."
        )
        choices.append(_choice(scenario.name, scenario.name, description))
    return choices


def _module_selection_choices() -> list[questionary.Choice]:
    return [
        _choice(
            module.name,
            module.key,
            f"{module.description} Module key={module.key}.",
        )
        for module in module_choices()
    ]


def _load_profile_choices() -> list[questionary.Choice]:
    choices: list[questionary.Choice] = []
    for profile in list_load_profiles():
        stage_summary = ", ".join(f"{stage.duration}@{stage.target}" for stage in profile.stages)
        description = (
            f"{profile.description} Stages={stage_summary}; summary window={profile.summary_window_seconds}s."
        )
        choices.append(_choice(profile.name.capitalize(), profile.name, description))
    return choices


def _prompt_scenario_selection() -> ScenarioSelectionConfig:
    save_defaults = questionary.confirm(
        "Save default E2E selection in this profile?",
        default=False,
    ).ask()
    if save_defaults is None:
        raise typer.Exit(code=1)
    if not save_defaults:
        return ScenarioSelectionConfig()

    selection_mode = _required_select_value(
        "Default E2E selection type:",
        choices=_E2E_SELECTION_MODE_CHOICES,
        default="preset",
    )

    if selection_mode == "scenario-file":
        scenario_file = _required_select_value(
            "Scenario file:",
            choices=_scenario_file_choices(),
            default="tools/controlplane/scenarios/k8s-demo-java.toml",
        )
        return ScenarioSelectionConfig(scenario_file=scenario_file)

    base_scenario = _required_select_value(
        "Base E2E scenario:",
        choices=_base_scenario_choices(),
        default="k3s-junit-curl",
    )

    if selection_mode == "preset":
        preset_name = _required_select_value(
            "Function preset:",
            choices=_function_preset_choices(),
            default="demo-java",
        )
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


def _prompt_cli_test_selection() -> CliTestConfig:
    save_defaults = questionary.confirm(
        "Save default CLI validation scenario in this profile?",
        default=False,
    ).ask()
    if save_defaults is None:
        raise typer.Exit(code=1)
    if not save_defaults:
        return CliTestConfig()

    default_scenario = _required_select_value(
        "Default CLI validation scenario:",
        choices=_cli_test_scenario_choices(),
        default="vm",
    )
    return CliTestConfig(default_scenario=default_scenario)


def build_profile_interactive(profile_name: str) -> Profile:
    runtime = _required_select_value(
        "Control plane implementation:",
        choices=_IMPLEMENTATION_CHOICES,
        default="java",
    )

    build_mode = "rust"
    if runtime == "java":
        selected_mode = _required_select_value(
            "Java build mode:",
            choices=_JAVA_BUILD_MODE_CHOICES,
            default="native",
        )
        build_mode = selected_mode

    selected_modules = _required_checkbox_values(
        "Select control-plane modules:",
        choices=_module_selection_choices(),
        default_values=[],
    )

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
        load_profile = _required_select_value(
            "Loadtest profile:",
            choices=_load_profile_choices(),
            default="quick",
        )
        tests.load_profile = load_profile
        selected_load_profile = load_profile

    scenario = _prompt_scenario_selection()
    cli_test = _prompt_cli_test_selection()
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
        cli_test=cli_test,
    )


def build_and_save_profile(profile_name: str) -> tuple[Profile, Path]:
    profile = build_profile_interactive(profile_name=profile_name)
    destination = save_profile(profile)
    return profile, destination
