from pathlib import Path

import pytest

from controlplane_tool.scenario_loader import (
    load_scenario_file,
    overlay_scenario_selection,
)
from controlplane_tool.scenario_models import ScenarioLoadConfig, ScenarioPrefectConfig, ScenarioSpec


def test_loader_resolves_function_preset_and_payload_paths() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    assert scenario.base_scenario == "k3s-junit-curl"
    assert scenario.function_preset == "demo-java"
    assert [function.key for function in scenario.functions] == [
        "word-stats-java",
        "json-transform-java",
    ]
    assert scenario.payloads["word-stats-java"].name == "word-stats-sample.json"


def test_load_scenario_file_resolves_relative_path_from_workspace_root(
    monkeypatch,
) -> None:
    monkeypatch.chdir("/")

    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    assert scenario.name == "k8s-demo-java"


def test_loader_rejects_both_functions_and_function_preset() -> None:
    with pytest.raises(ValueError, match="exactly one of"):
        ScenarioSpec(
            name="bad",
            base_scenario="k3s-junit-curl",
            runtime="java",
            function_preset="demo-java",
            functions=["word-stats-java"],
        )


def test_loader_rejects_load_targets_outside_selected_functions() -> None:
    with pytest.raises(ValueError, match="subset of the selected functions"):
        ScenarioSpec(
            name="bad-targets",
            base_scenario="k3s-junit-curl",
            runtime="java",
            functions=["word-stats-java"],
            load=ScenarioLoadConfig(targets=["json-transform-java"]),
        )


def test_overlay_selection_preserves_payloads_and_filters_load_targets() -> None:
    base = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    resolved = overlay_scenario_selection(
        base,
        function_preset=None,
        functions=["word-stats-java"],
        runtime="java",
        namespace=None,
        local_registry="localhost:5000",
    )

    assert resolved.function_keys == ["word-stats-java"]
    assert resolved.load.targets == ["word-stats-java"]
    assert resolved.payloads["word-stats-java"].name == "word-stats-sample.json"


def test_overlay_selection_rejects_when_load_targets_become_empty() -> None:
    base = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    with pytest.raises(
        ValueError,
        match="selected functions do not satisfy load targets for scenario 'k3s-junit-curl'",
    ):
        overlay_scenario_selection(
            base,
            function_preset=None,
            functions=["word-stats-go"],
            runtime="java",
            namespace=None,
            local_registry="localhost:5000",
        )


def test_loader_preserves_optional_prefect_metadata() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    updated = overlay_scenario_selection(
        scenario.model_copy(
            update={
                "prefect": ScenarioPrefectConfig(
                    enabled=True,
                    deployment_name="demo-k8s",
                    work_pool="local-process",
                )
            }
        ),
        function_preset=None,
        functions=["word-stats-java"],
        runtime="java",
        namespace=None,
        local_registry="localhost:5000",
    )

    assert updated.prefect.enabled is True
    assert updated.prefect.deployment_name == "demo-k8s"
    assert updated.prefect.work_pool == "local-process"
