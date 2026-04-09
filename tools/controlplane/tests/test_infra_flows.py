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
