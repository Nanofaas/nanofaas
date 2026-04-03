from typer.testing import CliRunner

from controlplane_tool.main import app


def test_build_command_accepts_profile_and_non_interactive_args() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["build", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert "bootJar" in result.stdout


def test_jar_command_maps_to_bootjar() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["jar", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert ":control-plane:bootJar" in result.stdout


def test_matrix_command_accepts_task_override() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["matrix", "--task", ":control-plane:test", "--max-combinations", "1", "--dry-run"],
    )
    assert result.exit_code == 0
    assert ":control-plane:test" in result.stdout
    assert ":control-plane:printSelectedControlPlaneModules" in result.stdout
