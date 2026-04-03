from __future__ import annotations

from pathlib import Path

from controlplane_tool.build_requests import BuildRequest, resolve_modules_selector


ACTION_TO_TASK: dict[str, str] = {
    "jar": ":control-plane:bootJar",
    "build": ":control-plane:bootJar",
    "run": ":control-plane:bootRun",
    "image": ":control-plane:bootBuildImage",
    "native": ":control-plane:nativeCompile",
    "test": ":control-plane:test",
    "inspect": ":control-plane:printSelectedControlPlaneModules",
}


def build_gradle_command(
    repo_root: Path,
    request: BuildRequest,
    extra_gradle_args: list[str] | None = None,
) -> list[str]:
    task = ACTION_TO_TASK[request.action]
    modules_selector = resolve_modules_selector(request)
    command = [
        str(repo_root / "gradlew"),
        task,
        f"-PcontrolPlaneModules={modules_selector}",
    ]
    if extra_gradle_args:
        command.extend(extra_gradle_args)
    return command
