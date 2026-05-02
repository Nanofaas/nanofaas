# Shim: re-exports from shellcraft + workflow-aware SubprocessShell for TUI integration.
from __future__ import annotations

from shellcraft.backend import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell as _ShellcraftSubprocessShell,
)

from tui_toolkit import has_workflow_sink, workflow_log

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

    Routes each output line to workflow_log when a workflow sink is active,
    in addition to any explicitly set output_listener.
    """

    def _emit_output(self, stream: str, line: str) -> None:
        super()._emit_output(stream, line)
        if has_workflow_sink():
            workflow_log(line, stream=stream)
