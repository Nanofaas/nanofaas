from __future__ import annotations

from pathlib import Path
from typing import Final, cast

from controlplane_tool.core.models import ScenarioSelectionConfig
from controlplane_tool.scenario.scenario_loader import (
    overlay_scenario_selection,
    resolve_scenario_spec,
)
from controlplane_tool.scenario.scenario_models import ResolvedScenario, ScenarioSpec
from controlplane_tool.workspace.paths import resolve_workspace_path

_FUNCTION_SELECTION_UNSET: Final = object()


def parse_function_csv(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def configured_scenario_path(path: str | Path | None) -> Path | None:
    if path is None:
        return None
    text = str(path).strip()
    if not text:
        return None
    return resolve_workspace_path(Path(text))


def explicit_selection_requested(
    *,
    function_preset: str | None,
    functions: list[str],
    scenario_file: Path | None,
) -> bool:
    return bool(function_preset or functions or scenario_file is not None)


def resolved_scenario_from_config(
    config: ScenarioSelectionConfig,
    *,
    name: str,
    base_scenario: str,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    return resolve_scenario_spec(
        ScenarioSpec(
            name=name,
            base_scenario=base_scenario,
            runtime=runtime,
            function_preset=config.function_preset,
            functions=list(config.functions),
            namespace=namespace if namespace is not None else config.namespace,
            local_registry=local_registry or config.local_registry,
        )
    )


def overlay_selected_scenario(
    scenario: ResolvedScenario,
    *,
    base_scenario: str | None = None,
    function_preset: str | None | object = _FUNCTION_SELECTION_UNSET,
    functions: list[str] | None = None,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    source = (
        scenario.model_copy(update={"base_scenario": base_scenario})
        if base_scenario is not None
        else scenario
    )
    selected_preset = scenario.function_preset
    selected_functions = [] if scenario.function_preset else list(scenario.function_keys)
    if function_preset is not _FUNCTION_SELECTION_UNSET or functions is not None:
        selected_preset = (
            cast(str | None, function_preset)
            if function_preset is not _FUNCTION_SELECTION_UNSET
            else None
        )
        selected_functions = functions if functions is not None else []
    return overlay_scenario_selection(
        source,
        function_preset=selected_preset,
        functions=selected_functions,
        runtime=runtime,
        namespace=namespace,
        local_registry=local_registry,
    )
