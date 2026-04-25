# Shim: re-exports from shellcraft + workflow-aware SubprocessShell for TUI integration.
from __future__ import annotations

from pathlib import Path

from shellcraft.backend import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell as _ShellcraftSubprocessShell,
)

from controlplane_tool.console import has_workflow_sink, workflow_log

__all__ = [
    "OutputListener",
    "RecordingShell",
    "ScriptedShell",
    "ShellBackend",
    "ShellExecutionResult",
    "SubprocessShell",
]


class SubprocessShell(_ShellcraftSubprocessShell):
    """SubprocessShell with TUI workflow-log integration.

    When a workflow sink is active (TUI log panel) and no explicit
    output_listener is set, automatically wires workflow_log as the listener
    so subprocess output appears in the TUI.
    """

    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if not dry_run and self.output_listener is None and has_workflow_sink():
            shell = _ShellcraftSubprocessShell(
                output_listener=lambda s, l: workflow_log(l, stream=s)
            )
            return shell.run(command, cwd=cwd, env=env, dry_run=dry_run)
        return super().run(command, cwd=cwd, env=env, dry_run=dry_run)
