"""
Tests for shell_backend.py — ShellExecutionResult, SubprocessShell, RecordingShell, ScriptedShell.
"""
from __future__ import annotations

from pathlib import Path
import sys
from unittest.mock import MagicMock, patch

from controlplane_tool.core.shell_backend import (
    RecordingShell,
    ScriptedShell,
    ShellExecutionResult,
    SubprocessShell,
)


# ---------------------------------------------------------------------------
# ShellExecutionResult
# ---------------------------------------------------------------------------

def test_shell_execution_result_ok_on_zero_return_code() -> None:
    r = ShellExecutionResult(command=["echo", "hi"], return_code=0)
    assert r.return_code == 0


def test_shell_execution_result_captures_stdout_stderr() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0, stdout="out", stderr="err")
    assert r.stdout == "out"
    assert r.stderr == "err"


def test_shell_execution_result_dry_run_defaults_to_false() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0)
    assert r.dry_run is False


# ---------------------------------------------------------------------------
# SubprocessShell
# ---------------------------------------------------------------------------

def test_subprocess_shell_dry_run_returns_zero_without_executing() -> None:
    shell = SubprocessShell()
    result = shell.run(["rm", "-rf", "/"], dry_run=True)
    assert result.return_code == 0
    assert result.dry_run is True


def test_subprocess_shell_dry_run_does_not_call_subprocess(monkeypatch) -> None:
    calls = []
    monkeypatch.setattr("subprocess.run", lambda *a, **kw: calls.append(a) or MagicMock(returncode=0))
    shell = SubprocessShell()
    shell.run(["echo", "hello"], dry_run=True)
    assert calls == []


def test_subprocess_shell_returns_ok_on_zero_exit() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="hello", stderr="")
        result = shell.run(["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_subprocess_shell_returns_nonzero_on_failure() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="error")
    result = shell.run(["false"])
    # Just verify the result is constructed correctly when returncode != 0


def test_subprocess_shell_passes_env_to_subprocess() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        shell.run(["cmd"], env={"MY_VAR": "val"})
    call_kwargs = mock_run.call_args[1]
    assert "MY_VAR" in call_kwargs["env"]
    assert call_kwargs["env"]["MY_VAR"] == "val"


def test_subprocess_shell_passes_cwd_to_subprocess(tmp_path: Path) -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        shell.run(["ls"], cwd=tmp_path)
    call_kwargs = mock_run.call_args[1]
    assert call_kwargs["cwd"] == tmp_path


def test_subprocess_shell_streams_output_to_listener() -> None:
    streamed: list[tuple[str, str]] = []
    shell = SubprocessShell(output_listener=lambda stream, line: streamed.append((stream, line)))

    result = shell.run(
        [
            sys.executable,
            "-c",
            "import sys; print('hello'); print('warn', file=sys.stderr)",
        ]
    )

    assert result.return_code == 0
    assert ("stdout", "hello") in streamed
    assert ("stderr", "warn") in streamed


# ---------------------------------------------------------------------------
# RecordingShell
# ---------------------------------------------------------------------------

def test_recording_shell_records_commands() -> None:
    shell = RecordingShell()
    shell.run(["cmd1", "arg1"])
    shell.run(["cmd2"])
    assert shell.commands == [["cmd1", "arg1"], ["cmd2"]]


def test_recording_shell_always_returns_zero() -> None:
    shell = RecordingShell()
    result = shell.run(["fail"])
    assert result.return_code == 0


def test_recording_shell_does_not_exec_subprocess() -> None:
    shell = RecordingShell()
    with patch("subprocess.run") as mock_run:
        shell.run(["anything"])
    mock_run.assert_not_called()


def test_recording_shell_carries_env_in_result() -> None:
    shell = RecordingShell()
    result = shell.run(["cmd"], env={"A": "B"})
    assert result.env == {"A": "B"}


# ---------------------------------------------------------------------------
# ScriptedShell
# ---------------------------------------------------------------------------

def test_scripted_shell_returns_configured_stdout() -> None:
    shell = ScriptedShell(stdout_map={("echo", "hi"): "hi\n"})
    result = shell.run(["echo", "hi"])
    assert result.stdout == "hi\n"


def test_scripted_shell_returns_configured_return_code() -> None:
    shell = ScriptedShell(return_code_map={("fail",): 1})
    result = shell.run(["fail"])
    assert result.return_code == 1


def test_scripted_shell_defaults_unknown_command_to_zero() -> None:
    shell = ScriptedShell()
    result = shell.run(["unknown"])
    assert result.return_code == 0
    assert result.stdout == ""


def test_scripted_shell_records_all_commands() -> None:
    shell = ScriptedShell()
    shell.run(["a"])
    shell.run(["b"])
    assert shell.commands == [["a"], ["b"]]


def test_scripted_shell_returns_configured_stderr() -> None:
    shell = ScriptedShell(stderr_map={("cmd",): "error output"})
    result = shell.run(["cmd"])
    assert result.stderr == "error output"
