from pathlib import Path

import pytest

from controlplane_tool.scenario.scenario_loader import (
    load_scenario_file,
    overlay_scenario_selection,
)
from controlplane_tool.scenario.scenario_models import (
    ResourceSpec,
    ScenarioLoadConfig,
    ScenarioSpec,
)


def test_loader_resolves_function_preset_and_payload_paths() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    assert scenario.base_scenario == "validate-k3s"
    assert scenario.function_preset == "demo-java"
    assert [function.key for function in scenario.functions] == [
        "word-stats-java",
        "json-transform-java",
    ]
    assert scenario.payloads["word-stats-java"].name == "word-stats-sample.json"
    assert scenario.functions[0].resources is not None
    assert scenario.functions[0].resources.requests.cpu == 0.25
    assert scenario.functions[0].resources.limits.memory_mib == 512


def test_load_scenario_file_resolves_relative_path_from_workspace_root(
    monkeypatch,
) -> None:
    monkeypatch.chdir("/")

    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    assert scenario.name == "k8s-demo-java"


def test_loader_resolves_javascript_scenario_manifest() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-javascript.toml"))

    assert scenario.base_scenario == "validate-k3s"
    assert scenario.function_preset == "demo-javascript"
    assert scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_loader_resolves_two_vm_loadtest_manifest() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/two-vm-loadtest-java.toml"))

    assert scenario.base_scenario == "loadtest-two-vm"
    assert scenario.function_preset == "demo-java"
    assert scenario.load.targets == ["word-stats-java"]


def test_loader_resolves_azure_vm_loadtest_manifest() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/azure-vm-loadtest-java.toml"))

    assert scenario.base_scenario == "loadtest-azure"
    assert scenario.function_preset == "demo-loadtest"
    assert scenario.load.targets == ["word-stats-java"]


def test_loader_rejects_both_functions_and_function_preset() -> None:
    with pytest.raises(ValueError, match="exactly one of"):
        ScenarioSpec(
            name="bad",
            base_scenario="validate-k3s",
            runtime="java",
            function_preset="demo-java",
            functions=["word-stats-java"],
        )

def test_loader_rejects_load_targets_outside_selected_functions() -> None:
    with pytest.raises(ValueError, match="subset of the selected functions"):
        ScenarioSpec(
            name="bad-targets",
            base_scenario="validate-k3s",
            runtime="java",
            functions=["word-stats-java"],
            load=ScenarioLoadConfig(targets=["json-transform-java"]),
        )


def test_loader_resolves_per_function_resources(tmp_path: Path) -> None:
    scenario_file = tmp_path / "resources.toml"
    scenario_file.write_text(
        """
name = "resources"
base_scenario = "validate-k3s"
functions = ["word-stats-java"]

[resources.word-stats-java.requests]
cpu = 0.25
memoryMiB = 256

[resources.word-stats-java.limits]
cpu = 0.5
memoryMiB = 512
""",
        encoding="utf-8",
    )

    scenario = load_scenario_file(scenario_file)

    resources = scenario.functions[0].resources
    assert resources is not None
    assert resources.model_dump(by_alias=True) == {
        "requests": {"cpu": 0.25, "memoryMiB": 256},
        "limits": {"cpu": 0.5, "memoryMiB": 512},
    }


def test_loader_rejects_resources_for_unselected_function() -> None:
    with pytest.raises(ValueError, match="resources must refer to selected functions"):
        ScenarioSpec(
            name="bad-resources",
            base_scenario="validate-k3s",
            functions=["word-stats-java"],
            resources={
                "json-transform-java": {
                    "limits": {"cpu": 0.5, "memoryMiB": 512}
                }
            },
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


def test_overlay_selection_preserves_selected_function_resources() -> None:
    base = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    base.functions[0].resources = ResourceSpec.model_validate({
        "requests": {"cpu": 0.25, "memoryMiB": 256},
        "limits": {"cpu": 0.5, "memoryMiB": 512},
    })

    resolved = overlay_scenario_selection(
        base,
        function_preset=None,
        functions=["word-stats-java"],
        runtime="java",
        namespace=None,
        local_registry="localhost:5000",
    )

    assert resolved.functions[0].resources == base.functions[0].resources


def test_overlay_selection_rejects_when_load_targets_become_empty() -> None:
    base = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    with pytest.raises(
        ValueError,
        match="selected functions do not satisfy load targets for scenario 'validate-k3s'",
    ):
        overlay_scenario_selection(
            base,
            function_preset=None,
            functions=["word-stats-go"],
            runtime="java",
            namespace=None,
            local_registry="localhost:5000",
        )
