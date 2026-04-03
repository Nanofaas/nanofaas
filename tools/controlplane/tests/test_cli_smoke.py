from typer.testing import CliRunner

from controlplane_tool.main import app


def test_cli_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "Control plane" in result.stdout
