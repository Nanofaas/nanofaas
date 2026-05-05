from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.models import ScenarioSelectionConfig
from controlplane_tool.scenario.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario.scenario_models import ScenarioSpec
from controlplane_tool.scenario.selection_resolution import (
    configured_scenario_path,
    explicit_selection_requested,
    overlay_selected_scenario,
    parse_function_csv,
    resolved_scenario_from_config,
)


def test_parse_function_csv_trims_empty_items() -> None:
    assert parse_function_csv(" word-stats-java, ,json-transform-java ") == [
        "word-stats-java",
        "json-transform-java",
    ]


def test_configured_scenario_path_resolves_workspace_relative_path() -> None:
    result = configured_scenario_path("tools/controlplane/scenarios/k8s-demo-java.toml")

    assert result is not None
    assert result.is_absolute()
    assert result.name == "k8s-demo-java.toml"


def test_configured_scenario_path_keeps_none_empty() -> None:
    assert configured_scenario_path(None) is None
    assert configured_scenario_path("") is None


def test_explicit_selection_requested_detects_preset_functions_or_file() -> None:
    assert explicit_selection_requested(
        function_preset="demo-java",
        functions=[],
        scenario_file=None,
    )
    assert explicit_selection_requested(
        function_preset=None,
        functions=["word-stats-java"],
        scenario_file=None,
    )
    assert explicit_selection_requested(
        function_preset=None,
        functions=[],
        scenario_file=Path("scenario.toml"),
    )
    assert not explicit_selection_requested(
        function_preset=None,
        functions=[],
        scenario_file=None,
    )


def test_resolved_scenario_from_config_uses_default_base_scenario() -> None:
    scenario = resolved_scenario_from_config(
        ScenarioSelectionConfig(function_preset="demo-java"),
        name="cli-test-selection",
        base_scenario="k3s-junit-curl",
        runtime="java",
        namespace="demo",
        local_registry="localhost:5000",
    )

    assert scenario.name == "cli-test-selection"
    assert scenario.base_scenario == "k3s-junit-curl"
    assert scenario.runtime == "java"
    assert scenario.namespace == "demo"
    assert scenario.local_registry == "localhost:5000"
    assert scenario.function_preset == "demo-java"


def test_overlay_selected_scenario_preserves_manifest_functions_with_overrides() -> None:
    original = resolve_scenario_spec(
        ScenarioSpec(
            name="manifest",
            base_scenario="k3s-junit-curl",
            runtime="java",
            functions=["word-stats-java"],
            namespace="original",
            local_registry="registry:5000",
        )
    )

    updated = overlay_selected_scenario(
        original,
        base_scenario="cli-stack",
        runtime="rust",
        namespace="override",
        local_registry="localhost:5001",
    )

    assert updated.base_scenario == "cli-stack"
    assert updated.runtime == "rust"
    assert updated.namespace == "override"
    assert updated.local_registry == "localhost:5001"
    assert updated.function_keys == ["word-stats-java"]
