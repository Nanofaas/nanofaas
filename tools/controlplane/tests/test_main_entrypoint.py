"""
Smoke tests for main.py — verifies the Typer app registers expected command groups.
"""
from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.main import app


runner = CliRunner()


def test_main_help_exits_zero() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0


def test_main_help_mentions_e2e_command() -> None:
    result = runner.invoke(app, ["--help"])
    assert "e2e" in result.output


def test_main_help_mentions_vm_command() -> None:
    result = runner.invoke(app, ["--help"])
    assert "vm" in result.output


def test_main_help_mentions_loadtest_command() -> None:
    result = runner.invoke(app, ["--help"])
    assert "loadtest" in result.output


def test_main_unknown_command_exits_nonzero() -> None:
    result = runner.invoke(app, ["totally-unknown-command"])
    assert result.exit_code != 0
