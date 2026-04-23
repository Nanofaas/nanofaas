from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import questionary

from controlplane_tool.function_catalog import (
    FunctionDefinition,
    list_function_presets,
    list_functions,
    resolve_function_definition,
    resolve_function_preset,
)
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.profiles import list_profiles, load_profile
from controlplane_tool.scenario_loader import load_scenario_file

SelectionMode = Literal["single", "multi"]
SelectionSource = Literal["default", "preset", "function", "scenario-file", "saved-profile"]

BUILDABLE_FUNCTION_RUNTIMES = frozenset({"java", "java-lite", "go", "python", "javascript", "exec"})


@dataclass(frozen=True, slots=True)
class TuiSelectionTarget:
    key: str
    label: str
    resolver_scenario: str
    selection_mode: SelectionMode
    allow_default: bool = True
    allow_presets: bool = True
    allow_single_functions: bool = False
    allow_scenario_files: bool = True
    allow_saved_profiles: bool = True
    strict_base_scenarios: frozenset[str] | None = None


@dataclass(frozen=True, slots=True)
class TuiSelectionResult:
    source: SelectionSource
    function_preset: str | None = None
    functions_csv: str | None = None
    scenario_file: Path | None = None
    saved_profile: str | None = None

    @property
    def summary_lines(self) -> list[str]:
        if self.source == "default":
            return ["Selection: built-in default"]
        if self.function_preset is not None:
            return [f"Function preset: {self.function_preset}"]
        if self.functions_csv is not None:
            return [f"Function: {self.functions_csv}"]
        if self.scenario_file is not None:
            return [f"Scenario file: {self.scenario_file}"]
        if self.saved_profile is not None:
            return [f"Saved profile: {self.saved_profile}"]
        return [f"Selection: {self.source}"]

    def as_resolver_kwargs(self) -> dict[str, object]:
        return {
            "function_preset": self.function_preset,
            "functions_csv": self.functions_csv,
            "scenario_file": self.scenario_file,
            "saved_profile": self.saved_profile,
        }


def _choice(title: str, value: str, description: str) -> questionary.Choice:
    return questionary.Choice(title, value, description=description)


def _value_choice(value: str, description: str, *, title: str | None = None) -> questionary.Choice:
    return _choice(title or value, value, description)


def selection_source_choices(target: TuiSelectionTarget) -> list[questionary.Choice]:
    choices: list[questionary.Choice] = []
    if target.allow_default:
        choices.append(
            _choice(
                "Built-in default",
                "default",
                f"Reuse the built-in scenario-aware default selection for {target.label}.",
            )
        )
    if target.allow_presets:
        choices.append(
            _choice(
                "Function preset",
                "preset",
                "Choose a compatible catalog preset and pass it to the existing resolver.",
            )
        )
    if target.allow_single_functions:
        choices.append(
            _choice(
                "Function",
                "function",
                "Choose one buildable catalog function and pass it to the existing resolver.",
            )
        )
    if target.allow_scenario_files:
        choices.append(
            _choice(
                "Scenario file",
                "scenario-file",
                "Choose a compatible TOML manifest from tools/controlplane/scenarios/.",
            )
        )
    if target.allow_saved_profiles:
        choices.append(
            _choice(
                "Saved profile",
                "saved-profile",
                "Choose a saved profile that contributes a compatible function selection.",
            )
        )
    return choices


def preset_choices(target: TuiSelectionTarget) -> list[questionary.Choice]:
    if not target.allow_presets:
        return []
    choices: list[questionary.Choice] = []
    for preset in list_function_presets():
        if not _matches_cardinality(target, list(preset.functions)):
            continue
        choices.append(
            _value_choice(
                preset.name,
                f"{preset.description} Selected functions: {_function_keys_summary(preset.functions)}.",
            )
        )
    return choices


def function_choices(target: TuiSelectionTarget) -> list[questionary.Choice]:
    if not target.allow_single_functions:
        return []
    choices: list[questionary.Choice] = []
    for function in list_functions():
        if not _is_buildable_function(function):
            continue
        choices.append(
            _value_choice(
                function.key,
                (
                    f"{function.description} Runtime={function.runtime}; "
                    f"default image={getattr(function, 'default_image', None) or getattr(function, 'image', None)}."
                ),
            )
        )
    return choices


def scenario_file_choices(target: TuiSelectionTarget) -> list[questionary.Choice]:
    if not target.allow_scenario_files:
        return []

    paths = default_tool_paths()
    choices: list[questionary.Choice] = []
    for path in sorted(paths.scenarios_dir.glob("*.toml")):
        try:
            scenario = load_scenario_file(path)
        except Exception:  # noqa: BLE001
            continue
        if not _scenario_is_compatible(target, scenario):
            continue
        relative = _relative_choice_value(path)
        choices.append(
            _choice(
                path.name,
                relative,
                _scenario_description(path.name, scenario),
            )
        )
    return choices


def saved_profile_choices(target: TuiSelectionTarget) -> list[questionary.Choice]:
    if not target.allow_saved_profiles:
        return []

    choices: list[questionary.Choice] = []
    for name in list_profiles():
        try:
            profile = load_profile(name)
        except Exception:  # noqa: BLE001
            continue
        if not _profile_is_compatible(target, profile):
            continue
        choices.append(
            _value_choice(
                name,
                f"Reuse saved profile '{name}' as a {target.label} function-selection source.",
            )
        )
    return choices


def _is_buildable_function(function: object) -> bool:
    image = getattr(function, "default_image", None) or getattr(function, "image", None)
    return (
        getattr(function, "runtime", None) in BUILDABLE_FUNCTION_RUNTIMES
        and getattr(function, "example_dir", None) is not None
        and bool(image)
    )


def _matches_cardinality(target: TuiSelectionTarget, functions: list[object]) -> bool:
    buildable = [function for function in functions if _is_buildable_function(function)]
    if target.selection_mode == "single":
        return len(buildable) == 1
    return len(buildable) >= 1


def _scenario_is_compatible(target: TuiSelectionTarget, scenario: object) -> bool:
    if target.strict_base_scenarios is not None:
        if getattr(scenario, "base_scenario", None) not in target.strict_base_scenarios:
            return False
    return _matches_cardinality(target, list(getattr(scenario, "functions", [])))


def _profile_is_compatible(target: TuiSelectionTarget, profile: object) -> bool:
    scenario_config = getattr(profile, "scenario", None)
    if scenario_config is None:
        return False

    function_preset = getattr(scenario_config, "function_preset", None)
    functions = list(getattr(scenario_config, "functions", []) or [])
    scenario_file = getattr(scenario_config, "scenario_file", None)

    if function_preset:
        try:
            preset = resolve_function_preset(function_preset)
        except Exception:  # noqa: BLE001
            return False
        return _matches_cardinality(target, list(preset.functions))

    if functions:
        try:
            resolved_functions = [resolve_function_definition(key) for key in functions]
        except Exception:  # noqa: BLE001
            return False
        return _matches_cardinality(target, resolved_functions)

    if scenario_file:
        try:
            scenario = load_scenario_file(_resolve_selection_path(Path(scenario_file)))
        except Exception:  # noqa: BLE001
            return False
        return _scenario_is_compatible(target, scenario)

    return False


def _resolve_selection_path(path: Path) -> Path:
    if path.is_absolute():
        return path
    workspace_candidate = default_tool_paths().workspace_root / path
    if workspace_candidate.exists():
        return workspace_candidate
    return path


def _relative_choice_value(path: Path) -> str:
    try:
        return path.relative_to(default_tool_paths().workspace_root).as_posix()
    except ValueError:
        return path.as_posix()


def _function_keys_summary(functions: tuple[FunctionDefinition, ...] | list[object]) -> str:
    return ", ".join(str(getattr(function, "key", "unknown")) for function in functions)


def _scenario_description(path_name: str, scenario: object) -> str:
    base_scenario = getattr(scenario, "base_scenario", "unknown")
    function_keys = list(getattr(scenario, "function_keys", []) or [])
    if not function_keys:
        function_keys = [
            str(getattr(function, "key", "unknown"))
            for function in getattr(scenario, "functions", [])
        ]
    return (
        f"Reuse manifest '{path_name}' from base scenario {base_scenario}. "
        f"Selected functions: {', '.join(function_keys)}."
    )
