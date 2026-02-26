from pathlib import Path

from controlplane_tool.models import ControlPlaneConfig, Profile, ReportConfig, TestsConfig
from controlplane_tool.profiles import load_profile, save_profile


def test_profile_roundtrip(tmp_path: Path) -> None:
    profile = Profile(
        name="dev",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["sync-queue", "runtime-config"],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True, load_profile="quick"),
        report=ReportConfig(title="Dev run", include_baseline=True),
    )

    save_profile(profile, root=tmp_path)
    loaded = load_profile("dev", root=tmp_path)

    assert loaded == profile
