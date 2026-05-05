"""
Tests for scenario_path_from_env() - extracted from the duplicate inline pattern
in k3s_e2e_commands, cli_e2e_commands, local_e2e_commands.

Gate: CLI arg takes precedence; env var used as fallback; None returned when both absent.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.workspace.paths import scenario_path_from_env


def test_scenario_path_from_env_returns_none_when_both_absent(monkeypatch) -> None:
    monkeypatch.delenv("NANOFAAS_SCENARIO_PATH", raising=False)
    assert scenario_path_from_env() is None


def test_scenario_path_from_env_returns_none_for_empty_env_var(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_SCENARIO_PATH", "  ")
    assert scenario_path_from_env() is None


def test_scenario_path_from_env_reads_env_var(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_SCENARIO_PATH", "/tmp/scenario.json")
    result = scenario_path_from_env()
    assert result == Path("/tmp/scenario.json")


def test_scenario_path_from_env_cli_arg_takes_precedence_over_env(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_SCENARIO_PATH", "/tmp/env-scenario.json")
    cli_path = Path("/tmp/cli-scenario.json")
    result = scenario_path_from_env(cli_path)
    assert result == cli_path


def test_scenario_path_from_env_cli_arg_used_when_env_absent(monkeypatch) -> None:
    monkeypatch.delenv("NANOFAAS_SCENARIO_PATH", raising=False)
    cli_path = Path("/tmp/direct.json")
    result = scenario_path_from_env(cli_path)
    assert result == cli_path


def test_scenario_path_from_env_strips_whitespace_from_env(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_SCENARIO_PATH", "  /tmp/padded.json  ")
    result = scenario_path_from_env()
    assert result == Path("/tmp/padded.json")
