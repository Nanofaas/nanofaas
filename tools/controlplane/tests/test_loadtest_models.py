from pathlib import Path

from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import LoadtestRequest
from controlplane_tool.metrics_contract import CORE_REQUIRED_METRICS
from controlplane_tool.models import ControlPlaneConfig, Profile
from controlplane_tool.scenario_loader import load_scenario_file


def test_resolved_scenario_carries_optional_load_profile_name_and_targets() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    assert scenario.name == "k8s-demo-java"
    assert scenario.function_preset == "demo-java"
    assert scenario.function_keys == ["word-stats-java", "json-transform-java"]
    assert scenario.load.load_profile_name == "quick"
    assert scenario.load.targets == ["word-stats-java", "json-transform-java"]


def test_loadtest_request_keeps_ordered_target_matrix_from_scenario() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    request = LoadtestRequest(
        name="perf-java",
        profile=Profile(
            name="perf-java",
            control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        ),
        scenario=scenario,
        load_profile=resolve_load_profile("quick"),
    )

    assert request.targets is not None
    assert request.targets.targets == ["word-stats-java", "json-transform-java"]


def test_loadtest_request_hydrates_effective_metrics_gate_from_profile_defaults() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    request = LoadtestRequest(
        name="perf-java",
        profile=Profile(
            name="perf-java",
            control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        ),
        scenario=scenario,
        load_profile=resolve_load_profile("quick"),
    )

    assert request.metrics_gate.required_metrics == list(CORE_REQUIRED_METRICS)
