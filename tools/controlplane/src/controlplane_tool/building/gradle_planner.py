from __future__ import annotations

from pathlib import Path

from controlplane_tool.building.requests import (
    BuildRequest,
    build_module_selectors,
    resolve_matrix_modules,
    resolve_modules_selector,
)


ACTION_TO_TASK: dict[str, str] = {
    "jar": ":control-plane:bootJar",
    "building": ":control-plane:bootJar",
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


def plan_module_matrix_commands(
    repo_root: Path,
    task: str,
    max_combinations: int = 0,
    modules_csv: str | None = None,
    modules: list[str] | None = None,
    extra_gradle_args: list[str] | None = None,
) -> list[list[str]]:
    if max_combinations < 0:
        raise ValueError("max_combinations must be non-negative")

    resolved_modules = resolve_matrix_modules(
        repo_root=repo_root,
        modules_csv=modules_csv,
        modules=modules,
    )
    if not resolved_modules:
        raise ValueError("No optional modules found under control-plane-modules/.")

    selectors = build_module_selectors(resolved_modules)
    limit = len(selectors)
    if 0 < max_combinations < limit:
        limit = max_combinations

    commands: list[list[str]] = []
    for selector in selectors[:limit]:
        command = [
            str(repo_root / "gradlew"),
            task,
            ":control-plane:printSelectedControlPlaneModules",
            f"-PcontrolPlaneModules={selector}",
        ]
        if extra_gradle_args:
            command.extend(extra_gradle_args)
        commands.append(command)
    return commands
