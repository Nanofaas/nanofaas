from pathlib import Path

from controlplane_tool.orchestation.pipeline import execute_pipeline
from controlplane_tool.core.models import ControlPlaneConfig, Profile, ReportConfig, TestsConfig
from controlplane_tool.orchestation.pipeline import PipelineRunner


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


class FakeLegacyMetricsAdapter:
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
        return (True, f"legacy metrics ok @ {run_dir.name}")


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


def test_pipeline_runner_supports_legacy_metrics_adapter_contract(tmp_path: Path) -> None:
    profile = Profile(
        name="legacy-metrics",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["sync-queue"],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        report=ReportConfig(title="legacy-metrics"),
    )

    result = PipelineRunner(adapter=FakeLegacyMetricsAdapter()).run(profile, runs_root=tmp_path)

    metrics_step = next(step for step in result.steps if step.name == "test_metrics_prometheus_k6")

    assert result.final_status == "passed"
    assert metrics_step.status == "passed"
    assert metrics_step.detail.startswith("legacy metrics ok @ ")
