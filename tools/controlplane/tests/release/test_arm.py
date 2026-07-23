from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.images.plan import build_image_plan
from controlplane_tool.release import arm
from controlplane_tool.release.state import ArtifactEvidence


REPO_ROOT = Path(__file__).resolve().parents[4]
REGISTRY = "localhost:5000/nanofaas"


def _plan():  # noqa: ANN202
    return arm.build_arm64_image_plan(
        REPO_ROOT,
        "v0.18.0",
        registry=REGISTRY,
    )


def test_arm64_plan_contains_all_26_logical_cells() -> None:
    plan = _plan()

    assert len(plan.cells) == 26
    assert len(plan.bake_cells) == 21
    assert len(plan.gradle_cells) == 5
    assert {cell.architecture for cell in plan.cells} == {"arm64"}


def test_arm64_commands_tunnel_the_registry_and_reuse_the_named_builder() -> None:
    plan = _plan()

    commands = arm.arm64_build_commands(
        plan,
        builder_name="nanofaas-release-v0-18-0",
        remote_bake_file="/srv/release/docker-bake-arm64.json",
        remote_source_dir="/srv/source",
        registry_upstream="203.0.113.10",
    )

    tunnel = commands[0]
    assert tunnel.task_id == "release.arm64.registry-tunnel"
    assert tunnel.argv[:2] == ("sh", "-c")
    assert "TCP-LISTEN:5000" in tunnel.argv[2]
    assert "TCP:203.0.113.10:5000" in tunnel.argv[2]
    assert all(command.role == "arm-builder" for command in commands)
    assert commands[1].argv == (
        "docker",
        "buildx",
        "inspect",
        "nanofaas-release-v0-18-0",
        "--bootstrap",
    )
    bake = next(command for command in commands if command.task_id == "release.images.bake.arm64")
    assert bake.argv == (
        "docker",
        "buildx",
        "bake",
        "--builder",
        "nanofaas-release-v0-18-0",
        "--file",
        "/srv/release/docker-bake-arm64.json",
        "--load",
        "docker-arm64",
    )
    native = [command for command in commands if command.task_id.startswith("release.arm64.native")]
    assert len(native) == 5
    assert all("-PimagePlatform=linux/arm64" in command.argv for command in native)


def test_builder_bootstrap_must_explicitly_support_linux_arm64() -> None:
    arm.require_arm64_builder("Name: release\nPlatforms: linux/amd64, linux/arm64\n")

    with pytest.raises(RuntimeError, match="does not support linux/arm64"):
        arm.require_arm64_builder("Name: release\nPlatforms: linux/amd64\n")


def test_arm64_evidence_must_cover_every_planned_registry_artifact() -> None:
    plan = _plan()
    evidence = tuple(
        ArtifactEvidence("remote", f"docker://{cell.image}", "sha256:" + "a" * 64)
        for cell in plan.cells
    )

    arm.require_complete_arm64_evidence(plan, evidence)

    with pytest.raises(RuntimeError, match="does not cover all 26"):
        arm.require_complete_arm64_evidence(plan, evidence[:-1])


def test_server_smokes_cover_every_non_watchdog_artifact_and_existing_health_endpoint() -> None:
    smokes = arm.server_smoke_specs(_plan())

    assert len(smokes) == 25
    assert all(smoke.cell.target.name != "watchdog" for smoke in smokes)
    control_planes = [smoke for smoke in smokes if smoke.cell.target.name == "control-plane"]
    assert {(smoke.container_port, smoke.health_path) for smoke in control_planes} == {
        (8081, "/actuator/health")
    }
    assert all(
        (smoke.container_port, smoke.health_path) == (8080, "/health")
        for smoke in smokes
        if smoke.cell.target.name != "control-plane"
    )


def test_watchdog_smoke_accepts_only_the_expected_missing_child_exit() -> None:
    arm.require_expected_watchdog_exit(
        1,
        "",
        "Failed to spawn runtime: No such file or directory (os error 2)",
    )

    with pytest.raises(RuntimeError, match="exec format error"):
        arm.require_expected_watchdog_exit(1, "", "exec format error")
    with pytest.raises(RuntimeError, match="unexpectedly succeeded"):
        arm.require_expected_watchdog_exit(0, "nanofaas-watchdog 0.18.0", "")
    with pytest.raises(RuntimeError, match="expected missing-child"):
        arm.require_expected_watchdog_exit(1, "", "unrelated failure")


def test_arm64_plan_is_the_arm_partition_of_the_complete_image_plan() -> None:
    complete = build_image_plan(REPO_ROOT, "v0.18.0", registry=REGISTRY)

    assert _plan().cells == tuple(
        cell for cell in complete.cells if cell.architecture == "arm64"
    )
