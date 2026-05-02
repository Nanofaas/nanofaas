from __future__ import annotations

from controlplane_tool.build_tasks import run_gradle_action_task
from controlplane_tool.cli_commands import CommandExecutionResult


class _RecordingExecutor:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str | None, list[str], bool]] = []

    def execute(
        self,
        action: str,
        profile: str,
        modules: str | None,
        extra_gradle_args: list[str],
        dry_run: bool,
    ) -> CommandExecutionResult:
        self.calls.append((action, profile, modules, extra_gradle_args, dry_run))
        return CommandExecutionResult(command=["./gradlew", "build"], return_code=0, dry_run=dry_run)


def test_run_gradle_action_task_delegates_to_executor() -> None:
    executor = _RecordingExecutor()

    result = run_gradle_action_task(
        executor=executor,
        action="build",
        profile="core",
        modules=None,
        extra_gradle_args=["--info"],
        dry_run=True,
    )

    assert result.command == ["./gradlew", "build"]
    assert executor.calls == [("build", "core", None, ["--info"], True)]
