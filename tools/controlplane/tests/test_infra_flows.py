from __future__ import annotations

from pathlib import Path

from controlplane_tool.infra_flows import build_gradle_action_flow, build_pipeline_flow, build_vm_flow
from controlplane_tool.models import ControlPlaneConfig, Profile, ReportConfig, TestsConfig
from controlplane_tool.vm_models import VmRequest


def test_vm_provision_base_flow_exposes_stable_task_id() -> None:
    flow = build_vm_flow(
        "vm.provision_base",
        request=VmRequest(lifecycle="external", host="vm.example.test", user="dev"),
        repo_root=Path("/repo"),
        dry_run=True,
    )

    assert flow.flow_id == "vm.provision_base"
    assert flow.task_ids == ["vm.provision_base"]


def test_build_action_flow_exposes_single_gradle_task_id() -> None:
    flow = build_gradle_action_flow(
        action="build",
        profile="core",
        modules=None,
        extra_gradle_args=[],
        dry_run=True,
    )

    assert flow.flow_id == "build.build"
    assert flow.task_ids == ["build.build"]


def test_pipeline_flow_exposes_shared_build_pipeline_task_ids() -> None:
    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=True, e2e_mockk8s=True, metrics=False),
        report=ReportConfig(title="qa"),
    )

    flow = build_pipeline_flow(profile)

    assert flow.task_ids == [
        "preflight.check",
        "build.compile",
        "images.build_control_plane",
        "tests.run_api",
        "tests.run_mockk8s",
    ]


def test_pipeline_flow_runs_mockk8s_path() -> None:
    class FakeAdapter:
        def preflight(self, profile: Profile) -> list[str]:  # noqa: ARG002
            return []

        def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
            return (True, "compile ok")

        def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
            return (True, "image ok")

        def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
            return (True, "api ok")

        def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
            return (True, "mockk8s ok")

        def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:  # noqa: ARG002
            raise AssertionError("metrics must not run")

    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=True, metrics=False),
        report=ReportConfig(title="qa"),
    )

    flow = build_pipeline_flow(profile, adapter=FakeAdapter(), runs_root=Path("/tmp"))
    result = flow.run()

    assert result.final_status == "passed"
    mockk8s_step = next(step for step in result.steps if step.name == "test_e2e_mockk8s")
    assert mockk8s_step.status == "passed"
    assert mockk8s_step.detail == "mockk8s ok"
