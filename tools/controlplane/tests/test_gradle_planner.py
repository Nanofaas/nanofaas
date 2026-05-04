from pathlib import Path

from controlplane_tool.building.requests import BuildRequest
from controlplane_tool.building.gradle_planner import (
    build_gradle_command,
    plan_module_matrix_commands,
)


def test_image_request_uses_boot_build_image_and_profile_modules() -> None:
    command = build_gradle_command(
        repo_root=Path("/repo"),
        request=BuildRequest(action="image", profile="k8s"),
        extra_gradle_args=["-PcontrolPlaneImage=nanofaas/control-plane:test"],
    )
    assert command[:2] == ["/repo/gradlew", ":control-plane:bootBuildImage"]
    assert "-PcontrolPlaneModules=k8s-deployment-provider" in command


def test_plan_module_matrix_commands_uses_detected_modules_and_task_override() -> None:
    commands = plan_module_matrix_commands(
        repo_root=Path("/repo"),
        task=":control-plane:test",
        max_combinations=1,
        modules=["async-queue", "k8s-deployment-provider"],
    )
    assert len(commands) == 1
    assert commands[0][:3] == [
        "/repo/gradlew",
        ":control-plane:test",
        ":control-plane:printSelectedControlPlaneModules",
    ]
    assert "-PcontrolPlaneModules=none" in commands[0]
