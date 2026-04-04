"""
Tests for k3s_e2e_commands CLI subcommands (M11).

Gate: k3s-e2e must be registered in the CLI, expose run subcommands for
k3s-curl and helm-stack, and require E2E_SKIP_VM_BOOTSTRAP guard.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

from controlplane_tool.main import app

runner = CliRunner()


def test_k3s_e2e_group_is_registered_in_main_cli() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "k3s-e2e" in result.stdout


def test_k3s_e2e_help_exits_zero() -> None:
    result = runner.invoke(app, ["k3s-e2e", "--help"])
    assert result.exit_code == 0


def test_k3s_e2e_run_subgroup_help_exits_zero() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "--help"])
    assert result.exit_code == 0


def test_k3s_e2e_run_k3s_curl_help_exits_zero() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "k3s-curl", "--help"])
    assert result.exit_code == 0


def test_k3s_e2e_run_helm_stack_help_exits_zero() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "helm-stack", "--help"])
    assert result.exit_code == 0


def test_k3s_e2e_run_k3s_curl_fails_without_skip_bootstrap(monkeypatch) -> None:
    """k3s-curl must raise unless E2E_SKIP_VM_BOOTSTRAP=true."""
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    result = runner.invoke(app, ["k3s-e2e", "run", "k3s-curl"])
    assert result.exit_code != 0


def test_k3s_e2e_run_helm_stack_fails_without_skip_bootstrap(monkeypatch) -> None:
    """helm-stack must raise unless E2E_SKIP_VM_BOOTSTRAP=true."""
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    result = runner.invoke(app, ["k3s-e2e", "run", "helm-stack"])
    assert result.exit_code != 0


def test_k3s_e2e_run_k3s_curl_accepts_namespace_option() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "k3s-curl", "--help"])
    assert "--namespace" in result.stdout


def test_k3s_e2e_run_k3s_curl_accepts_scenario_file_option() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "k3s-curl", "--help"])
    assert "--scenario-file" in result.stdout


def test_k3s_e2e_run_helm_stack_accepts_namespace_option() -> None:
    result = runner.invoke(app, ["k3s-e2e", "run", "helm-stack", "--help"])
    assert "--namespace" in result.stdout
