from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.models import Profile


@dataclass(frozen=True)
class CommandExecutionResult:
    command: list[str]
    return_code: int
    dry_run: bool
    stdout: str = ""
    stderr: str = ""


def run_gradle_action_task(
    *,
    executor: object,
    action: str,
    profile: str,
    modules: str | None,
    extra_gradle_args: list[str],
    dry_run: bool,
) -> CommandExecutionResult:
    return executor.execute(
        action=action,
        profile=profile,
        modules=modules,
        extra_gradle_args=extra_gradle_args,
        dry_run=dry_run,
    )


def run_matrix_task(
    *,
    executor: object,
    task: str,
    modules: str | None,
    max_combinations: int,
    extra_gradle_args: list[str],
    dry_run: bool,
) -> tuple[list[list[str]], int]:
    return executor.execute_matrix(
        task=task,
        modules=modules,
        max_combinations=max_combinations,
        extra_gradle_args=extra_gradle_args,
        dry_run=dry_run,
    )


def preflight_task(*, adapter: object, profile: Profile) -> list[str]:
    return adapter.preflight(profile)


def compile_task(*, adapter: object, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.compile(profile, run_dir)


def build_image_task(*, adapter: object, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.build_image(profile, run_dir)


def api_tests_task(*, adapter: object, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.run_api_tests(profile, run_dir)


def mockk8s_tests_task(*, adapter: object, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.run_mockk8s_tests(profile, run_dir)
