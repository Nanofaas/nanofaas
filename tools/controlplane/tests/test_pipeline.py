from pathlib import Path

import controlplane_tool.pipeline as pipeline_mod
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


class FakePassingAdapter:
    def preflight(self, profile: Profile) -> list[str]:  # noqa: ARG002
        return []

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
        return (True, "compile ok")

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
        return (True, "image ok")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
        raise AssertionError("api tests must not run")

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
        raise AssertionError("mockk8s tests must not run")


def test_pipeline_run_delegates_metrics_load_flow_to_loadtest_runner(
    tmp_path: Path,
    monkeypatch,
) -> None:
    calls: list[str] = []

    class RecordingLoadtestRunner:
        def __init__(self, adapter: object | None = None) -> None:  # noqa: ARG002
            return None

        def run(self, request, runs_root=None):  # noqa: ANN001
            calls.append(request.load_profile.name)
            run_dir = Path(runs_root or tmp_path) / "loadtest-run"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text("{}", encoding="utf-8")
            (run_dir / "report.html").write_text("<html></html>", encoding="utf-8")
            return pipeline_mod.RunResult(
                profile_name=request.profile.name,
                run_dir=run_dir,
                final_status="passed",
                steps=[
                    pipeline_mod.StepResult(
                        name="load_k6",
                        status="passed",
                        detail="ok",
                        duration_ms=1,
                    )
                ],
            )

    monkeypatch.setattr(pipeline_mod, "LoadtestRunner", RecordingLoadtestRunner)
    profile = Profile(
        name="metrics",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        report=ReportConfig(title="metrics"),
    )

    result = PipelineRunner(adapter=FakePassingAdapter()).run(profile, runs_root=tmp_path)

    assert calls == ["quick"]
    assert result.final_status == "passed"
    assert [step.name for step in result.steps] == [
        "preflight",
        "compile",
        "docker_image",
        "test_api",
        "test_e2e_mockk8s",
        "test_metrics_prometheus_k6",
    ]
    metrics_step = next(step for step in result.steps if step.name == "test_metrics_prometheus_k6")
    assert metrics_step.status == "passed"
    assert "loadtest" in metrics_step.detail
