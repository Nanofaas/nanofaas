from typer.testing import CliRunner
from pathlib import Path

from controlplane_tool.main import app


def test_cli_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "Control plane" in result.stdout


def test_tooling_lockfile_exists() -> None:
    assert Path("tools/controlplane/uv.lock").exists()
