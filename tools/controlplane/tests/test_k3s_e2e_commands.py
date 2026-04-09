"""Tests for the removal of the legacy k3s-e2e command group."""
from __future__ import annotations

import sys
from pathlib import Path
from datetime import UTC, datetime

import pytest
from typer.testing import CliRunner

from controlplane_tool.main import app

runner = CliRunner()


def test_k3s_e2e_group_is_not_registered_in_main_cli() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "k3s-e2e" not in result.stdout


def test_k3s_e2e_invocation_returns_unknown_command() -> None:
    result = runner.invoke(app, ["k3s-e2e", "--help"])

    assert result.exit_code != 0
