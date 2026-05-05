from pathlib import Path

from controlplane_tool.core.models import (
    CliTestConfig,
    ControlPlaneConfig,
    LoadtestConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    ScenarioSelectionConfig,
    TestsConfig,
)
from controlplane_tool.workspace.profiles import load_profile, load_profile_prefect_config, save_profile


def test_profile_roundtrip(tmp_path: Path) -> None:
    profile = Profile(
        name="dev",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["sync-queue", "runtime-config"],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True, load_profile="quick"),
        metrics=MetricsConfig(
            required=["function_dispatch_total", "function_latency_ms"],
            prometheus_url="http://127.0.0.1:8081/actuator/prometheus",
        ),
        report=ReportConfig(title="Dev run", include_baseline=True),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("dev", root=tmp_path)

    assert loaded == profile


def test_profile_roundtrip_without_prometheus_url(tmp_path: Path) -> None:
    profile = Profile(
        name="dev-no-prom",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["autoscaler"],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True, load_profile="stress"),
        metrics=MetricsConfig(
            required=["function_dispatch_total", "function_latency_ms"],
            prometheus_url=None,
        ),
        report=ReportConfig(title="Dev run (no prom)", include_baseline=False),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("dev-no-prom", root=tmp_path)

    assert loaded.metrics.prometheus_url is None


def test_profile_roundtrip_with_e2e_selection(tmp_path: Path) -> None:
    profile = Profile(
        name="demo-java",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        scenario=ScenarioSelectionConfig(
            base_scenario="k3s-junit-curl",
            function_preset="demo-java",
            namespace="nanofaas-e2e",
        ),
        cli_test=CliTestConfig(default_scenario="vm"),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("demo-java", root=tmp_path)

    assert loaded.scenario.function_preset == "demo-java"
    assert loaded.scenario.base_scenario == "k3s-junit-curl"
    assert loaded.cli_test.default_scenario == "vm"


def test_profile_roundtrip_with_javascript_e2e_selection(tmp_path: Path) -> None:
    profile = Profile(
        name="demo-javascript",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        scenario=ScenarioSelectionConfig(
            base_scenario="k3s-junit-curl",
            function_preset="demo-javascript",
            namespace="nanofaas-e2e",
        ),
        cli_test=CliTestConfig(default_scenario="cli-stack"),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("demo-javascript", root=tmp_path)

    assert loaded.scenario.function_preset == "demo-javascript"
    assert loaded.scenario.base_scenario == "k3s-junit-curl"
    assert loaded.cli_test.default_scenario == "cli-stack"


def test_profile_roundtrip_with_loadtest_defaults(tmp_path: Path) -> None:
    profile = Profile(
        name="perf-java",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        loadtest=LoadtestConfig(
            default_load_profile="smoke",
            metrics_gate_mode="warn",
            scenario_file="tools/controlplane/scenarios/k8s-demo-java.toml",
        ),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("perf-java", root=tmp_path)

    assert loaded.loadtest.default_load_profile == "smoke"
    assert loaded.loadtest.metrics_gate_mode == "warn"
    assert loaded.loadtest.scenario_file == "tools/controlplane/scenarios/k8s-demo-java.toml"


def test_profile_loader_reads_optional_prefect_metadata(tmp_path: Path) -> None:
    profile_path = tmp_path / "prefect-ready.toml"
    profile_path.write_text(
        """
name = "prefect-ready"

[control_plane]
implementation = "java"
build_mode = "native"

[prefect]
enabled = true
work_pool = "local-process"
tags = ["e2e", "optional"]
""".strip()
        + "\n",
        encoding="utf-8",
    )

    prefect = load_profile_prefect_config("prefect-ready", root=tmp_path)

    assert prefect is not None
    assert prefect.enabled is True
    assert prefect.work_pool == "local-process"
    assert prefect.tags == ["e2e", "optional"]
