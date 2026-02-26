from pathlib import Path

from controlplane_tool.models import ControlPlaneConfig, Profile, ReportConfig, TestsConfig
from controlplane_tool.pipeline import PipelineRunner


class FakeFailingAdapter:
    def preflight(self, profile: Profile) -> list[str]:
        return []

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (False, "compile failed")

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        raise AssertionError("build_image must not run after compile failure")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        raise AssertionError("api tests must not run")

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        raise AssertionError("mockk8s tests must not run")

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        raise AssertionError("metrics tests must not run")


def test_pipeline_stops_on_build_failure(tmp_path: Path) -> None:
    profile = Profile(
        name="failing",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=[],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True),
        report=ReportConfig(title="failing"),
    )
    runner = PipelineRunner(adapter=FakeFailingAdapter())

    result = runner.run(profile, runs_root=tmp_path)

    assert result.final_status == "failed"
    assert [step.name for step in result.steps if step.status == "failed"] == ["compile"]
