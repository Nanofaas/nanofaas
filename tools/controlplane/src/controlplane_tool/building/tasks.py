from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from controlplane_tool.core.models import BuildAction, Profile, ProfileName


@dataclass(frozen=True)
class CommandExecutionResult:
    command: list[str]
    return_code: int
    dry_run: bool
    stdout: str = ""
    stderr: str = ""


class GradleActionExecutor(Protocol):
    def execute(
        self,
        *,
        action: BuildAction,
        profile: ProfileName,
        modules: str | None,
        extra_gradle_args: list[str],
        dry_run: bool,
    ) -> CommandExecutionResult: ...


class GradleMatrixExecutor(Protocol):
    def execute_matrix(
        self,
        *,
        task: str,
        modules: str | None,
        max_combinations: int,
        extra_gradle_args: list[str],
        dry_run: bool,
    ) -> tuple[list[list[str]], int]: ...


class BuildPipelineAdapter(Protocol):
    def preflight(self, profile: Profile) -> list[str]: ...

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]: ...

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]: ...

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]: ...

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]: ...

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]: ...


BuildPipelineTask = Callable[
    ...,
    tuple[bool, str],
]


def run_gradle_action_task(
    *,
    executor: GradleActionExecutor,
    action: BuildAction,
    profile: ProfileName,
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
    executor: GradleMatrixExecutor,
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


def preflight_task(*, adapter: BuildPipelineAdapter, profile: Profile) -> list[str]:
    return adapter.preflight(profile)


def compile_task(*, adapter: BuildPipelineAdapter, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.compile(profile, run_dir)


def build_image_task(*, adapter: BuildPipelineAdapter, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.build_image(profile, run_dir)


def api_tests_task(*, adapter: BuildPipelineAdapter, profile: Profile, run_dir: Path) -> tuple[bool, str]:
    return adapter.run_api_tests(profile, run_dir)


def mockk8s_tests_task(
    *,
    adapter: BuildPipelineAdapter,
    profile: Profile,
    run_dir: Path,
) -> tuple[bool, str]:
    return adapter.run_mockk8s_tests(profile, run_dir)
