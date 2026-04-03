from pathlib import Path

from controlplane_tool.pipeline import execute_pipeline
from controlplane_tool.models import ControlPlaneConfig, Profile, ReportConfig, TestsConfig
from controlplane_tool.pipeline import PipelineRunner


class FakeSuccessAdapter:
    def preflight(self, profile: Profile) -> list[str]:
        return []

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (True, "compiled")

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (True, "image built")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (True, "api ok")

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (True, "mockk8s ok")

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return (True, "metrics ok")


def test_run_with_tests_emits_summary_and_report(tmp_path: Path) -> None:
    profile = Profile(
        name="integration",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["sync-queue"],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True),
        report=ReportConfig(title="integration"),
    )

    result = PipelineRunner(adapter=FakeSuccessAdapter()).run(profile, runs_root=tmp_path)

    assert (result.run_dir / "summary.json").exists()
    assert (result.run_dir / "report.html").exists()
    assert result.final_status in {"passed", "failed"}


def test_execute_pipeline_emits_same_artifact_shape(tmp_path: Path) -> None:
    profile = Profile(
        name="executor",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["sync-queue"],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=True),
        report=ReportConfig(title="executor"),
    )

    result = execute_pipeline(
        profile,
        runner=PipelineRunner(adapter=FakeSuccessAdapter()),
        runs_root=tmp_path,
    )

    assert (result.run_dir / "summary.json").exists()
    assert (result.run_dir / "report.html").exists()
