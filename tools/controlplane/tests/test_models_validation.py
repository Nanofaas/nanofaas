"""
Tests for models.py and scenario_models.py — Pydantic validation and helpers.
"""
from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from controlplane_tool.models import (
    VM_BACKED_SCENARIOS,
    ControlPlaneConfig,
    LoadtestConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    ScenarioSelectionConfig,
    TestsConfig,
)
from controlplane_tool.scenario_models import (
    ResolvedFunction,
    ResolvedScenario,
    ScenarioInvokeConfig,
    ScenarioLoadConfig,
    ScenarioSpec,
)


# ---------------------------------------------------------------------------
# models.py — ControlPlaneConfig
# ---------------------------------------------------------------------------

def test_control_plane_config_java_jvm() -> None:
    cfg = ControlPlaneConfig(implementation="java", build_mode="jvm")
    assert cfg.implementation == "java"
    assert cfg.build_mode == "jvm"


def test_control_plane_config_rejects_invalid_implementation() -> None:
    with pytest.raises(ValidationError):
        ControlPlaneConfig(implementation="go", build_mode="jvm")  # type: ignore[arg-type]


def test_control_plane_config_rejects_invalid_build_mode() -> None:
    with pytest.raises(ValidationError):
        ControlPlaneConfig(implementation="java", build_mode="docker")  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# models.py — TestsConfig
# ---------------------------------------------------------------------------

def test_tests_config_defaults() -> None:
    cfg = TestsConfig()
    assert cfg.enabled is True
    assert cfg.api is True
    assert cfg.e2e_mockk8s is True
    assert cfg.metrics is True
    assert cfg.load_profile == "quick"


def test_tests_config_custom_values() -> None:
    cfg = TestsConfig(enabled=False, api=False, metrics=False, load_profile="stress")
    assert cfg.enabled is False
    assert cfg.load_profile == "stress"


# ---------------------------------------------------------------------------
# models.py — MetricsConfig
# ---------------------------------------------------------------------------

def test_metrics_config_defaults_to_empty_required() -> None:
    cfg = MetricsConfig()
    assert cfg.required == []
    assert cfg.prometheus_url is None
    assert cfg.strict_required is False


def test_metrics_config_with_required_list() -> None:
    cfg = MetricsConfig(required=["function_dispatch_total"])
    assert "function_dispatch_total" in cfg.required


# ---------------------------------------------------------------------------
# models.py — Profile
# ---------------------------------------------------------------------------

def _java_profile(name: str = "qa") -> Profile:
    return Profile(
        name=name,
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
    )


def test_profile_defaults_applied() -> None:
    p = _java_profile()
    assert p.name == "qa"
    assert isinstance(p.tests, TestsConfig)
    assert isinstance(p.metrics, MetricsConfig)
    assert isinstance(p.report, ReportConfig)


def test_profile_name_stored() -> None:
    p = _java_profile("my-profile")
    assert p.name == "my-profile"


def test_profile_modules_list() -> None:
    p = Profile(
        name="all",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=["async-queue", "autoscaler"],
    )
    assert "async-queue" in p.modules


# ---------------------------------------------------------------------------
# models.py — VM_BACKED_SCENARIOS
# ---------------------------------------------------------------------------

def test_vm_backed_scenarios_includes_k3s_junit_curl() -> None:
    assert "k3s-junit-curl" in VM_BACKED_SCENARIOS


def test_vm_backed_scenarios_excludes_docker() -> None:
    assert "docker" not in VM_BACKED_SCENARIOS


# ---------------------------------------------------------------------------
# scenario_models.py — ScenarioSpec validation
# ---------------------------------------------------------------------------

def test_scenario_spec_requires_exactly_one_of_preset_or_functions() -> None:
    with pytest.raises(ValidationError, match="exactly one"):
        ScenarioSpec(
            name="test",
            base_scenario="k3s-junit-curl",
            function_preset=None,
            functions=[],
        )


def test_scenario_spec_rejects_both_preset_and_functions() -> None:
    with pytest.raises(ValidationError, match="exactly one"):
        ScenarioSpec(
            name="test",
            base_scenario="k3s-junit-curl",
            function_preset="basic",
            functions=["echo-test"],
        )


def test_scenario_spec_accepts_single_preset() -> None:
    spec = ScenarioSpec(
        name="test",
        base_scenario="k3s-junit-curl",
        function_preset="demo-java",
    )
    assert spec.function_preset == "demo-java"


def test_scenario_spec_accepts_single_functions_list() -> None:
    spec = ScenarioSpec(
        name="test",
        base_scenario="k3s-junit-curl",
        functions=["word-stats-java"],
    )
    assert spec.functions == ["word-stats-java"]


def test_scenario_spec_rejects_invalid_load_target() -> None:
    with pytest.raises(ValidationError, match="load.targets must be a subset"):
        ScenarioSpec(
            name="test",
            base_scenario="k3s-junit-curl",
            functions=["word-stats-java"],
            load=ScenarioLoadConfig(targets=["json-transform-java"]),
        )


# ---------------------------------------------------------------------------
# scenario_models.py — ResolvedScenario helpers
# ---------------------------------------------------------------------------

def _make_fn(key: str, runtime: str = "java") -> ResolvedFunction:
    return ResolvedFunction(
        key=key, family="echo", runtime=runtime, description="test fn"
    )


def test_resolved_scenario_selected_runtimes() -> None:
    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        functions=[_make_fn("fn-a", "java"), _make_fn("fn-b", "python")],
    )
    assert resolved.selected_runtimes() == {"java", "python"}


def test_resolved_scenario_payload_overrides_converts_to_strings(tmp_path: Path) -> None:
    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        payloads={"fn-a": tmp_path / "payload.json"},
    )
    overrides = resolved.payload_overrides()
    assert isinstance(overrides["fn-a"], str)


def test_resolved_scenario_default_registry() -> None:
    resolved = ResolvedScenario(name="test", base_scenario="k3s-junit-curl")
    assert resolved.local_registry == "localhost:5000"


def test_resolved_scenario_prefers_registry_url_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_REGISTRY_URL", "localhost:5001")
    resolved = ResolvedScenario(name="test", base_scenario="k3s-junit-curl")
    assert resolved.local_registry == "localhost:5001"


# ---------------------------------------------------------------------------
# scenario_models.py — ResolvedFunction
# ---------------------------------------------------------------------------

def test_resolved_function_optional_fields_default_none() -> None:
    fn = ResolvedFunction(key="echo-test", family="echo", runtime="java", description="desc")
    assert fn.image is None
    assert fn.payload_path is None
    assert fn.example_dir is None


def test_resolved_function_from_definition() -> None:
    from controlplane_tool.function_catalog import resolve_function_definition

    defn = resolve_function_definition("word-stats-java")
    fn = ResolvedFunction.from_definition(defn, image="my/img:v1", payload_path=None)
    assert fn.key == "word-stats-java"
    assert fn.image == "my/img:v1"
