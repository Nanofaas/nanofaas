from typer.testing import CliRunner

from controlplane_tool.main import app


def test_build_command_accepts_profile_and_non_interactive_args() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["build", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert "bootJar" in result.stdout
