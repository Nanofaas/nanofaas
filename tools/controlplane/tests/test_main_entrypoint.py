"""
Smoke tests for main.py — verifies the Typer app registers expected command groups.
"""
from __future__ import annotations

import os

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


def test_prefect_runtime_smoke_command_runs_without_api_url(monkeypatch) -> None:
    monkeypatch.delenv("PREFECT_API_URL", raising=False)

    result = runner.invoke(app, ["prefect-runtime-smoke"], env={})

    assert result.exit_code == 0
    assert "controlplane.prefect_runtime_smoke" in result.output
    assert "completed" in result.output
    assert "PREFECT_API_URL" not in os.environ
