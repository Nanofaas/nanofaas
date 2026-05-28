from __future__ import annotations

from contextlib import contextmanager
from unittest.mock import MagicMock, patch

from workflow_tasks.shell import (
    RecordingShell,
    ScriptedShell,
    ShellExecutionResult,
    SubprocessShell,
)


def test_shell_execution_result_captures_stdout_stderr() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0, stdout="out", stderr="err")
    assert r.stdout == "out"
    assert r.stderr == "err"


def test_subprocess_shell_dry_run_returns_zero_without_executing() -> None:
    shell = SubprocessShell()
    result = shell.run(["rm", "-rf", "/"], dry_run=True)
    assert result.return_code == 0
    assert result.dry_run is True


def test_subprocess_shell_returns_ok_on_zero_exit() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="hello", stderr="")
        result = shell.run(["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_recording_shell_records_commands() -> None:
    shell = RecordingShell()
    shell.run(["cmd1", "arg1"])
    shell.run(["cmd2"])
    assert shell.commands == [["cmd1", "arg1"], ["cmd2"]]


def test_scripted_shell_returns_configured_return_code() -> None:
    shell = ScriptedShell(return_code_map={("fail",): 1})
    result = shell.run(["fail"])
    assert result.return_code == 1


def test_subprocess_shell_routes_output_to_workflow_log_when_sink_active() -> None:
    """When a workflow sink is bound, _emit_output forwards lines to it via workflow_log.

    bind_workflow_sink is a context manager; we use it as such for setup/teardown.
    The sink must satisfy the WorkflowSink protocol (emit + status methods).
    """
    captured: list[tuple[str, str]] = []

    class CaptureSink:
        def emit(self, event) -> None:
            if event.kind == "log.line":
                captured.append((event.stream, event.line))

        @contextmanager
        def status(self, label: str):
            yield

    from workflow_tasks.workflow.context import bind_workflow_sink

    sink = CaptureSink()
    with bind_workflow_sink(sink):
        shell = SubprocessShell()
        shell._emit_output("stdout", "hello-line")

    assert ("stdout", "hello-line") in captured
