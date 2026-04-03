from pathlib import Path

from controlplane_tool.models import (
    ControlPlaneConfig,
    MetricsConfig,
    Profile,
    ReportConfig,
    TestsConfig,
)
from controlplane_tool.profiles import load_profile, save_profile


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
