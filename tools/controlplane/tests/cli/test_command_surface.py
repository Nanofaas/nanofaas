from typer.testing import CliRunner

from controlplane_tool.app.main import app


def test_top_level_exposes_only_six_product_commands() -> None:
    result = CliRunner().invoke(app, ["--help"])

    assert result.exit_code == 0
    commands = {command.name for command in app.registered_commands}
    assert commands == {"run", "plan", "list", "inspect", "doctor", "tui"}


def test_plan_builds_shared_validate_workflow() -> None:
    result = CliRunner().invoke(
        app,
        ["plan", "scenarios-v2/validate-k8s.yaml", "--environment", "environments/local.yaml"],
    )

    assert result.exit_code == 0
    assert "images.build.word-stats-java" in result.stdout
    assert "resources.inspect.k8s.word-stats-java" in result.stdout


def test_inspect_renders_validated_configuration() -> None:
    result = CliRunner().invoke(app, ["inspect", "scenarios-v2/cli.yaml"])

    assert result.exit_code == 0
    assert '"workflow": "cli"' in result.stdout


def test_plan_can_select_one_task() -> None:
    result = CliRunner().invoke(
        app,
        ["plan", "scenarios-v2/validate-k8s.yaml", "--only", "functions.invoke.word-stats-java"],
    )

    assert result.exit_code == 0
    assert "functions.invoke.word-stats-java" in result.stdout
    assert "images.build.word-stats-java" not in result.stdout
