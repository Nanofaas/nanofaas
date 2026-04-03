from pathlib import Path
from typer.testing import CliRunner

from controlplane_tool.main import app

PIPELINE_ALIAS = "pipeline" + "-run"


def test_cli_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "Control plane" in result.stdout
    assert PIPELINE_ALIAS not in result.stdout


def test_tooling_lockfile_exists() -> None:
    assert Path("tools/controlplane/uv.lock").exists()


def test_vm_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["vm", "--help"])
    assert result.exit_code == 0
    assert "vm" in result.stdout.lower()


def test_e2e_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "--help"])
    assert result.exit_code == 0
    assert "e2e" in result.stdout.lower()


def test_cli_test_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["cli-test", "--help"])
    assert result.exit_code == 0
    assert "cli" in result.stdout.lower()


def test_loadtest_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["loadtest", "--help"])
    assert result.exit_code == 0
    assert "loadtest" in result.stdout.lower()


def test_functions_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["functions", "--help"])
    assert result.exit_code == 0
    assert "functions" in result.stdout.lower()


def test_generic_controlplane_wrapper_uses_locked_tool() -> None:
    script = Path("scripts/controlplane.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked controlplane-tool" in script


def test_demo_java_profile_exists() -> None:
    assert Path("tools/controlplane/profiles/demo-java.toml").exists()


def test_removed_pipeline_run_command_is_rejected() -> None:
    runner = CliRunner()
    result = runner.invoke(app, [PIPELINE_ALIAS, "--help"])
    assert result.exit_code != 0
    assert PIPELINE_ALIAS in result.stdout + result.stderr
