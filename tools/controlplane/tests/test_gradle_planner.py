from pathlib import Path

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.gradle_planner import build_gradle_command


def test_image_request_uses_boot_build_image_and_profile_modules() -> None:
    command = build_gradle_command(
        repo_root=Path("/repo"),
        request=BuildRequest(action="image", profile="k8s"),
        extra_gradle_args=["-PcontrolPlaneImage=nanofaas/control-plane:test"],
    )
    assert command[:2] == ["/repo/gradlew", ":control-plane:bootBuildImage"]
    assert "-PcontrolPlaneModules=k8s-deployment-provider" in command
